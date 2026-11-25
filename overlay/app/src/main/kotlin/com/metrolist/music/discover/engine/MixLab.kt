/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * The mix builder ("🧪 בונה מיקסים"): up to 25 seed artists with optional Low / Normal / Boost weights,
 * seed songs, mood, energy, day-part bias, familiar <-> new, heard/liked filter, length and language -> one queue.
 * Includes SavedMixRecipe pure serialisation and WhyIndex ("why this song?").
 */
package com.metrolist.music.discover.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Per-artist weight tier in a custom mix. */
enum class ArtistWeight(val tier: Int, val he: String, val mult: Double) {
    LOW(1, "נמוך", 0.55),
    NORMAL(2, "רגיל", 1.0),
    BOOST(3, "מודגש", 1.75);

    fun next(): ArtistWeight = when (this) {
        LOW -> NORMAL
        NORMAL -> BOOST
        BOOST -> LOW
    }

    companion object {
        fun of(tier: Int?): ArtistWeight = entries.firstOrNull { it.tier == tier } ?: NORMAL
    }
}

/** Optional filter on whether songs are liked or previously heard. */
enum class HeardFilter(val he: String) {
    ALL("הכל"),
    ONLY_LIKED("רק שאהבתי"),
    ONLY_UNHEARD("רק שלא שמעתי"),
    EXCLUDE_LIKED("בלי שאהבתי"),
}

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
    /** 13.7.0.15: per-artist weight tier (1=LOW, 2=NORMAL, 3=BOOST); absent = NORMAL. */
    val artistWeights: Map<String, Int> = emptyMap(),
    /** 13.7.0.15: optional liked/unheard filter. */
    val heardFilter: HeardFilter = HeardFilter.ALL,
    /** 13.7.0.15: optional day-part bias (morning / noon / evening / night). */
    val dayPartBias: DayPart? = null,
    /** 13.7.0.15: optional explicit max tracks per artist (null = automatic). */
    val maxPerArtist: Int? = null,
)

/** A named custom mix recipe saved per profile. */
data class SavedMixRecipe(
    val id: String,
    val name: String,
    val recipe: MixRecipe,
    val createdAt: Long = 0L,
)

data class LabMix(val title: String, val tracks: List<PoolTrack>, val why: Map<String, String>)

object MixLab {
    /** 13.7.0.15: lifted from 3 to 25 artists per custom mix. */
    const val MAX_SEEDS = 25

    private fun name(h: History, pool: List<PoolTrack>, artistId: String): String {
        val t = pool.firstOrNull { it.track.mainArtist == artistId }?.track ?: h.tracks.values.firstOrNull { it.mainArtist == artistId }
        return t?.artistNames?.firstOrNull()?.takeIf { it.isNotBlank() } ?: t?.artistLabel ?: "האמן"
    }

