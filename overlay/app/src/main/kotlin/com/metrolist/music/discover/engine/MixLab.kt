/**
 * Metrolist Discover 13.7.0.14 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * The mix builder ("🧪 בונה מיקסים"): seed artists / songs, a mood, an energy level, familiar <-> new, length and
 * language -> one queue. And WhyIndex: the one-line "why this song?" reason shown under the player.
 */
package com.metrolist.music.discover.engine

import kotlin.math.max
import kotlin.math.min

data class MixRecipe(
    val seedArtists: List<String> = emptyList(),
    val seedTracks: List<String> = emptyList(),
    val mood: MoodPreset? = null,
    /** 0 calm .. 1 energetic; null = follow the mood / the seeds */
    val energy: Double? = null,
    /** share of never-heard songs, 0 = only songs you know .. 1 = only new */
    val newShare: Double = 0.3,
    val size: Int = 30,
    val lang: LangMode = LangMode.ALL,
)

data class LabMix(val title: String, val tracks: List<PoolTrack>, val why: Map<String, String>)

object MixLab {
    const val MAX_SEEDS = 3

    private fun name(h: History, pool: List<PoolTrack>, artistId: String): String {
        val t = pool.firstOrNull { it.track.mainArtist == artistId }?.track ?: h.tracks.values.firstOrNull { it.mainArtist == artistId }
        return t?.artistNames?.firstOrNull()?.takeIf { it.isNotBlank() } ?: t?.artistLabel ?: "האמן"
    }

    /** [linked]: song id -> reason for songs an outside source (YouTube Music radio of a seed) ties to the seeds. */
    fun build(r: MixRecipe, h: History, pool: List<PoolTrack>, mixer: VibeMixer, now: Long, linked: Map<String, String> = emptyMap()): LabMix {
        val size = r.size.coerceIn(5, 200)
        val share = r.newShare.coerceIn(0.0, 1.0)
        val seedsA = r.seedArtists.distinct().take(MAX_SEEDS)
        val seedsT = r.seedTracks.distinct().take(MAX_SEEDS)
        val uniq = pool.distinctBy { it.track.id }.filter { it.track.title.isNotBlank() && Lang.allows(r.lang, it.track) }
        val why = HashMap<String, String>()
        if (uniq.isEmpty()) return LabMix(title(r, h, pool), emptyList(), why)
        val byId = uniq.associateBy { it.track.id }
        // --- the target: mood > seeds > your taste; the energy slider overrides the energy
        val seedPool = uniq.filter { it.track.mainArtist in seedsA || it.track.id in seedsT }
        val base: VibeTarget = r.mood?.target ?: seedPool.takeIf { it.isNotEmpty() }?.let { l ->
            VibeTarget(l.map { it.vibe.energy }.average(), l.map { it.vibe.valence }.average(), l.map { it.vibe.mizrahi }.average())
        } ?: PersonalMixes.tasteCentroid(h, uniq, now)?.let { VibeTarget(it.energy, it.valence, it.mizrahi) } ?: VibeTarget()
        val t = if (r.energy != null) base.copy(energy = r.energy.coerceIn(0.0, 1.0)) else base
        // --- seed pull
        val relA = HashMap<String, String>() // related artist -> the seed artist it came from
        for (a in seedsA) for (x in MoreMixes.relatedArtists(h, uniq, a, 8)) if (x !in seedsA) relA.putIfAbsent(x, a)
        val relT = HashMap<String, Pair<Double, String>>()
        for (s in seedsT) for ((id, sc) in MoreMixes.relatedScores(h, s)) if ((relT[id]?.first ?: 0.0) < sc) relT[id] = sc to s
        val hasSeeds = seedsA.isNotEmpty() || seedsT.isNotEmpty() || linked.isNotEmpty()
        val boosted = uniq.map { p ->
            var b = 0.0
            val a = p.track.mainArtist
            if (a in seedsA) { b += 0.45; why[p.track.id] = "כי בחרת ב${name(h, uniq, a)}" }
            else relA[a]?.let { s -> b += 0.25; why[p.track.id] = "דומה ל${name(h, uniq, s)} שבחרת" }
            relT[p.track.id]?.let { (sc, s) -> b += 0.5 * sc; if (p.track.id !in why) why[p.track.id] = "קשור לשיר שבחרת: ${h.tracks[s]?.title ?: byId[s]?.track?.title ?: ""}".trim() }
            linked[p.track.id]?.let { reason -> b += 0.3; if (p.track.id !in why) why[p.track.id] = reason }
            if (b == 0.0) p else p.copy(context = p.context + b)
        }
        val seedsFirst = seedsT.mapNotNull { byId[it] }.onEach { why[it.track.id] = "השיר שבחרת" }
        val rest = boosted.filter { it.track.id !in seedsT }
        // with seeds: the seed-linked songs (boosted, so they rank first) + the best-fitting others as filler
        val candidates = if (hasSeeds) {
            val linked = rest.filter { it.track.id in why }
            linked + mixer.build(rest.filter { it.track.id !in why }, t, size * 2, 1.0 - share)
        } else rest
        val perArtist = if (seedsA.isNotEmpty()) max(4, size / 5) else max(2, size / 25)
        val body = mixer.buildBalanced(candidates, t, size - seedsFirst.size, share, maxPerArtist = perArtist)
        val ordered = seedsFirst + EnergyArc.order(body, wave = 10, startEnergy = seedsFirst.firstOrNull()?.vibe?.energy, minArtistGap = 1)
        return LabMix(title(r, h, pool), ordered.distinctBy { it.track.id }.take(size), why.filterKeys { k -> ordered.any { it.track.id == k } })
    }

