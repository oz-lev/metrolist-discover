/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 * Logic behind the v2.1 settings and functions: profiles, mix shaping, smart shuffle, ask-music, A-B loop,
 * saved queues, auto-EQ, Blend, sleep fade, bubble frequency, auto-like, daily challenge, Replay, listening clock.
 */
package com.metrolist.music.discover.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// ====================================================================== profiles

/** One switchable profile. [guest] = plays are never recorded (no history, no learning). */
data class DProfile(val id: String, val name: String, val emoji: String, val color: Long, val guest: Boolean = false) {
    fun encode(): String = listOf(id, clean(name), clean(emoji), color.toString(), guest.toString()).joinToString("~")

    companion object {
        private fun clean(s: String) = s.replace("~", " ").replace("^", " ").trim()
        fun decode(s: String): DProfile? {
            val p = s.split("~")
            if (p.size < 5 || p[0].isBlank()) return null
            return DProfile(p[0], p[1].ifBlank { p[0] }, p[2].ifBlank { "🎵" }, p[3].toLongOrNull() ?: 0xFF8B5CF6, p[4].toBooleanStrictOrNull() ?: false)
        }
    }
}

/**
 * Maps a settings key onto the active profile. Every v2/v2.1 setting and the learning state (self-tuner, vibe corrections)
 * is per profile; caches and the profile list itself are global. The default profile "me" keeps the original keys,
 * so everything set before v2.1 stays exactly where it was.
 */
object ProfileScope {
    const val DEFAULT = "me"
    @Volatile var active: String = DEFAULT
    @Volatile var guest: Boolean = false

    private val GLOBAL = setOf("v2.colors", "v2.offline.day", "v2.offline.ids")
    private val SCOPED_EXTRA = setOf("tuner.state", "tuner.likeAwarded")

    fun scoped(k: String): Boolean =
        k !in GLOBAL && !k.startsWith("g.") && !k.startsWith("p.") && (k.startsWith("v2.") || k.startsWith("v21.") || k in SCOPED_EXTRA)

    fun key(k: String, profile: String = active): String = if (profile == DEFAULT || !scoped(k)) k else "p.$profile.$k"
}

object Profiles {
    val PRESETS = listOf(
        DProfile("me", "אני", "😎", 0xFF8B5CF6),
        DProfile("gym", "חדר כושר", "💪", 0xFFEF4444),
        DProfile("work", "עבודה/ריכוז", "🎧", 0xFF0EA5E9),
        DProfile("night", "לילה", "🌙", 0xFF6366F1),
        DProfile("car", "רכב", "🚗", 0xFF22D3EE),
        DProfile("party", "מסיבה", "🥳", 0xFFF59E0B),
        DProfile("guest", "אורח", "👤", 0xFF94A3B8, guest = true),
    )

    /** Settings a preset profile starts with the first time it is used (all still editable). */
    fun presetDefaults(id: String): Map<String, String> = when (id) {
        "gym" -> mapOf("v21.theme" to "PHONK_RED", "v21.defaultMood" to "GYM", "v2.boldness" to "0.35", "v2.noLive" to "true")
        "work" -> mapOf("v21.theme" to "OCEAN", "v21.defaultMood" to "CHILL", "v2.guardToasts" to "false", "v21.djIntros" to "false")
        "night" -> mapOf("v21.theme" to "AMOLED", "v21.defaultMood" to "NIGHT", "v2.turtle" to "true", "v2.nb.on" to "true")
        "car" -> mapOf("v21.theme" to "NEON", "v21.defaultMood" to "DRIVE", "v21.autoEq" to "true")
        "party" -> mapOf("v21.theme" to "MIZRAHI_GOLD", "v21.defaultMood" to "PARTY", "v2.boldness" to "0.5", "v21.flow" to "true")
        "guest" -> mapOf("v21.theme" to "SUNSET", "v2.guard" to "false")
        else -> emptyMap()
    }

    fun encode(list: List<DProfile>): String = list.joinToString("^") { it.encode() }
    fun decode(raw: String?): List<DProfile> {
        val l = raw.orEmpty().split("^").mapNotNull { DProfile.decode(it) }.distinctBy { it.id }
        if (l.isEmpty()) return PRESETS
        return if (l.none { it.id == ProfileScope.DEFAULT }) listOf(PRESETS[0]) + l else l
    }

    fun newId(existing: Collection<String>, rnd: Random = Random.Default): String {
        while (true) { val id = "u" + rnd.nextInt(100_000, 999_999); if (id !in existing) return id }
    }

    fun canDelete(id: String) = id != ProfileScope.DEFAULT

