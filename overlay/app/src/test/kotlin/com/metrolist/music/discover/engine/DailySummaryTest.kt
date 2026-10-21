/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class DailySummaryTest {
    /** Metrolist's convention: local wall clock stored as UTC millis. */
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val min = 60_000L
    private val tracks = mapOf(
        "a" to TrackMeta("a", "Faded", listOf("UC_w"), listOf("Alan Walker"), durationSec = 212),
        "b" to TrackMeta("b", "שיר \"מיוחד\"", listOf("UC_o"), listOf("אושר כהן"), durationSec = 190),
        "c" to TrackMeta("c", "Calm Piano", listOf("UC_p"), listOf("Piano Guy"), durationSec = 300),
        "d" to TrackMeta("d", "Other", listOf("UC_o"), listOf("אושר כהן"), durationSec = 200),
    )
    private val oct21 = LocalDate.of(2026, 10, 21)

    private val plays = listOf(
        DailyPlay("a", ts(2026, 10, 21, 8, 5), 4 * min),   // started 08:01
        DailyPlay("b", ts(2026, 10, 21, 9, 0), 3 * min),
        DailyPlay("b", ts(2026, 10, 21, 12, 0), 3 * min),
        DailyPlay("d", ts(2026, 10, 21, 13, 0), 2 * min),
        DailyPlay("a", ts(2026, 10, 21, 22, 47), 3 * min + 30_000),
        DailyPlay("c", ts(2026, 10, 20, 23, 59), 5 * min), // the day before
        DailyPlay("c", ts(2026, 10, 22, 0, 2), 5 * min),   // crosses midnight: belongs to the 22nd (its end), starts clamped to 00:00
    )

    @Test fun aggregatesOneDay() {
        val s = DailySummaries.day(oct21, plays, tracks)
        assertEquals(5, s.plays)
        assertEquals(3, s.uniqueTracks)
        assertEquals(16, s.minutesListened) // 15.5 min rounds to 16
        // a and b both have 2 plays; a has more time (7.5 vs 6 min)
        assertEquals("Faded", s.topTrack)
        assertEquals("a", s.topTrackId)
        // אושר כהן: 3 plays (b x2 + d) beats Alan Walker's 2
        assertEquals("אושר כהן", s.topArtist)
        assertEquals("08:01", s.firstPlayTime)
        assertEquals("22:47", s.lastPlayTime)
        assertEquals(listOf("a", "b", "d"), s.top5.map { it.songId })
        assertNull(s.moodHint) // no estimator given
    }

    @Test fun midnightAndTimezoneConvention() {
        val s = DailySummaries.day(LocalDate.of(2026, 10, 22), plays, tracks)
        assertEquals(1, s.plays)
        assertEquals("00:00", s.firstPlayTime)
        assertEquals("00:02", s.lastPlayTime)
        val prev = DailySummaries.day(LocalDate.of(2026, 10, 20), plays, tracks)
        assertEquals("23:54", prev.firstPlayTime)
    }

    @Test fun emptyDayIsZeros() {
        val s = DailySummaries.day(LocalDate.of(2026, 1, 1), plays, tracks)
        assertEquals(0, s.plays); assertEquals(0, s.minutesListened); assertEquals(0, s.uniqueTracks)
        assertNull(s.topTrack); assertNull(s.topArtist); assertNull(s.firstPlayTime); assertNull(s.moodHint)
        assertEquals("[]", s.top5Json())
    }

    @Test fun rangeHasOneRowPerDay() {
        val r = DailySummaries.range(plays, tracks, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23))
        assertEquals(listOf(19, 20, 21, 22, 23), r.map { it.date.dayOfMonth })
        assertEquals(listOf(0, 1, 5, 1, 0), r.map { it.plays })
    }

    @Test(expected = IllegalArgumentException::class) fun rangeBackwardsRejected() {
        DailySummaries.range(plays, tracks, oct21, oct21.minusDays(1))
    }

    @Test(expected = IllegalArgumentException::class) fun rangeTooLongRejected() {
        DailySummaries.range(plays, tracks, oct21, oct21.plusDays(DailySummaries.MAX_RANGE_DAYS.toLong()))
    }

    @Test fun top5JsonEscapesAndCaps() {
        val many = (1..8).map { DailyPlay("t$it", ts(2026, 10, 21, 10, it), min) } + DailyPlay("b", ts(2026, 10, 21, 11), min)
        val s = DailySummaries.day(oct21, many, tracks)
        assertEquals(5, s.top5.size)
        val j = s.top5Json()
        assertTrue(j.startsWith("[{\"title\":"))
        assertTrue(j.contains("\"title\":\"שיר \\\"מיוחד\\\"\",\"artist\":\"אושר כהן\",\"plays\":1,\"id\":\"b\""))
        assertTrue(s.top5.filter { it.songId != "b" }.all { it.title == it.songId && it.artist == "" }) // unknown track: id as title
    }

    @Test fun moodHintFromVibes() {
        val est = VibeEstimator()
        val s = DailySummaries.day(oct21, plays, tracks) { est.own(it) }
        assertTrue(s.moodHint in setOf("energetic", "calm", "happy", "melancholic", "balanced"))
        assertEquals("energetic", DailySummaries.moodLabel(0.8, 0.5))
        assertEquals("calm", DailySummaries.moodLabel(0.3, 0.8))
        assertEquals("happy", DailySummaries.moodLabel(0.55, 0.7))
        assertEquals("melancholic", DailySummaries.moodLabel(0.55, 0.3))
        assertEquals("balanced", DailySummaries.moodLabel(0.55, 0.55))
        // weighted by listening time: a long calm play outweighs a short energetic one
        val fixed = mapOf("a" to Vibe(0.9, 0.5, 0.0, 0.0), "c" to Vibe(0.2, 0.5, 0.0, 0.0))
        val p = listOf(DailyPlay("a", ts(2026, 10, 21, 10), min), DailyPlay("c", ts(2026, 10, 21, 11), 20 * min))
        assertEquals("calm", DailySummaries.day(oct21, p, tracks) { fixed.getValue(it.id) }.moodHint)
    }

    @Test fun parsesStrictDates() {
        assertEquals(oct21, DailySummaries.parseDate("2026-10-21"))
        assertNull(DailySummaries.parseDate("2026-02-30"))
        assertNull(DailySummaries.parseDate("21-10-2026"))
        assertNull(DailySummaries.parseDate("2026-10-21x"))
        assertNull(DailySummaries.parseDate(null))
    }

    @Test fun callerRule() {
        val self = "com.metrolist.music.oz"
        assertTrue(DailySummaries.callerAllowed("com.oz.yomi", self))
        assertTrue(DailySummaries.callerAllowed("com.oz.yomi.debug", self))
        assertTrue(DailySummaries.callerAllowed(self, self))
        assertFalse(DailySummaries.callerAllowed("com.metrolist.music", self))
        assertFalse(DailySummaries.callerAllowed("com.evil.app", self))
        assertFalse(DailySummaries.callerAllowed("org.com.oz.yomi", self))
        assertFalse(DailySummaries.callerAllowed(null, self))
    }
}
