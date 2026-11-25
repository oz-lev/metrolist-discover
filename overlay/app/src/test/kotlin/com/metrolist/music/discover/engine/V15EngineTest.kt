/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Pure Kotlin unit tests for TasteModel, MixLab (up to 25 artists, weights, filters, saved recipes),
 * PlaylistTools (merge, dedupe, interleave, smart shuffle, sort, filter, set ops, clone),
 * and complete profile isolation paths + leaked-legacy-events detection.
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

class V15EngineTest {
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = LocalDateTime.of(2026, 11, 25, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun track(id: String, artist: String, title: String = "Song $id", artistName: String = "Artist $artist", dur: Int = 200) =
        TrackMeta(id, title, listOf(artist), listOf(artistName), durationSec = dur)

    private fun poolOf(tracks: Collection<TrackMeta>, fam: (TrackMeta) -> Double = { 0.5 }, aff: (TrackMeta) -> Double = { 0.5 }): List<PoolTrack> =
        tracks.map { PoolTrack(it, Vibe(0.55, 0.55, 0.3, 0.1), fam(it), aff(it)) }

    // ------------------------------------------------------------------ 1. TasteModel
    @Test fun tasteModelOffProducesNoTrackBoosts() {
        val t1 = track("s1", "a1")
        val h = History(mapOf("s1" to t1), listOf(PlayEvent("s1", now - 2 * day, 195_000L, 200_000L)))
        val learned = TasteModel.learn(h, now, TasteConfig.OFF)
        assertTrue(learned.trackBoosts.isEmpty())
        assertTrue(learned.artistBoosts.isEmpty())
    }

    @Test fun tasteModelRewardsCompletionAndPenalizesFastSkips() {
        val full = track("full", "a1")
        val skip = track("skip", "a2")
        val plays = listOf(
            PlayEvent("full", now - 2 * day, 195_000L, 200_000L),
            PlayEvent("full", now - 3 * day, 190_000L, 200_000L),
            PlayEvent("skip", now - 2 * day, 12_000L, 200_000L),
            PlayEvent("skip", now - 3 * day, 15_000L, 200_000L),
        )
        val h = History(mapOf("full" to full, "skip" to skip), plays)
        val normal = TasteModel.learn(h, now, TasteConfig(skipStrictness = 1.0))
        val strict = TasteModel.learn(h, now, TasteConfig(skipStrictness = 2.0))
        assertTrue(normal.boostFor("full", "a1") > 0.0)
        assertTrue(normal.boostFor("skip", "a2") < 0.0)
        assertTrue(strict.boostFor("skip", "a2") < normal.boostFor("skip", "a2"))
        assertTrue(normal.skipAvoidedTracks >= 1)
    }

    @Test fun tasteModelLearnsReplaysAndUnheardArtistAffinity() {
        val t1 = track("s1", "a1")
        val t2 = track("s2", "a1") // never heard, same artist
        val t3 = track("s3", "a2") // played once, no replay
        val plays = listOf(
            PlayEvent("s1", now - 2 * day, 195_000L, 200_000L),
            PlayEvent("s1", now - 2 * day + 5 * 60_000L, 198_000L, 200_000L), // back-to-back replay
            PlayEvent("s3", now - 2 * day, 195_000L, 200_000L),
        )
        val h = History(mapOf("s1" to t1, "s2" to t2, "s3" to t3), plays)
        val learned = TasteModel.learn(h, now)
        assertTrue(learned.replayCount >= 1)
        assertTrue(learned.boostFor("s1", "a1") > learned.boostFor("s3", "a2"))
        // unheard track of loved artist still gets positive artist affinity boost!
        assertTrue(learned.boostFor("s2", "a1") > 0.0)
    }

    @Test fun tasteModelLearnsCoListeningSessionTransitions() {
        val anchor = track("anchor", "a1")
        val buddy = track("buddy", "a2")
        val stranger = track("stranger", "a3")
        val plays = listOf(
            // Earlier session: anchor -> buddy (5 min apart)
            PlayEvent("anchor", now - 5 * day, 195_000L, 200_000L),
            PlayEvent("buddy", now - 5 * day + 5 * 60_000L, 195_000L, 200_000L),
            PlayEvent("stranger", now - 5 * day + 3 * hour, 195_000L, 200_000L),
            // Recent play of anchor (20 hours ago)
            PlayEvent("anchor", now - 20 * hour, 195_000L, 200_000L),
        )
        val h = History(mapOf("anchor" to anchor, "buddy" to buddy, "stranger" to stranger), plays)
        val learned = TasteModel.learn(h, now)
        assertTrue(learned.coListenEdges >= 1)
        assertTrue(learned.boostFor("buddy", "a2") > learned.boostFor("stranger", "a3"))
    }

    @Test fun tasteModelBoostedArtistsAndFeedbackFilteringAndCooldownGuard() {
        val t1 = track("s1", "a1")
        val t2 = track("s2", "a2")
        val t3 = track("s3", "a3")
        val h = History(
            mapOf("s1" to t1, "s2" to t2, "s3" to t3),
            listOf(PlayEvent("s1", now - 2 * hour, 195_000L, 200_000L)), // heard 2h ago -> cooldown damped
        )
        val fb = Feedback(hiddenSongs = setOf("s3"))
        val learned = TasteModel.learn(h, now, TasteConfig(), fb, boostedArtists = setOf("a2"))
        assertTrue((learned.artistBoosts["a2"] ?: 0.0) >= 0.40)
        // s1 was heard 2h ago so its positive track boost is damped/negative
        assertTrue((learned.trackBoosts["s1"] ?: 0.0) <= 0.0)
        val applied = TasteModel.apply(poolOf(listOf(t1, t2, t3)), learned, fb)
        assertEquals(2, applied.size)
        assertTrue(applied.none { it.track.id == "s3" })
        assertEquals(setOf("a1", "a2"), TasteModel.decodeBoosted(TasteModel.encodeBoosted(setOf("a1", "a2"))))
    }

    // ------------------------------------------------------------------ 2. MixLab (25 artists, weights, filters, saved mixes)
    @Test fun mixLabSupportsUpTo25ArtistsWithBalancedRepresentation() {
        assertEquals(25, MixLab.MAX_SEEDS)
        val tracks = HashMap<String, TrackMeta>()
        val plays = ArrayList<PlayEvent>()
        for (a in 1..25) {
            for (j in 1..4) {
                val id = "t_${a}_$j"
                tracks[id] = track(id, "art$a", "Song $a-$j", "אמן $a")
                plays += PlayEvent(id, now - (a + j) * day, 190_000L, 200_000L)
            }
        }
        val h = History(tracks, plays)
        val pool = poolOf(tracks.values)
        val seeds15 = (1..15).map { "art$it" }
        val mix15 = MixLab.build(MixRecipe(seedArtists = seeds15, size = 45, newShare = 0.0), h, pool, VibeMixer(Random(7)), now)
        assertEquals(45, mix15.tracks.size)
        val represented15 = mix15.tracks.map { it.track.mainArtist }.toSet()
        // All 15 chosen artists should be represented!
        assertTrue("expected >= 12 of 15 artists, got ${represented15.size}", represented15.size >= 12)
        assertTrue(mix15.title.contains("עוד 13 אמנים"))

        val seeds25 = (1..25).map { "art$it" }
        val mix25 = MixLab.build(MixRecipe(seedArtists = seeds25, size = 50, newShare = 0.0), h, pool, VibeMixer(Random(9)), now)
        assertEquals(50, mix25.tracks.size)
        val represented25 = mix25.tracks.map { it.track.mainArtist }.toSet()
        assertTrue("expected >= 20 of 25 artists, got ${represented25.size}", represented25.size >= 20)
    }

    @Test fun mixLabArtistWeightsAndHeardFiltersWork() {
        val tracks = HashMap<String, TrackMeta>()
        val plays = ArrayList<PlayEvent>()
        val signals = ArrayList<ExplicitSignal>()
        for (a in 1..6) {
            for (j in 1..8) {
                val id = "s_${a}_$j"
                tracks[id] = track(id, "art$a", "Song $a-$j", "Artist $a")
                if (j <= 5) plays += PlayEvent(id, now - j * day, 190_000L, 200_000L)
                if (j <= 2) signals += ExplicitSignal(id, SignalKind.LIKE, now - j * day)
            }
        }
        val h = History(tracks, plays, signals)
        val pool = poolOf(tracks.values, fam = { t -> if (t.id.endsWith("_6") || t.id.endsWith("_7") || t.id.endsWith("_8")) 0.0 else 0.7 })

        val seeds = (1..6).map { "art$it" }
        val weights = mapOf("art1" to ArtistWeight.BOOST.tier, "art6" to ArtistWeight.LOW.tier)
        val m = MixLab.build(MixRecipe(seedArtists = seeds, artistWeights = weights, size = 30, newShare = 0.0), h, pool, VibeMixer(Random(3)), now)
        val c1 = m.tracks.count { it.track.mainArtist == "art1" }
        val c6 = m.tracks.count { it.track.mainArtist == "art6" }
        assertTrue("boosted artist ($c1) should outnumber low artist ($c6)", c1 > c6)

        val likedOnly = MixLab.build(MixRecipe(seedArtists = seeds, heardFilter = HeardFilter.ONLY_LIKED, size = 20), h, pool, VibeMixer(Random(3)), now)
        assertEquals(12, likedOnly.tracks.size)
        assertTrue(likedOnly.tracks.all { it.track.id.endsWith("_1") || it.track.id.endsWith("_2") })

        val unheardOnly = MixLab.build(MixRecipe(seedArtists = seeds, heardFilter = HeardFilter.ONLY_UNHEARD, size = 20), h, pool, VibeMixer(Random(3)), now)
        assertTrue(unheardOnly.tracks.isNotEmpty() && unheardOnly.tracks.all { it.familiarity < 0.15 })
    }

    @Test fun savedMixRecipeRoundTripsCleanly() {
        val r = MixRecipe(
            seedArtists = (1..12).map { "a$it" },
            mood = MoodPreset.DRIVE,
            energy = 0.72,
            newShare = 0.45,
            size = 60,
            lang = LangMode.HEBREW,
            artistWeights = mapOf("a1" to 3, "a2" to 1),
            heardFilter = HeardFilter.EXCLUDE_LIKED,
            dayPartBias = DayPart.NIGHT,
            maxPerArtist = 4,
        )
        val saved = listOf(SavedMixRecipe("m1", "נסיעה | לילה", r, 123456789L))
        val decoded = MixLab.decodeSaved(MixLab.encodeSaved(saved))
        assertEquals(1, decoded.size)
        assertEquals("m1", decoded[0].id)
        assertEquals("נסיעה | לילה", decoded[0].name)
        assertEquals(12, decoded[0].recipe.seedArtists.size)
        assertEquals(MoodPreset.DRIVE, decoded[0].recipe.mood)
        assertEquals(0.72, decoded[0].recipe.energy!!, 1e-6)
        assertEquals(HeardFilter.EXCLUDE_LIKED, decoded[0].recipe.heardFilter)
        assertEquals(DayPart.NIGHT, decoded[0].recipe.dayPartBias)
        assertEquals(mapOf("a1" to 3, "a2" to 1), decoded[0].recipe.artistWeights)
        assertEquals(4, decoded[0].recipe.maxPerArtist)
    }

    // ------------------------------------------------------------------ 3. PlaylistTools
    private fun entry(id: String, artist: String, title: String = "T_$id", order: Int = 0, plays: Int = 0, aff: Double = 0.5) =
        PlaylistEntry(id, title, "id_$artist", artist, order, plays, aff)

    @Test fun playlistMergeAppendInterleaveAndDedupe() {
        val a = listOf(entry("1", "A", order = 0), entry("2", "A", order = 1), entry("3", "B", order = 2))
        val b = listOf(entry("2", "A", order = 0), entry("4", "C", order = 1), entry("5", "D", order = 2))

        val app = PlaylistTools.merge(listOf(a, b), MergeMode.APPEND, dedupe = true)
        assertEquals(6, app.totalBeforeDedupe)
        assertEquals(1, app.duplicatesRemoved)
        assertEquals(listOf("1", "2", "3", "4", "5"), app.entries.map { it.songId })

        val noDedupe = PlaylistTools.merge(listOf(a, b), MergeMode.APPEND, dedupe = false)
        assertEquals(0, noDedupe.duplicatesRemoved)
        assertEquals(6, noDedupe.entries.size)

        val inter = PlaylistTools.merge(listOf(a, b), MergeMode.INTERLEAVE, dedupe = true)
        // A1(1), B1(2), A2(2 dup dropped), B2(4), A3(3), B3(5) -> 1, 2, 4, 3, 5
        assertEquals(listOf("1", "2", "4", "3", "5"), inter.entries.map { it.songId })
        assertEquals(1, inter.duplicatesRemoved)
    }

    @Test fun playlistMergeSmartShuffleSpreadsArtistsAndSortsWork() {
        val a = (1..4).map { entry("a$it", "ArtistA", "Zeta $it", order = it, plays = it * 10, aff = 0.9) }
        val b = (1..4).map { entry("b$it", "ArtistB", "Alpha $it", order = it + 10, plays = 50 - it, aff = 0.8) }
        val c = (1..2).map { entry("c$it", "ArtistC", "Mid $it", order = it + 20, plays = 5, aff = 0.7) }

        val shuf = PlaylistTools.merge(listOf(a, b, c), MergeMode.SMART_SHUFFLE, dedupe = true, seed = 99L).entries
        assertEquals(10, shuf.size)
        // With 4 A, 4 B, 2 C, smart shuffle should achieve zero consecutive identical artists!
        for (i in 1 until shuf.size) {
            assertFalse("consecutive same artist at $i: ${shuf[i].artistId}", shuf[i].artistId == shuf[i - 1].artistId)
        }

        val byArtist = PlaylistTools.merge(listOf(b, a), MergeMode.SORT_ARTIST).entries
        assertEquals("ArtistA", byArtist.first().artistName)

        val byTitle = PlaylistTools.merge(listOf(a, b), MergeMode.SORT_TITLE).entries
        assertEquals("Alpha 1", byTitle.first().title)

        val byPlays = PlaylistTools.merge(listOf(a, b, c), MergeMode.SORT_PLAYS).entries
        assertEquals("b1", byPlays.first().songId)
    }

    @Test fun playlistDedupeFilterSetOpsAndClone() {
        val p1 = listOf(
            entry("1", "עומר אדם", "שיר ראשון", 0),
            entry("2", "Coldplay", "Yellow", 1),
            entry("1", "עומר אדם", "שיר ראשון", 2),
            entry("3", "אייל גולן", "צליל מיתר", 3),
        )
        val p2 = listOf(
            entry("2", "Coldplay", "Yellow", 0),
            entry("4", "Daft Punk", "One More Time", 1),
        )
        val dd = PlaylistTools.deduplicate(p1)
        assertEquals(1, dd.duplicatesRemoved)
        assertEquals(listOf("1", "2", "3"), dd.entries.map { it.songId })

        assertEquals(listOf("1", "3"), PlaylistTools.filterByLanguage(dd.entries, LangMode.HEBREW).map { it.songId })
        assertEquals(listOf("2"), PlaylistTools.filterByLanguage(dd.entries, LangMode.ENGLISH).map { it.songId })

        assertEquals(listOf("2"), PlaylistTools.filterByArtists(dd.entries, setOf("id_Coldplay"), include = true).map { it.songId })
        assertEquals(listOf("1", "3"), PlaylistTools.filterByArtists(dd.entries, setOf("id_Coldplay"), include = false).map { it.songId })

        assertEquals(listOf("2"), PlaylistTools.intersect(p1, p2).map { it.songId })
        assertEquals(listOf("1", "3"), PlaylistTools.difference(p1, p2).map { it.songId })
        assertEquals(listOf("1", "3", "4"), PlaylistTools.symmetricDifference(p1, p2).map { it.songId })

        val cloned = PlaylistTools.cloneEntries(p1, dedupe = true)
        assertEquals(listOf(0, 1, 2), cloned.map { it.addedOrder })
    }

    // ------------------------------------------------------------------ 4. Complete Profile Isolation (13.7.0.15)
    @Test fun completeProfileIsolationPathsAndLeakedEventDetection() {
        // Per-profile Discover DB, WorkManager DB & prefs, noBackup, codeCache, customDir, externalSubdir
        assertEquals("metrolist_discover.db", SpacePaths.discoverDbName("me"))
        assertEquals("discover_profile.db", SpacePaths.discoverDbName("gym"))
        assertEquals("spaces/gym/databases/discover_profile.db", SpacePaths.databasePath("gym", SpacePaths.PROFILE_DISCOVER_DB))
        assertEquals("spaces/gym/databases/androidx.work.workdb", SpacePaths.databasePath("gym", "androidx.work.workdb"))
        assertEquals("sp_gym__androidx.work.util.id", SpacePaths.prefsName("gym", "androidx.work.util.id"))
        assertEquals("spaces/gym/no_backup", SpacePaths.noBackupDir("gym"))
        assertEquals("code_cache/spaces/gym", SpacePaths.codeCacheDir("gym"))
        assertEquals("spaces/gym/app_webview_alt", SpacePaths.customDir("gym", "webview_alt"))
        assertEquals("spaces/gym", SpacePaths.externalSubdir("gym"))
        assertNull(SpacePaths.externalSubdir("me"))

        // Boosted artists & colors are per-profile
        assertEquals("p.gym.discover.boostedArtists", ProfileScope.key("discover.boostedArtists", "gym"))
        assertEquals("discover.boostedArtists", ProfileScope.key("discover.boostedArtists", "me"))
        assertEquals("p.gym.v2.colors", ProfileScope.key("v2.colors", "gym"))

        // Leaked legacy events detection (only pre-epoch foreign plays in "me"'s song.db)
        val epoch = now - 5 * day
        val timeline = ProfileTimeline.decode("${now - 8 * day}:gym;${now - 7 * day}:me")
        val events = listOf(
            PlayEvent("me_old", now - 9 * day, 190_000L, 200_000L),      // before gym -> me
            PlayEvent("gym_leaked", now - 8 * day + hour, 190_000L, 200_000L), // during gym -> LEAKED!
            PlayEvent("me_after_gym", now - 6 * day, 190_000L, 200_000L), // back to me -> me
            PlayEvent("me_post_epoch", now - 2 * day, 190_000L, 200_000L), // post epoch -> never flagged
        )
        val leaked = SpaceOwnership.leakedLegacyEvents(events, emptyList(), timeline, epoch)
        assertEquals(listOf("gym_leaked"), leaked.map { it.trackId })
    }
}
