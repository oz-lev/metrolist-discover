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

class SearchesTest {
    private fun ts(y: Int, mo: Int, d: Int, h: Int) = LocalDateTime.of(y, mo, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun parsesLikeXButY() {
        val r = QueryRewriter.parse("כמו Faded אבל במזרחית")
        assertEquals("Faded", r.base)
        assertEquals(QueryRewriter.Modifier.MIZRAHI, r.modifier)
        assertTrue(QueryRewriter.queries(r).all { it.contains("Faded") })
        assertEquals(QueryRewriter.Modifier.QUIETER, QueryRewriter.parse("כמו עדן בן זקן אבל יותר שקט").modifier)
        assertEquals(QueryRewriter.Modifier.REMIX, QueryRewriter.parse("like Alone but remix").modifier)
        assertNull(QueryRewriter.parse("just a song").modifier)
    }

    @Test fun rewriteFilters() {
        val r = QueryRewriter.parse("כמו X אבל יותר שקט")
        assertTrue(QueryRewriter.accepts(r, Vibe(0.3, 0.5, 0.0, 0.0), "x", Vibe(0.8, 0.5, 0.0, 0.0)))
        assertFalse(QueryRewriter.accepts(r, Vibe(0.9, 0.5, 0.0, 0.0), "x", Vibe(0.8, 0.5, 0.0, 0.0)))
    }

    @Test fun timeMachineFindsThatDay() {
        val a = TrackMeta("a", "A", listOf("x")); val b = TrackMeta("b", "B", listOf("y"))
        val plays = listOf(
            PlayEvent("a", ts(2025, 10, 19, 10), 200_000), PlayEvent("b", ts(2025, 10, 19, 11), 200_000), PlayEvent("b", ts(2025, 10, 19, 12), 200_000),
            PlayEvent("a", ts(2026, 9, 17, 10), 200_000),
        )
        val h = History(mapOf("a" to a, "b" to b), plays)
        val y = TimeMachine.lookup(h, ts(2026, 10, 19, 18), TimeMachine.Back.YEAR)
        assertTrue(y.exact)
        assertEquals(LocalDate.of(2025, 10, 19), y.date)
        assertEquals(listOf("b", "a"), y.tracks.map { it.first.id })
        assertEquals(2, y.tracks.first().second)
        // last month: 2026-09-19 has nothing, nearest within 3 days is 09-17
        val m = TimeMachine.lookup(h, ts(2026, 10, 19, 18), TimeMachine.Back.MONTH)
        assertFalse(m.exact)
        assertEquals(LocalDate.of(2026, 9, 17), m.date)
    }

    @Test fun colorBuckets() {
        assertEquals(ColorBuckets.Bucket.PURPLE, ColorBuckets.bucketOf(0x8E24AA))
        assertEquals(ColorBuckets.Bucket.RED, ColorBuckets.bucketOf(0xE01010))
        assertEquals(ColorBuckets.Bucket.BLUE, ColorBuckets.bucketOf(0x1E40E5))
        assertEquals(ColorBuckets.Bucket.BLACK, ColorBuckets.bucketOf(0x0A0A0A))
        assertEquals(ColorBuckets.Bucket.WHITE, ColorBuckets.bucketOf(0xF5F5F5))
        for (b in ColorBuckets.Bucket.entries) assertEquals(b.name, b, ColorBuckets.bucketOf(b.rgb))
    }

    @Test fun rouletteSeedsVary() {
        val r = kotlin.random.Random(1)
        val seeds = (0 until 30).map { Roulette.seed(r) }.toSet()
        assertTrue(seeds.size > 20)
    }
}
