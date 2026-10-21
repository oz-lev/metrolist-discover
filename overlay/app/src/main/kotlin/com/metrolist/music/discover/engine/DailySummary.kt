/**
 * Metrolist Discover (fork addition) - GPL-3.0
 *
 * Daily listening summary for the on-device "Yomi" journal API (see yomi/DailySummaryProvider.kt).
 * Pure Kotlin (no Android) so it is unit-tested with kotlinc like the rest of the engine.
 */
package com.metrolist.music.discover.engine

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One row of Metrolist's `event` table. [endMs] is Metrolist's own timestamp: it writes LocalDateTime.now()
 * (the phone's local wall clock) when a playback session ends and stores it as UTC millis, so the UTC fields
 * of [endMs] ARE the local time (the same convention DayClock uses everywhere in the engine).
 */
data class DailyPlay(val songId: String, val endMs: Long, val playMs: Long)

data class DailyTop(val songId: String, val title: String, val artist: String, val plays: Int)

data class DailySummary(
    val date: LocalDate,
    val minutesListened: Int,
    val plays: Int,
    val uniqueTracks: Int,
    val topTrack: String?,
    val topTrackId: String?,
    val topArtist: String?,
    val top5: List<DailyTop>,
    /** "HH:mm" local, start of the day's first play (its end minus its play time, never before 00:00). */
    val firstPlayTime: String?,
    /** "HH:mm" local, end of the day's last play. */
    val lastPlayTime: String?,
    /** energetic / calm / happy / melancholic / balanced, or null (no plays or no vibe estimate). */
    val moodHint: String?,
) {
    fun top5Json(): String = top5.joinToString(",", "[", "]") {
        "{\"title\":${DailySummaries.json(it.title)},\"artist\":${DailySummaries.json(it.artist)},\"plays\":${it.plays},\"id\":${DailySummaries.json(it.songId)}}"
    }
}

object DailySummaries {
    const val MAX_RANGE_DAYS = 366
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    /** Caller rule of the provider: the app itself, or any package whose name starts with com.oz.yomi. */
    const val YOMI_PREFIX = "com.oz.yomi"
    fun callerAllowed(pkg: String?, self: String): Boolean = pkg != null && (pkg == self || pkg.startsWith(YOMI_PREFIX))

    /** Strict yyyy-MM-dd, null otherwise. */
    fun parseDate(s: String?): LocalDate? {
        if (s == null || !Regex("""\d{4}-\d{2}-\d{2}""").matches(s)) return null
        return try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }
    }

    /** Epoch-style millis window (Metrolist's local-as-UTC convention) covering [from]..[to] inclusive. */
    fun windowMs(from: LocalDate, to: LocalDate): Pair<Long, Long> = DayClock.tsOf(from) to DayClock.tsOf(to.plusDays(1))

    /**
     * One summary per day from [from] to [to] inclusive (days without plays get zeros and nulls).
     * [mood] estimates a track's vibe (the engine's VibeEstimator in the app); null = no mood_hint.
     */
    fun range(
        plays: List<DailyPlay>,
        tracks: Map<String, TrackMeta>,
        from: LocalDate,
        to: LocalDate,
        mood: ((TrackMeta) -> Vibe)? = null,
    ): List<DailySummary> {
        require(!to.isBefore(from)) { "to is before from" }
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        require(days <= MAX_RANGE_DAYS) { "range longer than $MAX_RANGE_DAYS days" }
        val byDay = plays.filter { it.playMs >= 0 }.groupBy { DayClock.date(it.endMs) }
        return (0 until days).map { i ->
            val d = from.plusDays(i)
            day(d, byDay[d].orEmpty(), tracks, mood)
        }
    }

    fun day(date: LocalDate, plays: List<DailyPlay>, tracks: Map<String, TrackMeta>, mood: ((TrackMeta) -> Vibe)? = null): DailySummary {
        val mine = plays.filter { DayClock.date(it.endMs) == date && it.playMs >= 0 }
        if (mine.isEmpty()) return DailySummary(date, 0, 0, 0, null, null, null, emptyList(), null, null, null)
        val totalMs = mine.sumOf { it.playMs }
        fun title(id: String) = tracks[id]?.title?.takeIf { it.isNotBlank() } ?: id
        fun artist(id: String) = tracks[id]?.let { t -> t.artistNames.firstOrNull { n -> n.any { it.isLetterOrDigit() } } ?: t.artistIds.firstOrNull() } ?: ""
        data class Agg(val id: String, var plays: Int = 0, var ms: Long = 0, var last: Long = 0)
        val agg = LinkedHashMap<String, Agg>()
        for (p in mine) agg.getOrPut(p.songId) { Agg(p.songId) }.let { a -> a.plays++; a.ms += p.playMs; a.last = maxOf(a.last, p.endMs) }
        // most plays, then most time, then most recent, then id (fully deterministic)
        val ranked = agg.values.sortedWith(compareByDescending<Agg> { it.plays }.thenByDescending { it.ms }.thenByDescending { it.last }.thenBy { it.id })
        val top5 = ranked.take(5).map { DailyTop(it.id, title(it.id), artist(it.id), it.plays) }
        // top artist: most plays, then most time, then name
        val artistPlays = HashMap<String, Pair<Int, Long>>()
        for (a in agg.values) {
            val n = artist(a.id)
            if (n.isBlank()) continue
            val cur = artistPlays[n] ?: (0 to 0L)
            artistPlays[n] = (cur.first + a.plays) to (cur.second + a.ms)
        }
        val topArtist = artistPlays.entries.sortedWith(compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
            .thenByDescending { it.value.second }.thenBy { it.key }).firstOrNull()?.key
        val dayStart = DayClock.tsOf(date)
        val first = mine.minOf { maxOf(dayStart, it.endMs - it.playMs) }
        val last = mine.maxOf { it.endMs }
        val moodHint = mood?.let { est ->
            var w = 0.0; var e = 0.0; var v = 0.0
            for (p in mine) {
                val t = tracks[p.songId] ?: continue
                val vibe = est(t)
                val weight = maxOf(p.playMs, 1L).toDouble()
                w += weight; e += weight * vibe.energy; v += weight * vibe.valence
            }
            if (w > 0) moodLabel(e / w, v / w) else null
        }
        return DailySummary(
            date = date,
            minutesListened = Math.round(totalMs / 60_000.0).toInt(),
            plays = mine.size,
            uniqueTracks = agg.size,
            topTrack = top5.first().title,
            topTrackId = top5.first().songId,
            topArtist = topArtist,
            top5 = top5,
            firstPlayTime = DayClock.dateTime(first).format(HHMM),
            lastPlayTime = DayClock.dateTime(last).format(HHMM),
            moodHint = moodHint,
        )
    }

    /** Average energy / valence (0..1, VibeEstimator scale) of the day, weighted by listening time, to one word. */
    fun moodLabel(energy: Double, valence: Double): String = when {
        energy >= 0.65 -> "energetic"
        energy <= 0.40 -> "calm"
        valence >= 0.62 -> "happy"
        valence <= 0.42 -> "melancholic"
        else -> "balanced"
    }

    fun json(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> b.append("\\\"")
            ch == '\\' -> b.append("\\\\")
            ch == '\n' -> b.append("\\n")
            ch == '\r' -> b.append("\\r")
            ch == '\t' -> b.append("\\t")
            ch < ' ' -> b.append("\\u%04x".format(ch.code))
            else -> b.append(ch)
        }
        return b.append('"').toString()
    }
}
