/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** "כמו X אבל במזרחית / רמיקס / יותר שקט": query rewriting + a filter on the results. */
object QueryRewriter {
    enum class Modifier(val he: String, val words: List<String>) {
        MIZRAHI("במזרחית", listOf("מזרחי", "מזרחית", "במזרחית", "mizrahi")),
        REMIX("רמיקס", listOf("רמיקס", "remix", "רימיקס")),
        QUIETER("יותר שקט", listOf("שקט", "רגוע", "אקוסטי", "quiet", "calm", "acoustic", "chill")),
        ENERGETIC("יותר אנרגטי", listOf("אנרגטי", "קצבי", "חזק", "energetic", "hype")),
        EDM("ב-EDM", listOf("edm", "אלקטרוני", "electronic", "house")),
        INSTRUMENTAL("בלי מילים", listOf("בלי מילים", "אינסטרומנטלי", "instrumental")),
        LIVE("בהופעה", listOf("לייב", "הופעה", "live")),
        SLOWED("slowed", listOf("slowed", "מואט", "reverb")),
        PHONK("בפאנק", listOf("פאנק", "phonk")),
    }

    data class Rewrite(val base: String, val modifier: Modifier?)

    private val likeRe = Regex("^\\s*(?:כמו|like)\\s+(.+?)\\s+(?:אבל|but)\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun parse(text: String): Rewrite {
        val m = likeRe.find(text.trim()) ?: return Rewrite(text.trim(), null)
        val base = m.groupValues[1].trim()
        val rest = m.groupValues[2].lowercase()
        val mod = Modifier.entries.firstOrNull { md -> md.words.any { rest.contains(it) } }
        return Rewrite(base, mod)
    }

    fun queries(r: Rewrite): List<String> {
        val x = r.base
        return when (r.modifier) {
            null -> listOf(x)
            Modifier.MIZRAHI -> listOf("$x מזרחית", "$x קאבר מזרחי", "שירים מזרחיים כמו $x", "מזרחית $x")
            Modifier.REMIX -> listOf("$x remix", "$x רמיקס", "$x club mix")
            Modifier.QUIETER -> listOf("$x acoustic", "$x אקוסטי", "$x piano", "$x slow version")
            Modifier.ENERGETIC -> listOf("$x remix", "$x sped up", "$x party mix", "$x אנרגטי")
            Modifier.EDM -> listOf("$x edm remix", "$x house remix", "$x festival mix")
            Modifier.INSTRUMENTAL -> listOf("$x instrumental", "$x karaoke", "$x piano cover")
            Modifier.LIVE -> listOf("$x live", "$x לייב", "$x בהופעה")
            Modifier.SLOWED -> listOf("$x slowed reverb", "$x slowed")
            Modifier.PHONK -> listOf("$x phonk remix", "$x phonk", "phonk like $x")
        }
    }

    /** Does a result fit the modifier? seed = the vibe of the original X (when known). */
    fun accepts(r: Rewrite, candidate: Vibe, title: String, seed: Vibe?): Boolean {
        val t = title.lowercase()
        return when (r.modifier) {
            null -> true
            Modifier.MIZRAHI -> candidate.mizrahi >= 0.5
            Modifier.REMIX -> VibeTag.REMIX in candidate.tags || t.contains("mix") || t.contains("edit")
            Modifier.QUIETER -> candidate.energy < (seed?.energy ?: 0.6) - 0.05 || VibeTag.ACOUSTIC in candidate.tags
            Modifier.ENERGETIC -> candidate.energy > (seed?.energy ?: 0.5) + 0.05
            Modifier.EDM -> VibeTag.EDM in candidate.tags || VibeTag.REMIX in candidate.tags || candidate.mizrahi < 0.3
            Modifier.INSTRUMENTAL -> listOf("instrumental", "karaoke", "piano", "קריוקי", "אינסטרומנטלי").any { t.contains(it) }
            Modifier.LIVE -> VibeTag.LIVE in candidate.tags
            Modifier.SLOWED -> VibeTag.SLOWED in candidate.tags
            Modifier.PHONK -> VibeTag.PHONK in candidate.tags
        }
    }
}

/** "מה שמעתי באותו יום לפני שנה / חודש / שבוע" from local history. */
object TimeMachine {
    enum class Back(val he: String) { WEEK("לפני שבוע"), MONTH("לפני חודש"), YEAR("לפני שנה") }

    data class Result(val date: LocalDate, val exact: Boolean, val tracks: List<Pair<TrackMeta, Int>>, val minutes: Int)

