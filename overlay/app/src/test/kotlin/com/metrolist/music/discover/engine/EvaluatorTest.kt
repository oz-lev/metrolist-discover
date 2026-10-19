/**
 * Metrolist Discover (fork addition) - GPL-3.0. Plain JVM tests, synthetic data only.
 */
package com.metrolist.music.discover.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.util.Properties

class EvaluatorTest {
    private val world = SyntheticWorld.world()

    private fun average(mode: Evaluator.Mode, method: String, k: Int, engine: DiscoverEngine = DiscoverEngine()): Double =
        SyntheticWorld.PERSONAS.map { p ->
            Evaluator.get(Evaluator.holdout(SyntheticWorld.history(world, p), 14.0, listOf(20, 50), mode, engine), method, k)!!.recall
        }.average()

    @Test fun engineBeatsNaiveBaselinesOnSyntheticHistories() {
        for (mode in Evaluator.Mode.values()) {
            val e = average(mode, Evaluator.ENGINE, 20)
            assertTrue("$mode engine $e vs random", e > average(mode, Evaluator.RANDOM, 20))
            assertTrue("$mode engine $e vs related", e > average(mode, Evaluator.RELATED, 20))
        }
    }

    /** CI gate: fails when a change makes the engine measurably worse than the stored baseline. */
    @Test fun noRegressionAgainstStoredBaseline() {
        val props = Properties()
        val stream = javaClass.classLoader!!.getResourceAsStream("discover/baseline.properties")
        assertNotNull("baseline.properties on the test classpath", stream)
        stream!!.use { props.load(it) }
        for (key in props.stringPropertyNames()) {
            val (method, mode, kk) = key.split(".")
            val k = kk.removePrefix("recall@").toInt()
            val now = average(Evaluator.Mode.valueOf(mode), method, k)
            val base = props.getProperty(key).toDouble()
            assertTrue("$key regressed: %.4f < baseline %.4f".format(now, base), now >= base - 0.02)
        }
    }

    @Test fun holdoutHidesTheFuture() {
        val h = SyntheticWorld.history(world, SyntheticWorld.PERSONAS[0])
        val m = Evaluator.holdout(h, 14.0, listOf(20), Evaluator.Mode.NEW_ONLY)
        assertTrue(m.all { it.targets > 0 && it.recall in 0.0..1.0 })
    }
}
