/**
 * Metrolist Discover 13.7.0.13 (fork addition) - GPL-3.0
 * The extra statistics against fixture data: completion, period compare, milestones, forgotten favourites,
 * the daily-goal streak, genres/moods, the recap and the monthly new-vs-repeat share.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class StatsPlus1313Test {
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun t(id: String, artist: String, sec: Int = 200, title: String = "Song $id", name: String = "Artist $artist") =
        TrackMeta(id, title, listOf(artist), listOf(name), null, sec)
    private val tracks = listOf(t("a1", "A"), t("a2", "A"), t("b1", "B"), t("c1", "C"), t("d1", "D")).associateBy { it.id }
    private fun play(id: String, at: Long, sec: Int = 200) = PlayEvent(id, at, sec * 1000L)
    private fun ix(vararg p: PlayEvent) = FullStats.index(History(tracks, p.toList()))
    private fun ix(p: List<PlayEvent>, tr: Map<String, TrackMeta> = tracks) = FullStats.index(History(tr, p))

    // Monday, Nov 9 2026, noon
    private val now = ts(2026, 11, 9, 12)

    // ------------------------------------------------------------------ completion
    @Test fun completionCountsFinishedSkippedAndPartialOverallAndPerSongAndArtist() {
        val p = ArrayList<PlayEvent>()
        // a1: 4 full listens + 1 skip; b1: 1 full + 3 skips; c1: 2 listens only (under minEvents)
        repeat(4) { p += play("a1", ts(2026, 11, 2, 8 + it), 200) }
        p += play("a1", ts(2026, 11, 2, 14), 5)
        p += play("b1", ts(2026, 11, 3, 8), 200)
        repeat(3) { p += play("b1", ts(2026, 11, 3, 9 + it), 5) }
        repeat(2) { p += play("c1", ts(2026, 11, 4, 8 + it), 200) }
        val c = StatsPlus.completion(ix(p).rows, top = 10, minEvents = 3)
        assertEquals(11, c.overall.events)
        assertEquals(7, c.overall.finished)
        assertEquals(4, c.overall.skipped)
        assertEquals(7.0 / 11, c.overall.finishRate, 1e-9)
        assertEquals(0.0, c.overall.partialRate, 1e-9)
        assertEquals(listOf("a1", "b1"), c.songs.map { it.key })   // c1 has only 2 listens
        assertEquals(0.8, c.songs[0].finishRate, 1e-9)
        assertEquals(0.75, c.songs[1].skipRate, 1e-9)
        assertEquals("A", c.artists.first().key)
    }

    @Test fun aListenStoppedHalfwayIsNeitherFinishedNorSkipped() {
        val p = ArrayList<PlayEvent>()
        repeat(6) { p += play("a1", ts(2026, 11, 2, 8 + it), 200) }
        p += play("b1", ts(2026, 11, 3, 8), 100)
        val c = StatsPlus.completion(ix(p).rows, minEvents = 1)
        val b = c.songs.first { it.key == "b1" }
        assertEquals(0, b.finished); assertEquals(0, b.skipped)
        assertEquals(1.0, b.partialRate, 1e-9)
    }

    // ------------------------------------------------------------------ compare
    @Test fun monthVsLastIsCutToTheSameElapsedLength() {
        val (a, b) = StatsPlus.presetWindows(ComparePreset.MONTH_VS_LAST, now, null)
        assertEquals(ts(2026, 11, 1, 0), a.second.from)
        assertEquals(ts(2026, 10, 1, 0), b.second.from)
        // Nov 1 00:00 .. Nov 9 12:00 elapsed = 8.5 days, so October counts to Oct 9 12:00
        assertEquals(ts(2026, 10, 9, 12), b.second.to)
        val (y, ly) = StatsPlus.presetWindows(ComparePreset.MONTH_VS_LAST_YEAR, now, null)
        assertEquals(ts(2025, 11, 1, 0), ly.second.from)
        assertEquals(ts(2026, 11, 1, 0), y.second.from)
        // a finished month is compared with the whole month before it
        val (lm, before) = StatsPlus.presetWindows(ComparePreset.LAST_MONTH_VS_BEFORE, now, null)
        assertEquals(ts(2026, 10, 1, 0), lm.second.from); assertEquals(ts(2026, 11, 1, 0), lm.second.to)
        assertEquals(ts(2026, 9, 1, 0), before.second.from); assertEquals(ts(2026, 10, 1, 0), before.second.to)
    }

    @Test fun compareShowsBothSidesAndTheChange() {
        val i = ix(
            play("a1", ts(2026, 10, 3, 10)), play("b1", ts(2026, 10, 4, 10)),                   // Oct 1-9: 400 s, 2 artists
            play("a1", ts(2026, 10, 20, 10)),                                                     // after the cut: not counted
            play("a1", ts(2026, 11, 2, 10)), play("a2", ts(2026, 11, 3, 10)), play("c1", ts(2026, 11, 3, 11)), play("a1", ts(2026, 11, 5, 10)),
        )
        val c = StatsPlus.compare(i, ComparePreset.MONTH_VS_LAST, now)
        assertEquals(800_000L, c.a.totalMs)
        assertEquals(400_000L, c.b.totalMs)
        assertEquals(100, c.rows.first { it.he == "זמן האזנה" }.changePct)
        assertEquals(4, c.a.plays); assertEquals(2, c.b.plays)
        assertEquals("Artist A", c.a.topArtist)
        assertEquals("Song a1", c.a.topSong)
        assertEquals(3, c.a.activeDays)
        assertEquals(1, c.a.newArtists)        // C is new in November; A was first heard in October
        assertEquals(2, c.a.newSongs)          // a2, c1
        assertEquals(2, c.b.newArtists)
    }

    // ------------------------------------------------------------------ milestones
    @Test fun milestonesCarryTheDayTheyHappened() {
        val p = ArrayList<PlayEvent>()
        // 120 plays of 5 minutes, two a day from Jul 1: the 100th play is on day 50 (Aug 19), 10 hours = play 120 (Aug 29)
        var d = LocalDate.of(2026, 7, 1)
        for (k in 0 until 120) { p += PlayEvent(if (k % 3 == 0) "b1" else "a1", ts(d.year, d.monthValue, d.dayOfMonth, 9 + (k % 2)), 300_000L); if (k % 2 == 1) d = d.plusDays(1) }
        val ms = StatsPlus.milestones(ix(p, tracks + ("a1" to t("a1", "A", 300)) + ("b1" to t("b1", "B", 300))), now)
        val byId = ms.associateBy { it.id }
        assertEquals(LocalDate.of(2026, 7, 1), byId.getValue("first").date)
        assertEquals(LocalDate.of(2026, 8, 19), byId.getValue("plays100").date)
        assertEquals(LocalDate.of(2026, 8, 29), byId.getValue("hours10").date)
        assertEquals(LocalDate.of(2026, 7, 1), byId.getValue("firstTopA").date)
        assertTrue(byId.getValue("streak").he.contains("60"))
        // the next goals are not reached and carry progress
        val next = byId.getValue("plays500")
        assertFalse(next.reached); assertEquals(120.0 / 500, next.progress, 1e-9)
        assertNull(byId["plays1000"])
        // reached ones newest first, then the next goals
        val reached = ms.takeWhile { it.reached }
        assertEquals(reached.sortedByDescending { it.date }, reached)
        assertTrue(ms.drop(reached.size).none { it.reached })
    }

    @Test fun noHistoryNoMilestones() { assertTrue(StatsPlus.milestones(ix(), now).isEmpty()) }

    // ------------------------------------------------------------------ forgotten favourites
    @Test fun forgottenFavouritesArePlayedALotAndSilentFor60Days() {
        val p = ArrayList<PlayEvent>()
        repeat(8) { p += play("a1", ts(2026, 6, 1 + it, 10)) }         // 8 plays, last Jun 8 -> forgotten
        repeat(5) { p += play("a2", ts(2026, 9, 1 + it, 10)) }         // last Sep 5 = 65 days ago -> forgotten
        repeat(5) { p += play("b1", ts(2026, 9, 6 + it, 10)) }         // last Sep 10 = 60 days ago -> exactly 60: in
        repeat(5) { p += play("c1", ts(2026, 9, 7 + it, 10)) }         // last Sep 11 = 59 days ago -> out
        repeat(4) { p += play("d1", ts(2026, 5, 1 + it, 10)) }         // only 4 plays -> out
        val f = StatsPlus.forgotten(ix(p), now)
        assertEquals(listOf("a1", "a2", "b1"), f.map { it.track.id })
        assertEquals(8, f[0].plays)
        assertEquals(60, f.first { it.track.id == "b1" }.daysSince)
        assertEquals(ts(2026, 6, 8, 10), f[0].lastHeard)
    }

    @Test fun aRecentSkipMeansTheSongIsNotForgotten() {
        val p = ArrayList<PlayEvent>()
        repeat(6) { p += play("a1", ts(2026, 6, 1 + it, 10)) }
        p += play("a1", ts(2026, 11, 1, 10), 5)                        // skipped a week ago: you saw it
        assertTrue(StatsPlus.forgotten(ix(p), now).isEmpty())
    }

    // ------------------------------------------------------------------ goal
    @Test fun goalStreakCountsFromYesterdayWhileTodayIsNotDone() {
        val p = ArrayList<PlayEvent>()
        // 30 min on Nov 4..8 (5 days), 10 min today; best run earlier: Oct 1..7 (7 days)
        for (day in 4..8) p += PlayEvent("a1", ts(2026, 11, day, 20), 30 * 60_000L)
        for (day in 1..7) p += PlayEvent("a1", ts(2026, 10, day, 20), 45 * 60_000L)
        p += PlayEvent("b1", ts(2026, 11, 9, 9), 10 * 60_000L)
        val tr = tracks + ("a1" to t("a1", "A", 3600)) + ("b1" to t("b1", "B", 3600))
        val i = ix(p, tr)
        val g = StatsPlus.goal(i, now, 30)
        assertEquals(10, g.todayMinutes); assertFalse(g.done); assertEquals(20, g.leftMinutes)
        assertEquals(5, g.streak)
        assertEquals(7, g.best.days); assertEquals(LocalDate.of(2026, 10, 1), g.best.from)
        assertEquals(14, g.lastDays.size); assertEquals(LocalDate.of(2026, 11, 9), g.lastDays.last().date)
        assertEquals(5, g.metLast30)           // Nov 4-8; Oct 1-7 are more than 30 days back
        // the same numbers as the 🔥 card on the profile screen
        val dg = DailyGoal.compute(History(tr, p), now, 30)
        assertEquals(dg.streak, g.streak); assertEquals(dg.minutesToday, g.todayMinutes)
        // once today reaches the goal, today joins the streak
        val g2 = StatsPlus.goal(ix(p + PlayEvent("b1", ts(2026, 11, 9, 10), 25 * 60_000L), tr), now, 30)
        assertTrue(g2.done); assertEquals(6, g2.streak)
    }

    @Test fun shortListensDoNotCountTowardTheGoal() {
        val p = (0 until 20).map { PlayEvent("a1", ts(2026, 11, 9, 8, it), 20_000L) }   // 20 x 20 s = 6:40, all under 30 s
        val g = StatsPlus.goal(ix(p), now, 5)
        assertEquals(0, g.todayMinutes); assertEquals(0, g.streak)
    }

    // ------------------------------------------------------------------ genres / moods
    @Test fun genresComeFromDiscoversVibeAndAHebrewTitleAloneIsNotMizrahi() {
        val tr = mapOf(
            "x" to t("x", "omer", title = "Party Remix", name = "Omer Adam"),
            "h" to t("h", "z", title = "שיר בעברית", name = "זמר"),
            "e" to t("e", "e", title = "Song e", name = "Nobody Known"),
        )
        val est = VibeEstimator()
        val i = ix(listOf(play("x", ts(2026, 11, 2, 10)), play("h", ts(2026, 11, 2, 11)), play("e", ts(2026, 11, 2, 12)), play("e", ts(2026, 11, 2, 13))), tr)
        val g = StatsPlus.genres(i.rows, { est.own(it) }, { est.hasOwnEvidence(it) })
        assertEquals(1.0, g.genres.sumOf { it.share }, 1e-9)
        assertEquals(StatsGenre.OTHER.name, g.genres.last().key)        // "not recognised" always last
        assertEquals(StatsGenre.HEBREW.name, StatsPlus.genres(listOf(i.rows[1]), { est.own(it) }, { false }).genres.first().key)
        assertEquals(800_000L, g.totalMs)
        assertTrue(g.moods.isNotEmpty())
        assertTrue(g.evidenceShare in 0.0..1.0)
    }

    @Test fun genreOrderPrefersTheMostSpecificTag() {
        assertEquals(StatsGenre.MIZRAHI, StatsPlus.genreOf(Vibe(0.8, 0.7, 0.9, 0.1, setOf(VibeTag.HEBREW, VibeTag.MIZRAHI, VibeTag.REMIX))))
        assertEquals(StatsGenre.PHONK, StatsPlus.genreOf(Vibe(0.9, 0.4, 0.1, 0.1, setOf(VibeTag.PHONK, VibeTag.SLOWED))))
        assertEquals(StatsGenre.OTHER, StatsPlus.genreOf(Vibe(0.5, 0.5, 0.1, 0.1)))
        assertEquals(StatsMood.PARTY, StatsPlus.moodOf(Vibe(0.8, 0.7, 0.1, 0.1)))
        assertEquals(StatsMood.SAD, StatsPlus.moodOf(Vibe(0.5, 0.2, 0.1, 0.1)))
        assertEquals(StatsMood.CHILL, StatsPlus.moodOf(Vibe(0.2, 0.5, 0.1, 0.1)))
    }

    // ------------------------------------------------------------------ recap + extras
    @Test fun recapNamesTheTopArtistSongGenreAndStreak() {
        val i = ix(play("a1", ts(2026, 11, 2, 10)), play("a1", ts(2026, 11, 3, 10)), play("a2", ts(2026, 11, 4, 10)), play("b1", ts(2026, 11, 4, 11)))
        val r = FullStats.report(i, StatsPeriod(StatsUnit.MONTH), now)
        val g = GenreReport(listOf(ShareRow(StatsGenre.OTHER.name, "לא זוהה", 1, 0.5, 1, null), ShareRow(StatsGenre.ROCK.name, "רוק", 1, 0.5, 1, null)), emptyList(), 0.5, 2)
        val rc = StatsPlus.recap(r, g)
        assertEquals("Artist A", rc.topArtist)
        assertEquals("Song a1", rc.topSong)
        assertEquals(2, rc.topSongPlays)
        assertEquals("רוק", rc.topGenre)        // never "not recognised"
        assertTrue(rc.genreInferred)
        assertEquals(3, rc.streak.days)
        assertEquals(13L, rc.minutes)
        assertEquals(listOf("Artist A", "Artist B"), rc.topArtists)
    }

    @Test fun aSongWithNoArtistIsNeverTheTopArtist() {
        val tr = tracks + ("x" to TrackMeta("x", "No artist", emptyList(), emptyList(), null, 200))
        val i = ix(listOf(play("x", ts(2026, 11, 2, 10)), play("x", ts(2026, 11, 2, 11)), play("x", ts(2026, 11, 2, 12)), play("b1", ts(2026, 11, 3, 10))), tr)
        val r = FullStats.report(i, StatsPeriod(StatsUnit.MONTH), now)
        assertEquals("?", r.artists.first().key)                        // the plain list still shows it
        assertEquals("Artist B", StatsPlus.recap(r, null).topArtist)
        assertEquals("Artist B", StatsPlus.compare(i, ComparePreset.MONTH_VS_LAST, now).a.topArtist)
        assertTrue(StatsPlus.loyalty(i, now).none { it.key == "?" })
        assertTrue(StatsPlus.milestones(i, now).none { it.id == "firstTop?" })
    }

    @Test fun newShareByMonthAndLoyalty() {
        val i = ix(
            play("a1", ts(2026, 9, 2, 10)), play("a1", ts(2026, 10, 2, 10)), play("b1", ts(2026, 10, 3, 10)),
            play("a1", ts(2026, 11, 2, 10)), play("a1", ts(2026, 11, 3, 10)),
        )
        val bars = StatsPlus.newShareByMonth(i, now)
        assertEquals(listOf(100L, 50L, 0L), bars.map { it.value })
        val l = StatsPlus.loyalty(i, now)
        assertEquals("A", l.first().key)
        assertEquals(3, l.first().monthsActive); assertEquals(3, l.first().monthsSpan)
        assertEquals(1, l.first { it.key == "B" }.monthsActive); assertEquals(2, l.first { it.key == "B" }.monthsSpan)
    }
}
