/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Moods the UI offers (chips, the dial and the emoji picker). */
enum class MoodPreset(val he: String, val en: String, val emoji: String, val target: VibeTarget) {
    WAKE_UP("להתעורר", "Wake up", "☀️", VibeTarget(energy = 0.62, valence = 0.78)),
    DRIVE("נסיעה", "Drive", "🚗", VibeTarget(energy = 0.68, valence = 0.66)),
    GYM("חדר כושר", "Gym", "💪", VibeTarget(energy = 0.93, valence = 0.6, tags = setOf(VibeTag.EDM, VibeTag.PHONK))),
    NIGHT("לילה", "Night", "🌙", VibeTarget(energy = 0.28, valence = 0.45, tags = setOf(VibeTag.CHILL))),
    MIZRAHI("מזרחי", "Mizrahi", "🎶", VibeTarget(mizrahi = 1.0, valence = 0.65, tags = setOf(VibeTag.MIZRAHI))),
    HEAVY_PHONK("פאנק כבד", "Heavy phonk", "😤", VibeTarget(energy = 0.95, valence = 0.3, mizrahi = 0.0, tags = setOf(VibeTag.PHONK))),
    SAD("עצוב", "Sad", "😢", VibeTarget(energy = 0.25, valence = 0.12, tags = setOf(VibeTag.SAD))),
    PARTY("מסיבה", "Party", "🥳", VibeTarget(energy = 0.85, valence = 0.92, tags = setOf(VibeTag.PARTY))),
    CHILL("צ'יל", "Chill", "😌", VibeTarget(energy = 0.28, valence = 0.62, tags = setOf(VibeTag.CHILL))),
    HYPE("היייפ", "Hype", "🔥", VibeTarget(energy = 0.97, valence = 0.72)),
    DRAMA("מצב דרמה", "Drama mode", "🎬", VibeTarget(energy = 0.55, valence = 0.35, cinematic = 0.9, tags = setOf(VibeTag.CINEMATIC)));

    companion object {
        val chips = listOf(WAKE_UP, DRIVE, GYM, NIGHT, MIZRAHI)
        val emojis = listOf(HEAVY_PHONK, SAD, PARTY, CHILL, HYPE)
        val dial = listOf(WAKE_UP, DRIVE, GYM, PARTY, NIGHT, CHILL)
    }
}

/** One track the mixer may use. familiarity 0 = never heard, 1 = heavy rotation. affinity = how much the user likes it (0..1). */
data class PoolTrack(val track: TrackMeta, val vibe: Vibe, val familiarity: Double, val affinity: Double)

/** The Mixes-hub sliders. Each is 0..1; null = follow the preset. */
data class Sliders(val energy: Double? = null, val mizrahiEdm: Double? = null, val familiarNew: Double = 0.5)

enum class VibeAction { MORE_LIKE_THIS, CHANGE_VIBE, ENERGY_UP }

class VibeMixer(private val random: Random = Random.Default) {

    fun target(preset: MoodPreset?, sliders: Sliders): VibeTarget {
        val base = preset?.target ?: VibeTarget()
        return base.copy(
            energy = sliders.energy ?: base.energy,
            // slider: 0 = EDM (mizrahi 0), 1 = Mizrahi
            mizrahi = sliders.mizrahiEdm ?: base.mizrahi,
        )
    }

    fun score(p: PoolTrack, t: VibeTarget, familiar: Double): Double =
        1.0 - 2.2 * p.vibe.distanceTo(t) + 0.35 * p.affinity - 0.6 * abs(p.familiarity - familiar) + random.nextDouble() * 0.06

    /** A plain mood mix: best fitting tracks, artist-diversified. */
    fun build(pool: List<PoolTrack>, t: VibeTarget, size: Int = 25, familiar: Double = 0.5): List<PoolTrack> =
        diversify(pool.distinctBy { it.track.id }.sortedByDescending { score(it, t, familiar) }, size)

    /** "רגש להיפך": starts sad and calm, ends happy party. Mood score never goes down along the list. */
    fun moodFlip(pool: List<PoolTrack>, size: Int = 20): List<PoolTrack> {
        val unused = pool.distinctBy { it.track.id }.toMutableList()
        val out = ArrayList<PoolTrack>()
        val n = min(size, unused.size)
        for (i in 0 until n) {
            val f = if (n <= 1) 1.0 else i.toDouble() / (n - 1)
            val step = VibeTarget(energy = 0.2 + 0.75 * f, valence = 0.08 + 0.87 * f)
            val pick = unused.maxByOrNull { score(it, step, 0.5) - if (out.lastOrNull()?.track?.mainArtist == it.track.mainArtist) 0.5 else 0.0 } ?: break
            out += pick; unused.remove(pick)
        }
        return out.sortedBy { it.vibe.moodScore }
    }

