/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Scores candidates and builds the final diversified "Discover for you" list. */
class DiscoverEngine(val config: DiscoverConfig = DiscoverConfig()) {

    fun profile(history: History, nowMs: Long): TasteProfile = ProfileBuilder.build(history, nowMs, config)

    /** Feature vector of one candidate (list-independent part; over-representation is added while picking). */
    fun features(c: Candidate, p: TasteProfile, hourSlot: Int, overRep: Double = 0.0): Features {
        val t = c.track
        val artists = t.artistIds
        val direct = if (artists.isEmpty()) 0.0 else {
            val affs = artists.map { p.artistAffinity(it) }
            affs.max() + 0.3 * (affs.sum() - affs.max()) / max(1, affs.size - 1)
        }
        val inherited = config.seedRelatedDecay * c.seedAffinity
        val artistSide = maxOf(direct, inherited) + config.albumShare * 0.3 * p.albumAffinity(t.albumId)
        val track = p.trackAffinity(t.id)
        // a track the user already knows is judged mostly on its own record, a new one on its artist/seed
        // (an unheard track is assumed to be liked about half as much as its artist: shrinkage)
        val trackTerm = if (track != 0.0) track else config.unheardShrink * artistSide
        val sim = (config.trackShare * trackTerm + (1 - config.trackShare) * artistSide).coerceIn(-1.0, 1.0)

        val tod = p.hourFit(artists.firstOrNull()?.takeIf { p.artistPlays.containsKey(it) } ?: c.seedArtistId, hourSlot)

        val since = p.daysSince(t.id)
        val novelty = if (since == null) 1.0 else 1.0 - TasteProfile.decay(since, p.thresholds.repeatGapDays * 2)

        val trackSkip = p.trackSkipExcess(t.id)
        val artistSkip = artists.firstOrNull()?.let { p.artistSkipRate[it] }?.let {
            ((it - p.thresholds.userSkipRate) / (p.thresholds.userSkipRate + 0.05)).coerceIn(0.0, 1.5)
        } ?: 0.0
        val skip = trackSkip + 0.5 * artistSkip

        // An artist that already dominates the last days gets a mild push-down so the list widens the taste.
        val share = artists.firstOrNull()?.let { p.recentArtistShare[it] } ?: 0.0
        val saturation = max(0.0, share - 0.15) * 2.0

        val src = c.sources.maxOfOrNull { it.prior } ?: 0.0
        val srcBonus = min(0.3, 0.1 * (c.sources.size - 1))
        return Features(sim, tod, novelty, skip, overRep + saturation, src + srcBonus)
    }

    fun score(f: Features, w: RankWeights = config.weights): Double = w.dot(f)

    /** Plain ranking without list rules: used by the evaluator and as the base for [recommend]. */
    fun rankAll(p: TasteProfile, candidates: List<Candidate>, nowMs: Long, w: RankWeights = config.weights): List<Pair<Candidate, Double>> {
        val slot = TasteProfile.slotOf(nowMs)
        return candidates.map { it to score(features(it, p, slot), w) }.sortedByDescending { it.second }
    }

