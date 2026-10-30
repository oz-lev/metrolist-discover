/**
 * Metrolist Discover 13.7.0.8 (fork addition) - GPL-3.0. Plain JVM tests, synthetic data only.
 * The ranking refinements: "still new to you", repeat-artist fatigue, stale-library prior, and engine
 * robustness on empty / odd input (no history, tracks without artists, unknown durations).
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ranking1308Test {
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L
    private fun t(id: String, artist: String, dur: Int = 200) = TrackMeta(id, id, listOf(artist), listOf(artist), "$artist-al", dur)

    /** 30 artists heard over two months so the profile is not a cold start. */
    private fun base(): Pair<MutableMap<String, TrackMeta>, MutableList<PlayEvent>> {
        val tracks = HashMap<String, TrackMeta>(); val plays = ArrayList<PlayEvent>()
        for (i in 0 until 120) { val id = "b$i"; tracks[id] = t(id, "A${i % 30}"); plays += PlayEvent(id, now - (5 + i % 60) * day - i * 60_000L, 200_000, 200_000) }
        return tracks to plays
    }

    @Test fun aSongFirstHeardDaysAgoStillCountsAsNew() {
        val (tracks, plays) = base()
        tracks["fresh"] = t("fresh", "A1"); tracks["old"] = t("old", "A1")
        plays += PlayEvent("fresh", now - 6 * day, 200_000, 200_000)
        plays += PlayEvent("old", now - 80 * day, 200_000, 200_000); plays += PlayEvent("old", now - 6 * day, 200_000, 200_000)
        val h = History(tracks, plays)
        val on = DiscoverEngine(); val off = DiscoverEngine(DiscoverConfig(freshNovelty = 0.0))
        val c = Candidate(tracks["fresh"]!!, setOf(CandidateSource.RELATED_SONG), "A1", null, 0.5)
        val co = Candidate(tracks["old"]!!, setOf(CandidateSource.RELATED_SONG), "A1", null, 0.5)
        val pOn = on.profile(h, now); val pOff = off.profile(h, now)
        val nOn = on.features(c, pOn, 0).novelty; val nOff = off.features(c, pOff, 0).novelty
        assertTrue("fresh novelty $nOn > $nOff", nOn > nOff)
        assertTrue(nOn >= 0.6 - 1e-9)
        assertTrue(on.isStillNew("fresh", pOn)); assertTrue(!on.isStillNew("old", pOn)); assertTrue(!off.isStillNew("fresh", pOff))
        // a song known for months does not get the bonus
        assertEquals(off.features(co, pOff, 0).novelty, on.features(co, pOn, 0).novelty, 1e-9)
    }

    @Test fun anArtistThatDominatesTheLastDaysIsPushedDownHarder() {
        val (tracks, plays) = base()
        for (i in 0 until 40) { tracks["d$i"] = t("d$i", "DOM"); plays += PlayEvent("d$i", now - i * 3_600_000L, 200_000, 200_000) }
        tracks["x"] = t("x", "DOM")
        val h = History(tracks, plays)
        val c = Candidate(tracks["x"]!!, setOf(CandidateSource.ARTIST_TOP), "DOM", null, 0.8)
        val eNew = DiscoverEngine(); val eOld = DiscoverEngine(DiscoverConfig(fatigueStart = 0.15, fatigueSlope = 2.0))
        val sNew = eNew.features(c, eNew.profile(h, now), 0).overRepresentation
        val sOld = eOld.features(c, eOld.profile(h, now), 0).overRepresentation
        assertTrue("fatigue $sNew > $sOld", sNew > sOld && sOld > 0)
    }

    @Test fun staleLibraryTracksGetALowerPriorThanRelatedSongs() {
        val e = DiscoverEngine()
        val (tracks, plays) = base(); tracks["l"] = t("l", "Z"); tracks["r"] = t("r", "Z")
        val p = e.profile(History(tracks, plays), now)
        val lib = e.features(Candidate(tracks["l"]!!, setOf(CandidateSource.LIBRARY), "Z", "l", 0.3), p, 0).sourcePrior
        val rel = e.features(Candidate(tracks["r"]!!, setOf(CandidateSource.RELATED_SONG), "Z", null, 0.3), p, 0).sourcePrior
        assertTrue(lib < rel)
        // a forgotten favourite that also sits in the library keeps its strong prior
        val fav = e.features(Candidate(tracks["l"]!!, setOf(CandidateSource.LIBRARY, CandidateSource.FORGOTTEN), "Z", "l", 0.3), p, 0).sourcePrior
        assertTrue(fav >= CandidateSource.FORGOTTEN.prior)
    }

    @Test fun emptyAndOddInputNeverThrows() {
        val e = DiscoverEngine()
        val empty = History(emptyMap(), emptyList())
        val p0 = e.profile(empty, now)
        assertTrue(p0.isColdStart)
        assertTrue(e.recommend(empty, emptyList(), now, profile = p0).isEmpty())
        assertTrue(CandidateBuilder.offline(empty, p0).isEmpty())
        // tracks without artists, unknown durations, plays of tracks missing from the table, zero-length plays
        val tracks = mapOf("n" to TrackMeta("n", "", emptyList(), emptyList(), null, -1), "m" to TrackMeta("m", "m", listOf("M"), emptyList(), null, 0))
        val plays = (0 until 40).map { PlayEvent(listOf("n", "m", "ghost")[it % 3], now - it * day, if (it % 4 == 0) 0 else 120_000, -1) }
        val h = History(tracks, plays, listOf(ExplicitSignal("ghost", SignalKind.LIKE, null)))
        val p = e.profile(h, now)
        val recs = e.recommend(h, CandidateBuilder.offline(h, p) + tracks.values.map { Candidate(it, setOf(CandidateSource.LIBRARY)) }, now, profile = p)
        assertTrue(recs.size <= 2)
    }
}
