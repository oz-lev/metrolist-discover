/**
 * Metrolist Discover - offline evaluator CLI (GPL-3.0).
 * Same engine code as the app. Compiled in CI with kotlinc (no Android needed).
 *
 *   java -cp eval.jar EvalCliKt --synthetic [--baseline eval/baseline.properties --check] [--write-baseline FILE]
 *   java -cp eval.jar EvalCliKt --tsv DIR [--holdout 14] [--top 20] [--json out.json]
 *
 * DIR comes from tools/backup_to_tsv.py (a Metrolist backup .zip or song.db -> TSV). Personal data
 * stays on the machine that runs this; nothing here talks to the network.
 */
import com.metrolist.music.discover.engine.*
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.system.exitProcess

fun loadTsv(dir: File): History {
    val tracks = HashMap<String, TrackMeta>()
    File(dir, "tracks.tsv").forEachLine { l ->
        val c = l.split('\t'); if (c.size < 6) return@forEachLine
        tracks[c[0]] = TrackMeta(c[0], c[1], c[4].split(';').filter { it.isNotBlank() }, c[5].split(';').filter { it.isNotBlank() },
            c[3].ifBlank { null }, c[2].toIntOrNull() ?: -1)
    }
    val plays = ArrayList<PlayEvent>()
    File(dir, "plays.tsv").forEachLine { l ->
        val c = l.split('\t'); if (c.size >= 3) plays += PlayEvent(c[0], c[1].toLong(), c[2].toLong())
    }
    val signals = ArrayList<ExplicitSignal>()
    File(dir, "signals.tsv").takeIf { it.exists() }?.forEachLine { l ->
        val c = l.split('\t'); if (c.size >= 3) signals += ExplicitSignal(c[0], SignalKind.valueOf(c[1]), c[2].toLongOrNull())
    }
    val related = HashMap<String, MutableList<String>>()
    File(dir, "related.tsv").takeIf { it.exists() }?.forEachLine { l ->
        val c = l.split('\t'); if (c.size >= 2) related.getOrPut(c[0]) { ArrayList() }.add(c[1])
    }
    return History(tracks, plays, signals, related)
}

