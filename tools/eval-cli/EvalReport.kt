/**
 * Metrolist Discover - offline evaluator: rolling list report (GPL-3.0).
 * Rolling 7-day holdouts at several cut points; measures the FINAL Discover list (recommend()),
 * not just the raw ranking: hit rate, new-song hits, skipped picks, novelty, artist diversity.
 * Personal data never leaves the machine that runs it.
 */
import com.metrolist.music.discover.engine.*
import java.util.Locale

data class ListStats(
    val hit: Double, val newHit: Double, val skipped: Double, val novelty: Double,
    val distinctArtists: Double, val maxArtistShare: Double, val dominantShare: Double,
    val allRecall50: Double, val allRecall20: Double, val newRecall100: Double,
)

fun listReport(h: History, engine: DiscoverEngine, cuts: Int = 12, holdDays: Int = 7, n: Int = 40, verbose: Boolean = true): ListStats {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    val rows = ArrayList<ListStats>()
    for (k in 0 until cuts) {
        val testEnd = end - k * holdDays * day
        val cut = testEnd - holdDays * day
        val train = h.before(cut).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
        if (train.plays.size < 50) continue
        val p = engine.profile(train, cut)
        val th = p.thresholds
        val good = HashSet<String>(); val skippedSet = HashSet<String>()
        for (e in h.plays) {
            if (e.timestampMs < cut || e.timestampMs >= testEnd) continue
            if (ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction) == Outcome.SKIP) skippedSet += e.trackId else good += e.trackId
        }
        val onlySkipped = skippedSet - good
        val trainPlayed = train.plays.map { it.trackId }.toHashSet()
        val cands = CandidateBuilder.offline(train, p)
        val list = engine.recommend(train, cands, cut + 18 * 3_600_000L, n = n, profile = p).map { it.track }
        if (list.isEmpty()) continue
        val sz = list.size.toDouble()
        val hits = list.count { it.id in good }
        val newHits = list.count { it.id in good && it.id !in trainPlayed }
        val sk = list.count { it.id in onlySkipped }
        val nov = list.count { t -> p.daysSince(t.id)?.let { it >= th.forgottenAfterDays } ?: true }
        val arts = list.groupingBy { it.mainArtist }.eachCount()
        val dom = list.count { (p.recentArtistShare[it.mainArtist] ?: 0.0) > 0.15 }
        val all = Evaluator.holdout(h, holdDays.toDouble(), listOf(20, 50), Evaluator.Mode.ALL, engine, testEnd)
        val nw = Evaluator.holdout(h, holdDays.toDouble(), listOf(100), Evaluator.Mode.NEW_ONLY, engine, testEnd)
        val r = ListStats(hits / sz, newHits / sz, sk / sz, nov / sz, arts.size / sz, arts.values.max() / sz, dom / sz,
            Evaluator.get(all, "engine", 50)!!.recall, Evaluator.get(all, "engine", 20)!!.recall,
            Evaluator.get(nw, "engineList", 100)!!.recall)
        if (verbose) println("cut-%d  %s".format(Locale.ROOT, k, fmt(r)))
        rows += r
    }
    fun avg(f: (ListStats) -> Double) = rows.map(f).average()
    return ListStats(avg { it.hit }, avg { it.newHit }, avg { it.skipped }, avg { it.novelty }, avg { it.distinctArtists },
        avg { it.maxArtistShare }, avg { it.dominantShare }, avg { it.allRecall50 }, avg { it.allRecall20 }, avg { it.newRecall100 })
}

fun fmt(r: ListStats) = ("list: hit=%.3f newHit=%.3f skippedPick=%.3f novelty=%.3f artists/n=%.3f maxArtist=%.3f dominant=%.3f | " +
    "ALL r@20=%.3f r@50=%.3f | NEW list r@100=%.3f").format(Locale.ROOT, r.hit, r.newHit, r.skipped, r.novelty, r.distinctArtists,
    r.maxArtistShare, r.dominantShare, r.allRecall20, r.allRecall50, r.newRecall100)