    /**
     * The final list: ~70 % exploit / ~30 % explore, never the same artist twice in a row,
     * nothing played inside the user's own "recent" window, nothing skipped lately.
     */
    fun recommend(
        history: History,
        candidates: List<Candidate>,
        nowMs: Long,
        n: Int = config.listSize,
        weights: RankWeights = config.weights,
        exploreRatio: Double = config.exploreRatio,
        random: Random = Random(nowMs / 3_600_000L),
        profile: TasteProfile = profile(history, nowMs),
    ): List<Recommendation> {
        val p = profile
        val slot = TasteProfile.slotOf(nowMs)
        val recentCut = nowMs - (p.thresholds.recentWindowDays * TasteProfile.DAY_MS).toLong()
        val recentlyPlayed = history.plays.asSequence().filter { it.timestampMs >= recentCut }.map { it.trackId }.toHashSet()
        val pool = candidates
            .groupBy { it.track.id }.values.map { g -> g.reduce { a, b -> a.merge(b) } }
            .filter { it.track.id !in recentlyPlayed }
            .filter { p.trackSkipExcess(it.track.id) < 0.5 }
            // Discover is for discovery: well-worn tracks only come back as "forgotten favourites"
            .filter { (p.trackPlayCount[it.track.id] ?: 0) < 3 || CandidateSource.FORGOTTEN in it.sources }
        if (pool.isEmpty()) return emptyList()

        val nExplore = (n * exploreRatio).roundToInt().coerceIn(0, n)
        val famCut = familiarityCut(p)
        fun isExplore(c: Candidate): Boolean =
            CandidateSource.EXPLORE in c.sources ||
                (c.track.mainArtist.let { p.longFamiliarity(it) } < famCut && p.trackAffinity(c.track.id) <= 0.0)

        val exploitPool = pool.filterNot(::isExplore).toMutableList()
        val explorePool = pool.filter(::isExplore).toMutableList()

        val picked = ArrayList<Recommendation>()
        val artistCount = HashMap<String, Int>()

        fun pickFrom(src: MutableList<Candidate>, s: Slot, noise: Double): Recommendation? {
            var best: Candidate? = null; var bestScore = Double.NEGATIVE_INFINITY; var bestF: Features? = null
            for (c in src) {
                val over = (artistCount[c.track.mainArtist] ?: 0).toDouble()
                val f = features(c, p, slot, over)
                val sc = score(f, weights) + if (noise > 0) random.nextDouble(-noise, noise) else 0.0
                if (sc > bestScore) { bestScore = sc; best = c; bestF = f }
            }
            val b = best ?: return null
            src.remove(b)
            artistCount.merge(b.track.mainArtist, 1, Int::plus)
            return Recommendation(b.track, bestScore, bestF!!, s, reasonFor(b, p, bestF), seedLabel(b, history))
        }

        val exploreEvery = if (nExplore > 0) max(2, (n.toDouble() / nExplore).roundToInt()) else Int.MAX_VALUE
        var explored = 0
        for (i in 0 until n) {
            val wantExplore = explored < nExplore && (i % exploreEvery == exploreEvery - 1 || n - i <= nExplore - explored)
            val r = if (wantExplore) pickFrom(explorePool, Slot.EXPLORE, config.exploreNoise) ?: pickFrom(exploitPool, Slot.EXPLOIT, 0.0)
            else pickFrom(exploitPool, Slot.EXPLOIT, 0.0) ?: pickFrom(explorePool, Slot.EXPLORE, config.exploreNoise)
            if (r == null) break
            if (r.slot == Slot.EXPLORE) explored++
            picked += r
        }
        return noSameArtistTwice(picked)
    }

    private fun familiarityCut(p: TasteProfile): Double {
        val v = p.artistLong.keys.map { p.longFamiliarity(it) }.filter { it > 0 }.sorted()
        return if (v.isEmpty()) 0.1 else TasteProfile.quantile(v, 0.5)
    }

    private fun reasonFor(c: Candidate, p: TasteProfile, f: Features): Reason = when {
        CandidateSource.FORGOTTEN in c.sources -> Reason.FORGOTTEN_FAVORITE
        CandidateSource.EXPLORE in c.sources -> Reason.EXPLORE
        f.timeOfDay > 0.5 -> Reason.FITS_THIS_HOUR
        p.trackPlayCount.containsKey(c.track.id) -> Reason.BECAUSE_YOU_PLAY
        p.artistAffinity(c.track.mainArtist) > 0.3 -> Reason.NEW_FROM_ARTIST
        CandidateSource.RELATED_ARTIST in c.sources -> Reason.SIMILAR_ARTIST
        else -> Reason.BECAUSE_YOU_PLAY
    }

    private fun seedLabel(c: Candidate, h: History): String? =
        c.seedTrackId?.let { h.tracks[it]?.title } ?: c.seedArtistId?.let { id ->
            h.tracks.values.firstOrNull { it.artistIds.firstOrNull() == id }?.artistNames?.firstOrNull()
        }

    companion object {
        /** Reorders so that no artist appears twice in a row (keeps order otherwise). */
        fun noSameArtistTwice(list: List<Recommendation>): List<Recommendation> {
            val out = ArrayList<Recommendation>(list.size)
            val rest = list.toMutableList()
            while (rest.isNotEmpty()) {
                val lastArtist = out.lastOrNull()?.track?.mainArtist
                val idx = rest.indexOfFirst { it.track.mainArtist != lastArtist }
                if (idx < 0) break // only the same artist is left: drop the tail rather than repeat
                out += rest.removeAt(idx)
            }
            return out
        }
    }
}