    fun delete(list: List<DProfile>, id: String): List<DProfile> = if (!canDelete(id)) list else list.filter { it.id != id }
    fun rename(list: List<DProfile>, id: String, name: String, emoji: String? = null, color: Long? = null): List<DProfile> =
        list.map { if (it.id == id) it.copy(name = name.take(24).ifBlank { it.name }, emoji = emoji ?: it.emoji, color = color ?: it.color) else it }
}

/** Auto-switch rule: [profileId] is active on [days] (ISO 1=Mon..7=Sun) between [fromMinute] and [toMinute] (wraps midnight). */
data class AutoRule(val profileId: String, val days: Set<Int>, val fromMinute: Int, val toMinute: Int) {
    fun isActiveAt(dayOfWeek: Int, minuteOfDay: Int): Boolean {
        if (fromMinute == toMinute) return dayOfWeek in days
        return if (fromMinute < toMinute) dayOfWeek in days && minuteOfDay in fromMinute until toMinute
        else (dayOfWeek in days && minuteOfDay >= fromMinute) || (((dayOfWeek + 5) % 7 + 1) in days && minuteOfDay < toMinute)
    }
    fun encode(): String = listOf(profileId, days.sorted().joinToString(","), fromMinute, toMinute).joinToString("~")
    companion object {
        fun decode(s: String): AutoRule? {
            val p = s.split("~"); if (p.size < 4) return null
            return AutoRule(p[0], p[1].split(",").mapNotNull { it.toIntOrNull() }.toSet(), p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0)
        }
        fun encodeAll(l: List<AutoRule>) = l.joinToString("^") { it.encode() }
        fun decodeAll(s: String?) = s.orEmpty().split("^").mapNotNull { decode(it) }
    }
}

object AutoSwitch {
    /** The profile the schedule wants right now (first matching rule), or null when no window is open. */
    fun pick(rules: List<AutoRule>, existing: Set<String>, dayOfWeek: Int, minuteOfDay: Int): String? =
        rules.firstOrNull { it.profileId in existing && it.isActiveAt(dayOfWeek, minuteOfDay) }?.profileId

    /**
     * Edge-triggered: only switches when the schedule's wish changes, so a manual switch inside a window is respected
     * until the next boundary. Leaving every window returns to the default profile.
     */
    fun decide(lastPick: String?, pick: String?, active: String): String? {
        if (pick == lastPick) return null
        val target = pick ?: ProfileScope.DEFAULT
        return if (target == active) null else target
    }
}

/** Whether a play may be written to history and to the learning state. */
object Recording {
    fun shouldRecord(secretMode: Boolean, guestProfile: Boolean, historyPaused: Boolean): Boolean = !secretMode && !guestProfile && !historyPaused
}

// ====================================================================== mix shaping

enum class LangMode(val he: String) { ALL("הכל"), HEBREW("רק עברית"), ENGLISH("רק לועזית") }

object Lang {
    fun hasHebrew(s: String) = s.any { it in '\u0590'..'\u05FF' }
    fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }
    fun isHebrew(t: TrackMeta) = hasHebrew(t.title) || t.artistNames.any { hasHebrew(it) }
    fun allows(mode: LangMode, t: TrackMeta): Boolean = when (mode) {
        LangMode.ALL -> true
        LangMode.HEBREW -> isHebrew(t)
        LangMode.ENGLISH -> !isHebrew(t) && (hasLatin(t.title) || t.artistNames.any { hasLatin(it) })
    }
}

/** Options that reshape every Discover mix (all from the settings hub). */
data class ShapeOptions(
    val lang: LangMode = LangMode.ALL,
    val maxDurationSec: Int? = null,
    val neverHeard: Boolean = false,
    val favoriteEvery: Int = 0,
    val artistGap: Int = 0,
    val morningRamp: Boolean = false,
    val rampMinutes: Int = 20,
)

object MixShaping {
    private const val UNKNOWN_SEC = 210

    fun filter(list: List<PoolTrack>, o: ShapeOptions, heard: Set<String>): List<PoolTrack> = list.filter { p ->
        Lang.allows(o.lang, p.track) &&
            (o.maxDurationSec == null || p.track.durationSec <= 0 || p.track.durationSec <= o.maxDurationSec) &&
            (!o.neverHeard || p.track.id !in heard)
    }

