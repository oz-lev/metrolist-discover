/**
 * Metrolist Discover - offline evaluator for the vibe mixes (13.7.0.9, GPL-3.0).
 *   java -cp eval.jar EvalCliKt --tsv DIR --mixes
 * Rolling weekly cuts: builds the main mix the way the app does (pool = the history's songs, familiarity from plays,
 * affinity from the taste profile) with the 13.7.0.8 code path ("before": per-artist vibe, build(35, 0.65), rank order)
 * and the 13.7.0.9 path ("after": per-track vibe, balanced familiar/new, DJ energy arcs, endless batches), and measures
 * what was played (not skipped) in the next 7 days, novelty, variety and smoothness. Also a session replay for the
 * real-time learning. Personal data never leaves the machine that runs it.
 */
import com.metrolist.music.discover.engine.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

fun evalPool(train: History, p: TasteProfile, est: VibeEstimator): List<PoolTrack> {
    val counts = HashMap<String, Int>()
    for (e in train.plays) if (e.playedMs >= 30_000L) counts[e.trackId] = (counts[e.trackId] ?: 0) + 1
    val vibes = est.estimateAll(train.tracks.values, train.tracks, train.related)
    return train.tracks.values.filter { it.title.isNotBlank() }.map { t ->
        val c = counts[t.id] ?: 0
        val fam = min(1.0, ln(1.0 + c) / ln(31.0))
        val art = p.artistAffinity(t.mainArtist)
        var aff = (0.55 * p.trackAffinity(t.id).coerceIn(0.0, 1.0) + 0.45 * ((art + 1) / 2)).coerceIn(0.0, 1.0)
        aff = (aff - 0.5 * p.trackSkipExcess(t.id)).coerceIn(0.0, 1.0)
        PoolTrack(t, vibes[t.id] ?: est.own(t), fam, aff)
    }
}

data class MixRow(val n: Double, val hit: Double, val hitAt35: Double, val newShare: Double, val newHits: Double, val artistsPerN: Double,
                  val maxArtist: Double, val smooth: Double, val sameAdj: Double, val energyStd: Double, val defaultVibe: Double, val repeats: Double)