/** Aggregate-only diagnostic: which candidates of the filtered pool get played in the next week, by source/feature bucket. */
fun poolDiag(h: History, engine: DiscoverEngine, cuts: Int = 6, holdDays: Int = 7) {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    val tot = HashMap<String, IntArray>()
    fun add(k: String, hit: Boolean) { val a = tot.getOrPut(k) { IntArray(2) }; a[0]++; if (hit) a[1]++ }
    for (k in 0 until cuts) {
        val testEnd = end - k * holdDays * day; val cut = testEnd - holdDays * day
        val train = h.before(cut).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
        val p = engine.profile(train, cut); val th = p.thresholds
        val good = HashSet<String>()
        for (e in h.plays) if (e.timestampMs in cut until testEnd &&
            ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction) != Outcome.SKIP) good += e.trackId
        val recentCut = cut - (th.recentWindowDays * day).toLong()
        val recent = train.plays.filter { it.timestampMs >= recentCut }.map { it.trackId }.toHashSet()
        val pool = CandidateBuilder.offline(train, p).filter { it.track.id !in recent && p.trackSkipExcess(it.track.id) < 0.5 }
            .filter { (p.trackPlayCount[it.track.id] ?: 0) < 3 || CandidateSource.FORGOTTEN in it.sources }
        for (c in pool) {
            val hit = c.track.id in good
            val f = engine.features(c, p, TasteProfile.slotOf(cut))
            add("src=" + c.sources.sortedBy { it.name }.joinToString("+"), hit)
            add("plays=" + minOf(p.trackPlayCount[c.track.id] ?: 0, 5), hit)
            add("sim=" + "%.1f".format(Locale.ROOT, (f.similarity * 5).toInt() / 5.0), hit)
            val ash = p.artistShort[c.track.mainArtist] ?: 0.0; val al = p.longFamiliarity(c.track.mainArtist)
            add("artistShortRank=" + (if (ash <= 0) "none" else if (ash > 1.0) "hi" else "lo"), hit)
            add("longFam=" + "%.1f".format(Locale.ROOT, (al * 5).toInt() / 5.0), hit)
            add("since=" + (p.daysSince(c.track.id)?.let { if (it < 14) "<14" else if (it < 30) "<30" else if (it < 60) "<60" else "60+" } ?: "never"), hit)
            add("share=" + "%.2f".format(Locale.ROOT, minOf(0.3, ((p.recentArtistShare[c.track.mainArtist] ?: 0.0) * 20).toInt() / 20.0)), hit)
            add("artistSkip=" + (p.artistSkipRate[c.track.mainArtist]?.let { "%.1f".format(Locale.ROOT, (it * 10).toInt() / 10.0) } ?: "na"), hit)
        }
    }
    tot.toSortedMap().forEach { (k, a) -> println("%-40s n=%6d hit=%5d rate=%.3f".format(Locale.ROOT, k, a[0], a[1], a[1].toDouble() / a[0])) }
}

fun reportGrid(h: History) {
    val old = DiscoverConfig(freshNovelty = 0.0, fatigueStart = 0.15, fatigueSlope = 2.0, recentSkipWeight = 0.0, libraryPrior = 0.35)
    val variants = linkedMapOf(
        "old(13.7.0.7)" to old,
        "new(13.7.0.8)" to DiscoverConfig(),
        "new rskip0" to DiscoverConfig(recentSkipWeight = 0.0),
        "new rskip0.25" to DiscoverConfig(recentSkipWeight = 0.25),
        "new rskip.25 hl3" to DiscoverConfig(recentSkipWeight = 0.25, recentSkipHalfLifeDays = 3.0),
    )
    for ((name, cfg) in variants) println("%-16s %s".format(Locale.ROOT, name, fmt(listReport(h, DiscoverEngine(cfg), verbose = false))))
}

/** Local-only: per-candidate features + label (no ids, no titles) for offline fitting. */
fun poolDump(h: History, engine: DiscoverEngine, out: String, cuts: Int = 12, holdDays: Int = 7) {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    java.io.File(out).printWriter().use { w ->
        w.println("cut,hit,sim,tod,nov,skip,sat,src,plays,ashort,along,fresh,rskip,aff,seed,explore,forgotten,library")
        for (k in 0 until cuts) {
            val testEnd = end - k * holdDays * day; val cut = testEnd - holdDays * day
            val train = h.before(cut).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
            val p = engine.profile(train, cut); val th = p.thresholds
            val good = HashSet<String>()
            for (e in h.plays) if (e.timestampMs in cut until testEnd &&
                ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction) != Outcome.SKIP) good += e.trackId
            val recentCut = cut - (th.recentWindowDays * day).toLong()
            val recent = train.plays.filter { it.timestampMs >= recentCut }.map { it.trackId }.toHashSet()
            val pool = CandidateBuilder.offline(train, p).groupBy { it.track.id }.values.map { g -> g.reduce { a, b -> a.merge(b) } }
                .filter { it.track.id !in recent && p.trackSkipExcess(it.track.id) < 0.5 }
                .filter { (p.trackPlayCount[it.track.id] ?: 0) < 3 || CandidateSource.FORGOTTEN in it.sources }
            for (c in pool) {
                val f = engine.features(c, p, TasteProfile.slotOf(cut + 18 * 3_600_000L))
                val a = c.track.mainArtist
                val first = p.firstPlayed[c.track.id]
                val fresh = if (first != null && (cut - first) / TasteProfile.DAY_MS < 21) 1 else 0
                w.println(listOf(k, if (c.track.id in good) 1 else 0, f.similarity, f.timeOfDay, f.novelty, f.skipPenalty, f.overRepresentation, f.sourcePrior,
                    p.trackPlayCount[c.track.id] ?: 0, p.artistShort[a] ?: 0.0, p.longFamiliarity(a), fresh, p.artistRecentSkip[a] ?: 0.0,
                    p.artistAffinity(a), c.seedAffinity, if (CandidateSource.EXPLORE in c.sources) 1 else 0,
                    if (CandidateSource.FORGOTTEN in c.sources) 1 else 0, if (CandidateSource.LIBRARY in c.sources) 1 else 0).joinToString(","))
            }
        }
    }
}
