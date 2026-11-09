/**
 * Metrolist Discover 13.7.0.12 (fork addition) - GPL-3.0
 * Every profile is its own app instance on disk: path resolution per profile, "me" on the legacy files, deletion,
 * and whose rows a profile's own database holds.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class Space1312Test {
    private fun ts(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun track(id: String, artist: String) = TrackMeta(id, "Song $id", listOf(artist), listOf("Artist $artist"), durationSec = 200)

    @Test fun mainProfileResolvesToTheLegacyFileNames() {
        assertTrue(SpacePaths.isLegacy("me"))
        assertNull(SpacePaths.root("me"))
        assertEquals("databases/song.db", SpacePaths.databasePath("me", "song.db"))
        assertEquals("databases/exoplayer_internal.db", SpacePaths.databasePath("me", "exoplayer_internal.db"))
        assertEquals("files", SpacePaths.filesDir("me"))
        assertEquals("cache", SpacePaths.cacheDir("me"))
        assertEquals("settings", SpacePaths.prefsName("me", "settings"))
        assertEquals("innertubex_player_config", SpacePaths.prefsName("me", "innertubex_player_config"))
        assertNull(SpacePaths.webViewSuffix("me"))
    }

    @Test fun everyOtherProfileHasItsOwnTree() {
        assertEquals("spaces/gym/databases/song.db", SpacePaths.databasePath("gym", "song.db"))
        assertEquals("spaces/gym/databases/exoplayer_internal.db", SpacePaths.databasePath("gym", "exoplayer_internal.db")) // download index too
        assertEquals("spaces/gym/files", SpacePaths.filesDir("gym"))           // settings DataStore, player cache, downloads, queue
        assertEquals("cache/spaces/gym", SpacePaths.cacheDir("gym"))
        assertEquals("sp_gym__settings", SpacePaths.prefsName("gym", "settings"))
        assertEquals("space_gym", SpacePaths.webViewSuffix("gym"))
        // two profiles never share a path
        assertFalse(SpacePaths.databasePath("gym", "song.db") == SpacePaths.databasePath("p7", "song.db"))
        assertFalse(SpacePaths.prefsName("gym", "x") == SpacePaths.prefsName("p7", "x"))
        assertTrue(SpacePaths.prefsBelongTo("gym", "sp_gym__settings.xml"))
        assertFalse(SpacePaths.prefsBelongTo("gym", "sp_gym2__settings.xml"))
        assertFalse(SpacePaths.prefsBelongTo("me", "settings.xml"))
    }

    @Test fun onlyTheRegistryDiscoverDbAndJobDbStayDeviceWide() {
        assertEquals("databases/metrolist_discover.db", SpacePaths.databasePath("gym", "metrolist_discover.db"))
        assertTrue(SpacePaths.isGlobalDb("metrolist_discover.db-wal"))
        assertTrue(SpacePaths.isGlobalDb("androidx.work.workdb"))
        assertFalse(SpacePaths.isGlobalDb("song.db"))
        assertEquals("discover_space", SpacePaths.prefsName("gym", "discover_space"))
        assertEquals("androidx.work.util.preferences", SpacePaths.prefsName("gym", "androidx.work.util.preferences"))
        // an absolute path is never re-rooted
        assertEquals("/data/x/y.db", SpacePaths.databasePath("gym", "/data/x/y.db"))
    }

    @Test fun idsAreSanitisedIntoSafeNames() {
        assertEquals("p_3-x", SpacePaths.sanitize("p_3-x"))
        assertEquals("me", SpacePaths.sanitize("../me"))
        assertEquals("me", SpacePaths.sanitize("a/b"))
        assertEquals("me", SpacePaths.sanitize(""))
        assertEquals("me", SpacePaths.sanitize(null))
        assertEquals("me", SpacePaths.sanitize("x".repeat(41)))
        assertEquals("databases/song.db", SpacePaths.databasePath("../evil", "song.db"))
    }

    @Test fun deletingAProfileRemovesExactlyItsOwnFilesAndNeverTheMainOnes() {
        val me = SpacePaths.deletion("me")
        assertTrue(me.dirs.isEmpty()); assertNull(me.prefsPrefix)
        val gym = SpacePaths.deletion("gym")
        assertEquals(listOf("spaces/gym", "cache/spaces/gym", "app_webview_space_gym"), gym.dirs)
        assertEquals("sp_gym__", gym.prefsPrefix)
        // everything gym resolves to lies inside what its deletion removes
        val all = listOf(SpacePaths.databasePath("gym", "song.db"), SpacePaths.filesDir("gym") + "/datastore/settings.preferences_pb", SpacePaths.cacheDir("gym") + "/coil")
        for (p in all) assertTrue(p, gym.dirs.any { p.startsWith("$it/") })
        assertTrue(SpacePaths.prefsName("gym", "settings").startsWith(gym.prefsPrefix!!))
        // and nothing of another profile or of "me"
        for (p in listOf("spaces/gym2/files", "databases/song.db", "files/datastore", "cache/coil", "shared_prefs/settings.xml"))
            assertFalse(p, gym.dirs.any { p == it || p.startsWith("$it/") })
        assertFalse("sp_gym2__settings".startsWith(gym.prefsPrefix!!))
    }

    @Test fun aProfilesOwnDatabaseIsEntirelyItsOwn() {
        val timeline = ProfileTimeline.decode("${ts(7, 12)}:gym;${ts(7, 14)}:me")
        val a = SpaceOwnership.attribution("gym", timeline, ts(9, 20))
        // in gym's own song.db every row is gym's, whatever the old shared timeline said (and dateless rows too)
        assertEquals("gym", ProfileTimeline.owner(a.marks, ts(9, 21), a.default))
        assertEquals("gym", ProfileTimeline.owner(a.marks, ts(1, 9), a.default))
        assertEquals("gym", ProfileTimeline.owner(a.marks, null, a.default))
        val sig = ProfileIsolation.ownSignals(listOf(ExplicitSignal("s1", SignalKind.PLAYLIST_ADD, null)), a.marks, a.default)
        assertEquals("gym", sig.single().owner)
    }

    @Test fun mainProfileKeepsLegacyAttributionThenOwnsEverythingAfterTheUpgrade() {
        val epoch = ts(9, 20)
        // the upgrade happened while gym was the last mark: before 13.7.0.12 the timeline decides, after it "me" owns its file
        val timeline = ProfileTimeline.decode("${ts(7, 12)}:gym")
        val a = SpaceOwnership.attribution("me", timeline, epoch)
        assertEquals("me", ProfileTimeline.owner(a.marks, ts(6, 10), a.default))   // migrated history
        assertEquals("gym", ProfileTimeline.owner(a.marks, ts(8, 10), a.default))  // heard in gym in 13.7.0.10/11
        assertEquals("me", ProfileTimeline.owner(a.marks, ts(10, 10), a.default))  // after the upgrade: me's own file
        // without an epoch (never happens in the app) the old behaviour stands
        assertEquals("gym", ProfileTimeline.owner(SpaceOwnership.attribution("me", timeline, null).marks, ts(10, 10)))
    }

    @Test fun aProfileSeesOnlyItsOwnDiscoverLog() {
        val epoch = ts(9, 20)
        val plays = listOf(
            OwnedPlay(PlayEvent("a", ts(8, 10), 100_000L, 200_000L), "me"),
            OwnedPlay(PlayEvent("b", ts(8, 11), 100_000L, 200_000L), "gym"),
            OwnedPlay(PlayEvent("c", ts(10, 11), 100_000L, 200_000L), "gym"),
            OwnedPlay(PlayEvent("d", ts(10, 12), 100_000L, 200_000L), "me"),
        )
        assertEquals(listOf("b", "c"), SpaceOwnership.visiblePlays("gym", plays, epoch).map { it.play.trackId })
        // "me" keeps the pre-upgrade foreign rows only to tell its legacy rows apart, never a later one
        assertEquals(listOf("a", "b", "d"), SpaceOwnership.visiblePlays("me", plays, epoch).map { it.play.trackId })
    }

    @Test fun nothingOfTheMainProfileReachesAnotherProfilesHistory() {
        // gym's own database: two of its songs; "me"'s Discover plays of the same artist exist in the shared Discover log
        val tracks = mapOf("g1" to track("g1", "A"), "g2" to track("g2", "A"))
        val events = listOf(PlayEvent("g1", ts(10, 9), 190_000L, 200_000L))
        val log = listOf(OwnedPlay(PlayEvent("m1", ts(10, 8), 190_000L, 200_000L), "me"), OwnedPlay(PlayEvent("g2", ts(10, 10), 30_000L, 200_000L), "gym"))
        val epoch = ts(9, 20)
        val a = SpaceOwnership.attribution("gym", emptyList(), epoch)
        val merged = ProfileIsolation.mergePlays(events, SpaceOwnership.visiblePlays("gym", log, epoch), a.marks, default = a.default)
        val h = ProfileIsolation.isolate(tracks, merged, emptyList(), emptyMap(), "gym")
        assertEquals(setOf("g1", "g2"), h.plays.map { it.trackId }.toSet())
        assertTrue(h.plays.none { it.trackId == "m1" })
        assertEquals(setOf("g1", "g2"), h.tracks.keys)
        assertTrue(ProfileIsolation.foreignTracks(merged, emptyList(), "gym").isEmpty())
    }

    @Test fun theGuestBorrowsNothingWhenProfilesAreSeparateApps() {
        assertEquals("me", ProfileScope.historyOwner("guest", isGuest = true, borrow = true))   // 13.7.0.10 behaviour
        assertEquals("guest", ProfileScope.historyOwner("guest", isGuest = true, borrow = false))
        assertNull(ProfileScope.recordOwner("guest", isGuest = true))
        // the overnight offline mix is per profile now; "me" keeps its original key
        assertEquals("p.gym.v2.offline.ids", ProfileScope.key("v2.offline.ids", "gym"))
        assertEquals("v2.offline.ids", ProfileScope.key("v2.offline.ids", "me"))
        assertEquals("v2.colors", ProfileScope.key("v2.colors", "gym"))
    }
}
