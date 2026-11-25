/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * TasteModel: a measurable, user-tunable taste learner layered over MixContext.
 * Learns from actual listening behaviour:
 *  - completion ratio & fast-skip avoidance (full plays vs quick skips, track & artist level)
 *  - back-to-back & same-day replays (strong love signal)
 *  - recency-weighted artist affinity (lifts both familiar and unheard songs of artists you love now)
 *  - co-listening session transitions (songs & artists that flow together in your real sessions)
 *  - explicit signals (LIKE / DOWNLOAD / LIBRARY_ADD / PLAYLIST_ADD) & explicit boosted/muted artists
 *  - day-part (morning / noon / evening / night) artist & track preference learning
 *
 * Every knob lives in [TasteConfig]; [TasteConfig.OFF] reproduces 13.7.0.14 exactly.
 */
package com.metrolist.music.discover.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class TasteConfig(
    val enabled: Boolean = true,
    /** Rewards high completion ratio & penalises fast skips (multiplied by skipStrictness on penalties). */
    val completionWeight: Double = 0.18,
    /** Rewards back-to-back / same-session / same-day replays. */
    val replayWeight: Double = 0.20,
    /** Recency-weighted artist affinity (helps both familiar & unheard tracks of active artists). */
    val artistAffinityWeight: Double = 0.24,
    /** Half-life in days for artist affinity & completion memory. */
    val artistHalfLifeDays: Double = 14.0,
    /** Session co-listening transition boost (tracks & artists played in the same session as recent plays). */
    val coListenWeight: Double = 0.14,
    /** Half-life in days for session transition edges. */
    val coListenHalfLifeDays: Double = 21.0,
    /** Explicit positive library signals (LIKE / DOWNLOAD / LIBRARY_ADD / PLAYLIST_ADD). */
    val signalWeight: Double = 0.18,
    /** Day-part (morning / noon / evening / night) artist preference learning. */
    val dayPartWeight: Double = 0.08,
    /** User-tunable multiplier (0..2) for skip penalties & less-artist pushdown. */
    val skipStrictness: Double = 1.0,
    /** User-tunable multiplier (0..2) for recency / momentum & co-listening strength. */
    val recencyStrength: Double = 1.0,
    /** Boost for explicitly starred/boosted artists chosen by the user. */
    val boostArtistWeight: Double = 0.45,
) {
    companion object {
        val OFF = TasteConfig(enabled = false)
    }
}

/** Learned taste snapshot for a profile at a point in time, plus human-readable summary for the UI. */
data class LearnedTaste(
    val trackBoosts: Map<String, Double>,
    val artistBoosts: Map<String, Double>,
    val topArtists: List<Pair<String, Double>>,
    val avgCompletion: Double,
    val peakDayPart: DayPart?,
    val skipAvoidedTracks: Int,
    val replayCount: Int,
    val coListenEdges: Int,
) {
    fun boostFor(trackId: String, artistId: String): Double =
        (trackBoosts[trackId] ?: 0.0) + (artistBoosts[artistId] ?: 0.0)
}

object TasteModel {
    private const val DAY = 86_400_000L
    private const val MINUTE = 60_000L
    private const val SESSION_GAP = 30 * MINUTE
    private const val REPLAY_GAP = 45 * MINUTE

    fun decodeBoosted(s: String?): Set<String> =
        s.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun encodeBoosted(set: Set<String>): String =
        set.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(200).joinToString("\n")

