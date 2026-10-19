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

import kotlin.math.abs
import kotlin.random.Random

class VibeMixerTest {
    private fun pool(n: Int, seed: Int = 1): List<PoolTrack> {
        val r = Random(seed)
        return (0 until n).map { i ->
            val tm = TrackMeta("t$i", "Song $i", listOf("artist${i % 23}"), listOf("Artist ${i % 23}"), durationSec = 120 + r.nextInt(240))
            PoolTrack(tm, Vibe(r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble() * 0.5), r.nextDouble(), r.nextDouble())
        }
    }

    @Test fun tripLengthWithinTenPercent() {
        val m = VibeMixer(Random(7))
        for (minutes in listOf(10, 25, 40, 75, 120)) {
            val mix = m.tripLength(pool(300, minutes), minutes)
            val total = mix.sumOf { it.track.durationSec }
            val goal = minutes * 60
            assertTrue("$minutes min -> $total s", abs(total - goal) <= goal * 0.10)
            assertEquals(mix.size, mix.map { it.track.id }.toSet().size)
        }
    }

    @Test fun tripLengthIsUsuallyVeryClose() {
        val mix = VibeMixer(Random(3)).tripLength(pool(300, 9), 25)
        val total = mix.sumOf { it.track.durationSec }
        assertTrue("25 min -> $total s", abs(total - 1500) <= 1500 * 0.03)
    }

    @Test fun moodFlipStartsSadEndsParty() {
        val mix = VibeMixer(Random(2)).moodFlip(pool(200), 20)
        assertEquals(20, mix.size)
        for (i in 1 until mix.size) assertTrue(mix[i].vibe.moodScore >= mix[i - 1].vibe.moodScore)
        val first = mix.take(5).map { it.vibe.valence }.average()
        val last = mix.takeLast(5).map { it.vibe.valence }.average()
        assertTrue("first $first last $last", last - first > 0.4)
        assertTrue(mix.first().vibe.energy < mix.last().vibe.energy)
    }

    @Test fun moodMixFollowsTheTarget() {
        val m = VibeMixer(Random(5))
        val p = pool(300)
        val gym = m.build(p, MoodPreset.GYM.target, 20)
        val night = m.build(p, MoodPreset.NIGHT.target, 20)
        assertTrue(gym.map { it.vibe.energy }.average() > night.map { it.vibe.energy }.average() + 0.3)
    }

    @Test fun mizrahiSliderMovesTheMix() {
        val m = VibeMixer(Random(5))
        val p = pool(300)
        val miz = m.build(p, m.target(null, Sliders(mizrahiEdm = 1.0)), 20)
        val edm = m.build(p, m.target(null, Sliders(mizrahiEdm = 0.0)), 20)
        assertTrue(miz.map { it.vibe.mizrahi }.average() > edm.map { it.vibe.mizrahi }.average() + 0.4)
    }

    @Test fun diversifyNeverRepeatsAnArtistBackToBack() {
        val m = VibeMixer(Random(1))
        val mix = m.build(pool(300), MoodPreset.PARTY.target, 25)
        for (i in 1 until mix.size) assertTrue(mix[i].track.mainArtist != mix[i - 1].track.mainArtist)
        assertTrue(mix.groupingBy { it.track.mainArtist }.eachCount().values.all { it <= 2 })
    }

    @Test fun energyUpRamps() {
        val m = VibeMixer(Random(4))
        val p = pool(300)
        val cur = p.first { it.vibe.energy in 0.4..0.5 }
        val next = m.reshape(cur, p, VibeAction.ENERGY_UP, 10)
        assertTrue(next.isNotEmpty())
        assertTrue(next.all { it.vibe.energy >= cur.vibe.energy - 0.02 })
        for (i in 1 until next.size) assertTrue(next[i].vibe.energy >= next[i - 1].vibe.energy)
    }

    @Test fun changeVibeMovesAway() {
        val m = VibeMixer(Random(4))
        val p = pool(300)
        val cur = p.first { it.vibe.energy > 0.8 }
        val next = m.reshape(cur, p, VibeAction.CHANGE_VIBE, 10)
        assertTrue(next.map { it.vibe.energy }.average() < cur.vibe.energy - 0.2)
    }

    @Test fun dramaPeaksAfterTheMiddle() {
        val mix = VibeMixer(Random(9)).drama(pool(300), 18)
        val peak = mix.indices.maxByOrNull { mix[it].vibe.energy }!!
        assertTrue("peak at $peak of ${mix.size}", peak >= mix.size / 2 - 1)
    }
}
