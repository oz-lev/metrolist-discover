/**
 * Metrolist Discover 13.7.0.9 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * The "endless" main mix: balanced familiar/new picks, DJ-style energy arcs, session-scoped real-time learning
 * (an early skip pushes that artist / vibe down for the rest of the session, a full listen pulls similar songs up),
 * and next batches that follow what is playing right now, never repeating a song inside the session.
 */
package com.metrolist.music.discover.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Knobs for the in-session learning. */
data class SessionConfig(
    /** artist score change per early skip (negative) and per full listen (positive) */
    val skipArtist: Double = 0.35,
    val fullArtist: Double = 0.12,
    val likeArtist: Double = 0.2,
    val minArtist: Double = -0.9,
    val maxArtist: Double = 0.35,
    /** vibe-neighbourhood effect: songs within [radius] of a skipped / loved song's vibe move down / up */
    val skipVibe: Double = 0.35,
    val fullVibe: Double = 0.15,
    val radius: Double = 0.18,
    /** how many recent skipped / loved vibes are remembered */
    val memory: Int = 6,
)

/**
 * What this listening session taught so far. Lives only for the session; the long-term taste still learns
 * from the same plays through the normal history (ProfileBuilder) and the vibe self-correction.
 */
class SessionTaste(val config: SessionConfig = SessionConfig()) {
    private val artistDelta = HashMap<String, Double>()
    private val skipped = ArrayDeque<Vibe>()
    private val loved = ArrayDeque<Vibe>()
    var skips = 0; private set
    var fulls = 0; private set

    val isEmpty: Boolean get() = artistDelta.isEmpty() && skipped.isEmpty() && loved.isEmpty()

    fun onSkip(t: TrackMeta, v: Vibe) {
        skips++
        val a = t.mainArtist
        artistDelta[a] = max(config.minArtist, (artistDelta[a] ?: 0.0) - config.skipArtist)
        skipped.addLast(v); while (skipped.size > config.memory) skipped.removeFirst()
    }

    fun onFull(t: TrackMeta, v: Vibe, liked: Boolean = false) {
        fulls++
        val a = t.mainArtist
        val up = if (liked) config.likeArtist else config.fullArtist
        artistDelta[a] = min(config.maxArtist, (artistDelta[a] ?: 0.0) + up)
        loved.addLast(v); while (loved.size > config.memory) loved.removeFirst()
    }

    fun artistDelta(artist: String): Double = artistDelta[artist] ?: 0.0

    /** Score change for one candidate: artist delta + nearest skipped vibe (down) + nearest loved vibe (up). */
    fun adjust(p: PoolTrack): Double {
        var d = artistDelta[p.track.mainArtist] ?: 0.0
        val ks = skipped.maxOfOrNull { kernel(it, p.vibe) } ?: 0.0
        val kl = loved.maxOfOrNull { kernel(it, p.vibe) } ?: 0.0
        d += -config.skipVibe * ks + config.fullVibe * kl
        return d
    }

    /** The vibe the session is drifting to (average of the songs heard to the end), null before any. */
    fun centroid(): Vibe? = if (loved.isEmpty()) null else Vibe(
        loved.map { it.energy }.average(), loved.map { it.valence }.average(),
        loved.map { it.mizrahi }.average(), loved.map { it.cinematic }.average())

    private fun kernel(a: Vibe, b: Vibe): Double {
        val d = (1.4 * abs(a.energy - b.energy) + abs(a.valence - b.valence) + 1.2 * abs(a.mizrahi - b.mizrahi) +
            0.6 * abs(a.cinematic - b.cinematic)) / 4.2
        return max(0.0, 1.0 - d / config.radius)
    }

    /**
     * Re-rank the rest of a playing mix. The current order is the prior (earlier = better); the session adjustment
     * moves songs. Scores are computed once (no live comparator), then the artist spacing is restored.
     */
    fun rerank(upcoming: List<PoolTrack>, minArtistGap: Int = 1): List<PoolTrack> {
        if (upcoming.size < 2 || isEmpty) return upcoming
        // every queue slot is worth 0.025: one early skip (-0.35) sinks that artist ~14 places, a full listen lifts close songs ~6
        val scored = upcoming.mapIndexed { i, p -> p to -0.025 * i + adjust(p) }
        return EnergyArc.spaceArtists(scored.sortedByDescending { it.second }.map { it.first }, minArtistGap)
    }
}

