/**
 * Metrolist Discover 13.7.0.10 (fork addition) - GPL-3.0
 * Hard per-profile isolation: a song played only in one profile never reaches another profile's taste or candidates.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

class ProfileIsolation1310Test {
    private fun ts(day: Int, hour: Int, min: Int = 0) = LocalDateTime.of(2026, 10, day, hour, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun track(id: String, artist: String) = TrackMeta(id, "Song $id", listOf(artist), listOf("Artist $artist"), durationSec = 200)

    /** "me" listens to artists m0..m5 (songs m*), "gym" listens to g0..g3 (songs g*); x1 is in the database but never played. */
    private fun world(): Triple<Map<String, TrackMeta>, List<OwnedPlay>, Map<String, List<String>>> {
        val tracks = HashMap<String, TrackMeta>()
        for (a in 0 until 6) for (s in 0 until 6) tracks["m${a}_$s"] = track("m${a}_$s", "am$a")
        for (a in 0 until 4) for (s in 0 until 6) tracks["g${a}_$s"] = track("g${a}_$s", "ag$a")
        tracks["x1"] = track("x1", "ax")
        val plays = ArrayList<OwnedPlay>()
        val r = Random(3)
        for (d in 1..20) {
            repeat(12) { plays += OwnedPlay(PlayEvent("m${r.nextInt(6)}_${r.nextInt(4)}", ts(d, 10, it * 4), 190_000L, 200_000L), "me") }
            repeat(10) { plays += OwnedPlay(PlayEvent("g${r.nextInt(4)}_${r.nextInt(4)}", ts(d, 18, it * 4), 190_000L, 200_000L), "gym") }
        }
        // the related graph links both worlds (it is YouTube's, not anyone's taste)
        val related = mapOf("m0_0" to listOf("g0_5", "m0_5", "x1"), "g0_0" to listOf("m1_5", "g0_4"))
        return Triple(tracks, plays, related)
    }

    @Test fun timelineOwnsByLastMarkAndDefaultsToMainProfile() {
        val marks = ProfileTimeline.decode("${ts(5, 12)}:gym;${ts(5, 14)}:me;${ts(6, 9)}:work")
        assertEquals("me", ProfileTimeline.owner(marks, ts(1, 10)))      // before the first mark: migrated history
        assertEquals("gym", ProfileTimeline.owner(marks, ts(5, 13)))
        assertEquals("me", ProfileTimeline.owner(marks, ts(5, 20)))
        assertEquals("work", ProfileTimeline.owner(marks, ts(9, 1)))
        assertEquals("me", ProfileTimeline.owner(marks, null))           // no date (playlists): main profile
        assertEquals("me", ProfileTimeline.owner(emptyList(), ts(9, 1)))  // no timeline yet = everything stays in the main profile
        assertEquals(marks, ProfileTimeline.decode(ProfileTimeline.encode(marks)))
    }

    @Test fun timelineAppendCollapsesRepeatsAndReassignKeepsOrder() {
        var m = ProfileTimeline.append(emptyList(), 10, "me")
        m = ProfileTimeline.append(m, 20, "me")
        m = ProfileTimeline.append(m, 30, "gym")
        m = ProfileTimeline.append(m, 40, "me")
        assertEquals(listOf(SwitchMark(10, "me"), SwitchMark(30, "gym"), SwitchMark(40, "me")), m)
        assertEquals(listOf(SwitchMark(10, "me")), ProfileTimeline.reassign(m, "gym"))
        assertTrue(ProfileTimeline.decode("garbage;:x;12:;5:me").let { it.size == 1 && it[0] == SwitchMark(5, "me") })
    }

    @Test fun mergeKeepsTheOldHistoryRowsAndGivesEventsTheDiscoverOwner() {
        val ev = listOf(PlayEvent("a", ts(3, 10), 180_000L), PlayEvent("b", ts(3, 11), 200_000L), PlayEvent("c", ts(1, 9), 150_000L))
        val own = listOf(
            OwnedPlay(PlayEvent("a", ts(3, 10, 2), 180_000L, 200_000L, true), "gym"),   // same listen as event a
            OwnedPlay(PlayEvent("a", ts(3, 15), 8_000L, 200_000L), "gym"),               // a skip Metrolist did not keep
            OwnedPlay(PlayEvent("b", ts(3, 11, 1), 200_000L, 200_000L), "me"),
        )
        val marks = listOf(SwitchMark(ts(2, 0), "work"))
        val m = ProfileIsolation.mergePlays(ev, own, marks)
        assertEquals(4, m.size)                                     // 3 events + the unmatched skip, as before 13.7.0.10
        assertEquals("gym", m.first { it.play.trackId == "a" && it.play.timestampMs == ts(3, 10) }.owner)
        assertEquals("me", m.first { it.play.trackId == "b" }.owner)
        assertEquals("me", m.first { it.play.trackId == "c" }.owner)  // older than the timeline: main profile
        assertEquals("gym", m.first { it.play.playedMs == 8_000L }.owner)
    }

    @Test fun aSongPlayedOnlyInOneProfileNeverReachesAnotherProfilesTasteOrCandidates() {
        val (tracks, plays, related) = world()
        val signals = listOf(OwnedSignal(ExplicitSignal("g1_5", SignalKind.LIKE, ts(4, 18)), "gym"),
            OwnedSignal(ExplicitSignal("m2_5", SignalKind.LIBRARY_ADD, ts(4, 10)), "me"))
        val now = ts(21, 12)
        for ((me, other) in listOf("me" to "g", "gym" to "m")) {
            val h = ProfileIsolation.isolate(tracks, plays, signals, related, me)
            assertTrue(h.plays.isNotEmpty())
            assertTrue("$me sees a foreign play", h.plays.none { it.trackId.startsWith(other) })
            assertTrue("$me sees a foreign signal", h.signals.none { it.trackId.startsWith(other) })
            assertTrue("$me sees a foreign track", h.tracks.keys.none { it.startsWith(other) })
            assertTrue("$me related graph leaks", (h.related.keys + h.related.values.flatten()).none { it.startsWith(other) })
            val p = ProfileBuilder.build(h, now)
            assertTrue("$me taste has a foreign song", p.trackAff.keys.none { it.startsWith(other) })
            assertTrue("$me taste has a foreign artist", p.artistLong.keys.none { it.startsWith("a$other") })
            val cands = CandidateBuilder.offline(h, p, random = Random(1))
            assertTrue(cands.isNotEmpty())
            assertTrue("$me candidates leak", cands.none { it.track.id.startsWith(other) || it.track.mainArtist.startsWith("a$other") })
            val recs = DiscoverEngine(DiscoverConfig()).recommend(h, cands, now, n = 30, profile = p)
            assertTrue("$me recommendations leak", recs.none { it.track.id.startsWith(other) })
            // the never-played song is neutral: still a candidate source for anyone
            assertTrue("x1" in h.tracks)
        }
    }

    @Test fun aSongSharedByBothProfilesStaysButEachSeesOnlyItsOwnListens() {
        val (tracks, plays0, related) = world()
        val plays = plays0 + OwnedPlay(PlayEvent("m0_0", ts(10, 19), 190_000L, 200_000L), "gym")
        val gym = ProfileIsolation.isolate(tracks, plays, emptyList(), related, "gym")
        val me = ProfileIsolation.isolate(tracks, plays, emptyList(), related, "me")
        assertTrue("m0_0" in gym.tracks)
        assertEquals(1, gym.plays.count { it.trackId == "m0_0" })
        assertEquals(plays0.count { it.play.trackId == "m0_0" }, me.plays.count { it.trackId == "m0_0" })
        // its artist is now gym's too, so gym may get that artist's other songs, but nothing else of "me"
        assertTrue(gym.tracks.keys.filter { it.startsWith("m") }.all { it.startsWith("m0_") })
    }

    @Test fun aNewProfileStartsEmptyAndTheGuestBorrowsTheMainTaste() {
        val (tracks, plays, related) = world()
        val fresh = ProfileIsolation.isolate(tracks, plays, emptyList(), related, "u123456")
        assertTrue(fresh.plays.isEmpty())
        assertTrue(fresh.tracks.keys.none { it.startsWith("m") || it.startsWith("g") })
        assertTrue(ProfileBuilder.build(fresh, ts(21, 12)).isColdStart)
        assertEquals("me", ProfileScope.historyOwner("guest", isGuest = true))
        assertNull(ProfileScope.recordOwner("guest", isGuest = true))
        assertEquals("gym", ProfileScope.recordOwner("gym", isGuest = false))
    }

    @Test fun mainProfileWithoutAnyOtherProfileSeesExactlyTheOldHistory() {
        val (tracks, plays, related) = world()
        val allMe = plays.map { it.copy(owner = "me") }
        val h = ProfileIsolation.isolate(tracks, allMe, emptyList(), related, "me")
        assertEquals(tracks, h.tracks)
        assertEquals(allMe.map { it.play }, h.plays)
        assertEquals(related, h.related)
    }

    @Test fun discoverCachesHiddenListsAndThresholdsArePerProfileNow() {
        for (k in listOf("discover.cache", "discover.cacheTs", "mixes.cache", "mixes.cacheTs", "discover.hiddenSongs", "discover.hiddenArtists", "profile.thresholds", "tuner.state", "v2.vibe.corr")) {
            assertTrue(k, ProfileScope.scoped(k))
            assertEquals("p.gym.$k", ProfileScope.key(k, "gym"))
            assertEquals(k, ProfileScope.key(k, "me"))
        }
        assertFalse(ProfileScope.scoped("g.profiles"))
        assertFalse(ProfileScope.scoped("v2.colors"))
    }

    @Test fun foreignTracksListsOnlySongsThatNeverHappenedInThisProfile() {
        val (_, plays, _) = world()
        val f = ProfileIsolation.foreignTracks(plays, emptyList(), "gym")
        assertTrue(f.isNotEmpty() && f.all { it.startsWith("m") })
        assertTrue(ProfileIsolation.foreignTracks(plays, emptyList(), "me").all { it.startsWith("g") })
    }
}