    /**
     * "Morning ramp": the songs filling the first [rampMinutes] are re-ordered so the energy only goes up;
     * the rest of the mix keeps its order.
     */
    fun morningRamp(list: List<PoolTrack>, rampMinutes: Int = 20): List<PoolTrack> {
        if (list.size < 2) return list
        var acc = 0; var n = 0
        while (n < list.size && acc < rampMinutes * 60) { acc += list[n].track.durationSec.takeIf { it > 0 } ?: UNKNOWN_SEC; n++ }
        val head = list.take(n).sortedBy { it.vibe.energy }
        return head + list.drop(n)
    }

    /**
     * "Favourite artist every N songs": every window of [n] songs holds at least one song by a favourite artist,
     * inserting one from [favorites] when a window has none.
     */
    fun favoriteEvery(list: List<PoolTrack>, favorites: List<PoolTrack>, favArtists: Set<String>, n: Int): List<PoolTrack> {
        if (n <= 0 || favArtists.isEmpty()) return list
        val out = ArrayList<PoolTrack>()
        val used = list.map { it.track.id }.toHashSet()
        val spare = ArrayDeque(favorites.filter { it.track.mainArtist in favArtists && it.track.id !in used })
        var sinceFav = 0
        for (p in list) {
            if (sinceFav == n - 1 && p.track.mainArtist !in favArtists) {
                val f = spare.removeFirstOrNull()
                if (f != null) { out += f; sinceFav = 0 }
            }
            out += p
            sinceFav = if (p.track.mainArtist in favArtists) 0 else sinceFav + 1
        }
        return out
    }

    /** "Anti-repeat artist": the same artist never comes back within [gap] songs (greedy, keeps every song). */
    fun artistGap(list: List<PoolTrack>, gap: Int): List<PoolTrack> {
        if (gap <= 0 || list.size < 3) return list
        val rest = list.toMutableList()
        val out = ArrayList<PoolTrack>()
        val left = HashMap<String, Int>()
        for (p in rest) left[p.track.mainArtist] = (left[p.track.mainArtist] ?: 0) + 1
        while (rest.isNotEmpty()) {
            val recent = out.takeLast(gap).map { it.track.mainArtist }.toSet()
            // among allowed songs prefer the artist with the most songs left (avoids a clump at the end), then the earliest
            var best = -1
            for (i in rest.indices) {
                val a = rest[i].track.mainArtist
                if (a in recent) continue
                if (best < 0 || (left[a] ?: 0) > (left[rest[best].track.mainArtist] ?: 0)) best = i
            }
            if (best < 0) best = 0
            val p = rest.removeAt(best)
            left[p.track.mainArtist] = (left[p.track.mainArtist] ?: 1) - 1
            out += p
        }
        return out
    }

    fun apply(list: List<PoolTrack>, o: ShapeOptions, heard: Set<String>, favorites: List<PoolTrack>, favArtists: Set<String>, hour: Int): List<PoolTrack> {
        var l = filter(list, o, heard)
        l = favoriteEvery(l, filter(favorites, o.copy(neverHeard = false), emptySet()), favArtists, o.favoriteEvery)
        l = artistGap(l, o.artistGap)
        if (o.morningRamp && hour in 5..11) l = morningRamp(l, o.rampMinutes)
        return l
    }
}

/** Guard-side check for the anti-repeat-artist distance (queue guard in the service). */
object ArtistGap {
    fun violates(recentArtists: List<String>, artist: String, gap: Int): Boolean = gap > 0 && artist in recentArtists.takeLast(gap)
}

/** Reproducible mixes: with the lock on, the same seed gives the same mix all day. */
object SeedLock {
    fun random(lock: Boolean, seed: Long, dayKey: Long): Random = if (lock) Random(seed * 1_000_003L + dayKey) else Random(System.nanoTime())
}

/** Per-weekday default mood + Spotify-style "daylist" titles. */
object DailyPlan {
    private val DAY_HE = mapOf(1 to "שני", 2 to "שלישי", 3 to "רביעי", 4 to "חמישי", 5 to "שישי", 6 to "שבת", 7 to "ראשון")

    fun byHour(hour: Int): MoodPreset = when (hour) { in 5..10 -> MoodPreset.WAKE_UP; in 11..16 -> MoodPreset.DRIVE; in 17..21 -> MoodPreset.PARTY; else -> MoodPreset.NIGHT }

    fun presetFor(dayOfWeek: Int, hour: Int, weekday: Map<Int, MoodPreset>, profileDefault: MoodPreset?): MoodPreset =
        weekday[dayOfWeek] ?: profileDefault ?: byHour(hour)

    fun partOfDay(hour: Int) = when (hour) { in 5..11 -> "בוקר"; in 12..16 -> "צהריים"; in 17..20 -> "ערב"; else -> "לילה" }

