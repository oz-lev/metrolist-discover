/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 *
 * Playlist power tools: merge 2..N playlists (append, interleave, smart shuffle with artist spread, sort),
 * deduplicate, split/filter by language or artists, set operations (intersection / difference / symmetric difference),
 * and clone.
 */
package com.metrolist.music.discover.engine

import kotlin.random.Random

/** Minimal song representation inside a playlist for pure merge / dedupe / set-op transformations. */
data class PlaylistEntry(
    val songId: String,
    val title: String,
    val artistId: String = "?",
    val artistName: String = "",
    val addedOrder: Int = 0,
    val playCount: Int = 0,
    /** Optional taste/affinity score (higher = preferred earlier in smart shuffle). */
    val affinity: Double = 0.5,
) {
    fun asTrackMeta(): TrackMeta = TrackMeta(
        id = songId,
        title = title,
        artistIds = listOf(artistId),
        artistNames = if (artistName.isNotBlank()) listOf(artistName) else emptyList(),
    )
}

enum class MergeMode(val he: String, val desc: String) {
    APPEND("חיבור ברצף", "פלייליסט אחרי פלייליסט לפי הסדר"),
    INTERLEAVE("שילוב לסירוגין", "שיר מ-A, שיר מ-B, וחוזר חלילה"),
    SMART_SHUFFLE("ערבוב חכם", "פיזור אמנים בלי רצף כפול, לפי הטעם שלך"),
    SORT_ARTIST("מיון לפי אמן", "מסודר אלפביתית לפי אמן ושם שיר"),
    SORT_TITLE("מיון לפי שם", "מסודר אלפביתית לפי שם השיר"),
    SORT_DATE("לפי סדר הוספה", "שומר על סדר ההוספה המקורי"),
    SORT_PLAYS("לפי השמעות", "הכי מושמעים שלך קודם"),
}

data class MergeResult(
    val entries: List<PlaylistEntry>,
    val totalBeforeDedupe: Int,
    val duplicatesRemoved: Int,
)

enum class SetOp(val he: String, val desc: String) {
    INTERSECT("חיתוך (משותפים)", "רק שירים שמופיעים גם ב-A וגם ב-B"),
    DIFF_A_MINUS_B("הפרש (רק ב-A)", "שירים שנמצאים ב-A ולא ב-B"),
    SYMMETRIC_DIFF("ייחודיים בלבד", "שירים שמופיעים רק באחד מהשניים"),
}

object PlaylistTools {

    /**
     * Merges 2..N playlists according to [mode] and [dedupe].
     * Always deterministic for a given [seed] (used only in SMART_SHUFFLE tie-breaking).
     */
    fun merge(
        playlists: List<List<PlaylistEntry>>,
        mode: MergeMode = MergeMode.APPEND,
        dedupe: Boolean = true,
        seed: Long = 42L,
    ): MergeResult {
        val nonEmpty = playlists.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return MergeResult(emptyList(), 0, 0)

        val combined: List<PlaylistEntry> = when (mode) {
            MergeMode.INTERLEAVE -> interleaveAll(nonEmpty)
            else -> nonEmpty.flatten()
        }
        val totalBefore = combined.size
        val working = if (dedupe) {
            val seen = HashSet<String>(combined.size)
            combined.filter { seen.add(it.songId) }
        } else combined
        val dups = totalBefore - working.size

        val ordered = when (mode) {
            MergeMode.APPEND, MergeMode.INTERLEAVE -> working
            MergeMode.SMART_SHUFFLE -> smartShuffle(working, seed)
            MergeMode.SORT_ARTIST -> working.sortedWith(compareBy<PlaylistEntry> { it.artistName.lowercase() }.thenBy { it.title.lowercase() })
            MergeMode.SORT_TITLE -> working.sortedWith(compareBy<PlaylistEntry> { it.title.lowercase() }.thenBy { it.artistName.lowercase() })
            MergeMode.SORT_DATE -> working.sortedBy { it.addedOrder }
            MergeMode.SORT_PLAYS -> working.sortedWith(compareByDescending<PlaylistEntry> { it.playCount }.thenByDescending { it.affinity })
        }
        return MergeResult(ordered, totalBefore, dups)
    }

