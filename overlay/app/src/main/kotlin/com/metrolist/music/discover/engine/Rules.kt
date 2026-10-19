/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Clock helpers for Metrolist timestamps (local wall-clock time stored as UTC millis). */
object DayClock {
    const val DAY_MS = 86_400_000L
    fun dateTime(ts: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC)
    fun date(ts: Long): LocalDate = dateTime(ts).toLocalDate()
    fun dayKey(ts: Long): Long = Math.floorDiv(ts, DAY_MS)
    /** ISO day of week: 1 = Monday ... 5 = Friday, 6 = Saturday, 7 = Sunday. */
    fun dayOfWeek(ts: Long): Int = dateTime(ts).dayOfWeek.value
    fun minuteOfDay(ts: Long): Int = dateTime(ts).let { it.hour * 60 + it.minute }
    fun tsOf(d: LocalDate): Long = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}

enum class BlockKind { ARTIST, SAD_SONGS, KEYWORD }

/**
 * "Block X on chosen days / hours", e.g. no breakup songs on Friday, or no <artist> on weekday mornings.
 * days: ISO day-of-week numbers. A window whose end is before its start wraps past midnight
 * (Fri 20:00-02:00 = Friday evening + the first two hours of Saturday). from == to = all day.
 */
data class BlockRule(
    val kind: BlockKind,
    val value: String,
    val days: Set<Int>,
    val fromMinute: Int = 0,
    val toMinute: Int = 0,
) {
    fun isActiveAt(dayOfWeek: Int, minuteOfDay: Int): Boolean {
        if (fromMinute == toMinute) return dayOfWeek in days
        if (fromMinute < toMinute) return dayOfWeek in days && minuteOfDay >= fromMinute && minuteOfDay < toMinute
        val prev = if (dayOfWeek == 1) 7 else dayOfWeek - 1
        return (dayOfWeek in days && minuteOfDay >= fromMinute) || (prev in days && minuteOfDay < toMinute)
    }

    fun matches(t: TrackMeta, vibe: Vibe?): Boolean = when (kind) {
        BlockKind.ARTIST -> {
            val v = value.trim().lowercase()
            t.artistIds.any { it.lowercase() == v } || t.artistNames.any { it.trim().lowercase() == v }
        }
        BlockKind.SAD_SONGS -> vibe != null && (VibeTag.SAD in vibe.tags || vibe.valence < 0.3)
        BlockKind.KEYWORD -> value.isNotBlank() && (t.title.lowercase().contains(value.trim().lowercase()))
    }

    fun encode(): String = listOf(kind.name, value.replace("~", " ").replace("^", " "), days.sorted().joinToString(","), fromMinute, toMinute).joinToString("~")

    companion object {
        fun decode(s: String): BlockRule? = runCatching {
            val p = s.split("~")
            BlockRule(BlockKind.valueOf(p[0]), p[1], p[2].split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(), p[3].toInt(), p[4].toInt())
        }.getOrNull()
        fun encodeAll(list: List<BlockRule>) = list.joinToString("^") { it.encode() }
        fun decodeAll(s: String?): List<BlockRule> = s.orEmpty().split("^").filter { it.isNotBlank() }.mapNotNull { decode(it) }
    }
}

/** Every "weird setting" that filters what plays. */
data class RuleSet(
    val noRepeatToday: Boolean = false,
    val maxDurationSec: Int? = null,
    val maxMizrahiPct: Int = 100,
    val noRemix: Boolean = false,
    val noLive: Boolean = false,
    val maxEnergy: Double? = null,
    val blocks: List<BlockRule> = emptyList(),
)

enum class Violation { BLOCKED, REPEAT_TODAY, TOO_LONG, REMIX, LIVE, TOO_ENERGETIC }

/** The no-repeat lock: a song may play once per local day. */
class DayLock(private val todayKey: Long, playedToday: Collection<String>) {
    private val played = HashSet(playedToday)
    fun allows(trackId: String): Boolean = trackId !in played
    fun markPlayed(trackId: String, ts: Long) { if (DayClock.dayKey(ts) == todayKey) played += trackId }