    /** [linked]: song id -> reason for songs an outside source (YouTube Music radio of a seed) ties to the seeds. */
    fun build(r: MixRecipe, h: History, pool: List<PoolTrack>, mixer: VibeMixer, now: Long, linked: Map<String, String> = emptyMap()): LabMix {
        val size = r.size.coerceIn(5, 200)
        val share = if (r.heardFilter == HeardFilter.ONLY_UNHEARD) 1.0 else r.newShare.coerceIn(0.0, 1.0)
        val seedsA = r.seedArtists.distinct().take(MAX_SEEDS)
        val seedsT = r.seedTracks.distinct().take(MAX_SEEDS)
        val likedIds = if (r.heardFilter == HeardFilter.ONLY_LIKED || r.heardFilter == HeardFilter.EXCLUDE_LIKED) {
            h.signals.filter { it.kind == SignalKind.LIKE }.map { it.trackId }.toHashSet()
        } else emptySet()
        val heardIds = if (r.heardFilter == HeardFilter.ONLY_UNHEARD) {
            h.plays.filter { it.playedMs >= 30_000L }.map { it.trackId }.toHashSet()
        } else emptySet()

        val uniq = pool.distinctBy { it.track.id }.filter { p ->
            p.track.title.isNotBlank() &&
                Lang.allows(r.lang, p.track) &&
                when (r.heardFilter) {
                    HeardFilter.ALL -> true
                    HeardFilter.ONLY_LIKED -> p.track.id in likedIds
                    HeardFilter.ONLY_UNHEARD -> p.familiarity < 0.15 && p.track.id !in heardIds
                    HeardFilter.EXCLUDE_LIKED -> p.track.id !in likedIds
                }
        }
        val why = HashMap<String, String>()
        if (uniq.isEmpty()) return LabMix(title(r, h, pool), emptyList(), why)
        val byId = uniq.associateBy { it.track.id }

        // Target: mood > day-part preset > seeds centroid > taste centroid; energy slider overrides energy
        val seedPool = uniq.filter { it.track.mainArtist in seedsA || it.track.id in seedsT }
        val dayPartTarget = r.dayPartBias?.let { dp ->
            when (dp) {
                DayPart.MORNING -> MoodPreset.WAKE_UP.target
                DayPart.NOON -> MoodPreset.DRIVE.target
                DayPart.EVENING -> MoodPreset.CHILL.target
                DayPart.NIGHT -> MoodPreset.NIGHT.target
            }
        }
        val base: VibeTarget = r.mood?.target ?: dayPartTarget ?: seedPool.takeIf { it.isNotEmpty() }?.let { l ->
            VibeTarget(l.map { it.vibe.energy }.average(), l.map { it.vibe.valence }.average(), l.map { it.vibe.mizrahi }.average())
        } ?: PersonalMixes.tasteCentroid(h, uniq, now)?.let { VibeTarget(it.energy, it.valence, it.mizrahi) } ?: VibeTarget()
        val t = if (r.energy != null) base.copy(energy = r.energy.coerceIn(0.0, 1.0)) else base

        // Seed pull & per-artist weight tiers
        val relA = HashMap<String, String>() // related artist -> the seed artist it came from
        val relLimit = if (seedsA.size <= 3) 8 else if (seedsA.size <= 8) 4 else 2
        for (a in seedsA) for (x in MoreMixes.relatedArtists(h, uniq, a, relLimit)) if (x !in seedsA) relA.putIfAbsent(x, a)
        val relT = HashMap<String, Pair<Double, String>>()
        for (s in seedsT) for ((id, sc) in MoreMixes.relatedScores(h, s)) if ((relT[id]?.first ?: 0.0) < sc) relT[id] = sc to s

        val dayPartTrackBoost = if (r.dayPartBias != null) {
            val counts = HashMap<String, Int>(); val match = HashMap<String, Int>()
            for (e in h.plays) {
                if (e.timestampMs > now || e.playedMs < 30_000L) continue
                counts[e.trackId] = (counts[e.trackId] ?: 0) + 1
                if (DayPart.of(DayClock.dateTime(e.timestampMs).hour) == r.dayPartBias) match[e.trackId] = (match[e.trackId] ?: 0) + 1
            }
            counts.mapNotNull { (id, c) ->
                val m = match[id] ?: 0
                if (c >= 2 && m > 0) id to (0.28 * (m.toDouble() / c)) else null
            }.toMap()
        } else emptyMap()

        val hasSeeds = seedsA.isNotEmpty() || seedsT.isNotEmpty() || linked.isNotEmpty()
        val boosted = uniq.map { p ->
            var b = dayPartTrackBoost[p.track.id] ?: 0.0
            val a = p.track.mainArtist
            if (a in seedsA) {
                val aw = ArtistWeight.of(r.artistWeights[a])
                b += 0.45 * aw.mult
                val tag = if (aw == ArtistWeight.BOOST) " (מודגש)" else ""
                why[p.track.id] = "כי בחרת ב${name(h, uniq, a)}$tag"
            } else relA[a]?.let { s ->
                val aw = ArtistWeight.of(r.artistWeights[s])
                b += 0.25 * aw.mult
                why[p.track.id] = "דומה ל${name(h, uniq, s)} שבחרת"
            }
            relT[p.track.id]?.let { (sc, s) -> b += 0.5 * sc; if (p.track.id !in why) why[p.track.id] = "קשור לשיר שבחרת: ${h.tracks[s]?.title ?: byId[s]?.track?.title ?: ""}".trim() }
            linked[p.track.id]?.let { reason -> b += 0.3; if (p.track.id !in why) why[p.track.id] = reason }
            if (b == 0.0) p else p.copy(context = p.context + b)
        }

        val seedsFirst = seedsT.mapNotNull { byId[it] }.onEach { why[it.track.id] = "השיר שבחרת" }
        val rest = boosted.filter { it.track.id !in seedsT }
        val bodySize = (size - seedsFirst.size).coerceAtLeast(0)

        // When 4+ seed artists are selected, ensure fair proportional representation weighted by ArtistWeight
        // so one dominant artist doesn't crowd out the rest of the chosen artists.
        val body: List<PoolTrack> = if (seedsA.size >= 4 && bodySize > 0) {
            buildMultiArtistBalanced(seedsA, r, rest, t, bodySize, share, mixer)
        } else {
            val candidates = if (hasSeeds) {
                val linkedPool = rest.filter { it.track.id in why }
                linkedPool + mixer.build(rest.filter { it.track.id !in why }, t, size * 2, 1.0 - share)
            } else rest
            val perArtist = r.maxPerArtist ?: if (seedsA.isNotEmpty()) max(4, size / 5) else max(2, size / 25)
            mixer.buildBalanced(candidates, t, bodySize, share, maxPerArtist = perArtist)
        }

        val ordered = seedsFirst + EnergyArc.order(body, wave = 10, startEnergy = seedsFirst.firstOrNull()?.vibe?.energy, minArtistGap = 1)
        return LabMix(title(r, h, pool), ordered.distinctBy { it.track.id }.take(size), why.filterKeys { k -> ordered.any { it.track.id == k } })
    }

