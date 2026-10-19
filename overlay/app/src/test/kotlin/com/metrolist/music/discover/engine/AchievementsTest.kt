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

import java.time.LocalDateTime
import java.time.ZoneOffset

class AchievementsTest {
    private fun ts(d: Int, h: Int) = LocalDateTime.of(2026, 10, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun streaksLevelsAndBadges() {
        val tracks = (0 until 10).associate { "t$it" to TrackMeta("t$it", "Song $it (Phonk)", listOf("a${it % 3}"), listOf("Kordhell")) }
        val plays = ArrayList<PlayEvent>()
        for (d in 10..19) for (k in 0 until 12) plays += PlayEvent("t${k % 10}", ts(d, 1 + (k % 3)), 180_000)
        val h = History(tracks, plays)
        val p = Achievements.compute(h, ts(19, 20))
        assertEquals(10, p.currentStreak)
        assertEquals(10, p.bestStreak)
        assertTrue(p.level >= 2)
        assertTrue(p.levelProgress in 0.0..1.0)
        assertEquals(8, p.badges.size)
        assertTrue(p.badges.first { it.id == "night_owl" }.earned)   // 120 plays between 01:00 and 03:00
        assertTrue(p.badges.first { it.id == "streak7" }.earned)
        assertTrue(p.badges.first { it.id == "drop_hunter" }.earned)
        assertFalse(p.badges.first { it.id == "explorer" }.earned)
        assertEquals(84, p.weekly.plays)
    }

    @Test fun streakBreaks() {
        val tracks = mapOf("t" to TrackMeta("t", "x", listOf("a")))
        val plays = listOf(10, 11, 12, 15, 16).map { PlayEvent("t", ts(it, 12), 180_000) }
        val p = Achievements.compute(History(tracks, plays), ts(17, 9))
        assertEquals(2, p.currentStreak) // 15,16 and today (17) not yet played
        assertEquals(3, p.bestStreak)
        assertEquals(0, Achievements.compute(History(tracks, plays), ts(19, 9)).currentStreak)
    }

    @Test fun levelCurve() {
        assertEquals(1, Achievements.levelOf(0))
        assertEquals(2, Achievements.levelOf(250))
        assertEquals(3, Achievements.levelOf(1000))
        assertEquals(1000L, Achievements.xpForLevel(3))
    }
}