    fun targetDate(nowTs: Long, back: Back): LocalDate {
        val today = DayClock.date(nowTs)
        return when (back) { Back.WEEK -> today.minusWeeks(1); Back.MONTH -> today.minusMonths(1); Back.YEAR -> today.minusYears(1) }
    }

    /** Tracks played that day (meaningful plays only), most played first. Falls back to the nearest day within ±[slackDays]. */
    fun lookup(h: History, nowTs: Long, back: Back, slackDays: Int = 3, minPlayedMs: Long = 30_000L): Result {
        val target = targetDate(nowTs, back)
        val byDay = h.plays.filter { it.playedMs >= minPlayedMs }.groupBy { DayClock.date(it.timestampMs) }
        var day = target; var exact = true
        if (byDay[target].isNullOrEmpty()) {
            exact = false
            day = (1..slackDays).flatMap { listOf(target.minusDays(it.toLong()), target.plusDays(it.toLong())) }
                .firstOrNull { !byDay[it].isNullOrEmpty() } ?: target
        }
        val plays = byDay[day].orEmpty()
        val counts = plays.groupingBy { it.trackId }.eachCount()
        val first = plays.groupBy { it.trackId }.mapValues { e -> e.value.minOf { it.timestampMs } }
        val tracks = counts.entries.mapNotNull { (id, n) -> h.tracks[id]?.let { it to n } }
            .sortedWith(compareByDescending<Pair<TrackMeta, Int>> { it.second }.thenBy { first[it.first.id] ?: 0L })
        return Result(day, exact && plays.isNotEmpty(), tracks, (plays.sumOf { it.playedMs } / 60_000L).toInt())
    }
}

/** Album-art colour buckets for "search by colour 🟣". */
object ColorBuckets {
    enum class Bucket(val he: String, val emoji: String, val rgb: Int) {
        RED("אדום", "🔴", 0xE53935), ORANGE("כתום", "🟠", 0xFB8C00), YELLOW("צהוב", "🟡", 0xFDD835),
        GREEN("ירוק", "🟢", 0x43A047), CYAN("טורקיז", "🩵", 0x00ACC1), BLUE("כחול", "🔵", 0x1E88E5),
        PURPLE("סגול", "🟣", 0x8E24AA), PINK("ורוד", "🩷", 0xEC407A), BLACK("שחור", "⚫", 0x212121),
        WHITE("לבן", "⚪", 0xEEEEEE), GRAY("אפור", "🩶", 0x9E9E9E),
    }

    fun bucketOf(rgb: Int): Bucket {
        val r = (rgb shr 16 and 0xFF) / 255.0; val g = (rgb shr 8 and 0xFF) / 255.0; val b = (rgb and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val v = mx; val s = if (mx == 0.0) 0.0 else d / mx
        if (v < 0.18) return Bucket.BLACK
        if (s < 0.18) return if (v > 0.82) Bucket.WHITE else if (v < 0.3) Bucket.BLACK else Bucket.GRAY
        var h = when (mx) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * (((b - r) / d) + 2)
            else -> 60 * (((r - g) / d) + 4)
        }
        if (h < 0) h += 360.0
        return when {
            h < 15 || h >= 345 -> Bucket.RED
            h < 40 -> Bucket.ORANGE
            h < 70 -> Bucket.YELLOW
            h < 160 -> Bucket.GREEN
            h < 195 -> Bucket.CYAN
            h < 255 -> Bucket.BLUE
            h < 295 -> Bucket.PURPLE
            else -> Bucket.PINK
        }
    }
}

/** Roulette: a truly random YouTube Music search seed. */
object Roulette {
    private val words = listOf(
        "ירח", "ים", "אהבה", "לילה", "שמש", "רחוב", "חלום", "אש", "רוח", "גשם", "midnight", "ocean", "neon", "desert", "gold", "fire",
        "paradise", "electric", "tokyo", "paris", "habibi", "corazón", "amore", "sunset", "thunder", "galaxy", "velvet", "cherry",
        "storm", "dream", "highway", "dance", "secret", "crystal", "wild", "lonely", "summer", "winter", "river", "mountain",
    )
    private val genres = listOf("", "", "pop", "rock", "jazz", "funk", "house", "phonk", "מזרחית", "rap", "reggae", "soul", "disco", "lofi",
        "techno", "folk", "latin", "k-pop", "afrobeats", "metal", "synthwave", "blues", "trance")

    fun seed(random: kotlin.random.Random): String {
        val parts = mutableListOf(words.random(random))
        if (random.nextInt(3) == 0) parts += words.random(random)
        val g = genres.random(random); if (g.isNotEmpty()) parts += g
        if (random.nextInt(4) == 0) parts += (1965 + random.nextInt(61)).toString()
        return parts.shuffled(random).joinToString(" ")
    }
}