/** DJ-style ordering: rise-and-fall energy waves with small steps, keeping artists apart. */
object EnergyArc {
    /**
     * Orders [list] (best first) as waves of [wave] songs: the best [wave] songs make the first wave, the next ones
     * the second... Inside a wave energy climbs in small steps to a peak and comes back down, so waves join softly.
     * [startEnergy] (the song playing now) makes the first wave start near it (falling first when it is high).
     */
    fun order(list: List<PoolTrack>, wave: Int = 12, startEnergy: Double? = null, minArtistGap: Int = 2): List<PoolTrack> {
        if (list.size < 3) return list
        val w = max(3, wave)
        val out = ArrayList<PoolTrack>(list.size)
        var start = startEnergy
        for (chunk in list.chunked(w)) {
            val asc = chunk.sortedBy { it.vibe.energy }
            val up = asc.filterIndexed { i, _ -> i % 2 == 0 }
            val down = asc.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
            val median = asc[asc.size / 2].vibe.energy
            // a high starting point: fall first, then rise (a "V" instead of a "Λ")
            out += if (start != null && start > median + 0.05) vShape(asc) else up + down
            start = out.last().vibe.energy
        }
        return spaceArtists(out, minArtistGap)
    }

    /** low peak in the middle: high -> low -> high, built like the arc but inverted */
    private fun vShape(asc: List<PoolTrack>): List<PoolTrack> {
        val desc = asc.reversed()
        val fall = desc.filterIndexed { i, _ -> i % 2 == 0 }
        val rise = desc.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
        return fall + rise
    }

    /**
     * Keeps the same main artist at least [gap] songs apart where possible: a conflicting song swaps with the nearest
     * later song that does not conflict (closest in energy among the next few), otherwise stays.
     */
    fun spaceArtists(list: List<PoolTrack>, gap: Int = 2): List<PoolTrack> {
        if (gap <= 0 || list.size < 3) return list
        val m = list.toMutableList()
        fun conflicts(i: Int, p: PoolTrack): Boolean {
            for (k in max(0, i - gap) until i) if (m[k].track.mainArtist == p.track.mainArtist) return true
            return false
        }
        for (i in 1 until m.size) {
            if (!conflicts(i, m[i])) continue
            var best = -1; var bestCost = Double.MAX_VALUE
            for (j in i + 1 until min(m.size, i + 8)) {
                if (conflicts(i, m[j])) continue
                // the song moving out of j must not create a conflict at j... checked loosely: its artist differs from m[i]'s
                val cost = abs(m[j].vibe.energy - m[i].vibe.energy) + 0.01 * (j - i)
                if (cost < bestCost) { bestCost = cost; best = j }
            }
            if (best > 0) { val x = m[i]; m[i] = m[best]; m[best] = x }
        }
        return m
    }

    /** Mean energy step between neighbours (lower = smoother). */
    fun smoothness(list: List<PoolTrack>): Double =
        if (list.size < 2) 0.0 else list.zipWithNext { a, b -> abs(a.vibe.energy - b.vibe.energy) }.average()

    /** Fraction of neighbours that share the main artist. */
    fun adjacentSameArtist(list: List<PoolTrack>): Double =
        if (list.size < 2) 0.0 else list.zipWithNext().count { (a, b) -> a.track.mainArtist == b.track.mainArtist }.toDouble() / (list.size - 1)

    fun energyStd(list: List<PoolTrack>): Double {
        if (list.size < 2) return 0.0
        val mu = list.map { it.vibe.energy }.average()
        return sqrt(list.map { (it.vibe.energy - mu) * (it.vibe.energy - mu) }.average())
    }
}

/**
 * One playing "main mix" session. Builds the first batch and every next batch; remembers every song it handed out
 * so nothing repeats inside the session; learns from skips / full listens through [taste].
 */