    fun daylistTitle(dayOfWeek: Int, hour: Int, preset: MoodPreset): String {
        val day = DAY_HE[dayOfWeek] ?: ""
        return if (dayOfWeek == 6) "שבת ${partOfDay(hour)} · ${preset.emoji} ${preset.he}" else "יום $day ב${partOfDay(hour)} · ${preset.emoji} ${preset.he}"
    }

    fun encodeWeekday(m: Map<Int, MoodPreset>) = m.entries.joinToString(",") { "${it.key}=${it.value.name}" }
    fun decodeWeekday(s: String?): Map<Int, MoodPreset> = s.orEmpty().split(",").mapNotNull { e ->
        val k = e.substringBefore("=").toIntOrNull(); val v = MoodPreset.entries.firstOrNull { it.name == e.substringAfter("=") }
        if (k != null && v != null) k to v else null
    }.toMap()
}

// ====================================================================== smart shuffle / ask music / A-B loop

/** Spotify-style Smart Shuffle: shuffles the queue and slips one recommendation in after every [every] songs. */
object SmartShuffle {
    fun <T> mix(queue: List<T>, recs: List<T>, every: Int, rnd: Random): List<T> {
        val q = queue.shuffled(rnd)
        if (every <= 0 || recs.isEmpty()) return q
        val r = ArrayDeque(recs)
        val out = ArrayList<T>(q.size + q.size / every + 1)
        q.forEachIndexed { i, t ->
            out += t
            if ((i + 1) % every == 0) r.removeFirstOrNull()?.let { out += it }
        }
        return out
    }
}

/** YouTube-Music-style "ask music": free Hebrew/English text -> a vibe target (+ trip length when it says "X דקות"). */
object AskMusic {
    data class Parsed(val target: VibeTarget, val preset: MoodPreset?, val minutes: Int?, val matched: List<String>)

    private class Rule(val words: List<String>, val e: Double? = null, val v: Double? = null, val m: Double? = null, val c: Double? = null,
                       val tags: Set<VibeTag> = emptySet(), val preset: MoodPreset? = null, val label: String)

    private val RULES = listOf(
        Rule(listOf("עצוב", "עצובים", "עצובה", "דיכאון", "בוכה", "פרידה", "שבור", "sad", "cry", "heartbreak"), e = 0.35, v = 0.15, tags = setOf(VibeTag.SAD), preset = MoodPreset.SAD, label = "עצוב"),
        Rule(listOf("שמח", "שמחים", "שמחה", "happy", "חיובי", "אופטימי"), v = 0.85, e = 0.65, label = "שמח"),
        Rule(listOf("רגוע", "רגועים", "שקט", "שקטים", "chill", "רילקס", "relax", "calm", "לימודים", "ריכוז", "study", "focus"), e = 0.25, v = 0.55, tags = setOf(VibeTag.CHILL), preset = MoodPreset.CHILL, label = "רגוע"),
        Rule(listOf("אנרגטי", "אנרגיה", "קצבי", "קצביים", "כושר", "חדר כושר", "אימון", "ריצה", "gym", "workout", "run", "energy"), e = 0.9, preset = MoodPreset.GYM, label = "אנרגטי"),
        Rule(listOf("מסיבה", "מסיבות", "ריקודים", "לרקוד", "party", "dance", "חתונה"), e = 0.88, v = 0.82, tags = setOf(VibeTag.PARTY), preset = MoodPreset.PARTY, label = "מסיבה"),
        Rule(listOf("לילה", "בלילה", "night", "late"), e = 0.35, v = 0.45, preset = MoodPreset.NIGHT, label = "לילה"),
        Rule(listOf("בוקר", "בבוקר", "morning", "להתעורר"), e = 0.6, v = 0.78, preset = MoodPreset.WAKE_UP, label = "בוקר"),
        Rule(listOf("נסיעה", "לנסיעה", "רכב", "באוטו", "דרך", "drive", "driving", "road"), e = 0.66, v = 0.62, preset = MoodPreset.DRIVE, label = "נסיעה"),
        Rule(listOf("מזרחי", "מזרחית", "ים תיכוני", "mizrahi"), m = 0.92, tags = setOf(VibeTag.MIZRAHI), label = "מזרחי"),
        Rule(listOf("פאנק", "phonk"), e = 0.88, m = 0.05, tags = setOf(VibeTag.PHONK), preset = MoodPreset.HEAVY_PHONK, label = "פאנק"),
        Rule(listOf("אלקטרוני", "אלקטרונית", "edm", "טכנו", "techno", "house", "טראנס"), e = 0.82, m = 0.05, tags = setOf(VibeTag.EDM), label = "EDM"),
        Rule(listOf("רומנטי", "רומנטיים", "אהבה", "love", "romantic", "דייט"), e = 0.4, v = 0.7, label = "רומנטי"),
        Rule(listOf("אפי", "אפיים", "דרמטי", "דרמה", "סרט", "epic", "cinematic", "soundtrack"), c = 0.85, preset = MoodPreset.DRAMA, label = "דרמטי"),
        Rule(listOf("עברית", "ישראלי", "ישראלית", "ישראליים"), m = 0.6, tags = setOf(VibeTag.HEBREW), label = "עברית"),
    )

