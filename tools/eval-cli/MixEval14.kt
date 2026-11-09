/**
 * Metrolist Discover - offline evaluator for 13.7.0.14 (GPL-3.0). Local only: personal data never leaves the machine.
 *   java -cp eval.jar EvalCliKt --tsv DIR --context    listening context (MixContext) in the main mix, before/after + variants
 *   java -cp eval.jar EvalCliKt --tsv DIR --newmixes   the new mixes: size, newness, hit rate in the next 7 days
 *   java -cp eval.jar EvalCliKt --tsv DIR --grid3      Discover-row knobs (time of day, taste decay, skip weight)
 * "hit" = played (not skipped) in the 7 days after the cut; "hourHit" = played within +-2 h of the build hour in those days;
 * "recent12h" = share of the first 35 heard in the 12 h before the build (repetition); artists35 = distinct artists in the first 35.
 */
import com.metrolist.music.discover.engine.*
import java.util.Locale
import kotlin.random.Random

data class CtxRow(val hit35: Double, val hit100: Double, val famHit: Double, val hourHit35: Double, val recent12h: Double, val artists35: Double, val maxArtist: Double, val newShare: Double)

fun contextReport(h: History, cuts: Int = 12, holdDays: Int = 7, variants: Map<String, ContextConfig>? = null, hours: List<Int> = listOf(9, 14, 18, 22)) {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    val vs = variants ?: linkedMapOf(
        "off(13.7.0.13)" to ContextConfig.OFF,
        "on(13.7.0.14)" to ContextConfig(),
        "momentum only" to ContextConfig.OFF.copy(momentumWeight = 0.50),
        "hour only" to ContextConfig.OFF.copy(hourWeight = 0.10),
        "cooldown only" to ContextConfig.OFF.copy(cooldownWeight = 0.50, artistCooldownSlope = 1.0),
        "stale only" to ContextConfig.OFF.copy(staleWeight = 0.06),
        "mom.3" to ContextConfig(momentumWeight = 0.3),
        "hour.2" to ContextConfig(hourWeight = 0.2),
        "cool.25" to ContextConfig(cooldownWeight = 0.25),
        "stale0" to ContextConfig(staleWeight = 0.0),
    )
    val rows = LinkedHashMap<String, ArrayList<CtxRow>>()
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
            val good = HashSet<String>(); val goodHour = HashSet<String>()
            for (e in h.plays) if (e.timestampMs in at until at + holdDays * day &&
                ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction) != Outcome.SKIP) {
                good += e.trackId
                val dh = kotlin.math.abs(DayClock.dateTime(e.timestampMs).hour - hr).let { minOf(it, 24 - it) }
                if (dh <= 2) goodHour += e.trackId
            }
            val recent = h.plays.filter { it.timestampMs in (at - 12 * 3_600_000L) until at }.map { it.trackId }.toHashSet()
            // the training history up to the build moment (the cut is at midnight; plays of that day before [at] are known)
            val known = h.before(at).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
            val dt = DayClock.dateTime(at)
            val target = VibeMixer().target(DailyPlan.presetFor(dt.dayOfWeek.value, dt.hour, emptyMap(), null), Sliders(familiarNew = 0.65)).copy(tags = emptySet())
            for ((name, cfg) in vs) {
                val boosts = MixContext.boosts(known, at, cfg)
                val p2 = MixContext.apply(pool, boosts, Feedback(), cfg)
                val sess = MixSession(target, newShare = 0.30)
                val m = VibeMixer(Random(42 + k * 31 + hr))
                val list = ArrayList<PoolTrack>()
                list += sess.nextBatch(m, p2, 40)
                while (list.size < 100) { val b = sess.nextBatch(m, p2, 20, list.last()); if (b.isEmpty()) break; list += b }
                val f35 = list.take(35)
                val fam = list.filter { it.track.id in trainPlayed }
                val arts = f35.groupingBy { it.track.mainArtist }.eachCount()
                rows.getOrPut(name) { ArrayList() } += CtxRow(
                    f35.count { it.track.id in good } / 35.0, list.count { it.track.id in good } / list.size.toDouble().coerceAtLeast(1.0),
                    if (fam.isEmpty()) 0.0 else fam.count { it.track.id in good } / fam.size.toDouble(),
                    f35.count { it.track.id in goodHour } / 35.0, f35.count { it.track.id in recent } / 35.0,
                    arts.size.toDouble(), (arts.values.maxOrNull() ?: 0) / 35.0, f35.count { it.track.id !in trainPlayed } / 35.0)
            }
        }
    }
    fun a(l: List<CtxRow>, f: (CtxRow) -> Double) = l.map(f).average()
    println("main mix with listening context, %d cuts x %d build hours (first 35 / first 100 of the endless mix, 30%% new)".format(Locale.ROOT, cuts, hours.size))
    for ((name, l) in rows) println("%-16s hit@35=%.3f hit@100=%.3f famHit=%.3f hourHit@35=%.3f recent12h=%.3f artists35=%.1f maxArtist=%.3f new=%.3f".format(Locale.ROOT,
        name, a(l) { it.hit35 }, a(l) { it.hit100 }, a(l) { it.famHit }, a(l) { it.hourHit35 }, a(l) { it.recent12h }, a(l) { it.artists35 }, a(l) { it.maxArtist }, a(l) { it.newShare }))
}

