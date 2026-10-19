/**
 * Metrolist Discover (fork addition) - GPL-3.0. Plain JVM tests, synthetic data only.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTunerTest {
    @Test fun weightsMoveTowardWhatWorksAndStayBounded() {
        val tuner = SelfTuner()
        var s = tuner.reset()
        val timeHeavy = Features(0.2, 1.0, 0.5, 0.0, 0.0, 0.3)
        repeat(300) { s = tuner.update(s, timeHeavy, Slot.EXPLOIT, 1.0) }
        assertTrue(s.weights.wTimeOfDay > RankWeights().wTimeOfDay)
        val hi = RankWeights().toArray().map { maxOf(it * 3.0, 0.3) }
        s.weights.toArray().forEachIndexed { i, v -> assertTrue("weight $i=$v within bounds", v <= hi[i] + 1e-9) }
        var t = tuner.reset()
        val skippy = Features(0.5, 0.0, 0.5, 1.0, 0.0, 0.3)
        repeat(300) { t = tuner.update(t, skippy, Slot.EXPLOIT, -1.0) }
        assertTrue(t.weights.wSkip > RankWeights().wSkip)
        assertTrue(t.weights.toArray().all { it >= 0.0 })
    }

    @Test fun exploreShareFollowsExploreOutcomesWithinBounds() {
        val tuner = SelfTuner()
        var s = tuner.reset()
        val f = Features(0.1, 0.0, 1.0, 0.0, 0.0, 0.2)
        repeat(50) { s = tuner.update(s, f, Slot.EXPLORE, -1.0) }
        assertTrue(tuner.exploreRatio(s) < 0.3)
        assertTrue(tuner.exploreRatio(s) >= 0.15)
        var g = tuner.reset()
        repeat(50) { g = tuner.update(g, f, Slot.EXPLORE, 1.0); g = tuner.update(g, f, Slot.EXPLOIT, -1.0) }
        assertTrue(tuner.exploreRatio(g) in 0.3..0.45)
    }

    @Test fun stateSurvivesEncoding() {
        val s = TunerState(RankWeights(1.1, 0.2, 0.3, 0.9, 0.4, 0.25), 7, 4.0, 3.0, 5.0, 2.0)
        assertEquals(s, TunerState.decode(s.encode()))
        assertEquals(TunerState(), SelfTuner().reset())
    }
}