    fun title(r: MixRecipe, h: History, pool: List<PoolTrack>): String {
        val parts = ArrayList<String>()
        r.mood?.let { parts += "${it.emoji} ${it.he}" }
        if (r.seedArtists.isNotEmpty()) parts += r.seedArtists.take(MAX_SEEDS).joinToString(" + ") { name(h, pool, it) }
        else if (r.seedTracks.isNotEmpty()) parts += h.tracks[r.seedTracks.first()]?.title ?: "השיר שבחרת"
        if (r.lang != LangMode.ALL) parts += r.lang.he
        return "🧪 " + (parts.joinToString(" · ").ifBlank { "המיקס שבנית" })
    }
}

/** "Why this song?": one short Hebrew line about any song, from this profile's own history. */
class WhyIndex(private val h: History, private val now: Long, pool: List<PoolTrack> = emptyList()) {
    private val day = 86_400_000L
    private val n14 = HashMap<String, Int>(); private val total = HashMap<String, Int>(); private val last = HashMap<String, Long>()
    private val first = HashMap<String, Long>(); private val inHour = HashMap<String, Int>()
    private val artistPlays = HashMap<String, Int>()
    private val topArtists: List<String>
    private val artistVibes: Map<String, Vibe>
    private val hourNow = DayClock.dateTime(now).hour

    init {
        for (e in h.plays) {
            if (e.timestampMs > now || e.playedMs < 30_000L || SkipRule.isEarlySkip(e.playedMs, h.duration(e))) continue
            val id = e.trackId
            total[id] = (total[id] ?: 0) + 1
            last[id] = max(last[id] ?: Long.MIN_VALUE, e.timestampMs)
            first[id] = min(first[id] ?: Long.MAX_VALUE, e.timestampMs)
            if (now - e.timestampMs <= 14 * day) n14[id] = (n14[id] ?: 0) + 1
            val dh = kotlin.math.abs(DayClock.dateTime(e.timestampMs).hour - hourNow).let { min(it, 24 - it) }
            if (dh <= 2) inHour[id] = (inHour[id] ?: 0) + 1
            if (now - e.timestampMs <= 90 * day) h.tracks[id]?.mainArtist?.let { if (it != "?") artistPlays[it] = (artistPlays[it] ?: 0) + 1 }
        }
        topArtists = artistPlays.entries.filter { it.value >= 5 }.sortedByDescending { it.value }.take(10).map { it.key }
        artistVibes = topArtists.mapNotNull { a -> MoreMixes.artistVibe(pool, a)?.let { a to it } }.toMap()
    }

    private fun artistName(t: TrackMeta) = t.artistNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: t.artistLabel
    private fun nameOf(artistId: String): String =
        h.tracks.values.firstOrNull { it.mainArtist == artistId }?.let { artistName(it) } ?: "אמן שאתה אוהב"

    /** The reason line for [t]; [vibe] (when known) lets a brand-new artist be compared with the artists you love. */
    fun reason(t: TrackMeta, vibe: Vibe? = null): String {
        val id = t.id
        val c14 = n14[id] ?: 0; val all = total[id] ?: 0
        val a = t.mainArtist
        val rank = topArtists.indexOf(a)
        val hourShare = if (all >= 3) (inHour[id] ?: 0).toDouble() / all else 0.0
        val daysSince = last[id]?.let { ((now - it) / day).toInt() }
        val heardArtist = (artistPlays[a] ?: 0) > 0 || h.plays.any { p -> h.tracks[p.trackId]?.mainArtist == a && p.playedMs >= 30_000L }
        return when {
            c14 >= 3 -> "שמעת אותו $c14 פעמים בשבועיים האחרונים"
            all >= 5 && daysSince != null && daysSince >= 45 -> "שמעת אותו $all פעמים, ולא כבר $daysSince ימים"
            hourShare >= 0.5 -> "אתה שומע אותו בעיקר בשעות האלה"
            all == 0 && rank >= 0 -> "שיר שעוד לא שמעת של ${artistName(t)}, מהאמנים שאתה הכי שומע"
            all == 0 && heardArtist -> "חדש לך, של ${artistName(t)} שכבר שמעת"
            all == 0 -> {
                val near = vibe?.let { v -> artistVibes.minByOrNull { (_, av) -> av.distanceTo(VibeTarget(v.energy, v.valence, v.mizrahi, v.cinematic)) }?.key }
                if (near != null) "אמן חדש לך, בסגנון של ${nameOf(near)}" else "אמן חדש לך, קרוב לטעם שלך"
            }
            first[id]?.let { now - it < 21 * day } == true -> "גילית אותו לא מזמן ועוד לא נמאס"
            rank in 0..4 -> "${artistName(t)} מהאמנים שאתה הכי שומע עכשיו"
            all >= 3 -> "אחד הקבועים שלך: שמעת אותו $all פעמים"
            else -> "מתאים לוייב של המיקס"
        }
    }
}
