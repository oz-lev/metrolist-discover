/**
 * Metrolist Discover 13.7.0.11 (fork addition) - GPL-3.0
 * The full statistics engine: totals, local-time period boundaries, streaks, sessions, skips, first-heard,
 * the previous-period comparison and the CSV export.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class FullStats1311Test {
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun t(id: String, artist: String, album: String? = null, sec: Int = 200) = TrackMeta(id, "Song $id", listOf(artist), listOf("Artist $artist"), album, sec)
    private val tracks = listOf(t("a1", "A", "alA"), t("a2", "A", "alA"), t("b1", "B", "alB"), t("c1", "C"), t("d1", "D")).associateBy { it.id }
    private fun play(id: String, at: Long, playedSec: Int) = PlayEvent(id, at, playedSec * 1000L)
    private fun hist(vararg p: PlayEvent) = History(tracks, p.toList())

    // a Sunday: 2026-11-08 (the Israeli week starts on Sunday)
    private val now = ts(2026, 11, 8, 12)

    @Test fun totalsUseRealPlayedTimeNotCountTimesLength() {
        val h = hist(play("a1", ts(2026, 11, 2, 10), 200), play("a1", ts(2026, 11, 3, 10), 100), play("b1", ts(2026, 11, 4, 10), 50))
        val r = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.MONTH), now)
        assertEquals(350_000L, r.overview.totalMs)
        assertEquals(3, r.overview.plays)
        assertEquals(2, r.overview.songs)
        assertEquals(2, r.overview.artists)
        assertEquals(2, r.overview.albums)
        assertEquals(7, r.overview.days)                 // Nov 2 (the first listen ever) .. Nov 8
        assertEquals(350_000L / 7, r.overview.dailyAvgMs)
        assertEquals("A", r.artists.first().key)
        assertEquals(300_000L, r.artists.first().ms)
        assertEquals(1, r.artists.first().songs)
        assertEquals(300.0 / 350.0, r.artists.first().share, 1e-9)
    }

    @Test fun unrecordedPlayTimeFallsBackToSongLengthAndIsMarked() {
        val h = hist(PlayEvent("a1", ts(2026, 11, 2, 10), 0L))
        val r = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.MONTH), now)
        assertEquals(200_000L, r.overview.totalMs)
        assertEquals(200_000L, r.overview.estimatedMs)
    }

    @Test fun periodBoundariesAreLocalCalendarUnits() {
        val w = StatsPeriods.window(StatsPeriod(StatsUnit.WEEK), now, null)
        assertEquals(ts(2026, 11, 8, 0), w.from)        // Sunday
        assertEquals(ts(2026, 11, 15, 0), w.to)
        val lastWeek = StatsPeriods.window(StatsPeriod(StatsUnit.WEEK, -1), now, null)
        assertEquals(ts(2026, 11, 1, 0), lastWeek.from)
        val wed = ts(2026, 11, 11, 23, 59)
        assertEquals(ts(2026, 11, 8, 0), StatsPeriods.window(StatsPeriod(StatsUnit.WEEK), wed, null).from)
        val m = StatsPeriods.window(StatsPeriod(StatsUnit.MONTH, -10), now, null)
        assertEquals(ts(2026, 1, 1, 0), m.from); assertEquals(ts(2026, 2, 1, 0), m.to)
        val y = StatsPeriods.window(StatsPeriod(StatsUnit.YEAR, -1), now, null)
        assertEquals(ts(2025, 1, 1, 0), y.from); assertEquals(ts(2026, 1, 1, 0), y.to)
        // a listen at 23:59 local on Oct 31 belongs to October, one at 00:00 Nov 1 to November
        val h = hist(play("a1", ts(2026, 10, 31, 23, 59), 100), play("b1", ts(2026, 11, 1, 0, 0), 100))
        val ix = FullStats.index(h)
        assertEquals(listOf("b1"), FullStats.report(ix, StatsPeriod(StatsUnit.MONTH), now).songs.map { it.track.id })
        assertEquals(listOf("a1"), FullStats.report(ix, StatsPeriod(StatsUnit.MONTH, -1), now).songs.map { it.track.id })
        assertEquals("נובמבר 2026", StatsPeriods.label(StatsPeriod(StatsUnit.MONTH), now))
        assertEquals("2025", StatsPeriods.label(StatsPeriod(StatsUnit.YEAR, -1), now))
        assertEquals(-2, StatsPeriods.offsetOf(StatsUnit.MONTH, LocalDate.of(2026, 9, 14), now))
        assertEquals(-1, StatsPeriods.offsetOf(StatsUnit.YEAR, LocalDate.of(2025, 3, 1), now))
    }

    @Test fun allTimeStartsAtTheFirstListen() {
        val h = hist(play("a1", ts(2026, 4, 16, 9), 100), play("b1", ts(2026, 11, 7, 9), 100))
        val r = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.ALL), now)
        assertEquals(ts(2026, 4, 16, 0), r.window.from)
        assertEquals(2, r.overview.plays)
        assertNull(r.overview.timeChangePct)
        assertEquals("אפר׳", r.monthly.first().label)
        assertEquals(8, r.monthly.size)                   // Apr..Nov
    }

    @Test fun comparisonWithThePreviousPeriodCutToTheSameElapsedLength() {
        // now = Nov 8 12:00: this month so far is 7.5 days; October's first 7.5 days are the comparison
        val h = hist(
            play("a1", ts(2026, 10, 3, 10), 100),          // inside Oct 1 .. Oct 8 12:00
            play("a1", ts(2026, 10, 20, 10), 600),         // later in October: not compared
            play("b1", ts(2026, 11, 2, 10), 150),
        )
        val r = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.MONTH), now)
        assertEquals(100_000L, r.overview.prevTotalMs)
        assertEquals(50, r.overview.timeChangePct)
        val prev = StatsPeriods.previous(StatsPeriod(StatsUnit.MONTH), now, null)!!
        assertEquals(ts(2026, 10, 1, 0), prev.from); assertEquals(ts(2026, 10, 8, 12), prev.to)
        // a finished month is compared with the whole month before it
        val oct = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.MONTH, -1), now)
        assertEquals(700_000L, oct.overview.totalMs)
        assertEquals(0L, oct.overview.prevTotalMs)
        assertNull(oct.overview.timeChangePct)
        assertEquals(-25, StatsPeriods.changePct(75, 100))
        assertEquals(100, StatsPeriods.changePct(200, 100))
    }

    @Test fun longestStreakOfListeningDays() {
        val days = listOf(1, 2, 3, 5, 6, 7, 8, 9, 20).map { LocalDate.of(2026, 10, it) }
        val s = FullStats.longestStreak(days)
        assertEquals(5, s.days); assertEquals(LocalDate.of(2026, 10, 5), s.from); assertEquals(LocalDate.of(2026, 10, 9), s.to)
        assertEquals(0, FullStats.longestStreak(emptyList()).days)
        assertEquals(1, FullStats.longestStreak(listOf(LocalDate.of(2026, 1, 1))).days)
        // across a month boundary
        assertEquals(3, FullStats.longestStreak(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2))).days)
    }

    @Test fun aGapOfThirtyMinutesOrMoreStartsANewSession() {
        // song 1 ends 10:00 (started 9:56:40), song 2 starts 10:29:59 (gap 29:59 -> same session), song 3 starts exactly 30:00 after song 2 ends
        val h = hist(
            play("a1", ts(2026, 11, 2, 10, 0, 0), 200),
            play("a2", ts(2026, 11, 2, 10, 33, 19), 200),
            play("b1", ts(2026, 11, 2, 11, 6, 39), 200),
        )
        val ix = FullStats.index(h)
        val s = FullStats.sessions(ix.rows)
        assertEquals(2, s.size)
        assertEquals(2, s[0].songs)
        assertEquals(ts(2026, 11, 2, 9, 56, 40), s[0].start)
        assertEquals(ts(2026, 11, 2, 10, 33, 19), s[0].end)
        assertEquals(400_000L, s[0].ms)
        val r = FullStats.report(ix, StatsPeriod(StatsUnit.MONTH), now)
        assertEquals(2, r.sessions)
        assertEquals(s[0], r.longestSession)
    }

    @Test fun skipsSkipRateAndCompletion() {
        val p = ArrayList<PlayEvent>()
        // 30 normal full listens to build the thresholds, then d1 skipped 3 of 4 times
        for (i in 0 until 30) p += play(if (i % 2 == 0) "a1" else "b1", ts(2026, 11, 1, 8) + i * 300_000L, 195)
        p += play("d1", ts(2026, 11, 3, 8), 5); p += play("d1", ts(2026, 11, 3, 9), 8); p += play("d1", ts(2026, 11, 3, 10), 6)
        p += play("d1", ts(2026, 11, 3, 11), 190)
        val r = FullStats.report(FullStats.index(History(tracks, p)), StatsPeriod(StatsUnit.MONTH), now)
        val d = r.songs.first { it.track.id == "d1" }
        assertEquals(3, d.skips); assertEquals(4, d.events); assertEquals(0.75, d.skipRate, 1e-9)
        assertEquals(1, d.plays)                                   // only the 190 s listen is a play
        assertEquals(3, r.overview.skips)
        assertEquals("d1", r.mostSkippedSongs.first().track.id)
        assertEquals("D", r.mostSkippedArtists.first().key)
        assertEquals(31.0 / 34.0, r.completionRate!!, 1e-9)
    }

    @Test fun firstHeardIsAllTimeAndDrivesDiscovery() {
        val h = hist(
            play("a1", ts(2026, 9, 10, 10), 200),      // A known since September
            play("a1", ts(2026, 11, 2, 10), 200),
            play("a2", ts(2026, 11, 3, 10), 100),      // new song of a known artist
            play("c1", ts(2026, 11, 4, 10), 5),        // C only skipped so far...
            play("c1", ts(2026, 11, 5, 10), 100),      // ...first real play on Nov 5
            play("d1", ts(2026, 11, 6, 10), 100),      // D heard exactly once: a one-hit artist
        )
        val ix = FullStats.index(h)
        assertEquals(ts(2026, 9, 10, 10), ix.firstTrack["a1"])
        assertEquals(ts(2026, 11, 5, 10), ix.firstTrack["c1"])
        val r = FullStats.report(ix, StatsPeriod(StatsUnit.MONTH), now)
        assertEquals(listOf("C", "D"), r.discovered.map { it.key })
        assertEquals(listOf("D"), r.oneHit.map { it.key })        // C has one play but was also heard (skipped) before
        assertEquals(200_000L, r.familiarMs)
        assertEquals(305_000L, r.newMs)
        assertEquals(ts(2026, 9, 10, 10), r.artists.first { it.key == "A" }.firstHeard)
        assertEquals(ts(2026, 11, 3, 10), r.artists.first { it.key == "A" }.lastHeard)
        val nm = r.newArtistsPerMonth.associate { it.label to it.value }
        assertEquals(1L, nm["ספט׳"]); assertEquals(2L, nm["נוב׳"])
        val sep = r.monthTops.first { it.month.monthValue == 9 }
        assertEquals("Artist A", sep.artist)
    }

    @Test fun artistAndSongDetail() {
        val h = hist(play("a1", ts(2026, 9, 10, 10), 200), play("a2", ts(2026, 11, 2, 10), 200), play("a2", ts(2026, 11, 3, 10), 200), play("b1", ts(2026, 11, 3, 11), 200))
        val ix = FullStats.index(h)
        val a = FullStats.artist(ix, "A", now)!!
        assertEquals(600_000L, a.totalMs); assertEquals(3, a.plays); assertEquals(2, a.songs)
        assertEquals("a2", a.topSongs.first().track.id)
        assertEquals(ts(2026, 9, 10, 10), a.firstHeard)
        assertEquals(listOf(3L, 0L, 6L), a.monthly.map { it.value })   // Sep, Oct, Nov minutes
        val s = FullStats.song(ix, "a2", now)!!
        assertEquals(2, s.row.plays)
        assertEquals(listOf(2L), s.monthly.map { it.value })
        assertNull(FullStats.artist(ix, "nobody", now))
    }

    @Test fun heatMapIsWeekdayByHourInLocalTime() {
        // Sunday Nov 1 2026 at 21:30 local, 10 minutes
        val h = hist(play("a1", ts(2026, 11, 1, 21, 30), 600))
        val r = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.MONTH), now)
        assertEquals(10L, r.heat[0][21])
        assertEquals(10L, r.heat.sumOf { it.sum() })
    }

    @Test fun albumsUseIdOrTitle() {
        val h = hist(play("a1", ts(2026, 11, 2, 10), 200), play("a2", ts(2026, 11, 2, 11), 100), play("c1", ts(2026, 11, 2, 12), 100))
        val ix = FullStats.index(h, mapOf("a1" to "First Album", "a2" to "First Album", "c1" to "Loose"))
        val r = FullStats.report(ix, StatsPeriod(StatsUnit.MONTH), now)
        assertEquals("First Album", r.albums.first().name)
        assertEquals(2, r.albums.first().songs)
        assertEquals("Artist A", r.albums.first().artist)
        assertTrue(r.albums.any { it.key == "n:Loose" })
    }

    @Test fun csvEscapingAndRows() {
        assertEquals("plain", StatsCsv.escape("plain"))
        assertEquals("\"a,b\"", StatsCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", StatsCsv.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", StatsCsv.escape("two\nlines"))
        assertEquals("שיר בעברית", StatsCsv.escape("שיר בעברית"))
        val tr = mapOf("x" to TrackMeta("x", "Hello, \"World\"", listOf("Z"), listOf("זמר"), "al", 200))
        val ix = FullStats.index(History(tr, listOf(PlayEvent("x", ts(2026, 11, 2, 9, 5, 7), 61_900L))), mapOf("x" to "אלבום, ראשון"))
        val csv = StatsCsv.build(ix)
        assertTrue(csv.startsWith("\uFEFF" + StatsCsv.HEADER + "\r\n"))
        val line = csv.removePrefix("\uFEFF").split("\r\n")[1]
        assertEquals("2026-11-02,09:05:07,\"Hello, \"\"World\"\"\",זמר,\"אלבום, ראשון\",61,0", line)
        val none = StatsCsv.build(ix, StatsWindow(0L, 1L), bom = false)
        assertEquals(StatsCsv.HEADER + "\r\n", none)
    }

    @Test fun aYearThatBeganBeforeTheFirstListenAveragesFromThatDay() {
        val h = hist(play("a1", ts(2026, 11, 1, 10), 600), play("b1", ts(2026, 11, 8, 10), 600))
        val r = FullStats.report(FullStats.index(h), StatsPeriod(StatsUnit.YEAR), now)
        assertEquals(8, r.overview.days)                       // Nov 1..8, not Jan 1..Nov 8
        assertEquals(150_000L, r.overview.dailyAvgMs)
        assertEquals(listOf("נוב׳"), r.monthly.map { it.label })
        assertEquals(8, r.daily.size)
    }

    @Test fun emptyHistoryGivesAnEmptyReport() {
        val r = FullStats.report(FullStats.index(History(emptyMap(), emptyList())), StatsPeriod(StatsUnit.YEAR), now)
        assertTrue(r.isEmpty); assertNull(r.longestSession); assertNull(r.completionRate)
        assertEquals(0, r.longestStreak.days)
        assertNotNull(r.daily)
    }
}