    /** "אני נוסע 25 דקות": a mix whose total length lands as close as possible to [minutes] (goal: within ±3%, never worse than ±10% when the pool allows it). */
    fun tripLength(pool: List<PoolTrack>, minutes: Int, t: VibeTarget = MoodPreset.DRIVE.target): List<PoolTrack> {
        val goal = minutes * 60
        val ranked = pool.filter { it.track.durationSec in 60..900 }.distinctBy { it.track.id }.sortedByDescending { score(it, t, 0.6) }
        if (ranked.isEmpty()) return emptyList()
        val chosen = ArrayList<PoolTrack>()
        var total = 0
        for (p in ranked) {
            if (total >= goal * 0.97) break
            if (total + p.track.durationSec <= goal * 1.03) { chosen += p; total += p.track.durationSec }
            if (chosen.size > 80) break
        }
        // local search: swap one chosen for one unused (or add / drop) to get closer to the goal
        val unused = ranked.filter { it !in chosen }.take(200).toMutableList()
        for (iter in 0 until 300) {
            val gap = goal - total
            if (abs(gap) <= goal * 0.01) break
            var bestGain = 0; var bestIn: PoolTrack? = null; var bestOut: PoolTrack? = null
            for (u in unused) {
                val add = abs(gap - u.track.durationSec)
                if (abs(gap) - add > bestGain) { bestGain = abs(gap) - add; bestIn = u; bestOut = null }
            }
            for (c in chosen) {
                val drop = abs(gap + c.track.durationSec)
                if (abs(gap) - drop > bestGain) { bestGain = abs(gap) - drop; bestIn = null; bestOut = c }
                for (u in unused) {
                    val sw = abs(gap + c.track.durationSec - u.track.durationSec)
                    if (abs(gap) - sw > bestGain) { bestGain = abs(gap) - sw; bestIn = u; bestOut = c }
                }
            }
            if (bestGain <= 0) break
            bestOut?.let { chosen.remove(it); unused += it; total -= it.track.durationSec }
            bestIn?.let { chosen += it; unused.remove(it); total += it.track.durationSec }
        }
        // play order: warm up, peak in the middle, land gently
        val byEnergy = chosen.sortedBy { it.vibe.energy }
        val rise = byEnergy.filterIndexed { i, _ -> i % 2 == 0 }
        val fall = byEnergy.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
        return rise + fall
    }

    /** "מצב דרמה": cinematic tracks ordered as a film arc (build up to a climax ~70% in, then resolve). */
    fun drama(pool: List<PoolTrack>, size: Int = 18): List<PoolTrack> {
        val picked = build(pool, MoodPreset.DRAMA.target, size, 0.4)
        val asc = picked.sortedBy { it.vibe.energy }
        val rise = asc.filterIndexed { i, _ -> i % 3 != 2 }
        val fall = asc.filterIndexed { i, _ -> i % 3 == 2 }.reversed()
        return rise + fall
    }

    /** Now-playing vibe buttons: what should come after [current]. */
    fun reshape(current: PoolTrack, pool: List<PoolTrack>, action: VibeAction, size: Int = 15, related: Set<String> = emptySet()): List<PoolTrack> {
        val cv = current.vibe
        val others = pool.filter { it.track.id != current.track.id }
        return when (action) {
            VibeAction.MORE_LIKE_THIS -> {
                val t = VibeTarget(energy = cv.energy, valence = cv.valence, mizrahi = cv.mizrahi, cinematic = cv.cinematic,
                    tags = cv.tags.filter { it != VibeTag.HEBREW }.toSet())
                diversify(others.sortedByDescending { score(it, t, 0.5) + (if (it.track.id in related) 0.4 else 0.0) +
                    (if (it.track.mainArtist == current.track.mainArtist) 0.15 else 0.0) }, size, maxPerArtist = 3)
            }
            VibeAction.CHANGE_VIBE -> {
                val t = VibeTarget(
                    energy = if (cv.energy > 0.5) max(0.15, cv.energy - 0.45) else min(0.95, cv.energy + 0.45),
                    valence = if (cv.valence > 0.5) cv.valence - 0.3 else cv.valence + 0.35,
                    mizrahi = 1.0 - cv.mizrahi,
                )
                diversify(others.filter { it.vibe.distanceTo(VibeTarget(cv.energy, cv.valence, cv.mizrahi)) >= 0.2 }
                    .sortedByDescending { score(it, t, 0.5) }, size)
            }
            VibeAction.ENERGY_UP -> {
                val t = VibeTarget(energy = min(1.0, cv.energy + 0.22), valence = max(cv.valence, 0.6), mizrahi = cv.mizrahi)
                diversify(others.filter { it.vibe.energy >= cv.energy - 0.02 }.sortedByDescending { score(it, t, 0.5) }, size)
                    .sortedBy { it.vibe.energy }
            }
        }
    }

    /** No artist twice in a row, at most [maxPerArtist] per artist, keeps the input's order otherwise. */
    fun diversify(sorted: List<PoolTrack>, size: Int, maxPerArtist: Int = 2): List<PoolTrack> {
        val out = ArrayList<PoolTrack>()
        val count = HashMap<String, Int>()
        val waiting = ArrayList<PoolTrack>()
        for (p in sorted) {
            if (out.size >= size) break
            val a = p.track.mainArtist
            if ((count[a] ?: 0) >= maxPerArtist) continue
            if (out.lastOrNull()?.track?.mainArtist == a) { waiting += p; continue }
            out += p; count[a] = (count[a] ?: 0) + 1
            val w = waiting.firstOrNull { it.track.mainArtist != a && (count[it.track.mainArtist] ?: 0) < maxPerArtist }
            if (w != null && out.size < size) { waiting.remove(w); out += w; count[w.track.mainArtist] = (count[w.track.mainArtist] ?: 0) + 1 }
        }
        for (p in waiting) { if (out.size >= size) break; if ((count[p.track.mainArtist] ?: 0) < maxPerArtist) { out += p; count[p.track.mainArtist] = (count[p.track.mainArtist] ?: 0) + 1 } }
        return out
    }
}