    fun parse(text: String): Parsed {
        val t = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ") + " "
        val hits = RULES.filter { r -> r.words.any { w -> t.contains(" $w ") || (w.length >= 4 && t.contains(w)) } }
        fun avg(sel: (Rule) -> Double?): Double? = hits.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
        val minutes = Regex("(\\d{1,3})\\s*(דק|דקות|min|minutes)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 300)
        // with both "sad" and an energetic word, energy follows the activity, valence the feeling
        val target = VibeTarget(energy = avg { it.e }, valence = hits.mapNotNull { it.v }.minOrNull()?.let { lo -> if (hits.any { it.label == "עצוב" }) lo else avg { it.v } },
            mizrahi = avg { it.m }, cinematic = avg { it.c }, tags = hits.flatMap { it.tags }.toSet())
        return Parsed(target, hits.firstNotNullOfOrNull { it.preset }, minutes, hits.map { it.label })
    }
}

data class AbLoop(val a: Long, val b: Long)

/** Poweramp-style A-B repeat: bounds are normalised (ordered, inside the song, at least [minGapMs] apart). */
object AbLoops {
    fun normalize(a: Long, b: Long, durationMs: Long, minGapMs: Long = 1_000L): AbLoop? {
        val d = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val lo = min(a, b).coerceIn(0L, d); val hi = max(a, b).coerceIn(0L, d)
        return if (hi - lo >= minGapMs) AbLoop(lo, hi) else null
    }
    fun shouldJump(loop: AbLoop?, positionMs: Long): Boolean = loop != null && positionMs >= loop.b
}

// ====================================================================== saved queues / queue history

object TrackCodec {
    private const val F = '\u001F'; private const val L = '\u001E'
    private fun c(s: String) = s.replace(F, ' ').replace(L, ' ').replace('\n', ' ')
    fun encode(t: TrackMeta): String = listOf(c(t.id), c(t.title), t.artistIds.joinToString("|") { c(it).replace("|", " ") },
        t.artistNames.joinToString("|") { c(it).replace("|", " ") }, c(t.albumId ?: ""), t.durationSec.toString(), c(t.thumbnailUrl ?: "")).joinToString(F.toString())
    fun decode(s: String): TrackMeta? {
        val p = s.split(F); if (p.size < 7 || p[0].isBlank()) return null
        return TrackMeta(p[0], p[1], p[2].split("|").filter { it.isNotEmpty() }, p[3].split("|").filter { it.isNotEmpty() },
            p[4].ifBlank { null }, p[5].toIntOrNull() ?: -1, p[6].ifBlank { null })
    }
    fun encodeList(l: List<TrackMeta>) = l.joinToString(L.toString()) { encode(it) }
    fun decodeList(s: String?) = s.orEmpty().split(L).mapNotNull { decode(it) }
}

data class SavedQueue(val name: String, val savedAt: Long, val tracks: List<TrackMeta>, val startIndex: Int = 0)

/** Musicolet-style multiple queues: up to [MAX] named slots, plus a rolling queue history. */
object QueueSlots {
    const val MAX = 5
    const val HISTORY = 20

    fun encode(q: SavedQueue?): String = if (q == null) "" else listOf(q.name.replace("\n", " "), q.savedAt.toString(), q.startIndex.toString(), TrackCodec.encodeList(q.tracks)).joinToString("\n")
    fun decode(s: String?): SavedQueue? {
        if (s.isNullOrBlank()) return null
        val p = s.split("\n", limit = 4); if (p.size < 4) return null
        val tracks = TrackCodec.decodeList(p[3]); if (tracks.isEmpty()) return null
        return SavedQueue(p[0], p[1].toLongOrNull() ?: 0L, tracks, (p[2].toIntOrNull() ?: 0).coerceIn(0, tracks.size - 1))
    }

    fun put(slots: List<SavedQueue?>, index: Int, q: SavedQueue?): List<SavedQueue?> {
        val l = (0 until MAX).map { slots.getOrNull(it) }.toMutableList()
        if (index in 0 until MAX) l[index] = q?.copy(tracks = q.tracks.take(500))
        return l
    }

