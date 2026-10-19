/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Cut-offs derived from this user's own distribution of listening, not hard-coded absolutes. */
data class Thresholds(
    val skipFraction: Double,
    val skipSeconds: Double,
    val fullFraction: Double,
    /** Typical gap (days) between two plays of the same track: scales novelty and "recent". */
    val repeatGapDays: Double,
    val recentWindowDays: Double,
    val forgottenAfterDays: Double,
    val userSkipRate: Double,
)

enum class Outcome { SKIP, PARTIAL, FULL }

class TasteProfile(
    val nowMs: Long,
    val thresholds: Thresholds,
    val artistShort: Map<String, Double>,
    val artistLong: Map<String, Double>,
    val albumAff: Map<String, Double>,
    val trackAff: Map<String, Double>,
    val trackShort: Map<String, Double>,
    val trackSkips: Map<String, Double>,
    val artistSkipRate: Map<String, Double>,
    val artistPlays: Map<String, Int>,
    val lastPlayed: Map<String, Long>,
    val trackPlayCount: Map<String, Int>,
    private val artistHours: Map<String, DoubleArray>,
    private val globalHours: DoubleArray,
    val recentArtistShare: Map<String, Double>,
    val playCount: Int,
    val config: DiscoverConfig,
) {
    val isColdStart: Boolean get() = playCount < config.minPlaysForProfile

    private val artistScaleLong = Scale.of(artistLong.values)
    private val artistScaleShort = Scale.of(artistShort.values)
    private val albumScale = Scale.of(albumAff.values)
    private val trackScale = Scale.of(trackAff.values)
    private val trackScaleShort = Scale.of(trackShort.values)

    /** Blended short/long artist affinity, log-scaled against this user's own distribution: about -1..1. */
    fun artistAffinity(artistId: String): Double {
        val l = artistScaleLong(artistLong[artistId] ?: 0.0)
        val s = artistScaleShort(artistShort[artistId] ?: 0.0)
        return ((1 - config.shortTermMix) * l + config.shortTermMix * s).coerceIn(-1.0, 1.0)
    }
    fun longFamiliarity(artistId: String): Double = artistScaleLong(artistLong[artistId] ?: 0.0)
    fun albumAffinity(albumId: String?): Double = if (albumId == null) 0.0 else albumScale(albumAff[albumId] ?: 0.0)
    fun trackAffinity(trackId: String): Double =
        (1 - config.shortTermMix) * trackScale(trackAff[trackId] ?: 0.0) + config.shortTermMix * trackScaleShort(trackShort[trackId] ?: 0.0)

    /** Share of this track's plays that were early skips, above the user's own skip rate (0 = normal). */
    fun trackSkipExcess(trackId: String): Double {
        val sk = trackSkips[trackId] ?: return 0.0
        val good = (trackPlayCount[trackId] ?: 0).toDouble()
        val rate = sk / (sk + good + 1.0)
        return ((rate - thresholds.userSkipRate) / (1 - thresholds.userSkipRate)).coerceIn(0.0, 1.0) * kotlin.math.min(1.0, sk)
    }

    /** Maps a raw affinity to -1..1 with log(1 + v / median) / log(1 + p99 / median): keeps the top end apart. */
    class Scale(private val mid: Double, private val top: Double) {
        operator fun invoke(v: Double): Double {
            val x = kotlin.math.ln(1 + kotlin.math.abs(v) / mid) / kotlin.math.ln(1 + top / mid)
            return (if (v < 0) -x else x).coerceIn(-1.0, 1.0)
        }
        companion object {
            fun of(values: Collection<Double>): Scale {
                val pos = values.filter { it > 0 }.sorted()
                if (pos.isEmpty()) return Scale(1.0, 2.0)
                val mid = max(quantile(pos, 0.5), 1e-6)
                return Scale(mid, max(pos.last(), mid * 1.5))
            }
        }
    }

    /** log-ratio of P(hour | artist) to P(hour), shrunk by how much we know about the artist. -1..1 */
    fun hourFit(artistId: String?, hourSlot: Int): Double {
        val h = artistId?.let { artistHours[it] } ?: return 0.0
        val n = h.sum()
        if (n <= 0.0) return 0.0
        val g = globalHours.sum().coerceAtLeast(1e-9)
        val p = (smoothed(h, hourSlot) + 0.02) / (n + 0.02 * SLOTS)
        val q = (smoothed(globalHours, hourSlot) + 0.02) / (g + 0.02 * SLOTS)
        val conf = n / (n + 8.0)
        return (ln(p / q) / 1.5).coerceIn(-1.0, 1.0) * conf
    }

    fun topArtists(n: Int): List<Pair<String, Double>> =
        artistLong.keys.associateWith { artistAffinity(it) }.entries
            .filter { it.value > 0 }.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    fun topTracks(n: Int): List<Pair<String, Double>> =
        trackAff.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(n).map { it.key to trackAffinity(it.key) }

    fun daysSince(trackId: String): Double? = lastPlayed[trackId]?.let { (nowMs - it) / DAY_MS }

    companion object {
        const val DAY_MS = 86_400_000.0
        const val SLOTS = 48 // 24 hours x (weekday, weekend)

        fun slotOf(tsMs: Long): Int {
            val days = Math.floorDiv(tsMs, 86_400_000L)
            val hour = ((tsMs - days * 86_400_000L) / 3_600_000L).toInt()
            // 1970-01-01 was a Thursday; Israel's weekend is Fri+Sat, most of the world Sat+Sun:
            // we treat Fri, Sat, Sun as "weekend-ish" to fit both.
            val dow = ((days + 3) % 7).toInt() // 0 = Monday
            val weekend = dow >= 4
            return hour + if (weekend) 24 else 0
        }

        private fun smoothed(h: DoubleArray, slot: Int): Double {
            val base = if (slot >= 24) 24 else 0
            val hr = slot - base
            var s = 0.0
            for (d in -2..2) {
                val w = when (kotlin.math.abs(d)) { 0 -> 1.0; 1 -> 0.6; else -> 0.25 }
                s += w * h[base + Math.floorMod(hr + d, 24)]
                s += 0.3 * w * h[(24 - base) + Math.floorMod(hr + d, 24)] // other day-type, weaker
            }
            return s
        }

        fun quantile(sorted: List<Double>, q: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val pos = q * (sorted.size - 1)
            val lo = pos.toInt(); val hi = min(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }

        fun decay(ageDays: Double, halfLife: Double): Double = exp(-ln(2.0) * max(ageDays, 0.0) / halfLife)
    }
}

object ProfileBuilder {
    fun thresholds(history: History, cfg: DiscoverConfig): Thresholds {
        val ratios = ArrayList<Double>()
        val secs = ArrayList<Double>()
        for (e in history.plays) {
            val d = history.duration(e)
            secs += e.playedMs / 1000.0
            if (d > 0) ratios += (e.playedMs.toDouble() / d).coerceIn(0.0, 1.5)
        }
        ratios.sort(); secs.sort()
        val skipFrac = if (ratios.size >= 20) TasteProfile.quantile(ratios, 0.2).coerceIn(0.12, cfg.defaultSkipFraction + 0.1)
        else cfg.defaultSkipFraction
        val skipSecs = if (secs.size >= 20) TasteProfile.quantile(secs, 0.1).coerceIn(15.0, cfg.defaultSkipSeconds + 15.0)
        else cfg.defaultSkipSeconds
        val fullFrac = if (ratios.size >= 20) TasteProfile.quantile(ratios, 0.5).coerceIn(0.6, 0.9) else 0.8

        // repeat gap: median days between consecutive plays of the same track
        val gaps = ArrayList<Double>()
        val last = HashMap<String, Long>()
        for (e in history.sortedPlays) {
            last[e.trackId]?.let { gaps += (e.timestampMs - it) / TasteProfile.DAY_MS }
            last[e.trackId] = e.timestampMs
        }
        gaps.sort()
        val gap = if (gaps.size >= 10) TasteProfile.quantile(gaps, 0.5).coerceIn(0.5, 60.0) else 7.0
        val recent = if (gaps.size >= 10) TasteProfile.quantile(gaps, 0.25).coerceIn(0.5, 7.0) else 2.0
        val forgotten = max(21.0, 3.0 * gap).coerceAtMost(120.0)
        var skips = 0; var n = 0
        for (e in history.plays) {
            n++
            if (outcome(e, history, skipFrac, skipSecs, fullFrac) == Outcome.SKIP) skips++
        }
        val sr = if (n > 0) skips.toDouble() / n else 0.2
        return Thresholds(skipFrac, skipSecs, fullFrac, gap, recent, forgotten, sr)
    }

    fun outcome(e: PlayEvent, h: History, skipFrac: Double, skipSecs: Double, fullFrac: Double): Outcome {
        val d = h.duration(e)
        val ratio = if (d > 0) e.playedMs.toDouble() / d else -1.0
        val sec = e.playedMs / 1000.0
        // Short track: the fraction rule decides. Unknown duration: the seconds rule decides.
        val skip = if (ratio >= 0) ratio < skipFrac || (sec < skipSecs && ratio < 0.5) else sec < skipSecs
        return when {
            skip -> Outcome.SKIP
            ratio < 0 -> Outcome.PARTIAL
            ratio >= fullFrac -> Outcome.FULL
            else -> Outcome.PARTIAL
        }
    }

    fun build(history: History, nowMs: Long, cfg: DiscoverConfig = DiscoverConfig()): TasteProfile {
        val th = thresholds(history, cfg)
        val aShort = HashMap<String, Double>(); val aLong = HashMap<String, Double>()
        val album = HashMap<String, Double>(); val track = HashMap<String, Double>(); val trackS = HashMap<String, Double>()
        val tSkips = HashMap<String, Double>()
        val aSkip = HashMap<String, Int>(); val aPlays = HashMap<String, Int>()
        val lastPlayed = HashMap<String, Long>(); val tCount = HashMap<String, Int>()
        val hours = HashMap<String, DoubleArray>(); val global = DoubleArray(TasteProfile.SLOTS)
        val recentShare = HashMap<String, Double>()
        val prevPlay = HashMap<String, Long>()
        var recentTotal = 0.0

        fun add(trackId: String, value: Double, ts: Long?) {
            val t = history.tracks[trackId]
            val age = if (ts == null) 30.0 else (nowMs - ts) / TasteProfile.DAY_MS
            val dl = TasteProfile.decay(age, cfg.longHalfLifeDays)
            val ds = TasteProfile.decay(age, cfg.shortHalfLifeDays)
            track.merge(trackId, value * dl, Double::plus)
            trackS.merge(trackId, value * ds, Double::plus)
            val artists = t?.artistIds.orEmpty()
            artists.forEachIndexed { i, a ->
                val share = if (i == 0) 1.0 else 0.5
                aLong.merge(a, value * dl * share, Double::plus)
                aShort.merge(a, value * ds * share, Double::plus)
            }
            t?.albumId?.let { album.merge(it, value * dl * cfg.albumShare, Double::plus) }
        }

        for (e in history.sortedPlays) {
            if (e.timestampMs > nowMs) continue
            val o = outcome(e, history, th.skipFraction, th.skipSeconds, th.fullFraction)
            val d = history.duration(e)
            val ratio = if (d > 0) (e.playedMs.toDouble() / d).coerceIn(0.0, 1.0) else 0.5
            var v = when (o) {
                Outcome.SKIP -> cfg.skipWeight
                Outcome.PARTIAL -> cfg.partialListenWeight * ratio + 0.15
                Outcome.FULL -> cfg.fullListenWeight
            }
            val prev = prevPlay[e.trackId]
            if (o != Outcome.SKIP && prev != null && (e.timestampMs - prev) < cfg.repeatWindowHours * 3_600_000) v += cfg.repeatBonus
            if (o != Outcome.SKIP) prevPlay[e.trackId] = e.timestampMs
            add(e.trackId, v, e.timestampMs)

            val t = history.tracks[e.trackId]
            val main = t?.mainArtist
            if (main != null) {
                aPlays.merge(main, 1, Int::plus)
                if (o == Outcome.SKIP) aSkip.merge(main, 1, Int::plus)
            }
            val age = (nowMs - e.timestampMs) / TasteProfile.DAY_MS
            if (o == Outcome.SKIP) tSkips.merge(e.trackId, TasteProfile.decay(age, cfg.longHalfLifeDays), Double::plus)
            else {
                lastPlayed[e.trackId] = e.timestampMs
                tCount.merge(e.trackId, 1, Int::plus)
                val slot = TasteProfile.slotOf(e.timestampMs)
                val dw = TasteProfile.decay(age, cfg.longHalfLifeDays * 2)
                global[slot] += dw
                if (main != null) hours.getOrPut(main) { DoubleArray(TasteProfile.SLOTS) }[slot] += dw
                if (age <= max(3.0, th.recentWindowDays * 2) && main != null) {
                    recentShare.merge(main, 1.0, Double::plus); recentTotal += 1.0
                }
            }
        }
        for (s in history.signals) {
            if ((s.timestampMs ?: 0L) > nowMs) continue
            val v = when (s.kind) {
                SignalKind.LIKE -> cfg.likeWeight
                SignalKind.PLAYLIST_ADD -> cfg.playlistAddWeight
                SignalKind.DOWNLOAD -> cfg.downloadWeight
                SignalKind.LIBRARY_ADD -> cfg.libraryAddWeight
            }
            add(s.trackId, v, s.timestampMs)
        }
        val skipRate = HashMap<String, Double>()
        for ((a, n) in aPlays) {
            val k = 5.0 // shrink toward the user's own skip rate
            skipRate[a] = ((aSkip[a] ?: 0) + k * th.userSkipRate) / (n + k)
        }
        if (recentTotal > 0) for (k in recentShare.keys.toList()) recentShare[k] = recentShare[k]!! / recentTotal
        return TasteProfile(
            nowMs, th, aShort, aLong, album, track, trackS, tSkips, skipRate, aPlays, lastPlayed, tCount,
            hours, global, recentShare, history.plays.count { it.timestampMs <= nowMs }, cfg,
        )
    }
}
