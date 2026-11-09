/**
 * Metrolist Discover 13.7.0.14 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * Listening context for every vibe mix (main mix, mood mixes, personal mixes, the mix builder): a small score added to
 * VibeMixer.score through PoolTrack.context.
 *  - momentum: songs you are into right now (recency-weighted real listens) rise a little;
 *  - hour fit: songs you play mostly around this hour of the day rise a little (time-of-day context);
 *  - cooldown: a song heard in the last hours waits (anti-repetition), and an artist that filled the last two days waits a bit;
 *  - old taste fades: a song you looped long ago and never since slides down slightly (it has its own "forgotten" mixes);
 *  - feedback: "not this" songs and blocked artists are removed, "less like this" artists are pushed down.
 * Every knob is in ContextConfig; ContextConfig.OFF reproduces 13.7.0.13 exactly. Defaults picked on one real history
 * (EvalCli --context / --context-grid, 12 weekly cuts x 4 build hours): main-mix hit@35 0.237 -> 0.312, see README-OZ.md.
 */
package com.metrolist.music.discover.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

data class ContextConfig(
    val momentumWeight: Double = 0.50,
    val momentumHalfLifeDays: Double = 7.0,
    val hourWeight: Double = 0.10,
    /** hours on each side of the current hour that count as "this time of day" */
    val hourWindow: Int = 2,
    val cooldownHours: Double = 12.0,
    val cooldownWeight: Double = 0.50,
    /** an artist above this share of the last 2 days' real listens is pushed down... */
    val artistCooldownShare: Double = 0.12,
    /** ...by this much per unit of share above it */
    val artistCooldownSlope: Double = 1.0,
    val staleDays: Double = 60.0,
    val staleWeight: Double = 0.06,
    /** per "less like this" press on an artist (capped at 3 presses) */
    val lessArtistWeight: Double = 0.25,
) {
    companion object {
        val OFF = ContextConfig(momentumWeight = 0.0, hourWeight = 0.0, cooldownWeight = 0.0, artistCooldownSlope = 0.0, staleWeight = 0.0, lessArtistWeight = 0.0)
    }
}

/** The per-profile "not for me" state: hidden songs, blocked artists, and soft "less like this" counts per artist. */
data class Feedback(
    val hiddenSongs: Set<String> = emptySet(),
    val hiddenArtists: Set<String> = emptySet(),
    val lessArtists: Map<String, Int> = emptyMap(),
) {
    fun blocks(t: TrackMeta): Boolean = t.id in hiddenSongs || t.mainArtist in hiddenArtists
    val isEmpty: Boolean get() = hiddenSongs.isEmpty() && hiddenArtists.isEmpty() && lessArtists.isEmpty()

    fun withLess(artistId: String): Feedback = copy(lessArtists = lessArtists + (artistId to min(3, (lessArtists[artistId] ?: 0) + 1)))

    companion object {
        /** "artist=count" lines (the stored form). Bad lines are ignored. */
        fun decodeLess(s: String?): Map<String, Int> = s.orEmpty().split('\n').mapNotNull { l ->
            val i = l.lastIndexOf('='); if (i <= 0) null else l.substring(i + 1).toIntOrNull()?.let { l.substring(0, i) to it.coerceIn(1, 3) }
        }.toMap()
        fun encodeLess(m: Map<String, Int>): String = m.entries.toList().takeLast(300).joinToString("\n") { "${it.key}=${it.value}" }
    }
}

object MixContext {
    private const val DAY = 86_400_000L
    private const val HOUR = 3_600_000L

    private fun circ(a: Int, b: Int): Int { val d = abs(a - b) % 24; return min(d, 24 - d) }