    private fun buildMultiArtistBalanced(
        seedsA: List<String>,
        r: MixRecipe,
        rest: List<PoolTrack>,
        t: VibeTarget,
        bodySize: Int,
        share: Double,
        mixer: VibeMixer,
    ): List<PoolTrack> {
        val byArtist = rest.groupBy { it.track.mainArtist }
        val present = seedsA.filter { (byArtist[it]?.isNotEmpty()) == true }
        if (present.isEmpty()) return mixer.buildBalanced(rest, t, bodySize, share, maxPerArtist = r.maxPerArtist)

        // Reserve ~75% of slots for chosen seed artists (or 100% if share==0), rest for related/discovery filler
        val seedBudget = if (share >= 0.85) (bodySize * 0.45).roundToInt().coerceAtLeast(1)
        else if (share <= 0.05) bodySize
        else (bodySize * (1.0 - share * 0.45)).roundToInt().coerceIn(present.size.coerceAtMost(bodySize), bodySize)

        val totalW = present.sumOf { ArtistWeight.of(r.artistWeights[it]).mult }.coerceAtLeast(1e-6)
        val picked = ArrayList<PoolTrack>(bodySize)
        val pickedIds = HashSet<String>()
        val perCap = r.maxPerArtist ?: max(2, (bodySize.toDouble() / present.size * 1.8).roundToInt().coerceAtLeast(3))

        // First pass: weighted quotas per selected artist, preserving familiar/new balance per artist
        val quotas = present.associateWith { a ->
            val w = ArtistWeight.of(r.artistWeights[a]).mult
            max(1, ((seedBudget * w) / totalW).roundToInt()).coerceAtMost(perCap)
        }
        // Interleave round-robin across artists ordered by weight descending so even small sizes include many artists
        val orderedArtists = present.sortedByDescending { ArtistWeight.of(r.artistWeights[it]).mult }
        val rankedPerArtist = orderedArtists.associateWith { a ->
            val tracks = byArtist[a].orEmpty()
            mixer.buildBalanced(tracks, t, tracks.size, share, maxPerArtist = tracks.size)
        }
        val artistIdx = HashMap<String, Int>()
        var progress = true
        while (picked.size < seedBudget && progress) {
            progress = false
            for (a in orderedArtists) {
                if (picked.size >= seedBudget) break
                val taken = artistIdx[a] ?: 0
                val quota = quotas[a] ?: 1
                val list = rankedPerArtist[a].orEmpty()
                if (taken < quota && taken < list.size) {
                    val tr = list[taken]
                    artistIdx[a] = taken + 1
                    if (pickedIds.add(tr.track.id)) {
                        picked += tr
                        progress = true
                    }
                }
            }
        }

        // Fill remaining slots with related/discovery tracks from the rest of the pool
        val rem = bodySize - picked.size
        if (rem > 0) {
            val remainingPool = rest.filter { it.track.id !in pickedIds }
            val filler = mixer.buildBalanced(remainingPool, t, rem, share, maxPerArtist = r.maxPerArtist ?: max(2, perCap))
            for (tr in filler) if (pickedIds.add(tr.track.id)) picked += tr
        }
        return picked.take(bodySize)
    }