fun mixReport(h: History, cuts: Int = 12, holdDays: Int = 7) {
    val day = 86_400_000L
    val end = h.plays.maxOf { it.timestampMs } + 1
    val before = ArrayList<MixRow>(); val after = ArrayList<MixRow>()
    val variants = LinkedHashMap<Double, ArrayList<MixRow>>(); var famHit = 0.0; var art35B = 0; var art35A = 0; var art100A = 0
    var sessA = 0.0; var sessB = 0.0; var sessN = 0; var top50A = 0; var top50B = 0
    for (k in 0 until cuts) {
        val testEnd = end - k * holdDays * day
        val cut = testEnd - holdDays * day
        val train = h.before(cut).let { t -> t.copy(signals = t.signals.filter { it.timestampMs != null }) }
        if (train.plays.size < 50) continue
        val prof = ProfileBuilder.build(train, cut)
        val th = prof.thresholds
        val good = HashSet<String>()
        for (e in h.plays) if (e.timestampMs in cut until testEnd &&
            ProfileBuilder.outcome(e, h, th.skipFraction, th.skipSeconds, th.fullFraction) != Outcome.SKIP) good += e.trackId
        val trainPlayed = train.plays.filter { it.playedMs >= 30_000L }.map { it.trackId }.toHashSet()
        val at = cut + 18 * 3_600_000L
        val dt = DayClock.dateTime(at)
        val preset = DailyPlan.presetFor(dt.dayOfWeek.value, dt.hour, emptyMap(), null)
        fun row(list: List<PoolTrack>, defaultVibes: Double): MixRow {
            val sz = list.size.toDouble().coerceAtLeast(1.0)
            val arts = list.groupingBy { it.track.mainArtist }.eachCount()
            val first = list.take(35)
            return MixRow(list.size.toDouble(), list.count { it.track.id in good } / sz, first.count { it.track.id in good } / first.size.toDouble().coerceAtLeast(1.0),
                list.count { it.track.id !in trainPlayed } / sz, list.count { it.track.id in good && it.track.id !in trainPlayed } / sz,
                arts.size / sz, (arts.values.maxOrNull() ?: 0) / sz, EnergyArc.smoothness(list), EnergyArc.adjacentSameArtist(list),
                EnergyArc.energyStd(list), defaultVibes, (list.size - list.distinctBy { it.track.id }.size).toDouble())
        }
        fun defaultShare(pool: List<PoolTrack>, est: VibeEstimator): Double {
            val base = VibeConfig()
            return pool.count { abs(it.vibe.energy - base.baseEnergy) < 1e-9 && abs(it.vibe.valence - base.baseValence) < 1e-9 } / pool.size.toDouble()
        }
        // ---- before: 13.7.0.8
        val estOld = VibeEstimator(VibeConfig(perTrack = false))
        val poolOld = evalPool(train, prof, estOld)
        val mOld = VibeMixer(Random(42 + k))
        val tOld = mOld.target(preset, Sliders(familiarNew = 0.65)).copy(tags = emptySet())
        val listOld = mOld.build(poolOld, tOld, 35, 0.65)
        before += row(listOld, defaultShare(poolOld, estOld))
        // ---- after: 13.7.0.9 (first batch 40, then endless batches of 20 to 100)
        val estNew = VibeEstimator()
        val poolNew = evalPool(train, prof, estNew)
        val mNew = VibeMixer(Random(42 + k))
        val sess = MixSession(tOld, newShare = 0.30)
        val listNew = ArrayList<PoolTrack>()
        listNew += sess.nextBatch(mNew, poolNew, 40)
        while (listNew.size < 100) { val b = sess.nextBatch(mNew, poolNew, 20, listNew.last()); if (b.isEmpty()) break; listNew += b }
        after += row(listNew, defaultShare(poolNew, estNew))
        for (ns in listOf(0.0, 0.2, 0.3, 0.5)) {
            val sv = MixSession(tOld, newShare = ns); val l = sv.nextBatch(VibeMixer(Random(42 + k)), poolNew, 35)
            variants.getOrPut(ns) { ArrayList() } += row(l, 0.0)
        }
        famHit += listNew.filter { it.track.id in trainPlayed }.let { f -> if (f.isEmpty()) 0.0 else f.count { it.track.id in good } / f.size.toDouble() }
        art35B += listOld.take(35).map { it.track.mainArtist }.distinct().size; art35A += listNew.take(35).map { it.track.mainArtist }.distinct().size
        art100A += listNew.map { it.track.mainArtist }.distinct().size
        // ---- session replay (real-time learning): within each listening session of the test week, after each
        // early skip / full listen, where does the NEXT song he really played to the end rank among the pool?
        val byId = poolNew.associateBy { it.track.id }
        val test = h.plays.filter { it.timestampMs in cut until testEnd }.sortedBy { it.timestampMs }
        val sessions = ArrayList<MutableList<PlayEvent>>()
        for (e in test) { if (sessions.isEmpty() || e.timestampMs - sessions.last().last().timestampMs > 30 * 60_000L) sessions += mutableListOf(e) else sessions.last() += e }
        val scorer = VibeMixer(Random(7))
        val base = poolNew.associate { it.track.id to scorer.score(it, tOld, 0.65) }
        for (s in sessions) {
            if (s.size < 4) continue
            val taste = SessionTaste()
            for (i in s.indices) {
                val e = s[i]; val p = byId[e.trackId]
                val dur = h.duration(e)
                if (i >= 1 && p != null && !SkipRule.isEarlySkip(e.playedMs, dur) && !taste.isEmpty) {
                    // rank of the next good song: without vs with the session adjustment
                    val sb = base[p.track.id] ?: 0.0; val sa = sb + taste.adjust(p)
                    var rb = 0; var ra = 0; var tot = 0
                    for (q in poolNew) { if (q.track.id == p.track.id) continue; tot++; val qb = base[q.track.id] ?: 0.0; if (qb > sb) rb++; if (qb + taste.adjust(q) > sa) ra++ }
                    sessB += 1.0 - rb.toDouble() / tot; sessA += 1.0 - ra.toDouble() / tot; sessN++
                    if (rb < 50 * tot / 1000) top50B++; if (ra < 50 * tot / 1000) top50A++
                }
                if (p != null) { if (SkipRule.isEarlySkip(e.playedMs, dur)) taste.onSkip(p.track, p.vibe) else if (SkipRule.isFull(e.playedMs, dur)) taste.onFull(p.track, p.vibe) }
            }
        }
    }
    fun avg(l: List<MixRow>, f: (MixRow) -> Double) = l.map(f).average()
    fun show(name: String, l: List<MixRow>) = println(("%-7s n=%.0f hit=%.3f hit@35=%.3f new=%.3f newHit=%.3f artists/n=%.3f maxArtist=%.3f " +
        "energyStep=%.3f sameArtistAdj=%.3f energyStd=%.3f defaultVibePool=%.3f repeats=%.0f").format(Locale.ROOT, name,
        avg(l) { it.n }, avg(l) { it.hit }, avg(l) { it.hitAt35 }, avg(l) { it.newShare }, avg(l) { it.newHits }, avg(l) { it.artistsPerN },
        avg(l) { it.maxArtist }, avg(l) { it.smooth }, avg(l) { it.sameAdj }, avg(l) { it.energyStd }, avg(l) { it.defaultVibe }, avg(l) { it.repeats }))
    println("cuts=${before.size}")
    show("before", before); show("after", after)
    val nc = before.size.coerceAtLeast(1)
    println("after: hit among familiar picks=%.3f | distinct artists in first 35: before=%.1f after=%.1f | in 100: %.1f".format(Locale.ROOT, famHit / nc, art35B.toDouble() / nc, art35A.toDouble() / nc, art100A.toDouble() / nc))
    for ((ns, l) in variants) show("new=%.2f".format(Locale.ROOT, ns), l)
    if (sessN > 0) println("session replay: next fully-played song percentile without=%.3f with=%.3f (n=%d) | in top 5%%: %.3f -> %.3f".format(Locale.ROOT,
        sessB / sessN, sessA / sessN, sessN, top50B.toDouble() / sessN, top50A.toDouble() / sessN))
}