    /** Context score per track id at [at] (only tracks with something to say are in the map). */
    fun boosts(h: History, at: Long, cfg: ContextConfig = ContextConfig(), feedback: Feedback = Feedback()): Map<String, Double> {
        val out = HashMap<String, Double>()
        fun add(id: String, v: Double) { if (v != 0.0) out[id] = (out[id] ?: 0.0) + v }
        val nowHour = DayClock.dateTime(at).hour
        val mom = HashMap<String, Double>(); val n = HashMap<String, Int>(); val inHour = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        val recentArtist = HashMap<String, Int>(); var recentTotal = 0
        val lnHalf = ln(2.0) / (cfg.momentumHalfLifeDays * DAY)
        for (e in h.plays) {
            if (e.timestampMs > at) continue
            last[e.trackId] = maxOf(last[e.trackId] ?: Long.MIN_VALUE, e.timestampMs)
            val dur = h.duration(e)
            if (e.playedMs < 30_000L || SkipRule.isEarlySkip(e.playedMs, dur)) continue
            val age = (at - e.timestampMs).toDouble()
            mom[e.trackId] = (mom[e.trackId] ?: 0.0) + exp(-lnHalf * age)
            n[e.trackId] = (n[e.trackId] ?: 0) + 1
            if (circ(DayClock.dateTime(e.timestampMs).hour, nowHour) <= cfg.hourWindow) inHour[e.trackId] = (inHour[e.trackId] ?: 0) + 1
            if (at - e.timestampMs <= 2 * DAY) { h.tracks[e.trackId]?.mainArtist?.let { if (it != "?") { recentArtist[it] = (recentArtist[it] ?: 0) + 1; recentTotal++ } } }
        }
        val base = (2 * cfg.hourWindow + 1) / 24.0
        for ((id, m) in mom) {
            // saturating: 1 recent listen ~0.5, 3 ~0.75, many -> 1
            add(id, cfg.momentumWeight * (m / (m + 1.0)))
            val c = n[id] ?: 0
            if (c >= 3 && cfg.hourWeight != 0.0) {
                val lift = ((inHour[id] ?: 0).toDouble() / c - base).coerceIn(-0.25, 0.6)
                add(id, cfg.hourWeight * lift * (c / (c + 3.0)))
            }
            val l = last[id] ?: continue
            if (c >= 5 && at - l > cfg.staleDays * DAY) add(id, -cfg.staleWeight)
        }
        if (cfg.cooldownWeight != 0.0) for ((id, l) in last) {
            val ageH = (at - l).toDouble() / HOUR
            if (ageH < cfg.cooldownHours) add(id, -cfg.cooldownWeight * (1.0 - ageH / cfg.cooldownHours))
        }
        if (recentTotal >= 10 && cfg.artistCooldownSlope != 0.0) {
            val heavy = recentArtist.filter { it.value.toDouble() / recentTotal > cfg.artistCooldownShare }
            if (heavy.isNotEmpty()) for (t in h.tracks.values) {
                val c = heavy[t.mainArtist] ?: continue
                add(t.id, -cfg.artistCooldownSlope * (c.toDouble() / recentTotal - cfg.artistCooldownShare))
            }
        }
        if (feedback.lessArtists.isNotEmpty() && cfg.lessArtistWeight != 0.0) for (t in h.tracks.values) {
            val k = feedback.lessArtists[t.mainArtist] ?: continue
            add(t.id, -cfg.lessArtistWeight * k)
        }
        return out
    }

    /** The pool with context applied: blocked songs / artists removed, every other track carries its context score. */
    fun apply(pool: List<PoolTrack>, boosts: Map<String, Double>, feedback: Feedback = Feedback(), cfg: ContextConfig = ContextConfig()): List<PoolTrack> =
        pool.mapNotNull { p ->
            if (feedback.blocks(p.track)) return@mapNotNull null
            var c = boosts[p.track.id] ?: 0.0
            // online songs are not in the history map: "less like this" still reaches them through the artist
            if (p.track.id !in boosts) feedback.lessArtists[p.track.mainArtist]?.let { c -= cfg.lessArtistWeight * it }
            if (c == 0.0) p else p.copy(context = c)
        }
}