class MixSession(
    val baseTarget: VibeTarget,
    /** share of never-heard songs per batch, 0..1 (0.35 = 65% familiar / 35% new); 13.7.0.14: changeable while it plays */
    @Volatile var newShare: Double = 0.35,
    val djOrder: Boolean = true,
    val taste: SessionTaste = SessionTaste(),
) {
    private val handedOut = LinkedHashSet<String>()
    private val known = HashMap<String, PoolTrack>()
    var batches = 0; private set

    val ids: Set<String> get() = handedOut
    fun contains(id: String) = id in handedOut
    fun track(id: String): PoolTrack? = known[id]

    /** Target for the next batch: the base mood pulled halfway toward what the session is actually enjoying / playing now. */
    fun currentTarget(current: Vibe?): VibeTarget {
        val c = taste.centroid()
        val pull = when { c != null && current != null -> Vibe((c.energy + current.energy) / 2, (c.valence + current.valence) / 2,
            (c.mizrahi + current.mizrahi) / 2, (c.cinematic + current.cinematic) / 2); c != null -> c; else -> current } ?: return baseTarget
        fun mix(a: Double?, b: Double) = if (a == null) b else 0.5 * a + 0.5 * b
        return baseTarget.copy(energy = mix(baseTarget.energy, pull.energy), valence = mix(baseTarget.valence, pull.valence),
            mizrahi = baseTarget.mizrahi?.let { 0.5 * it + 0.5 * pull.mizrahi })
    }

    /**
     * The next [size] songs. Never returns a song handed out before in this session; returns fewer (or none)
     * when the pool runs dry, so the caller can fetch more candidates online.
     */
    fun nextBatch(mixer: VibeMixer, pool: List<PoolTrack>, size: Int, current: PoolTrack? = null): List<PoolTrack> {
        if (size <= 0) return emptyList()
        val fresh = pool.filter { it.track.id !in handedOut && it.track.id != current?.track?.id }.distinctBy { it.track.id }
        if (fresh.isEmpty()) return emptyList()
        val t = if (batches == 0 && current == null) baseTarget else currentTarget(current?.vibe)
        val picked = mixer.buildBalanced(fresh, t, size, newShare, taste)
        val ordered = if (djOrder) EnergyArc.order(picked, wave = 12, startEnergy = current?.vibe?.energy) else picked
        for (p in ordered) { handedOut += p.track.id; known[p.track.id] = p }
        batches++
        return ordered
    }

    /** Session learning from one finished play of a song this session handed out (or any known song). */
    fun onPlayed(id: String, skipped: Boolean, full: Boolean, liked: Boolean = false, fallback: PoolTrack? = null) {
        val p = known[id] ?: fallback ?: return
        if (skipped) taste.onSkip(p.track, p.vibe) else if (full || liked) taste.onFull(p.track, p.vibe, liked)
    }

    /** New order for the songs still waiting in the queue (ids in queue order), or null when nothing should move. */
    fun reorder(upcomingIds: List<String>): List<String>? {
        if (upcomingIds.size < 2 || taste.isEmpty) return null
        val mine = upcomingIds.mapNotNull { known[it] }
        // only touch a queue that is (mostly) still ours
        if (mine.size < max(2, (upcomingIds.size * 0.6).roundToInt())) return null
        val reranked = taste.rerank(mine).map { it.track.id }.iterator()
        // songs the listener added himself keep their places; only our songs move between our slots
        val out = upcomingIds.map { id -> if (id in known && reranked.hasNext()) reranked.next() else id }
        return if (out == upcomingIds) null else out
    }
}

/** Early skip, by the same rule the rest of the app uses: under 30 s, or under 35% of the song. */
object SkipRule {
    fun isEarlySkip(playedMs: Long, durationMs: Long): Boolean = playedMs < 30_000L || (durationMs > 0 && playedMs < durationMs * 0.35)
    fun isFull(playedMs: Long, durationMs: Long): Boolean = durationMs > 0 && playedMs >= durationMs * 0.85
}