    /** Newest first, at most [HISTORY], the same list of songs is kept once (moved to the top). */
    fun pushHistory(history: List<SavedQueue>, q: SavedQueue): List<SavedQueue> {
        if (q.tracks.isEmpty()) return history
        val ids = q.tracks.map { it.id }
        return (listOf(q.copy(tracks = q.tracks.take(200))) + history.filter { it.tracks.map { t -> t.id } != ids }).take(HISTORY)
    }
}

// ====================================================================== auto-EQ

data class EqBand(val freq: Double, val gainDb: Double, val q: Double, val type: String)

/** Auto-EQ by vibe/genre: picks a curve from the song's estimated vibe; bands go to Metrolist's own equalizer. */
object AutoEq {
    enum class Curve(val he: String) { FLAT("שטוח"), BASS("בס כבד"), VOCAL("ווקאל"), WARM("חם"), SOFT("רך"), BRIGHT("בהיר") }

    fun curveFor(v: Vibe): Curve = when {
        VibeTag.PHONK in v.tags || VibeTag.EDM in v.tags || VibeTag.RAP in v.tags || v.energy >= 0.8 -> Curve.BASS
        v.isMizrahi -> Curve.VOCAL
        VibeTag.CHILL in v.tags || VibeTag.ACOUSTIC in v.tags || VibeTag.LOFI in v.tags -> Curve.WARM
        VibeTag.SAD in v.tags || v.energy < 0.35 -> Curve.SOFT
        VibeTag.CINEMATIC in v.tags || v.cinematic >= 0.6 -> Curve.BRIGHT
        else -> Curve.FLAT
    }

    fun bands(curve: Curve, intensity: Double): List<EqBand> {
        val k = intensity.coerceIn(0.0, 1.5)
        val base = when (curve) {
            Curve.FLAT -> emptyList()
            Curve.BASS -> listOf(EqBand(60.0, 6.0, 0.7, "LSC"), EqBand(150.0, 2.5, 1.0, "PK"), EqBand(3500.0, -1.0, 1.0, "PK"), EqBand(10000.0, 1.5, 0.7, "HSC"))
            Curve.VOCAL -> listOf(EqBand(100.0, -1.5, 0.7, "LSC"), EqBand(1500.0, 2.0, 1.0, "PK"), EqBand(3000.0, 3.0, 1.2, "PK"), EqBand(8000.0, 1.0, 0.7, "HSC"))
            Curve.WARM -> listOf(EqBand(120.0, 2.5, 0.7, "LSC"), EqBand(400.0, 1.0, 1.0, "PK"), EqBand(7000.0, -2.0, 0.7, "HSC"))
            Curve.SOFT -> listOf(EqBand(80.0, -1.0, 0.7, "LSC"), EqBand(2500.0, -1.5, 1.0, "PK"), EqBand(9000.0, -2.5, 0.7, "HSC"))
            Curve.BRIGHT -> listOf(EqBand(200.0, -1.0, 1.0, "PK"), EqBand(5000.0, 2.0, 1.0, "PK"), EqBand(12000.0, 3.0, 0.7, "HSC"))
        }
        return base.map { it.copy(gainDb = it.gainDb * k) }
    }

    /** Pre-amp that keeps boosted curves from clipping. */
    fun preamp(bands: List<EqBand>): Double = -(bands.maxOfOrNull { it.gainDb }?.coerceAtLeast(0.0) ?: 0.0) * 0.6
}

// ====================================================================== Blend

/** A shareable taste file (no personal history, only top artists and songs). */
data class TasteFile(val name: String, val artists: List<Pair<String, Double>>, val tracks: List<TrackMeta>)

object Blend {
    const val MAGIC = "METROLIST-DISCOVER-BLEND v1"

    fun export(h: History, name: String, nowTs: Long, days: Int = 120): TasteFile {
        val since = nowTs - days * DayClock.DAY_MS
        val good = h.plays.filter { it.playedMs >= 30_000L && it.timestampMs >= since }.ifEmpty { h.plays.filter { it.playedMs >= 30_000L } }
        val perTrack = good.groupingBy { it.trackId }.eachCount()
        val perArtist = HashMap<String, Int>()
        for ((id, c) in perTrack) h.tracks[id]?.artistNames?.firstOrNull()?.let { perArtist[it] = (perArtist[it] ?: 0) + c }
        val maxA = perArtist.values.maxOrNull()?.toDouble() ?: 1.0
        val artists = perArtist.entries.sortedByDescending { it.value }.take(40).map { it.key to (it.value / maxA) }
        val tracks = perTrack.entries.sortedByDescending { it.value }.mapNotNull { h.tracks[it.key] }.take(60)
        return TasteFile(name.ifBlank { "חבר" }, artists, tracks)
    }