fun syntheticMetrics(engine: DiscoverEngine): Map<String, Double> {
    val world = SyntheticWorld.world()
    val acc = HashMap<String, Double>()
    for (p in SyntheticWorld.PERSONAS) {
        val h = SyntheticWorld.history(world, p)
        for (mode in Evaluator.Mode.values()) for (m in Evaluator.holdout(h, 14.0, listOf(20, 50), mode, engine)) {
            println("${p.name.padEnd(12)} $m")
            acc.merge("${m.method}.${mode}.recall@${m.k}", m.recall / SyntheticWorld.PERSONAS.size, Double::plus)
        }
    }
    return acc
}

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    fun arg(n: String) = args.indexOf(n).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val engine = DiscoverEngine()
    if ("--synthetic" in args) {
        val m = syntheticMetrics(engine)
        println("--- averages over personas ---")
        m.toSortedMap().forEach { (k, v) -> println("%-34s %.4f".format(k, v)) }
        arg("--write-baseline")?.let { f ->
            val pr = Properties(); m.filterKeys { it.startsWith("engine") }.forEach { (k, v) -> pr[k] = "%.4f".format(v) }
            File(f).outputStream().use { pr.store(it, "Metrolist Discover synthetic holdout baseline") }
            println("baseline written to $f")
        }
        arg("--baseline")?.let { f ->
            val pr = Properties(); File(f).inputStream().use { pr.load(it) }
            val tol = 0.02
            var bad = 0
            for (k in pr.stringPropertyNames().sorted()) {
                val base = pr.getProperty(k).toDouble(); val now = m[k] ?: 0.0
                val ok = now >= base - tol
                if (!ok) bad++
                println("%-34s base=%.4f now=%.4f %s".format(k, base, now, if (ok) "OK" else "REGRESSION"))
            }
            // the engine must also beat the naive baselines
            for (mode in listOf("NEW_ONLY", "ALL")) for (b in listOf("random", "related")) {
                val e = m["engine.$mode.recall@20"] ?: 0.0; val o = m["$b.$mode.recall@20"] ?: 0.0
                if (e <= o) { bad++; println("engine.$mode.recall@20=%.4f does not beat $b=%.4f  REGRESSION".format(e, o)) }
            }
            if ("--check" in args && bad > 0) { println("FAILED: $bad regression(s)"); exitProcess(2) }
            println("baseline check passed")
        }
        return
    }
    val dir = File(arg("--tsv") ?: run { println("need --synthetic or --tsv DIR"); exitProcess(1) })
    val h = loadTsv(dir)
    val holdout = arg("--holdout")?.toDouble() ?: 14.0
    if ("--grid" in args) {
        // tune on the 14 days BEFORE the test window, then report the test window: no peeking.
        val end = h.plays.maxOf { it.timestampMs } + 1
        val valEnd = end - (holdout * 86_400_000L).toLong()
        fun obj(e: DiscoverEngine, endMs: Long): Pair<Double, Double> {
            val a = Evaluator.get(Evaluator.holdout(h, holdout, listOf(100), Evaluator.Mode.ALL, e, endMs), "engine", 100)!!.recall
            val n = Evaluator.get(Evaluator.holdout(h, holdout, listOf(200), Evaluator.Mode.NEW_ONLY, e, endMs), "engine", 200)!!.recall
            return a to n
        }
        val res = ArrayList<Triple<String, Double, DiscoverConfig>>()
        for (stm in listOf(0.2, 0.4, 0.6)) for (ts in listOf(0.5, 0.7, 0.85)) for (hl in listOf(30.0, 60.0, 120.0)) for (tod in listOf(0.0, 0.2, 0.4)) {
            val cfg = DiscoverConfig(shortTermMix = stm, trackShare = ts, longHalfLifeDays = hl, weights = RankWeights(wTimeOfDay = tod))
            val (a, n) = obj(DiscoverEngine(cfg), valEnd)
            res += Triple("stm=$stm ts=$ts hl=$hl tod=$tod all@100=%.3f new@200=%.3f".format(a, n), a + n, cfg)
        }
        res.sortByDescending { it.second }
        res.take(5).forEach { println("VAL " + it.first) }
        val best = res.first().third
        val (ta, tn) = obj(DiscoverEngine(best), end); val (da, dn) = obj(DiscoverEngine(), end)
        println("TEST best: all@100=%.3f new@200=%.3f | defaults: all@100=%.3f new@200=%.3f".format(ta, tn, da, dn))
        return
    }
    println("tracks=${h.tracks.size} plays=${h.plays.size} signals=${h.signals.size} relatedFrom=${h.related.size}")
    val all = ArrayList<Evaluator.Metric>()
    for (mode in Evaluator.Mode.values()) for (m in Evaluator.holdout(h, holdout, listOf(20, 50, 100, 200), mode, engine)) { println(m); all += m }
    val now = h.plays.maxOf { it.timestampMs } + 1
    val p = engine.profile(h, now)
    if ("--debug" in args) {
        val cut = now - 14 * 86_400_000L
        val tr = h.before(cut); val tp = engine.profile(tr, cut)
        val tr2 = tr.copy(signals = tr.signals.filter { it.timestampMs != null })
        val tp2 = engine.profile(tr2, cut)
        val off = CandidateBuilder.offline(tr2, tp2)
        val have = off.map { it.track.id }.toHashSet()
        val pool = off + tr.plays.map { it.trackId }.distinct().filter { it !in have }.mapNotNull { h.tracks[it] }.map { Candidate(it, setOf(CandidateSource.LIBRARY), it.mainArtist, it.id, tp2.trackAffinity(it.id)) }
        val tgt = h.plays.filter { it.timestampMs >= cut }.map { it.trackId }.toHashSet()
        val w0 = engine.config.weights.copy(wNovelty = 0.0, wOverRep = 0.0, wSource = 0.0)
        engine.rankAll(tp2, pool, cut, w0).take(12).forEach { (c, sc) ->
            println((if (c.track.id in tgt) "HIT " else "    ") + c.sources + " %.3f %s | %s plays=%d f=%s".format(sc, c.track.title.take(30), c.track.artistLabel.take(20), tp.trackPlayCount[c.track.id] ?: 0, engine.features(c, tp2, TasteProfile.slotOf(cut))))
        }
        println("top by plays:")
        tp.trackPlayCount.entries.sortedByDescending { it.value }.take(8).forEach { (id, n) ->
            val c = Candidate(h.tracks[id]!!, setOf(CandidateSource.LIBRARY), null, id, tp.trackAffinity(id))
            println("$n ${h.tracks[id]?.title?.take(30)} aff=%.3f f=%s".format(tp.trackAffinity(id), engine.features(c, tp, TasteProfile.slotOf(cut))))
        }
        return
    }
    println("thresholds: ${p.thresholds}")
    val top = arg("--top")?.toInt() ?: 20
    val recs = engine.recommend(h, CandidateBuilder.offline(h, p), now, n = top, profile = p)
    println("--- Discover for you (top $top, offline candidates only) ---")
    recs.forEachIndexed { i, r -> println("${i + 1}. ${r.track.title} - ${r.track.artistLabel} [${r.slot} ${r.reason}]") }
    val facts = StatsFacts.compute(h, now)
    println("--- stats facts ---"); facts.forEach { println("${it.type}: ${it.he}") }
    val mixes = MixBuilder.build(h, now, engine)
    println("--- mixes ---"); mixes.forEach { mx -> println("${mx.titleHe} (${mx.tracks.size}): " + mx.tracks.take(5).joinToString(" | ") { "${it.title} - ${it.artistLabel}" }) }
    arg("--json")?.let { f ->
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val sb = StringBuilder("{\"metrics\":[")
        sb.append(all.joinToString(",") { "{\"method\":\"${it.method}\",\"mode\":\"${it.mode}\",\"k\":${it.k},\"recall\":${it.recall},\"precision\":${it.precision},\"dayHit\":${it.dayHitRate},\"targets\":${it.targets},\"reachable\":${it.reachable},\"pool\":${it.pool}}" })
        sb.append("],\"top\":[")
        sb.append(recs.joinToString(",") { "{\"title\":\"${esc(it.track.title)}\",\"artist\":\"${esc(it.track.artistLabel)}\",\"slot\":\"${it.slot}\",\"reason\":\"${it.reason}\"}" })
        sb.append("],\"facts\":[")
        sb.append(facts.joinToString(",") { "{\"type\":\"${it.type}\",\"he\":\"${esc(it.he)}\"}" })
        sb.append("],\"mixes\":[")
        sb.append(mixes.joinToString(",") { mx -> "{\"title\":\"${esc(mx.titleHe)}\",\"n\":${mx.tracks.size},\"tracks\":[" + mx.tracks.take(8).joinToString(",") { "\"${esc(it.title)} - ${esc(it.artistLabel)}\"" } + "]}" })
        sb.append("],\"thresholds\":\"${esc(p.thresholds.toString())}\"}")
        File(f).writeText(sb.toString())
    }
}