    /** Round-robin interleave across any number of playlists (A1, B1, C1, A2, B2, C2...). */
    fun interleaveAll(playlists: List<List<PlaylistEntry>>): List<PlaylistEntry> {
        val maxLen = playlists.maxOfOrNull { it.size } ?: 0
        val out = ArrayList<PlaylistEntry>(playlists.sumOf { it.size })
        for (i in 0 until maxLen) {
            for (pl in playlists) if (i < pl.size) out += pl[i]
        }
        return out
    }

    /**
     * Taste-aware smart shuffle: scores tracks with affinity + small seeded jitter, then spreads artists so the same
     * artist never plays back-to-back whenever another artist is available.
     */
    fun smartShuffle(entries: List<PlaylistEntry>, seed: Long = 42L): List<PlaylistEntry> {
        if (entries.size <= 1) return entries
        val rng = Random(seed)
        val scored = entries.map { it to (it.affinity * 0.45 + rng.nextDouble() * 0.55) }
            .sortedByDescending { it.second }
            .map { it.first }
            .toMutableList()
        val out = ArrayList<PlaylistEntry>(scored.size)
        while (scored.isNotEmpty()) {
            val lastA = out.lastOrNull()?.artistId?.takeIf { it != "?" }
            val pickIdx = if (lastA == null) 0
            else scored.indexOfFirst { it.artistId != lastA }.let { if (it >= 0) it else 0 }
            out += scored.removeAt(pickIdx)
        }
        return out
    }

    /** Removes duplicate songIds from a single playlist, keeping the first occurrence in order. */
    fun deduplicate(entries: List<PlaylistEntry>): MergeResult {
        val seen = HashSet<String>(entries.size)
        val clean = entries.filter { seen.add(it.songId) }
        return MergeResult(clean, entries.size, entries.size - clean.size)
    }

    /** Filters a playlist by Hebrew / non-Hebrew language using the engine's canonical [Lang] rules. */
    fun filterByLanguage(entries: List<PlaylistEntry>, lang: LangMode): List<PlaylistEntry> =
        if (lang == LangMode.ALL) entries else entries.filter { Lang.allows(lang, it.asTrackMeta()) }

    /** Filters a playlist to include (or exclude) songs by [artistIds]. */
    fun filterByArtists(entries: List<PlaylistEntry>, artistIds: Set<String>, include: Boolean = true): List<PlaylistEntry> {
        if (artistIds.isEmpty()) return if (include) emptyList() else entries
        return entries.filter { (it.artistId in artistIds) == include }
    }

    /** Songs present in BOTH [a] and [b], in [a]'s order, deduplicated. */
    fun intersect(a: List<PlaylistEntry>, b: List<PlaylistEntry>): List<PlaylistEntry> {
        val bIds = b.map { it.songId }.toHashSet()
        val seen = HashSet<String>()
        return a.filter { it.songId in bIds && seen.add(it.songId) }
    }

    /** Songs present in [a] but NOT in [b], in [a]'s order, deduplicated. */
    fun difference(a: List<PlaylistEntry>, b: List<PlaylistEntry>): List<PlaylistEntry> {
        val bIds = b.map { it.songId }.toHashSet()
        val seen = HashSet<String>()
        return a.filter { it.songId !in bIds && seen.add(it.songId) }
    }

    /** Songs present in [a] or [b] exclusively (A \ B) U (B \ A), deduplicated. */
    fun symmetricDifference(a: List<PlaylistEntry>, b: List<PlaylistEntry>): List<PlaylistEntry> {
        val aIds = a.map { it.songId }.toHashSet()
        val bIds = b.map { it.songId }.toHashSet()
        val seen = HashSet<String>()
        return (a.filter { it.songId !in bIds } + b.filter { it.songId !in aIds }).filter { seen.add(it.songId) }
    }

    fun applySetOp(a: List<PlaylistEntry>, b: List<PlaylistEntry>, op: SetOp): List<PlaylistEntry> = when (op) {
        SetOp.INTERSECT -> intersect(a, b)
        SetOp.DIFF_A_MINUS_B -> difference(a, b)
        SetOp.SYMMETRIC_DIFF -> symmetricDifference(a, b)
    }

    /** Produces a clean re-indexed clone of [entries]. */
    fun cloneEntries(entries: List<PlaylistEntry>, dedupe: Boolean = false): List<PlaylistEntry> {
        val src = if (dedupe) deduplicate(entries).entries else entries
        return src.mapIndexed { i, e -> e.copy(addedOrder = i) }
    }
}