/** The new mixes on real data: rolling weekly cuts, which mixes exist, size, newness and hit rate in the next 7 days. */
fun newMixesReport(h: History, cuts: Int = 12, holdDays: Int = 7) {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    val est = VibeEstimator()
    class Acc { var n = 0; var size = 0.0; var hit = 0.0; var new = 0.0; var artists = 0.0 }
    val acc = LinkedHashMap<String, Acc>()
    for (k in 0 until cuts) {
        val testEnd = end - k * holdDays * day; val cut = testEnd - holdDays * day
        val at = cut + 18 * 3_600_000L
        val train = h.before(at).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
        if (train.plays.size < 50) continue
        val prof = ProfileBuilder.build(train, at); val th = prof.thresholds
        val good = HashSet<String>()
        for (e in h.plays) if (e.timestampMs in at until at + holdDays * day && ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction) != Outcome.SKIP) good += e.trackId
        val played = train.plays.filter { it.playedMs >= 30_000L }.map { it.trackId }.toHashSet()
        val pool = MixContext.apply(evalPool(train, prof, est), MixContext.boosts(train, at))
        val mixes = PersonalMixes.all(train, pool, at, VibeMixer(Random(k.toLong()))) + MoreMixes.all(train, pool, at, 18, VibeMixer(Random(k.toLong())))
        for (m in mixes) {
            val key = m.kind.name + (if (m.kind == PersonalKind.LANGUAGE || m.kind == PersonalKind.SESSION) ":" + m.key else "")
            val a = acc.getOrPut(key) { Acc() }
            a.n++; a.size += m.tracks.size; a.hit += m.tracks.count { it.track.id in good }.toDouble() / m.tracks.size
            a.new += m.tracks.count { it.track.id !in played }.toDouble() / m.tracks.size; a.artists += m.tracks.map { it.track.mainArtist }.distinct().size
        }
    }
    println("new + existing personal mixes over $cuts weekly cuts (built at 18:00): cuts where it exists, avg size, hit rate next 7 d, never-heard share, distinct artists")
    for ((k, a) in acc) println("%-28s cuts=%2d size=%4.1f hit=%.3f new=%.2f artists=%4.1f".format(Locale.ROOT, k, a.n, a.size / a.n, a.hit / a.n, a.new / a.n, a.artists / a.n))
}

fun contextGrid(h: History) = contextReport(h, variants = linkedMapOf(
    "off" to ContextConfig.OFF,
    "default" to ContextConfig(),
    "m.4 c.4" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4),
    "m.5 c.5" to ContextConfig(momentumWeight = 0.5, cooldownWeight = 0.5),
    "m.4 c.4 h.2" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4, hourWeight = 0.2),
    "m.4 c.4 hl4" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4, momentumHalfLifeDays = 4.0),
    "m.4 c.4 hl14" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4, momentumHalfLifeDays = 14.0),
    "m.4 c.4 cd24" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4, cooldownHours = 24.0),
    "m.4 c.4 ac0" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4, artistCooldownSlope = 0.0),
    "m.4 c.4 ac2" to ContextConfig(momentumWeight = 0.4, cooldownWeight = 0.4, artistCooldownSlope = 2.0),
))

fun reportGrid3(h: History) {
    val variants = linkedMapOf(
        "13.7.0.13" to DiscoverConfig(),
        "tod0.10" to DiscoverConfig(weights = RankWeights(wTimeOfDay = 0.10)),
        "tod0.35" to DiscoverConfig(weights = RankWeights(wTimeOfDay = 0.35)),
        "short0.45" to DiscoverConfig(shortTermMix = 0.45),
        "short0.75" to DiscoverConfig(shortTermMix = 0.75),
        "longHL30" to DiscoverConfig(longHalfLifeDays = 30.0),
        "longHL120" to DiscoverConfig(longHalfLifeDays = 120.0),
        "shortHL7" to DiscoverConfig(shortHalfLifeDays = 7.0),
        "skip-1.3" to DiscoverConfig(skipWeight = -1.3),
        "skip-0.6" to DiscoverConfig(skipWeight = -0.6),
        "partial0.2" to DiscoverConfig(partialListenWeight = 0.2),
        "partial0.5" to DiscoverConfig(partialListenWeight = 0.5),
        "fatigue.08" to DiscoverConfig(fatigueStart = 0.08),
    )
    for ((name, cfg) in variants) println("%-12s %s".format(Locale.ROOT, name, fmt(listReport(h, DiscoverEngine(cfg), verbose = false))))
}
