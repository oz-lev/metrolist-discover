/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.random.Random

/**
 * Offline holdout evaluation: hide the last N days, learn from everything before, and measure how
 * much of what the user really played in the hidden days the engine would have put in its top K.
 * The same code runs in unit tests (synthetic histories), on the phone (Discover -> "test on my history")
 * and on an exported Metrolist backup (tools/eval-cli).
 */
object Evaluator {
    enum class Mode { NEW_ONLY, ALL }

    data class Metric(
        val method: String,
        val mode: Mode,
        val k: Int,
        val recall: Double,
        val precision: Double,
        val dayHitRate: Double,
        val targets: Int,
        val pool: Int,
        val reachable: Int = 0,
    ) {
        override fun toString() =
            "%-10s %-8s @%-3d recall=%.3f precision=%.3f dayHit=%.3f (targets=%d reachable=%d pool=%d)".format(
                java.util.Locale.ROOT, method, mode, k, recall, precision, dayHitRate, targets, reachable, pool)
    }

    const val ENGINE = "engine"
    const val ENGINE_LIST = "engineList"
    const val POPULAR = "popular"
    const val RELATED = "related"
    const val RANDOM = "random"

    fun holdout(
        history: History,
        holdoutDays: Double = 14.0,
        ks: List<Int> = listOf(10, 20, 50),
        mode: Mode = Mode.NEW_ONLY,
        engine: DiscoverEngine = DiscoverEngine(),
        endMs: Long = history.plays.maxOf { it.timestampMs } + 1,
    ): List<Metric> {
        val cut = endMs - (holdoutDays * TasteProfile.DAY_MS).toLong()
        // Undated signals (playlist membership) could have happened inside the hidden window: leave them out.
        val train = history.before(cut).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
        val p = engine.profile(train, cut)
        val trainPlayed = train.plays.map { it.trackId }.toHashSet()
        val th = p.thresholds

        val targetsByDay = HashMap<Long, MutableSet<String>>()
        for (e in history.plays) {
            if (e.timestampMs < cut || e.timestampMs >= endMs) continue
            if (ProfileBuilder.outcome(e, history, th.skipFraction, th.skipSeconds, th.fullFraction) == Outcome.SKIP) continue
            if (mode == Mode.NEW_ONLY && e.trackId in trainPlayed) continue
            targetsByDay.getOrPut(e.timestampMs / 86_400_000L) { HashSet() }.add(e.trackId)
        }
        val targets = targetsByDay.values.flatten().toHashSet()

        var pool = CandidateBuilder.offline(train, p)
        if (mode == Mode.NEW_ONLY) pool = pool.filter { it.track.id !in trainPlayed }
        else {
            val have = pool.map { it.track.id }.toHashSet()
            pool = pool + trainPlayed.filter { it !in have }.mapNotNull { history.tracks[it] }
                .map { Candidate(it, setOf(CandidateSource.LIBRARY), it.mainArtist, it.id, p.trackAffinity(it.id)) }
        }
        val poolIds = pool.map { it.track.id }
        val poolSet = poolIds.toHashSet()
        val kMax = ks.max()

        val rankings = LinkedHashMap<String, List<List<String>>>()
        // "what will you play" (ALL) is a prediction task: the novelty preference of Discover is switched off.
        val w = if (mode == Mode.ALL) engine.config.weights.copy(wNovelty = 0.0, wOverRep = 0.0, wSource = 0.0) else engine.config.weights
        rankings[ENGINE] = listOf(engine.rankAll(p, pool, cut, w).map { it.first.track.id })
        if (mode == Mode.NEW_ONLY) {
            rankings[ENGINE_LIST] = listOf(
                engine.recommend(train, pool, cut, n = kMax, random = Random(1), profile = p).map { it.track.id })
        }
        rankings[POPULAR] = listOf(popular(train, pool, mode, p))
        rankings[RELATED] = listOf(related(train, poolIds.toHashSet()))
        rankings[RANDOM] = (1..5).map { s -> poolIds.shuffled(Random(s)) }

        val out = ArrayList<Metric>()
        for ((name, lists) in rankings) for (k in ks) {
            var rec = 0.0; var prec = 0.0; var day = 0.0
            for (l in lists) {
                val top = l.take(k).toHashSet()
                val hits = targets.count { it in top }
                rec += if (targets.isEmpty()) 0.0 else hits.toDouble() / targets.size
                prec += hits.toDouble() / k
                day += if (targetsByDay.isEmpty()) 0.0
                else targetsByDay.values.count { d -> d.any { it in top } }.toDouble() / targetsByDay.size
            }
            val n = lists.size
            out += Metric(name, mode, k, rec / n, prec / n, day / n, targets.size, poolIds.size, targets.count { it in poolSet })
        }
        return out
    }

    /** "Most popular": tracks the user plays most (ALL) or the songs of the most-played artists (NEW). */
    private fun popular(train: History, pool: List<Candidate>, mode: Mode, p: TasteProfile): List<String> =
        if (mode == Mode.ALL) pool.sortedByDescending { p.trackPlayCount[it.track.id] ?: 0 }.map { it.track.id }
        else pool.sortedByDescending { p.artistPlays[it.track.mainArtist] ?: 0 }.map { it.track.id }

    /** Metrolist's plain "related songs": related lists of the last songs played, round robin, in order. */
    private fun related(train: History, pool: Set<String>): List<String> {
        val recent = LinkedHashSet<String>()
        for (e in train.sortedPlays.asReversed()) { recent += e.trackId; if (recent.size >= 25) break }
        val lists = recent.map { train.related[it].orEmpty().filter { r -> r in pool } }
        val out = LinkedHashSet<String>()
        var i = 0
        while (lists.any { it.size > i }) { lists.forEach { l -> l.getOrNull(i)?.let { out += it } }; i++ }
        out += pool.filter { it !in out }.shuffled(Random(3))
        return out.toList()
    }

    fun get(metrics: List<Metric>, method: String, k: Int): Metric? = metrics.firstOrNull { it.method == method && it.k == k }
}