    fun title(r: MixRecipe, h: History, pool: List<PoolTrack>): String {
        val parts = ArrayList<String>()
        r.mood?.let { parts += "${it.emoji} ${it.he}" }
        val seeds = r.seedArtists.distinct().take(MAX_SEEDS)
        if (seeds.isNotEmpty()) {
            parts += if (seeds.size <= 3) {
                seeds.joinToString(" + ") { name(h, pool, it) }
            } else {
                val head = seeds.take(2).joinToString(" + ") { name(h, pool, it) }
                "$head + עוד ${seeds.size - 2} אמנים"
            }
        } else if (r.seedTracks.isNotEmpty()) {
            parts += h.tracks[r.seedTracks.first()]?.title ?: "השיר שבחרת"
        }
        r.dayPartBias?.let { parts += it.he }
        if (r.lang != LangMode.ALL) parts += r.lang.he
        return "🧪 " + (parts.joinToString(" · ").ifBlank { "המיקס שבנית" })
    }

    // ------------------------------------------------------------------ pure serialisation of SavedMixRecipe
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", " ").replace("\t", " ")
    private fun unesc(s: String): String = s.replace("\\p", "|").replace("\\\\", "\\")

    fun encodeSaved(list: List<SavedMixRecipe>): String = list.takeLast(50).joinToString("\n") { sm ->
        val r = sm.recipe
        val weights = r.artistWeights.entries.joinToString(",") { "${it.key}:${it.value}" }
        listOf(
            esc(sm.id),
            esc(sm.name),
            sm.createdAt.toString(),
            r.seedArtists.joinToString(","),
            r.seedTracks.joinToString(","),
            r.mood?.name.orEmpty(),
            r.energy?.toString().orEmpty(),
            r.newShare.toString(),
            r.size.toString(),
            r.lang.name,
            weights,
            r.heardFilter.name,
            r.dayPartBias?.name.orEmpty(),
            r.maxPerArtist?.toString().orEmpty(),
        ).joinToString("|")
    }

    fun decodeSaved(raw: String?): List<SavedMixRecipe> = raw.orEmpty().lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val p = line.split('|')
        if (p.size < 10) return@mapNotNull null
        val id = unesc(p[0]).ifBlank { return@mapNotNull null }
        val name = unesc(p[1]).ifBlank { "מיקס שמור" }
        val createdAt = p[2].toLongOrNull() ?: 0L
        val seedArtists = p[3].split(',').filter { it.isNotBlank() }.take(MAX_SEEDS)
        val seedTracks = p[4].split(',').filter { it.isNotBlank() }.take(MAX_SEEDS)
        val mood = p[5].takeIf { it.isNotBlank() }?.let { n -> MoodPreset.entries.firstOrNull { it.name == n } }
        val energy = p[6].toDoubleOrNull()
        val newShare = p[7].toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.3
        val size = p[8].toIntOrNull()?.coerceIn(5, 200) ?: 30
        val lang = LangMode.entries.firstOrNull { it.name == p[9] } ?: LangMode.ALL
        val weights = p.getOrNull(10).orEmpty().split(',').mapNotNull { kv ->
            val i = kv.lastIndexOf(':'); if (i <= 0) null else kv.substring(i + 1).toIntOrNull()?.let { kv.substring(0, i) to it.coerceIn(1, 3) }
        }.toMap()
        val heard = p.getOrNull(11)?.let { n -> HeardFilter.entries.firstOrNull { it.name == n } } ?: HeardFilter.ALL
        val dayPart = p.getOrNull(12)?.takeIf { it.isNotBlank() }?.let { n -> DayPart.entries.firstOrNull { it.name == n } }
        val maxPer = p.getOrNull(13)?.toIntOrNull()?.takeIf { it > 0 }
        SavedMixRecipe(id, name, MixRecipe(seedArtists, seedTracks, mood, energy, newShare, size, lang, weights, heard, dayPart, maxPer), createdAt)
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
