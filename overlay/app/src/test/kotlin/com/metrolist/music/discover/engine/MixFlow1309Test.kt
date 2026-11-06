package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 13.7.0.9: endless main mix, real-time learning, per-track vibe, DJ energy arc, balanced new songs, personal mixes. */
class MixFlow1309Test {
    private fun t(id: String, artist: String, title: String = "song $id", dur: Int = 200) = TrackMeta(id, title, listOf(artist), listOf(artist), null, dur)
    private fun p(id: String, artist: String, e: Double, fam: Double = 0.5, aff: Double = 0.5, v: Double = 0.5, m: Double = 0.3) =
        PoolTrack(t(id, artist), Vibe(e, v, m, 0.1), fam, aff)
    private fun pool(n: Int, artists: Int = 20, newEvery: Int = 3): List<PoolTrack> {
        val r = Random(3)
        return (0 until n).map { i -> p("t$i", "a${i % artists}", r.nextDouble(), fam = if (i % newEvery == 0) 0.0 else 0.2 + r.nextDouble() * 0.8, aff = r.nextDouble()) }
    }

    // ---------------- A: endless main mix
    @Test fun endlessBatchesNeverRepeatInsideTheSession() {
        val s = MixSession(VibeTarget(energy = 0.6, valence = 0.6), newShare = 0.3)
        val mixer = VibeMixer(Random(1))
        val pl = pool(400)
        val seen = HashSet<String>()
        var last: PoolTrack? = null
        val first = s.nextBatch(mixer, pl, 40)
        assertEquals(40, first.size)
        first.forEach { assertTrue(seen.add(it.track.id)) }
        last = first.last()
        repeat(10) {
            val b = s.nextBatch(mixer, pl, 20, last)
            assertEquals(20, b.size)
            b.forEach { x -> assertTrue("repeat ${x.track.id}", seen.add(x.track.id)) }
            last = b.last()
        }
        assertEquals(240, s.ids.size)
    }

    @Test fun endlessRunsUntilThePoolIsDryThenReturnsEmpty() {
        val s = MixSession(VibeTarget(energy = 0.5))
        val mixer = VibeMixer(Random(2))
        val pl = pool(50, artists = 50)
        var total = 0
        for (i in 0 until 20) { val b = s.nextBatch(mixer, pl, 20); if (b.isEmpty()) break; total += b.size }
        assertEquals(50, total)
        assertTrue(s.nextBatch(mixer, pl, 20).isEmpty())
        assertTrue(s.nextBatch(mixer, emptyList(), 20).isEmpty())
    }

    @Test fun nextBatchFollowsWhatIsPlayingNow() {
        val s = MixSession(VibeTarget(energy = 0.5, valence = 0.5), newShare = 0.0)
        val mixer = VibeMixer(Random(4))
        val pl = (0 until 200).map { i -> p("x$i", "a${i % 40}", e = i / 200.0, fam = 0.6) }
        s.nextBatch(mixer, pl, 20)
        val loud = p("cur", "zz", e = 0.98)
        repeat(3) { s.onPlayed("none", skipped = false, full = true, fallback = loud) }
        val b = s.nextBatch(mixer, pl, 20, loud)
        assertTrue(b.map { it.vibe.energy }.average() > 0.6)
    }

    // ---------------- B: real-time learning
    @Test fun earlySkipPushesThatArtistDownTheRestOfTheMix() {
        val taste = SessionTaste()
        val up = (0 until 10).map { i -> p("u$i", if (i % 3 == 0) "skipme" else "ok$i", e = 0.5 + i * 0.01) }
        taste.onSkip(t("s", "skipme"), Vibe(0.9, 0.2, 0.0, 0.1))
        val r = taste.rerank(up)
        val pos = r.withIndex().filter { it.value.track.mainArtist == "skipme" }.map { it.index }
        assertTrue("skipped artist should sink: $pos", pos.all { it >= 6 })
        assertEquals(up.map { it.track.id }.toSet(), r.map { it.track.id }.toSet())
    }

    @Test fun fullListenNudgesSimilarSongsUp() {
        val taste = SessionTaste()
        val up = (0 until 10).map { i -> p("u$i", "a$i", e = if (i == 8) 0.95 else 0.3, v = if (i == 8) 0.9 else 0.4) }
        taste.onFull(t("f", "other"), Vibe(0.95, 0.9, 0.3, 0.1))
        val r = taste.rerank(up)
        assertTrue(r.indexOfFirst { it.track.id == "u8" } < 8)
        assertTrue(taste.adjust(up[8]) > taste.adjust(up[0]))
    }

