/**
 * Metrolist Discover (fork addition) - GPL-3.0. Plain JVM tests, synthetic data only.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverEngineTest {
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L
    private fun t(id: String, artist: String, dur: Int = 200) = TrackMeta(id, id, listOf(artist), listOf(artist), "$artist-al", dur)

    private val world by lazy { SyntheticWorld.world() }
    private val history by lazy { SyntheticWorld.history(world, SyntheticWorld.PERSONAS[2], endMs = now) }

    @Test fun thresholdsFollowTheUsersOwnDistribution() {
        val tracks = (0 until 40).associate { "s$it" to t("s$it", "a${it % 4}") }
        // a user who usually bails out early vs one who listens to the end
        val early = (0 until 200).map { PlayEvent("s${it % 40}", now - it * 3_600_000L, if (it % 3 == 0) 30_000 else 60_000, 200_000) }
        val full = (0 until 200).map { PlayEvent("s${it % 40}", now - it * 3_600_000L, if (it % 10 == 0) 20_000 else 195_000, 200_000) }
        val te = ProfileBuilder.thresholds(History(tracks, early), DiscoverConfig())
        val tf = ProfileBuilder.thresholds(History(tracks, full), DiscoverConfig())
        assertTrue("skip cut adapts: ${te.skipFraction} vs ${tf.skipFraction}", te.skipFraction != tf.skipFraction)
        assertTrue(tf.fullFraction > te.fullFraction)
    }

    @Test fun skipsLowerArtistAffinityAndLikesRaiseIt() {
        val tracks = mapOf("a" to t("a", "A"), "b" to t("b", "B"), "c" to t("c", "C"))
        val plays = (1..20).flatMap { i ->
            listOf(PlayEvent("a", now - i * day, 200_000, 200_000), PlayEvent("b", now - i * day + 1000, 8_000, 200_000), PlayEvent("c", now - i * day + 2000, 200_000, 200_000))
        }
        val p = ProfileBuilder.build(History(tracks, plays, listOf(ExplicitSignal("c", SignalKind.LIKE, now - day))), now)
        assertTrue(p.artistAffinity("A") > 0)
        assertTrue(p.artistAffinity("B") < 0)
        assertTrue(p.trackAffinity("c") > p.trackAffinity("a"))
    }

    @Test fun repeatsCountMoreThanSinglePlays() {
        val tracks = mapOf("r" to t("r", "A"), "s" to t("s", "B"))
        val plays = (1..10).flatMap { i ->
            listOf(PlayEvent("r", now - i * day, 200_000, 200_000), PlayEvent("r", now - i * day + 210_000, 200_000, 200_000),
                PlayEvent("s", now - i * day - 5 * 3_600_000L, 200_000, 200_000), PlayEvent("s", now - i * day - 9 * 3_600_000L - day / 2, 200_000, 200_000))
        }
        val p = ProfileBuilder.build(History(tracks, plays), now)
        assertTrue(p.trackAff["r"]!! > p.trackAff["s"]!!)
    }

    @Test fun recommendationListFollowsTheRules() {
        val engine = DiscoverEngine()
        val p = engine.profile(history, now)
        val cands = CandidateBuilder.offline(history, p)
        val recs = engine.recommend(history, cands, now, n = 30, profile = p)
        assertTrue("list size ${recs.size}", recs.size >= 25)
        for (i in 1 until recs.size) assertFalse("same artist twice in a row at $i", recs[i].track.mainArtist == recs[i - 1].track.mainArtist)
        val recentCut = now - (p.thresholds.recentWindowDays * day).toLong()
        val recent = history.plays.filter { it.timestampMs >= recentCut }.map { it.trackId }.toSet()
        assertTrue("dedupe vs recently played", recs.none { it.track.id in recent })
        assertEquals("ids unique", recs.size, recs.map { it.track.id }.toSet().size)
        val explore = recs.count { it.slot == Slot.EXPLORE }.toDouble() / recs.size
        assertTrue("explore share ~30%: $explore", explore in 0.2..0.4)
    }

    @Test fun coldStartIsDetected() {
        val tracks = mapOf("a" to t("a", "A"))
        val p = ProfileBuilder.build(History(tracks, listOf(PlayEvent("a", now - day, 200_000, 200_000))), now)
        assertTrue(p.isColdStart)
    }

    @Test fun hourFitPrefersTheArtistsUsualHours() {
        val tracks = mapOf("n" to t("n", "Night"), "m" to t("m", "Morning"))
        val plays = (1..30).flatMap { i ->
            val d = (now / day - i) * day
            listOf(PlayEvent("n", d + 23 * 3_600_000L, 200_000, 200_000), PlayEvent("m", d + 8 * 3_600_000L, 200_000, 200_000))
        }
        val p = ProfileBuilder.build(History(tracks, plays), now)
        val nightSlot = TasteProfile.slotOf((now / day) * day + 23 * 3_600_000L)
        assertTrue(p.hourFit("Night", nightSlot) > p.hourFit("Morning", nightSlot))
    }

    @Test fun noSameArtistTwiceReorders() {
        fun r(id: String, a: String) = Recommendation(t(id, a), 1.0, Features(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), Slot.EXPLOIT, Reason.EXPLORE)
        val out = DiscoverEngine.noSameArtistTwice(listOf(r("1", "A"), r("2", "A"), r("3", "B"), r("4", "C")))
        for (i in 1 until out.size) assertFalse(out[i].track.mainArtist == out[i - 1].track.mainArtist)
    }

    @Test fun statsFactsAreCountedExactly() {
        val tracks = mapOf("a" to t("a", "A"), "b" to t("b", "B"))
        val plays = (0 until 12).map { PlayEvent("a", now - it * day, 200_000, 200_000) } +
            (0 until 4).map { PlayEvent("b", now - it * day - 3_600_000L, 200_000, 200_000) }
        val facts = StatsFacts.compute(History(tracks, plays), now)
        val top = facts.first { it.type == StatsFacts.Type.TOP_ARTIST_MONTH }
        assertEquals(12.0, top.value, 0.0)
        val streak = facts.first { it.type == StatsFacts.Type.STREAK }
        assertEquals(12.0, streak.value, 0.0)
        val first = StatsFacts.pick(facts, null, 0)!!
        assertTrue(StatsFacts.pick(facts, first.type, 0)!!.type != first.type)
    }

    @Test fun mixesAreBuiltForAnActiveListener() {
        val mixes = MixBuilder.build(history, now)
        assertTrue(mixes.any { it.kind == MixBuilder.Kind.DAILY })
        assertTrue(mixes.all { it.tracks.size >= 8 })
    }
}
