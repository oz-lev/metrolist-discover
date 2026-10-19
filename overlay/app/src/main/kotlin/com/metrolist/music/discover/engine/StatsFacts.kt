/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import kotlin.math.roundToInt

/**
 * "Stats bubble" facts: one surprising, exactly counted fact about the user's own listening.
 * Counts use only meaningful plays (not an early skip by this user's own skip cut-off).
 */
object StatsFacts {
    enum class Type { TOP_ARTIST_MONTH, TOP_SONG_MONTH, PEAK_HOUR, STREAK, NEW_ARTIST_WEEK, MOST_SKIPPED, HOURS_MONTH, NIGHT_VS_MORNING, COMEBACK, HEBREW_SHARE }

    data class Fact(val type: Type, val he: String, val en: String, val value: Double)

    private const val DAY = 86_400_000L
    private fun hebrew(s: String) = s.any { it in '\u0590'..'\u05FF' }
    private fun le(name: String) = if (hebrew(name.take(1))) "ל$name" else "ל-$name"

    fun compute(h: History, nowMs: Long, cfg: DiscoverConfig = DiscoverConfig()): List<Fact> {
        val th = ProfileBuilder.thresholds(h, cfg)
        val plays = h.sortedPlays.filter { it.timestampMs <= nowMs }
        val good = plays.filter { ProfileBuilder.outcome(it, h, th.skipFraction, th.skipSeconds, th.fullFraction) != Outcome.SKIP }
        if (good.size < 10) return emptyList()
        val out = ArrayList<Fact>()
        val monthStart = nowMs - 30 * DAY
        val month = good.filter { it.timestampMs >= monthStart }
        fun title(id: String) = h.tracks[id]?.title ?: id
        fun artistName(id: String) = h.tracks.values.firstOrNull { it.artistIds.firstOrNull() == id }?.artistNames?.firstOrNull() ?: id

        month.groupingBy { h.tracks[it.trackId]?.mainArtist ?: "?" }.eachCount().filterKeys { it != "?" }
            .maxByOrNull { it.value }?.let { (a, n) ->
                if (n >= 5) out += Fact(Type.TOP_ARTIST_MONTH, "בחודש האחרון האזנת $n פעמים ${le(artistName(a))}", "In the last 30 days you played ${artistName(a)} $n times", n.toDouble())
            }
        month.groupingBy { it.trackId }.eachCount().maxByOrNull { it.value }?.let { (t, n) ->
            if (n >= 3) out += Fact(Type.TOP_SONG_MONTH, "האזנת $n פעמים לשיר \u2018${title(t)}\u2019 בחודש האחרון", "You played \u2018${title(t)}\u2019 $n times in the last 30 days", n.toDouble())
        }
        val byHour = good.groupingBy { ((it.timestampMs % DAY) / 3_600_000L).toInt() }.eachCount()
        byHour.maxByOrNull { it.value }?.let { (hr, n) ->
            val pct = (100.0 * n / good.size).roundToInt()
            out += Fact(Type.PEAK_HOUR, "השעה הכי מוזיקלית שלך: %02d:00 (%d%% מההאזנות)".format(hr, pct), "Your peak listening hour: %02d:00 (%d%% of plays)".format(hr, pct), hr.toDouble())
        }
        val days = good.map { it.timestampMs / DAY }.toSortedSet()
        var streak = 0
        var d = nowMs / DAY
        if (d !in days) d -= 1
        while (d in days) { streak++; d-- }
        if (streak >= 3) out += Fact(Type.STREAK, "רצף של $streak ימים ברציפות עם מוזיקה \uD83D\uDD25", "A $streak-day listening streak", streak.toDouble())

        val firstSeen = HashMap<String, Long>()
        for (e in good) h.tracks[e.trackId]?.mainArtist?.let { firstSeen.putIfAbsent(it, e.timestampMs) }
        val weekStart = nowMs - 7 * DAY
        good.filter { it.timestampMs >= weekStart }.mapNotNull { h.tracks[it.trackId]?.mainArtist }
            .filter { (firstSeen[it] ?: 0) >= weekStart }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { (a, n) ->
                out += Fact(Type.NEW_ARTIST_WEEK, "גילית השבוע את ${artistName(a)} ($n האזנות)", "New this week: ${artistName(a)} ($n plays)", n.toDouble())
            }
        plays.filter { it.timestampMs >= monthStart && ProfileBuilder.outcome(it, h, th.skipFraction, th.skipSeconds, th.fullFraction) == Outcome.SKIP }
            .groupingBy { it.trackId }.eachCount().maxByOrNull { it.value }?.let { (t, n) ->
                if (n >= 2) out += Fact(Type.MOST_SKIPPED, "השיר שהכי דילגת עליו החודש: \u2018${title(t)}\u2019 ($n פעמים)", "Most skipped this month: \u2018${title(t)}\u2019 ($n times)", n.toDouble())
            }
        val hours = month.sumOf { it.playedMs } / 3_600_000.0
        if (hours >= 1) out += Fact(Type.HOURS_MONTH, "%.0f שעות מוזיקה בחודש האחרון".format(hours), "%.0f hours of music in the last 30 days".format(hours), hours)

        fun topIn(r: (Int) -> Boolean) = good.filter { r(((it.timestampMs % DAY) / 3_600_000L).toInt()) }
            .groupingBy { it.trackId }.eachCount().maxByOrNull { it.value }
        val night = topIn { it >= 22 || it < 5 }; val morning = topIn { it in 6..11 }
        if (night != null && morning != null && night.key != morning.key && night.value >= 3 && morning.value >= 3)
            out += Fact(Type.NIGHT_VS_MORNING, "בלילה: \u2018${title(night.key)}\u2019. בבוקר: \u2018${title(morning.key)}\u2019 \uD83C\uDF19\u2600\uFE0F",
                "Night: \u2018${title(night.key)}\u2019. Morning: \u2018${title(morning.key)}\u2019", night.value.toDouble())

        // comeback: an artist played in the last 7 days after a silence of 60+ days
        val lastBefore = HashMap<String, Long>()
        var best: Pair<String, Long>? = null
        for (e in good) {
            val a = h.tracks[e.trackId]?.mainArtist ?: continue
            val prev = lastBefore[a]
            if (e.timestampMs >= weekStart && prev != null) {
                val gap = e.timestampMs - prev
                if (gap >= 60 * DAY && (best == null || gap > best.second)) best = a to gap
            }
            lastBefore[a] = e.timestampMs
        }
        best?.let { (a, gap) ->
            val months = (gap / (30.0 * DAY)).roundToInt()
            out += Fact(Type.COMEBACK, "חזרת ${le(artistName(a))} אחרי $months חודשים", "You came back to ${artistName(a)} after $months months", months.toDouble())
        }
        if (month.size >= 20) {
            val heb = month.count { e -> h.tracks[e.trackId]?.let { hebrew(it.title) || it.artistNames.any(::hebrew) } == true }
            val pct = (100.0 * heb / month.size).roundToInt()
            out += Fact(Type.HEBREW_SHARE, "$pct% מההאזנות שלך החודש בעברית, ${100 - pct}% לועזית", "$pct% of your plays this month were Hebrew songs", pct.toDouble())
        }
        return out
    }

    /** Rotation: never the same fact type twice in a row; cycles through the types. */
    fun pick(facts: List<Fact>, lastType: Type?, counter: Int): Fact? {
        val usable = facts.filter { it.type != lastType }.ifEmpty { facts }
        if (usable.isEmpty()) return null
        return usable[Math.floorMod(counter, usable.size)]
    }
}