    fun encode(f: TasteFile): String = buildString {
        append(MAGIC).append('\n')
        append("name\t").append(f.name.replace("\t", " ").replace("\n", " ")).append('\n')
        f.artists.forEach { append("A\t").append("%.3f".format(java.util.Locale.ROOT, it.second)).append('\t').append(it.first.replace("\t", " ").replace("\n", " ")).append('\n') }
        f.tracks.forEach { append("T\t").append(TrackCodec.encode(it).replace("\t", " ")).append('\n') }
    }

    fun decode(s: String): TasteFile? {
        val lines = s.lines()
        if (lines.firstOrNull()?.trim() != MAGIC) return null
        var name = "חבר"; val a = ArrayList<Pair<String, Double>>(); val t = ArrayList<TrackMeta>()
        for (l in lines.drop(1)) {
            val p = l.split("\t", limit = 3)
            when (p[0]) {
                "name" -> if (p.size >= 2) name = p[1].take(30)
                "A" -> if (p.size >= 3) p[1].toDoubleOrNull()?.let { a += p[2] to it }
                "T" -> if (p.size >= 2) TrackCodec.decode(p[1])?.let { t += it }
            }
        }
        return if (a.isEmpty() && t.isEmpty()) null else TasteFile(name, a, t)
    }

    private fun norm(s: String) = s.lowercase().trim()

    /** Shared artists' songs first, then alternating mine / friend's, no duplicates. */
    fun merge(mine: TasteFile, friend: TasteFile, size: Int = 30): List<TrackMeta> {
        val shared = mine.artists.map { norm(it.first) }.toSet() intersect friend.artists.map { norm(it.first) }.toSet()
        fun isShared(t: TrackMeta) = t.artistNames.any { norm(it) in shared }
        val seen = HashSet<String>(); val out = ArrayList<TrackMeta>()
        fun add(t: TrackMeta) { if (out.size < size && seen.add(t.id)) out += t }
        val a = mine.tracks; val b = friend.tracks
        for (i in 0 until max(a.size, b.size)) { a.getOrNull(i)?.takeIf { isShared(it) }?.let { add(it) }; b.getOrNull(i)?.takeIf { isShared(it) }?.let { add(it) } }
        for (i in 0 until max(a.size, b.size)) { a.getOrNull(i)?.let { add(it) }; b.getOrNull(i)?.let { add(it) } }
        return out
    }

    /** "% match" between two tastes (weighted artist overlap). */
    fun matchPct(mine: TasteFile, friend: TasteFile): Int {
        val ma = mine.artists.associate { norm(it.first) to it.second }; val fa = friend.artists.associate { norm(it.first) to it.second }
        val keys = ma.keys + fa.keys
        if (keys.isEmpty()) return 0
        val inter = keys.sumOf { min(ma[it] ?: 0.0, fa[it] ?: 0.0) }
        val union = keys.sumOf { max(ma[it] ?: 0.0, fa[it] ?: 0.0) }
        return if (union <= 0) 0 else (100 * inter / union).toInt().coerceIn(0, 100)
    }
}

// ====================================================================== small rules

/** Gentle sleep timer: full volume until the fade window, then an eased curve down to silence. */
object SleepFade {
    fun volumeAt(remainingMs: Long, fadeMs: Long): Float {
        if (remainingMs <= 0) return 0f
        if (fadeMs <= 0 || remainingMs >= fadeMs) return 1f
        return (remainingMs.toDouble() / fadeMs).pow(1.6).toFloat().coerceIn(0f, 1f)
    }
}

enum class BubbleFreq(val he: String) { EVERY_OPEN("בכל פתיחה"), EVERY_3RD("כל פתיחה שלישית"), DAILY("פעם ביום"), NEVER("אף פעם") }

object BubbleGate {
    fun shouldShow(freq: BubbleFreq, openCount: Int, lastShownDay: Long, today: Long): Boolean = when (freq) {
        BubbleFreq.EVERY_OPEN -> true
        BubbleFreq.EVERY_3RD -> openCount % 3 == 0
        BubbleFreq.DAILY -> lastShownDay != today
        BubbleFreq.NEVER -> false
    }
}

/** "Auto-like after N full listens". */
object AutoLike {
    fun shouldLike(fullListensBefore: Int, thisWasFull: Boolean, alreadyLiked: Boolean, threshold: Int = 3): Boolean =
        threshold > 0 && !alreadyLiked && thisWasFull && fullListensBefore + 1 >= threshold
}

/** Spotify-DJ-style intro line when a song starts (or null when there is nothing worth saying). */
object DjIntro {
    fun line(title: String, playsBefore: Int, lastPlayedTs: Long?, nowTs: Long): String? = when {
        playsBefore == 0 -> "🎧 משהו חדש בשבילך: $title"
        lastPlayedTs != null && nowTs - lastPlayedTs > 60L * DayClock.DAY_MS -> "🎧 הנה משהו שלא שמעת מזמן: $title"
        playsBefore >= 50 -> "🎧 אחד הקבועים שלך: $title"
        else -> null
    }
}

/** Daily challenge: listen to one artist you have never played before, today. */
object DailyChallenge {
    data class State(val doneToday: Boolean, val newArtistToday: String?, val streak: Int, val totalDone: Int)

