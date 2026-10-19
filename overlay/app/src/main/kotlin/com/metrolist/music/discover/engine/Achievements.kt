/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.floor
import kotlin.math.sqrt

/** Profile: XP, level, streaks, 8 badges and a weekly mini summary, all from local history. */
object Achievements {
    data class Badge(val id: String, val emoji: String, val he: String, val descHe: String, val progress: Double, val earned: Boolean)
    data class Weekly(val plays: Int, val minutes: Int, val topArtist: String?, val topSong: String?, val newArtists: Int, val changePct: Int?)
    data class Profile(
        val xp: Long, val level: Int, val levelProgress: Double, val xpToNext: Long,
        val currentStreak: Int, val bestStreak: Int, val totalMinutes: Long, val distinctArtists: Int,
        val badges: List<Badge>, val weekly: Weekly,
    )

    fun levelOf(xp: Long): Int = floor(sqrt(xp / 1000.0)).toInt() + 1
    fun xpForLevel(level: Int): Long = ((level - 1).toLong() * (level - 1)) * 1000L

    fun compute(h: History, nowTs: Long, vibe: VibeEstimator = VibeEstimator(), minPlayedMs: Long = 30_000L): Profile {
        val plays = h.plays.filter { it.timestampMs <= nowTs }
        val good = plays.filter { it.playedMs >= minPlayedMs }
        val skips = plays.size - good.size
        val likes = h.signals.count { it.kind == SignalKind.LIKE }
        val artists = good.mapNotNull { h.tracks[it.trackId]?.mainArtist }.toSet()
        val fromDiscover = good.count { it.fromDiscover }
        val totalMinutes = good.sumOf { it.playedMs } / 60_000L
        val xp = good.size * 10L + totalMinutes * 2L + likes * 25L + artists.size * 15L + fromDiscover * 30L
        val level = levelOf(xp)
        val cur = xpForLevel(level); val next = xpForLevel(level + 1)

        // streaks over local days
        val days = good.map { DayClock.dayKey(it.timestampMs) }.toSortedSet()
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (d in days) { run = if (d == prev + 1) run + 1 else 1; if (run > best) best = run; prev = d }
        val today = DayClock.dayKey(nowTs)
        var current = 0
        var d = if (today in days) today else today - 1
        while (d in days) { current++; d-- }

        // badges
        fun hour(p: PlayEvent) = DayClock.dateTime(p.timestampMs).hour
        val night = good.count { hour(it) in 0..3 }
        val morning = good.count { hour(it) in 5..8 }
        val perArtist = good.mapNotNull { h.tracks[it.trackId]?.mainArtist }.groupingBy { it }.eachCount()
        val topArtistPlays = perArtist.values.maxOrNull() ?: 0
        val vibes = HashMap<String, Vibe>()
        fun v(id: String) = vibes.getOrPut(id) { h.tracks[id]?.let { vibe.own(it) } ?: Vibe(0.5, 0.5, 0.0, 0.0) }
        val drops = good.count { val t = v(it.trackId).tags; VibeTag.EDM in t || VibeTag.PHONK in t }
        val mizrahi = good.count { v(it.trackId).isMizrahi }
        val maxDayMin = good.groupBy { DayClock.dayKey(it.timestampMs) }.values.maxOfOrNull { l -> l.sumOf { it.playedMs } / 60_000L } ?: 0L
        val skipRate = if (plays.isEmpty()) 1.0 else skips.toDouble() / plays.size
        fun b(id: String, emoji: String, he: String, desc: String, value: Double, goal: Double) =
            Badge(id, emoji, he, desc, (value / goal).coerceIn(0.0, 1.0), value >= goal)
        val badges = listOf(
            b("night_owl", "🦉", "ינשוף לילה", "300 שירים בין חצות ל-4 לפנות בוקר", night.toDouble(), 300.0),
            b("early_bird", "🐦", "ציפור בוקר", "300 שירים בין 5 ל-9 בבוקר", morning.toDouble(), 300.0),
            b("loyal", "💛", "נאמן", "500 השמעות לאותו אמן", topArtistPlays.toDouble(), 500.0),
            b("explorer", "🧭", "מגלה ארצות", "300 אמנים שונים", artists.size.toDouble(), 300.0),
            b("drop_hunter", "💥", "צייד דרופים", "500 שירי EDM / פאנק", drops.toDouble(), 500.0),
            b("mizrahi_heart", "🎶", "לב מזרחי", "1,000 שירים מזרחיים", mizrahi.toDouble(), 1000.0),
            b("marathon", "🏃", "מרתון", "6 שעות האזנה ביום אחד", maxDayMin.toDouble(), 360.0),
            b("streak30", "🔥", "רצף של חודש", "30 ימים ברצף עם מוזיקה", best.toDouble(), 30.0),
        )

        // weekly mini summary
        val weekStart = nowTs - 7 * DayClock.DAY_MS
        val week = good.filter { it.timestampMs >= weekStart }
        val prevWeek = good.filter { it.timestampMs in (weekStart - 7 * DayClock.DAY_MS) until weekStart }
        val before = good.filter { it.timestampMs < weekStart }.mapNotNull { h.tracks[it.trackId]?.mainArtist }.toSet()
        val wkArtists = week.mapNotNull { h.tracks[it.trackId] }
        val topA = wkArtists.groupingBy { it.mainArtist }.eachCount().maxByOrNull { it.value }?.key
        val topAName = topA?.let { a -> wkArtists.firstOrNull { it.mainArtist == a }?.artistNames?.firstOrNull() ?: a }
        val topS = week.groupingBy { it.trackId }.eachCount().maxByOrNull { it.value }?.key?.let { h.tracks[it]?.title }
        val weekly = Weekly(
            plays = week.size,
            minutes = (week.sumOf { it.playedMs } / 60_000L).toInt(),
            topArtist = topAName, topSong = topS,
            newArtists = wkArtists.map { it.mainArtist }.toSet().count { it !in before },
            changePct = if (prevWeek.isEmpty()) null else ((week.size - prevWeek.size) * 100 / prevWeek.size),
        )
        return Profile(xp, level, ((xp - cur).toDouble() / (next - cur)).coerceIn(0.0, 1.0), next - xp,
            current, best, totalMinutes, artists.size, badges, weekly)
    }
}