    /**
     * Learns taste boosts from [h] strictly up to [at] (zero future leakage).
     * Combines track-level and artist-level signals cleanly so even unseen/online tracks get artist boosts.
     */
    fun learn(
        h: History,
        at: Long,
        cfg: TasteConfig = TasteConfig(),
        feedback: Feedback = Feedback(),
        boostedArtists: Set<String> = emptySet(),
    ): LearnedTaste {
        if (!cfg.enabled) {
            val ab = HashMap<String, Double>()
            for (a in boostedArtists) if (a !in feedback.hiddenArtists) ab[a] = cfg.boostArtistWeight
            return LearnedTaste(emptyMap(), ab, emptyList(), 0.0, null, 0, 0, 0)
        }

        val recK = cfg.recencyStrength.coerceIn(0.0, 2.0)
        val skipK = cfg.skipStrictness.coerceIn(0.0, 2.0)
        val lnArtist = ln(2.0) / (cfg.artistHalfLifeDays.coerceAtLeast(1.0) * DAY)
        val lnCo = ln(2.0) / (cfg.coListenHalfLifeDays.coerceAtLeast(1.0) * DAY)
        val nowPart = DayPart.of(DayClock.dateTime(at).hour)

        val trackFullW = HashMap<String, Double>()
        val trackSkipW = HashMap<String, Double>()
        val trackReplayW = HashMap<String, Double>()
        val artistFullW = HashMap<String, Double>()
        val artistSkipW = HashMap<String, Double>()
        val artistPartPlays = HashMap<String, IntArray>()
        val globalParts = IntArray(4)

        val trackCo = HashMap<String, HashMap<String, Double>>()
        val artistCo = HashMap<String, HashMap<String, Double>>()

        var totalRatio = 0.0
        var ratioCount = 0
        var replayEvents = 0
        var coEdges = 0

        val lastPlayedTrack = HashMap<String, Long>()
        var prevTrack: String? = null
        var prevArtist: String? = null
        var prevTs = Long.MIN_VALUE
        val recentAnchors = ArrayList<Pair<String, String>>() // (trackId, artistId) of recent non-skip plays

        for (e in h.sortedPlays) {
            if (e.timestampMs > at) break
            val age = (at - e.timestampMs).coerceAtLeast(0L).toDouble()
            val wA = exp(-lnArtist * age)
            val dur = h.duration(e)
            val ratio = when {
                dur > 0 -> (e.playedMs.toDouble() / dur).coerceIn(0.0, 1.5)
                e.playedMs >= 150_000L -> 0.95
                e.playedMs < 30_000L -> 0.10
                else -> 0.55
            }
            val isSkip = e.playedMs < 30_000L || SkipRule.isEarlySkip(e.playedMs, dur) || (dur > 0 && ratio < 0.30)
            val isFull = !isSkip && (ratio >= 0.80 || e.playedMs >= 180_000L)
            val artist = h.tracks[e.trackId]?.mainArtist?.takeIf { it != "?" }

            totalRatio += min(1.0, ratio)
            ratioCount++

            if (isSkip) {
                trackSkipW[e.trackId] = (trackSkipW[e.trackId] ?: 0.0) + wA
                if (artist != null) artistSkipW[artist] = (artistSkipW[artist] ?: 0.0) + wA
            } else {
                val credit = if (isFull) 1.0 else 0.45
                trackFullW[e.trackId] = (trackFullW[e.trackId] ?: 0.0) + wA * credit
                if (artist != null) {
                    artistFullW[artist] = (artistFullW[artist] ?: 0.0) + wA * credit
                    val partIdx = DayPart.of(DayClock.dateTime(e.timestampMs).hour).ordinal
                    artistPartPlays.getOrPut(artist) { IntArray(4) }[partIdx]++
                    globalParts[partIdx]++
                }

                // Back-to-back or same-day replay detection
                val lastT = lastPlayedTrack[e.trackId]
                if (lastT != null && e.timestampMs - lastT in 1L..DAY) {
                    val mult = if (e.timestampMs - lastT <= REPLAY_GAP) 1.0 else 0.5
                    trackReplayW[e.trackId] = (trackReplayW[e.trackId] ?: 0.0) + wA * mult
                    replayEvents++
                }
                lastPlayedTrack[e.trackId] = e.timestampMs

                // Co-listening session transition
                if (prevTrack != null && prevTrack != e.trackId && e.timestampMs - prevTs in 1L..SESSION_GAP) {
                    val wC = exp(-lnCo * age)
                    trackCo.getOrPut(prevTrack) { HashMap() }.merge(e.trackId, wC, Double::plus)
                    trackCo.getOrPut(e.trackId) { HashMap() }.merge(prevTrack, wC * 0.65, Double::plus)
                    coEdges++
                    if (prevArtist != null && artist != null && prevArtist != artist) {
                        artistCo.getOrPut(prevArtist) { HashMap() }.merge(artist, wC, Double::plus)
                        artistCo.getOrPut(artist) { HashMap() }.merge(prevArtist, wC * 0.65, Double::plus)
                    }
                }
                prevTrack = e.trackId
                prevArtist = artist
                prevTs = e.timestampMs

                if (at - e.timestampMs <= 3 * DAY) {
                    recentAnchors += (e.trackId to (artist ?: "?"))
                }
            }
        }

        val trackBoosts = HashMap<String, Double>()
        val artistBoosts = HashMap<String, Double>()
        fun addTrack(id: String, v: Double) { if (v != 0.0) trackBoosts[id] = (trackBoosts[id] ?: 0.0) + v }
        fun addArtist(a: String, v: Double) { if (v != 0.0) artistBoosts[a] = (artistBoosts[a] ?: 0.0) + v }

        // 1. Track completion & skip avoidance
        var skipAvoided = 0
        val allTids = HashSet<String>().apply { addAll(trackFullW.keys); addAll(trackSkipW.keys) }
        for (id in allTids) {
            val fw = trackFullW[id] ?: 0.0
            val sw = trackSkipW[id] ?: 0.0
            if (cfg.completionWeight != 0.0) {
                val net = fw - 1.2 * sw
                if (net > 0.0) {
                    val compRate = fw / (fw + sw + 0.5)
                    addTrack(id, cfg.completionWeight * recK * compRate * (net / (net + 1.2)))
                } else if (sw > 0.0) {
                    val pen = cfg.completionWeight * 1.6 * skipK * (sw / (sw + 0.6))
                    addTrack(id, -pen)
                    if (sw >= 0.4) skipAvoided++
                }
            }
        }

        // 2. Replay boost
        if (cfg.replayWeight != 0.0) {
            for ((id, rw) in trackReplayW) {
                addTrack(id, cfg.replayWeight * recK * (rw / (rw + 0.8)))
            }
        }

        // 3. Recency-weighted artist affinity (applies to all tracks of that artist, including unheard!)
        val rankedArtists = ArrayList<Pair<String, Double>>()
        val allArtists = HashSet<String>().apply { addAll(artistFullW.keys); addAll(artistSkipW.keys) }
        for (a in allArtists) {
            val fw = artistFullW[a] ?: 0.0
            val sw = artistSkipW[a] ?: 0.0
            val net = fw - 1.1 * sw
            if (cfg.artistAffinityWeight != 0.0) {
                if (net > 0.0) {
                    val sc = cfg.artistAffinityWeight * recK * (net / (net + 2.5))
                    addArtist(a, sc)
                    rankedArtists += a to sc
                } else if (sw > 0.5 && sw / (fw + sw + 1.0) > 0.35) {
                    addArtist(a, -cfg.artistAffinityWeight * 0.9 * skipK * (sw / (sw + 1.5)))
                }
            }
        }
        rankedArtists.sortByDescending { it.second }

        // 4. Co-listening session transition boost from recent plays (last 8 anchors within 3 days)
        if (cfg.coListenWeight != 0.0 && recentAnchors.isNotEmpty()) {
            val anchors = recentAnchors.takeLast(8)
            val tAcc = HashMap<String, Double>()
            val aAcc = HashMap<String, Double>()
            anchors.forEachIndexed { idx, (tid, aid) ->
                val decay = 0.5 + 0.5 * (idx + 1).toDouble() / anchors.size
                trackCo[tid]?.forEach { (nxt, w) -> tAcc[nxt] = (tAcc[nxt] ?: 0.0) + w * decay }
                if (aid != "?") artistCo[aid]?.forEach { (nxtA, w) -> aAcc[nxtA] = (aAcc[nxtA] ?: 0.0) + w * decay }
            }
            for ((tid, w) in tAcc) addTrack(tid, cfg.coListenWeight * recK * (w / (w + 1.5)))
            for ((aid, w) in aAcc) addArtist(aid, cfg.coListenWeight * 0.55 * recK * (w / (w + 2.5)))
        }

        // 5. Explicit LIKE signal (offline ablation showed bulk DOWNLOAD/LIBRARY_ADD hurt hit@35 0.312->0.298 by boosting stale tracks)
        if (cfg.signalWeight != 0.0) {
            for (s in h.signals) {
                if (s.kind != SignalKind.LIKE) continue
                if (s.timestampMs != null && s.timestampMs > at) continue
                addTrack(s.trackId, cfg.signalWeight)
            }
        }

        // Anti-repetition guard: never let positive TasteModel track boosts overpower the 12h cooldown
        val twelveH = 12 * 3_600_000L
        for ((id, lastTs) in lastPlayedTrack) {
            val ageMs = at - lastTs
            if (ageMs in 0L until twelveH) {
                val cur = trackBoosts[id] ?: 0.0
                if (cur > 0.0) {
                    val scale = ageMs.toDouble() / twelveH
                    trackBoosts[id] = cur * scale - 0.30 * (1.0 - scale)
                }
            }
        }

        // 6. Day-part artist preference learning
        val totalGlobal = globalParts.sum().coerceAtLeast(1)
        val basePartShare = (globalParts[nowPart.ordinal].toDouble() / totalGlobal).coerceIn(0.10, 0.50)
        if (cfg.dayPartWeight != 0.0) {
            for ((a, counts) in artistPartPlays) {
                val sum = counts.sum()
                if (sum < 4) continue
                val share = counts[nowPart.ordinal].toDouble() / sum
                val lift = (share - basePartShare).coerceIn(-0.20, 0.50)
                addArtist(a, cfg.dayPartWeight * lift * (sum.toDouble() / (sum + 4.0)))
            }
        }

        // 7. Explicit user boosted artists & skipStrictness scaling on lessArtists
        for (a in boostedArtists) {
            if (a !in feedback.hiddenArtists) addArtist(a, cfg.boostArtistWeight)
        }
        if (skipK != 1.0 && feedback.lessArtists.isNotEmpty()) {
            // MixContext applies 1.0 * lessArtistWeight; adjust the delta for skipStrictness != 1.0
            val deltaMult = skipK - 1.0
            for ((a, k) in feedback.lessArtists) {
                addArtist(a, -0.25 * k * deltaMult)
            }
        }

        val peakPart = if (globalParts.sum() > 0) DayPart.entries[globalParts.indices.maxBy { globalParts[it] }] else null
        return LearnedTaste(
            trackBoosts = trackBoosts,
            artistBoosts = artistBoosts,
            topArtists = rankedArtists.take(10),
            avgCompletion = if (ratioCount > 0) totalRatio / ratioCount else 0.0,
            peakDayPart = peakPart,
            skipAvoidedTracks = skipAvoided,
            replayCount = replayEvents,
            coListenEdges = coEdges,
        )
    }

    /** Applies learned taste boosts on top of a pool (preserving existing MixContext scores & respecting Feedback). */
    fun apply(pool: List<PoolTrack>, learned: LearnedTaste, feedback: Feedback = Feedback()): List<PoolTrack> =
        pool.mapNotNull { p ->
            if (feedback.blocks(p.track)) return@mapNotNull null
            val delta = learned.boostFor(p.track.id, p.track.mainArtist)
            if (delta == 0.0) p else p.copy(context = p.context + delta)
        }
}