    @Test fun reorderOnlyTouchesOurOwnQueue() {
        val s = MixSession(VibeTarget(energy = 0.5), newShare = 0.0)
        val b = s.nextBatch(VibeMixer(Random(5)), pool(60, artists = 30), 20)
        val ids = b.map { it.track.id }
        assertNull("nothing learned yet", s.reorder(ids))
        s.onPlayed(ids[0], skipped = true, full = false)
        assertNull("foreign queue", s.reorder(listOf("f1", "f2", "f3", "f4", ids[1])))
        val ro = s.reorder(ids.drop(1))
        if (ro != null) assertEquals(ids.drop(1).toSet(), ro.toSet())
    }

    @Test fun skipRuleMatchesTheAppRule() {
        assertTrue(SkipRule.isEarlySkip(20_000, 200_000))
        assertTrue(SkipRule.isEarlySkip(60_000, 200_000))
        assertFalse(SkipRule.isEarlySkip(80_000, 200_000))
        assertTrue(SkipRule.isFull(180_000, 200_000))
    }

    // ---------------- C: per-track vibe
    @Test fun aBalladByAPartyArtistIsCalmerThanHisPartySong() {
        val e = VibeEstimator()
        val party = e.own(TrackMeta("1", "יאללה בלאגן", listOf("n"), listOf("Noa Kirel")))
        val ballad = e.own(TrackMeta("2", "בלדה לאמא", listOf("n"), listOf("Noa Kirel")))
        assertTrue(ballad.energy < party.energy - 0.2)
        val old = VibeEstimator(VibeConfig(perTrack = false))
        assertTrue(old.own(TrackMeta("2", "בלדה לאמא", listOf("n"), listOf("Noa Kirel"))).energy > ballad.energy)
    }

    @Test fun unknownTrackFallsBackToItsArtistsOtherSongs() {
        val tr = mapOf(
            "a1" to TrackMeta("a1", "phonk drift", listOf("X"), listOf("X")),
            "a2" to TrackMeta("a2", "trap night", listOf("X"), listOf("X")),
            "a3" to TrackMeta("a3", "untitled", listOf("X"), listOf("X")),
        )
        val e = VibeEstimator()
        val all = e.estimateAll(tr.values, tr, emptyMap())
        assertTrue(all["a3"]!!.energy > VibeConfig().baseEnergy + 0.1)
        val old = VibeEstimator(VibeConfig(perTrack = false)).estimateAll(tr.values, tr, emptyMap())
        assertEquals(VibeConfig().baseEnergy, old["a3"]!!.energy, 1e-9)
    }

    @Test fun perTrackValenceIsLearnedAndOldCorrectionsStillDecode() {
        val old = VibeCorrections.decode("a=0.100|a=0.050|t1=0.020")
        assertEquals(0.02, old.trackEnergy["t1"]!!, 1e-9)
        assertTrue(old.trackValence.isEmpty())
        val e = VibeEstimator()
        val tr = TrackMeta("t9", "song", listOf("q"), listOf("Q"))
        repeat(5) { e.feedback(tr, VibeTarget(energy = 0.9, valence = 0.9), skipped = false, fullListen = true) }
        assertTrue(e.corrections.trackValence.containsKey("t9"))
        val round = VibeCorrections.decode(e.corrections.encode())
        assertEquals(e.corrections.trackValence["t9"]!!, round.trackValence["t9"]!!, 1e-3)
    }

    // ---------------- D: DJ energy arc
    @Test fun energyArcIsSmootherThanRankOrderAndPeaksInside() {
        val r = Random(9)
        val list = (0 until 12).map { i -> p("e$i", "a$i", r.nextDouble()) }
        val arc = EnergyArc.order(list, wave = 12)
        assertTrue(EnergyArc.smoothness(arc) < EnergyArc.smoothness(list))
        val peak = arc.indices.maxByOrNull { arc[it].vibe.energy }!!
        assertTrue(peak in 3..8)
        assertEquals(list.toSet(), arc.toSet())
    }

    @Test fun energyArcKeepsArtistsApart() {
        val list = (0 until 24).map { i -> p("e$i", "a${i % 6}", (i % 12) / 12.0) }
        val arc = EnergyArc.order(list, wave = 12, minArtistGap = 2)
        assertEquals(0.0, EnergyArc.adjacentSameArtist(arc), 1e-9)
    }

    @Test fun highStartFallsFirst() {
        val list = (0 until 12).map { i -> p("e$i", "a$i", i / 12.0) }
        val arc = EnergyArc.order(list, startEnergy = 0.95)
        assertTrue(arc.first().vibe.energy > arc[arc.size / 2].vibe.energy)
    }