/** Personal mixes on real data: which appear, how big, how new; the daily rotation over a week. */
fun personalReport(h: History) {
    val now = h.plays.maxOf { it.timestampMs } + 1
    val prof = ProfileBuilder.build(h, now)
    val pool = evalPool(h, prof, VibeEstimator())
    val played = h.plays.filter { it.playedMs >= 30_000L }.map { it.trackId }.toHashSet()
    val all = PersonalMixes.all(h, pool, now)
    for (m in all) println("%-18s %-28s n=%2d new=%.2f artists=%d | %s".format(Locale.ROOT, m.kind, m.titleHe.take(28), m.tracks.size,
        m.tracks.count { it.track.id !in played }.toDouble() / m.tracks.size, m.tracks.map { it.track.mainArtist }.distinct().size, m.subtitleHe))
    for (d in 0 until 7) {
        val day = DayClock.dayKey(now) + d
        println("day+$d: " + PersonalMixes.today(all, day, 18).joinToString(" | ") { it.titleHe })
    }
    // sparse data must not crash
    val sparse = h.copy(plays = h.plays.take(5))
    println("sparse(5 plays): " + PersonalMixes.all(sparse, evalPool(sparse, ProfileBuilder.build(sparse, now), VibeEstimator()), now).size + " mixes")
}
