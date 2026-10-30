/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

/**
 * The ranking weights: score = similarity*wSimilarity + timeOfDay*wTimeOfDay + novelty*wNovelty
 *   - skipPenalty*wSkip - overRepresentation*wOverRep + sourcePrior*wSource.
 * These six are the ones the on-device self-tuner adapts (within [min,max] bounds).
 */
data class RankWeights(
    val wSimilarity: Double = 1.0,
    val wTimeOfDay: Double = 0.20,
    val wNovelty: Double = 0.30,
    val wSkip: Double = 0.80,
    val wOverRep: Double = 0.45,
    val wSource: Double = 0.30,
) {
    fun toArray() = doubleArrayOf(wSimilarity, wTimeOfDay, wNovelty, wSkip, wOverRep, wSource)
    fun dot(f: Features): Double {
        val w = toArray(); val x = f.toArray()
        var s = 0.0
        for (i in w.indices) s += w[i] * x[i]
        return s
    }
    fun encode(): String = toArray().joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

    companion object {
        fun of(a: DoubleArray) = RankWeights(a[0], a[1], a[2], a[3], a[4], a[5])
        fun decode(s: String?): RankWeights? = runCatching {
            of(s!!.split(",").map { it.toDouble() }.toDoubleArray())
        }.getOrNull()
        val NAMES = listOf("similarity", "timeOfDay", "novelty", "skip", "overRep", "source")
    }
}

/** Every knob of the engine in one place. Defaults tuned on synthetic + one real history (see README-OZ.md). */
data class DiscoverConfig(
    val weights: RankWeights = RankWeights(),
    // --- signal strengths (per event, before time decay) ---
    val fullListenWeight: Double = 1.0,
    val partialListenWeight: Double = 0.35, // multiplied by completion ratio
    val skipWeight: Double = -0.9,
    val repeatBonus: Double = 1.5, // replay of the same track within repeatWindowHours
    val repeatWindowHours: Double = 24.0,
    val likeWeight: Double = 4.0,
    val playlistAddWeight: Double = 2.5,
    val downloadWeight: Double = 2.5,
    val libraryAddWeight: Double = 1.5,
    // --- profiles ---
    val shortHalfLifeDays: Double = 4.0,
    val longHalfLifeDays: Double = 60.0,
    val shortTermMix: Double = 0.6, // share of the short-term profile in the blended affinity
    val albumShare: Double = 0.5,
    val trackShare: Double = 0.7, // weight of a known track's own affinity vs its artist's
    val unheardShrink: Double = 0.5, // album affinity counts this much relative to artist affinity
    // --- defaults the data-relative thresholds fall back to / are clamped around ---
    val defaultSkipSeconds: Double = 30.0,
    val defaultSkipFraction: Double = 0.25,
    val minPlaysForProfile: Int = 15,
    // --- list building ---
    val exploreRatio: Double = 0.30,
    val listSize: Int = 30,
    val seedRelatedDecay: Double = 0.6, // a related artist/track inherits this share of its seed's affinity
    val exploreNoise: Double = 0.15,
    // --- 13.7.0.8 ranking refinements (measured on a real history, see README-OZ.md "13.7.0.8") ---
    /** A track first heard less than this many days ago, played at most twice and never skipped, still counts as new. */
    val freshDays: Double = 21.0,
    /** Novelty floor for such a "still new to you" track (0 = off, the pre-13.7.0.8 behaviour). */
    val freshNovelty: Double = 0.6,
    /** Repeat-artist fatigue: an artist above this share of the last days' listening is pushed down... */
    val fatigueStart: Double = 0.10,
    /** ...by this much per unit of share above [fatigueStart]. */
    val fatigueSlope: Double = 3.0,
    /** Half-life of the "you skipped this artist lately" signal. */
    val recentSkipHalfLifeDays: Double = 7.0,
    /** Weight of the recent artist-skip excess inside the skip feature (0 = off). */
    val recentSkipWeight: Double = 0.0, // measured: no gain on a real history (kept as a knob, off)
    /** Source prior of a barely-played library/playlist track (was 0.35: on a real history these were the least replayed candidates). */
    val libraryPrior: Double = 0.2,
)
