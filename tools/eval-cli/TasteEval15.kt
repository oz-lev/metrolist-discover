/**
 * Metrolist Discover 13.7.0.15 - offline evaluator for TasteModel (GPL-3.0).
 * Local only: personal data never leaves the machine.
 *   java -cp eval.jar EvalCliKt --tsv DIR --taste    13.7.0.14 baseline vs 13.7.0.15 TasteModel + per-component ablation
 */
import com.metrolist.music.discover.engine.*
import java.util.Locale
import kotlin.random.Random

data class TasteEvalRow(
    val hit35: Double,
    val hit100: Double,
    val famHit: Double,
    val newHit35: Double,
    val skip35: Double,
    val hourHit35: Double,
    val recent12h: Double,
    val artists35: Double,
    val maxArtist: Double,
    val newShare: Double,
)

fun tasteReport(h: History, cuts: Int = 12, holdDays: Int = 7, hours: List<Int> = listOf(9, 14, 18, 22)) {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    val variants = linkedMapOf(
        "13.7.0.13 (off)" to (ContextConfig.OFF to TasteConfig.OFF),
        "13.7.0.14 (ctx)" to (ContextConfig() to TasteConfig.OFF),
        "13.7.0.15 (new)" to (ContextConfig() to TasteConfig()),
        "v15-bal2" to (ContextConfig() to TasteConfig(completionWeight = 0.22, replayWeight = 0.20, artistAffinityWeight = 0.30, artistHalfLifeDays = 12.0, coListenWeight = 0.20, signalWeight = 0.18, dayPartWeight = 0.08)),
        "v15-bal3" to (ContextConfig() to TasteConfig(completionWeight = 0.18, replayWeight = 0.20, artistAffinityWeight = 0.24, artistHalfLifeDays = 14.0, coListenWeight = 0.14, signalWeight = 0.18, dayPartWeight = 0.08)),
        "v15-hiDiv" to (ContextConfig() to TasteConfig(completionWeight = 0.22, replayWeight = 0.20, artistAffinityWeight = 0.20, artistHalfLifeDays = 14.0, coListenWeight = 0.18, signalWeight = 0.18, dayPartWeight = 0.08)),
        "+completion" to (ContextConfig() to TasteConfig(completionWeight = 0.22, replayWeight = 0.0, artistAffinityWeight = 0.0, coListenWeight = 0.0, signalWeight = 0.0, dayPartWeight = 0.0)),
        "+replay" to (ContextConfig() to TasteConfig(completionWeight = 0.0, replayWeight = 0.20, artistAffinityWeight = 0.0, coListenWeight = 0.0, signalWeight = 0.0, dayPartWeight = 0.0)),
        "+artistAff" to (ContextConfig() to TasteConfig(completionWeight = 0.0, replayWeight = 0.0, artistAffinityWeight = 0.26, artistHalfLifeDays = 14.0, coListenWeight = 0.0, signalWeight = 0.0, dayPartWeight = 0.0)),
        "+coListen" to (ContextConfig() to TasteConfig(completionWeight = 0.0, replayWeight = 0.0, artistAffinityWeight = 0.0, coListenWeight = 0.18, signalWeight = 0.0, dayPartWeight = 0.0)),
        "+dayPart" to (ContextConfig() to TasteConfig(completionWeight = 0.0, replayWeight = 0.0, artistAffinityWeight = 0.0, coListenWeight = 0.0, signalWeight = 0.0, dayPartWeight = 0.08)),
    )
    val rows = LinkedHashMap<String, ArrayList<TasteEvalRow>>()
    val est = VibeEstimator()
    for (k in 0 until cuts) {
        val testEnd = end - k * holdDays * day
        val cut = testEnd - holdDays * day
        val train = h.before(cut).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
        if (train.plays.size < 50) continue
        val prof = ProfileBuilder.build(train, cut)
        val th = prof.thresholds
        val pool = evalPool(train, prof, est)
        val trainPlayed = train.plays.filter { it.playedMs >= 30_000L }.map { it.trackId }.toHashSet()
        for (hr in hours) {
            val at = cut + hr * 3_600_000L
            val good = HashSet<String>()
            val skipped = HashSet<String>()
            val goodHour = HashSet<String>()
            for (e in h.plays) if (e.timestampMs in at until at + holdDays * day) {
                val out = ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction)
                if (out == Outcome.SKIP) skipped += e.trackId
                else {
                    good += e.trackId
                    val dh = kotlin.math.abs(DayClock.dateTime(e.timestampMs).hour - hr).let { minOf(it, 24 - it) }
                    if (dh <= 2) goodHour += e.trackId
                }
            }
            val recent = h.plays.filter { it.timestampMs in (at - 12 * 3_600_000L) until at }.map { it.trackId }.toHashSet()
            val known = h.before(at).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
            val dt = DayClock.dateTime(at)
            val target = VibeMixer().target(DailyPlan.presetFor(dt.dayOfWeek.value, dt.hour, emptyMap(), null), Sliders(familiarNew = 0.65)).copy(tags = emptySet())
            for ((name, pair) in variants) {
                val (ctxCfg, tasteCfg) = pair
                val boosts = MixContext.boosts(known, at, ctxCfg)
                var p2 = MixContext.apply(pool, boosts, Feedback(), ctxCfg)
                if (tasteCfg.enabled) {
                    val learned = TasteModel.learn(known, at, tasteCfg)
                    p2 = TasteModel.apply(p2, learned)
                }
                val sess = MixSession(target, newShare = 0.30)
                val m = VibeMixer(Random(42 + k * 31 + hr))
                val list = ArrayList<PoolTrack>()
                list += sess.nextBatch(m, p2, 40)
                while (list.size < 100) { val b = sess.nextBatch(m, p2, 20, list.last()); if (b.isEmpty()) break; list += b }
                val f35 = list.take(35)
                val fam = list.filter { it.track.id in trainPlayed }
                val news = f35.filter { it.track.id !in trainPlayed }
                val arts = f35.groupingBy { it.track.mainArtist }.eachCount()
                rows.getOrPut(name) { ArrayList() } += TasteEvalRow(
                    hit35 = f35.count { it.track.id in good } / 35.0,
                    hit100 = list.count { it.track.id in good } / list.size.toDouble().coerceAtLeast(1.0),
                    famHit = if (fam.isEmpty()) 0.0 else fam.count { it.track.id in good } / fam.size.toDouble(),
                    newHit35 = if (news.isEmpty()) 0.0 else news.count { it.track.id in good } / news.size.toDouble(),
                    skip35 = f35.count { it.track.id in skipped && it.track.id !in good } / 35.0,
                    hourHit35 = f35.count { it.track.id in goodHour } / 35.0,
                    recent12h = f35.count { it.track.id in recent } / 35.0,
                    artists35 = arts.size.toDouble(),
                    maxArtist = (arts.values.maxOrNull() ?: 0) / 35.0,
                    newShare = news.size / 35.0,
                )
            }
        }
    }
    fun a(l: List<TasteEvalRow>, f: (TasteEvalRow) -> Double) = l.map(f).average()
    println("TasteModel forward-only holdout, %d cuts x %d build hours (first 35 / first 100, 30%% new)".format(Locale.ROOT, cuts, hours.size))
    for ((name, l) in rows) println("%-17s hit@35=%.3f hit@100=%.3f fam=%.3f newHit=%.3f skip35=%.4f hr35=%.3f rec12h=%.3f art35=%.1f maxA=%.3f".format(
        Locale.ROOT, name, a(l) { it.hit35 }, a(l) { it.hit100 }, a(l) { it.famHit }, a(l) { it.newHit35 },
        a(l) { it.skip35 }, a(l) { it.hourHit35 }, a(l) { it.recent12h }, a(l) { it.artists35 }, a(l) { it.maxArtist }))
}
