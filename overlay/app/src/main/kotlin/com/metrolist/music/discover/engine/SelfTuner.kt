/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * On-device self-tuning. Each recommended track that gets played becomes a training example:
 * reward = +1 full listen, +0.3 partial, -1 early skip, +1.5 extra when liked afterwards.
 * Weights take an online gradient step on (reward - prediction) * feature, with a decaying learning
 * rate and hard bounds, so a few odd days can never wreck the ranking. The explore share is a
 * two-armed Beta bandit (explore vs exploit slots) bounded to 15..45 %. reset() returns to defaults.
 */
data class TunerState(
    val weights: RankWeights = RankWeights(),
    val updates: Int = 0,
    val exploreWins: Double = 3.0, val exploreLosses: Double = 3.0,
    val exploitWins: Double = 3.0, val exploitLosses: Double = 3.0,
) {
    fun encode(): String = listOf(weights.encode().replace(",", ";"), updates, exploreWins, exploreLosses, exploitWins, exploitLosses)
        .joinToString("|")

    companion object {
        fun decode(s: String?): TunerState? = runCatching {
            val p = s!!.split("|")
            TunerState(RankWeights.decode(p[0].replace(";", ","))!!, p[1].toInt(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
        }.getOrNull()
    }
}

class SelfTuner(private val defaults: RankWeights = RankWeights(), private val baseExplore: Double = 0.30) {
    private val lo = defaults.toArray().map { it * 0.25 }.toDoubleArray()
    private val hi = defaults.toArray().map { max(it * 3.0, 0.3) }.toDoubleArray()

    fun reward(outcome: Outcome, liked: Boolean): Double = when (outcome) {
        Outcome.FULL -> 1.0
        Outcome.PARTIAL -> 0.3
        Outcome.SKIP -> -1.0
    } + if (liked) 1.5 else 0.0

    fun update(s: TunerState, f: Features, slot: Slot, reward: Double): TunerState {
        val w = s.weights.toArray()
        val x = f.toArray()
        val pred = tanh(s.weights.dot(f))
        val target = tanh(reward)
        val lr = 0.08 / sqrt(1.0 + s.updates / 25.0)
        val err = target - pred
        for (i in w.indices) {
            // penalty features are stored negated in x; their weights stay positive magnitudes
            w[i] = (w[i] + lr * err * x[i]).coerceIn(lo[i], hi[i])
        }
        val win = reward > 0
        return if (slot == Slot.EXPLORE) s.copy(
            weights = RankWeights.of(w), updates = s.updates + 1,
            exploreWins = s.exploreWins + if (win) 1 else 0, exploreLosses = s.exploreLosses + if (win) 0 else 1,
        ) else s.copy(
            weights = RankWeights.of(w), updates = s.updates + 1,
            exploitWins = s.exploitWins + if (win) 1 else 0, exploitLosses = s.exploitLosses + if (win) 0 else 1,
        )
    }

    /** Explore share: grows when explore picks succeed about as often as safe picks, shrinks when they flop. */
    fun exploreRatio(s: TunerState): Double {
        val e = s.exploreWins / (s.exploreWins + s.exploreLosses)
        val x = s.exploitWins / (s.exploitWins + s.exploitLosses)
        return (baseExplore * (e / x.coerceAtLeast(0.05))).coerceIn(0.15, 0.45)
    }

    fun reset(): TunerState = TunerState(weights = defaults)
}