    fun compute(h: History, nowTs: Long): State {
        val good = h.sortedPlays.filter { it.playedMs >= 30_000L && it.timestampMs <= nowTs }
        val firstDay = HashMap<String, Long>(); val firstName = HashMap<String, String>()
        val firstEverDay = good.firstOrNull()?.let { DayClock.dayKey(it.timestampMs) }
        for (p in good) {
            val t = h.tracks[p.trackId] ?: continue
            val a = t.mainArtist
            if (a !in firstDay) { firstDay[a] = DayClock.dayKey(p.timestampMs); firstName[a] = t.artistNames.firstOrNull() ?: a }
        }
        val doneDays = firstDay.filter { it.value != firstEverDay }.values.toSortedSet()
        val today = DayClock.dayKey(nowTs)
        var streak = 0; var d = if (today in doneDays) today else today - 1
        while (d in doneDays) { streak++; d-- }
        val newToday = firstDay.entries.firstOrNull { it.value == today && it.value != firstEverDay }?.key?.let { firstName[it] }
        return State(today in doneDays, newToday, streak, doneDays.size)
    }
}

/** Apple-Music-style Replay: top songs/artists of a period. */
object Replay {
    data class Entry(val track: TrackMeta, val plays: Int)
    fun top(h: History, fromTs: Long, toTs: Long, n: Int = 25): List<Entry> =
        h.plays.filter { it.playedMs >= 30_000L && it.timestampMs in fromTs until toTs }.groupingBy { it.trackId }.eachCount()
            .entries.sortedByDescending { it.value }.mapNotNull { e -> h.tracks[e.key]?.let { Entry(it, e.value) } }.take(n)
    fun topArtists(h: History, fromTs: Long, toTs: Long, n: Int = 10): List<Pair<String, Int>> {
        val m = HashMap<String, Int>()
        for (p in h.plays) if (p.playedMs >= 30_000L && p.timestampMs in fromTs until toTs) h.tracks[p.trackId]?.let { t ->
            val name = t.artistNames.firstOrNull() ?: t.mainArtist; m[name] = (m[name] ?: 0) + 1 }
        return m.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }
}

/** Last.fm-style listening clock (plays per hour) and an "obscurity" score of how spread-out the taste is. */
object TasteStats {
    fun clock(h: History, nowTs: Long, days: Int = 90): IntArray {
        val out = IntArray(24); val since = nowTs - days * DayClock.DAY_MS
        for (p in h.plays) if (p.playedMs >= 30_000L && p.timestampMs in since..nowTs) out[DayClock.dateTime(p.timestampMs).hour]++
        return out
    }

    /** 0 = everything is the same few artists, 100 = a very long tail. */
    fun obscurity(h: History): Int {
        val per = HashMap<String, Int>()
        for (p in h.plays) if (p.playedMs >= 30_000L) h.tracks[p.trackId]?.let { per[it.mainArtist] = (per[it.mainArtist] ?: 0) + 1 }
        val total = per.values.sum(); if (total == 0) return 0
        val top10 = per.values.sortedDescending().take(10).sum().toDouble() / total
        val breadth = min(1.0, per.size / 400.0)
        return (100 * (0.65 * (1 - top10) + 0.35 * breadth)).toInt().coerceIn(0, 100)
    }

    fun obscurityLabel(score: Int) = when { score < 25 -> "נאמן ללהיטים שלך 💛"; score < 50 -> "מאוזן ⚖️"; score < 75 -> "חוקר 🧭"; else -> "היפסטר אמיתי 🕶️" }
}

/** Daily random theme (stable for the whole day). */
object DailyPick {
    fun index(dayKey: Long, count: Int): Int = if (count <= 0) 0 else (abs(dayKey * 2_654_435_761L) % count).toInt()
}