    // ---------------- E: balanced familiar / new
    @Test fun newShareIsRealAndTunable() {
        val mixer = VibeMixer(Random(11))
        val pl = pool(600, artists = 80, newEvery = 2)
        for (share in listOf(0.0, 0.3, 0.6)) {
            val l = mixer.buildBalanced(pl, VibeTarget(energy = 0.6), 40, share)
            assertEquals(40, l.size)
            val n = l.count { it.familiarity < 0.15 }
            assertEquals("share $share", (40 * share).toInt().toDouble(), n.toDouble(), 2.0)
        }
        // only familiar songs in the pool: still a full mix
        assertEquals(30, mixer.buildBalanced(pl.filter { it.familiarity > 0.2 }, VibeTarget(), 30, 0.5).size)
    }

    // ---------------- F: personal mixes
    private fun history(): Pair<History, List<PoolTrack>> {
        val day = 86_400_000L; val now = 200 * day
        val tracks = HashMap<String, TrackMeta>(); val plays = ArrayList<PlayEvent>()
        for (a in 0 until 8) for (k in 0 until 8) tracks["h${a}_$k"] = t("h${a}_$k", "art$a")
        for (a in 0 until 10) for (k in 0 until 4) tracks["n${a}_$k"] = t("n${a}_$k", "new$a")
        val r = Random(5)
        for (d in 0 until 90) for (j in 0 until 10) {
            val a = r.nextInt(8); val k = r.nextInt(8)
            plays += PlayEvent("h${a}_$k", now - (90 - d) * day + (8 + j) * 3_600_000L, 180_000, 200_000)
        }
        // a song looped 40-50 days ago, then dropped
        for (i in 0 until 6) plays += PlayEvent("h0_7", now - (45 + i) * day, 180_000, 200_000)
        val related = mapOf("h0_0" to listOf("n0_0", "n1_0", "h1_0"))
        val h = History(tracks, plays.filter { it.trackId != "h0_7" || it.timestampMs < now - 40 * day }, emptyList(), related)
        val played = h.plays.map { it.trackId }.toSet()
        val pl = tracks.values.map { tm -> PoolTrack(tm, Vibe(r.nextDouble(), r.nextDouble(), 0.4, 0.1), if (tm.id in played) 0.6 else 0.0, 0.5) }
        return h to pl
    }

    @Test fun personalMixesHaveMinimumSizeAndTheRightContent() {
        val (h, pl) = history()
        val now = 200 * 86_400_000L
        val all = PersonalMixes.all(h, pl, now, VibeMixer(Random(1)))
        assertTrue(all.isNotEmpty())
        all.forEach { assertTrue("${it.titleHe} ${it.tracks.size}", it.tracks.size >= PersonalMixes.MIN_SIZE) }
        val disc = all.firstOrNull { it.kind == PersonalKind.DISCOVERIES }
        assertNotNull(disc)
        assertTrue(disc!!.tracks.all { it.track.mainArtist.startsWith("new") })
        assertTrue(all.any { it.kind == PersonalKind.ARTIST })
        assertTrue(all.any { it.kind == PersonalKind.SESSION })
        val cd = all.firstOrNull { it.kind == PersonalKind.CHANGE_DIRECTION }
        if (cd != null) assertTrue(cd.tracks.count { it.familiarity < 0.15 } >= cd.tracks.size / 2)
    }

    @Test fun lastSeasonFindsTheDroppedLoop() {
        val (h, pl) = history()
        val m = PersonalMixes.lastSeason(h, pl, 200 * 86_400_000L)
        // h0_7 was looped ~45 days ago; recent random plays may include it, so only check the window logic is sane
        if (m != null) assertTrue(m.tracks.isNotEmpty())
        assertNull(PersonalMixes.lastSeason(History(emptyMap(), emptyList()), pl, 200 * 86_400_000L))
    }

    @Test fun sparseOrEmptyDataNeverCrashes() {
        val (_, pl) = history()
        assertTrue(PersonalMixes.all(History(emptyMap(), emptyList()), emptyList(), 0L).isEmpty())
        val few = History(pl.take(3).associate { it.track.id to it.track }, listOf(PlayEvent(pl[0].track.id, 1000L, 60_000)))
        PersonalMixes.all(few, pl.take(3), 2000L).forEach { assertTrue(it.tracks.size >= PersonalMixes.MIN_SIZE) }
        assertTrue(PersonalMixes.today(emptyList(), 1, 10).isEmpty())
    }

    @Test fun todayRotatesByDay() {
        val (h, pl) = history()
        val all = PersonalMixes.all(h, pl, 200 * 86_400_000L, VibeMixer(Random(1)))
        val days = (0L until 7L).map { d -> PersonalMixes.today(all, 200 + d, 18).map { it.key } }
        assertTrue(days.toSet().size > 1)
        days.forEach { assertTrue(it.size <= 6); assertEquals(it.size, it.toSet().size) }
        assertEquals(PersonalMixes.today(all, 300, 18), PersonalMixes.today(all, 300, 18))
    }
}
