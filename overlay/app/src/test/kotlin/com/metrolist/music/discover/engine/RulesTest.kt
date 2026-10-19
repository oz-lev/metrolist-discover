/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
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

class RulesTest {
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val walker = TrackMeta("w1", "Faded", listOf("UC_walker"), listOf("Alan Walker"), durationSec = 212)
    private val osher = TrackMeta("o1", "שיר", listOf("UC_osher"), listOf("אושר כהן"), durationSec = 190)

    // 2026-10-16 is a Friday, 2026-10-17 a Saturday
    @Test fun artistBlockOnFridayOnly() {
        val rule = BlockRule(BlockKind.ARTIST, "alan walker", setOf(5))
        val eng = RuleEngine(RuleSet(blocks = listOf(rule)), null)
        assertEquals(Violation.BLOCKED, eng.violation(walker, null, ts(2026, 10, 16, 14)))
        assertNull(eng.violation(walker, null, ts(2026, 10, 17, 14)))
        assertNull(eng.violation(osher, null, ts(2026, 10, 16, 14)))
    }

    @Test fun blockWindowWrapsPastMidnight() {
        val rule = BlockRule(BlockKind.ARTIST, "UC_walker", setOf(5), fromMinute = 20 * 60, toMinute = 2 * 60)
        assertFalse(rule.isActiveAt(5, 19 * 60 + 59))
        assertTrue(rule.isActiveAt(5, 20 * 60))
        assertTrue(rule.isActiveAt(5, 23 * 60 + 59))
        assertTrue(rule.isActiveAt(6, 60))      // Saturday 01:00 belongs to Friday night
        assertFalse(rule.isActiveAt(6, 2 * 60)) // ends at 02:00
        assertFalse(rule.isActiveAt(4, 60))     // Friday 01:00 is Thursday night: not blocked
        // Sunday(7) -> Monday(1) wrap
        val sun = BlockRule(BlockKind.ARTIST, "x", setOf(7), 22 * 60, 6 * 60)
        assertTrue(sun.isActiveAt(1, 5 * 60))
    }

    @Test fun daytimeWindow() {
        val rule = BlockRule(BlockKind.ARTIST, "alan walker", setOf(1, 2, 3, 4, 7), 8 * 60, 12 * 60)
        assertTrue(rule.isActiveAt(7, 9 * 60))
        assertFalse(rule.isActiveAt(7, 12 * 60))
        assertFalse(rule.isActiveAt(6, 9 * 60))
    }

    @Test fun sadSongsBlockedOnFriday() {
        val rule = BlockRule(BlockKind.SAD_SONGS, "", setOf(5))
        val est = VibeEstimator()
        val sad = TrackMeta("s", "פרידה", listOf("x"), listOf("Someone"))
        val eng = RuleEngine(RuleSet(blocks = listOf(rule)), null)
        assertEquals(Violation.BLOCKED, eng.violation(sad, est.own(sad), ts(2026, 10, 16, 21)))
        assertNull(eng.violation(sad, est.own(sad), ts(2026, 10, 18, 21)))
    }

    @Test fun rulesSurviveEncoding() {
        val list = listOf(BlockRule(BlockKind.ARTIST, "Alan Walker", setOf(5, 6), 1200, 120), BlockRule(BlockKind.KEYWORD, "remix", setOf(1)))
        assertEquals(list, BlockRule.decodeAll(BlockRule.encodeAll(list)))
    }

    @Test fun noRepeatLockSameDayOnly() {
        val now = ts(2026, 10, 19, 18)
        val plays = listOf(
            PlayEvent("w1", ts(2026, 10, 19, 9), 200_000),   // today, full
            PlayEvent("o1", ts(2026, 10, 18, 23, 50), 200_000), // yesterday
            PlayEvent("x1", ts(2026, 10, 19, 10), 5_000),     // today but skipped after 5 s
        )
        val lock = DayLock.from(plays, now)
        assertFalse(lock.allows("w1"))
        assertTrue(lock.allows("o1"))
        assertTrue(lock.allows("x1"))
        val eng = RuleEngine(RuleSet(noRepeatToday = true), lock)
        assertEquals(Violation.REPEAT_TODAY, eng.violation(walker, null, now))
        assertNull(eng.violation(osher, null, now))
        lock.markPlayed("o1", now)
        assertEquals(Violation.REPEAT_TODAY, eng.violation(osher, null, now))
        // lock off -> allowed
        assertNull(RuleEngine(RuleSet(noRepeatToday = false), lock).violation(walker, null, now))
        // a new day starts clean
        assertTrue(DayLock.from(plays, ts(2026, 10, 20, 0, 1)).allows("w1"))
    }

    @Test fun shortSongsOnly() {
        val eng = RuleEngine(RuleSet(maxDurationSec = 180), null)
        assertEquals(Violation.TOO_LONG, eng.violation(walker, null, ts(2026, 10, 19, 10)))
        assertNull(eng.violation(walker.copy(durationSec = 179), null, ts(2026, 10, 19, 10)))
    }

    @Test fun noRemixNoLive() {
        val eng = RuleEngine(RuleSet(noRemix = true, noLive = true), null)
        assertEquals(Violation.REMIX, eng.violation(walker.copy(title = "Faded (Remix)"), null, 0))
        assertEquals(Violation.LIVE, eng.violation(walker.copy(title = "Faded (Live)"), null, 0))
        assertNull(eng.violation(walker.copy(title = "Delivery"), null, 0)) // "live" inside a word is fine
    }

    @Test fun mizrahiCap() {
        val v = { m: Double -> Vibe(0.5, 0.5, m, 0.1) }
        val list = (0 until 20).map { PoolTrack(TrackMeta("t$it", "s", listOf("a$it")), v(if (it % 2 == 0) 0.9 else 0.1), 0.5, 0.5) }
        val capped = RuleEngine.capMizrahi(list, 30)
        val share = capped.count { it.vibe.isMizrahi } * 100.0 / capped.size
        assertTrue("share $share", share <= 30.0)
        assertEquals(0, RuleEngine.capMizrahi(list, 0).count { it.vibe.isMizrahi })
        assertEquals(list, RuleEngine.capMizrahi(list, 100))
        assertTrue(RuleEngine.mizrahiOverCap(List(9) { true }, true, 50))
        assertFalse(RuleEngine.mizrahiOverCap(List(9) { false }, true, 50))
    }

    @Test fun neighboursWindow() {
        val n = NeighboursMode(enabled = true)
        assertTrue(n.activeAt(23 * 60)); assertTrue(n.activeAt(3 * 60)); assertFalse(n.activeAt(7 * 60)); assertFalse(n.activeAt(22 * 60 + 59))
        assertFalse(NeighboursMode(enabled = false).activeAt(23 * 60 + 30))
    }

    @Test fun dayClock() {
        assertEquals(5, DayClock.dayOfWeek(ts(2026, 10, 16, 12)))
        assertEquals(LocalDate.of(2026, 10, 16), DayClock.date(ts(2026, 10, 16, 23, 59)))
        assertEquals(23 * 60 + 59, DayClock.minuteOfDay(ts(2026, 10, 16, 23, 59)))
    }
}