    companion object {
        fun from(plays: List<PlayEvent>, nowTs: Long, minPlayedMs: Long = 30_000L): DayLock {
            val today = DayClock.dayKey(nowTs)
            return DayLock(today, plays.filter { DayClock.dayKey(it.timestampMs) == today && it.playedMs >= minPlayedMs }.map { it.trackId })
        }
    }
}

class RuleEngine(val rules: RuleSet, val lock: DayLock?) {
    fun violation(t: TrackMeta, vibe: Vibe?, nowTs: Long): Violation? {
        val dow = DayClock.dayOfWeek(nowTs); val min = DayClock.minuteOfDay(nowTs)
        if (rules.blocks.any { it.isActiveAt(dow, min) && it.matches(t, vibe) }) return Violation.BLOCKED
        if (rules.noRepeatToday && lock != null && !lock.allows(t.id)) return Violation.REPEAT_TODAY
        rules.maxDurationSec?.let { if (t.durationSec > it) return Violation.TOO_LONG }
        val title = t.title.lowercase()
        if (rules.noRemix && listOf("remix", "רמיקס", "slowed", "sped up", "nightcore", "rmx", "bootleg").any { title.contains(it) }) return Violation.REMIX
        if (rules.noLive && listOf("live", "לייב", "בהופעה").any { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(title) }) return Violation.LIVE
        rules.maxEnergy?.let { cap -> if (vibe != null && vibe.energy > cap) return Violation.TOO_ENERGETIC }
        return null
    }

    fun filter(list: List<PoolTrack>, nowTs: Long): List<PoolTrack> = capMizrahi(list.filter { violation(it.track, it.vibe, nowTs) == null }, rules.maxMizrahiPct)

    companion object {
        /** Keeps list order; drops Mizrahi tracks that would push their running share over [maxPct]. */
        fun capMizrahi(list: List<PoolTrack>, maxPct: Int): List<PoolTrack> {
            if (maxPct >= 100) return list
            val out = ArrayList<PoolTrack>()
            var miz = 0
            for (p in list) {
                if (p.vibe.isMizrahi) {
                    if ((miz + 1) * 100 > maxPct * (out.size + 1)) continue
                    miz++
                }
                out += p
            }
            return out
        }

        /** Live queue check: would playing a Mizrahi track now push the last-10 share over the cap? */
        fun mizrahiOverCap(recent: List<Boolean>, currentIsMizrahi: Boolean, maxPct: Int): Boolean {
            if (!currentIsMizrahi || maxPct >= 100) return false
            val window = (recent.takeLast(9) + true)
            return window.count { it } * 100 > maxPct * window.size
        }
    }
}

/** Neighbours mode: after [startMinute] (default 23:00) until [endMinute] cap volume and cut bass. */
data class NeighboursMode(
    val enabled: Boolean = false,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    val volumeCap: Float = 0.4f,
    val bassCutDb: Double = -9.0,
) {
    fun activeAt(minuteOfDay: Int): Boolean {
        if (!enabled) return false
        return if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/** "Someone else's taste" presets for an hour. Seeds are YouTube Music search queries. */
enum class TastePreset(val he: String, val emoji: String, val target: VibeTarget, val seeds: List<String>) {
    EDM_ONLY("רק EDM", "🎧", VibeTarget(energy = 0.88, mizrahi = 0.0, tags = setOf(VibeTag.EDM)),
        listOf("EDM festival hits", "progressive house anthems", "big room house", "melodic dubstep", "future bass hits")),
    MIZRAHI_ONLY("רק מזרחית", "🪘", VibeTarget(mizrahi = 1.0, tags = setOf(VibeTag.MIZRAHI)),
        listOf("מזרחית להיטים", "שירים מזרחיים חדשים", "מחרוזת מזרחית", "להיטי מזרחית 2025", "זמר מזרחי")),
    Y2K("שנות ה-2000", "📼", VibeTarget(),
        listOf("2000s pop hits", "להיטים ישראליים שנות ה-2000", "2000s dance hits", "2005 hits", "early 2000s r&b")),
    STRANGER("בנאדם אקראי", "🎲", VibeTarget(),
        listOf("k-pop", "afrobeats", "bossa nova", "french rap", "j-rock", "cumbia", "reggaeton classics", "irish folk", "turkish pop",
            "greek laiko", "bollywood hits", "country hits", "jazz standards", "city pop", "flamenco", "polish disco", "baile funk", "amapiano"));
}
