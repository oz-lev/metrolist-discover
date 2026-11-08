/**
 * Metrolist Discover 13.7.0.11 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: the full listening-statistics engine behind the "📊 סטטיסטיקות" screen.
 *
 * Input is the active profile's isolated History (the same loader the recommendation engine uses), so
 * another profile's listens never enter these numbers. Times follow the DayClock convention: an event's
 * timestamp is the local wall clock encoded as UTC millis, recorded when the listen ended.
 */
package com.metrolist.music.discover.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class StatsUnit { WEEK, MONTH, YEAR, ALL }

/** A calendar period in local time. offset 0 = the one that contains now, -1 = the one before it, and so on. */
data class StatsPeriod(val unit: StatsUnit, val offset: Int = 0) {
    val key: String get() = "${unit.name}:$offset"
}

/** [from, to) in DayClock millis. */
data class StatsWindow(val from: Long, val to: Long) {
    operator fun contains(ts: Long): Boolean = ts >= from && ts < to
    val days: Int get() = Math.floorDiv(to - from + DayClock.DAY_MS - 1, DayClock.DAY_MS).toInt()
}

object StatsPeriods {
    private val MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")
    fun monthName(m: Int): String = MONTHS[(m - 1).coerceIn(0, 11)]
    private val SHORT = listOf("ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳")
    fun monthShort(m: Int): String = SHORT[(m - 1).coerceIn(0, 11)]

    /** The week starts on Sunday (the Israeli week). */
    fun startOf(unit: StatsUnit, d: LocalDate): LocalDate = when (unit) {
        StatsUnit.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        StatsUnit.MONTH -> d.withDayOfMonth(1)
        StatsUnit.YEAR -> d.withDayOfYear(1)
        StatsUnit.ALL -> d
    }

    fun shift(d: LocalDate, unit: StatsUnit, n: Int): LocalDate = when (unit) {
        StatsUnit.WEEK -> d.plusWeeks(n.toLong())
        StatsUnit.MONTH -> d.plusMonths(n.toLong())
        StatsUnit.YEAR -> d.plusYears(n.toLong())
        StatsUnit.ALL -> d
    }

    /** All time runs from the first listen's day to the end of today. */
    fun window(p: StatsPeriod, now: Long, firstPlay: Long?): StatsWindow {
        val today = DayClock.date(now)
        if (p.unit == StatsUnit.ALL) {
            val first = firstPlay?.let { DayClock.date(it) } ?: today
            return StatsWindow(DayClock.tsOf(minOf(first, today)), DayClock.tsOf(today.plusDays(1)))
        }
        val s = shift(startOf(p.unit, today), p.unit, p.offset)
        return StatsWindow(DayClock.tsOf(s), DayClock.tsOf(shift(s, p.unit, 1)))
    }

    /**
     * What a period is compared with: the period before it. While the period is still running, the previous one is cut
     * to the same elapsed length (the first 8 days of this month against the first 8 days of last month). All time has none.
     */
    fun previous(p: StatsPeriod, now: Long, firstPlay: Long?): StatsWindow? {
        if (p.unit == StatsUnit.ALL) return null
        val cur = window(p, now, firstPlay)
        val prev = window(p.copy(offset = p.offset - 1), now, firstPlay)
        if (now in cur.from until cur.to) return StatsWindow(prev.from, minOf(prev.to, prev.from + (now - cur.from)))
        return prev
    }

    fun label(p: StatsPeriod, now: Long): String {
        val today = DayClock.date(now)
        return when (p.unit) {
            StatsUnit.ALL -> "כל הזמנים"
            StatsUnit.WEEK -> if (p.offset == 0) "השבוע" else if (p.offset == -1) "שבוע שעבר" else {
                val s = shift(startOf(StatsUnit.WEEK, today), StatsUnit.WEEK, p.offset); val e = s.plusDays(6)
                "${s.dayOfMonth}.${s.monthValue}–${e.dayOfMonth}.${e.monthValue}" + if (e.year != today.year) ".${e.year % 100}" else ""
            }
            StatsUnit.MONTH -> { val s = shift(startOf(StatsUnit.MONTH, today), StatsUnit.MONTH, p.offset); "${monthName(s.monthValue)} ${s.year}" }
            StatsUnit.YEAR -> "${today.year + p.offset}"
        }
    }

    /** Offset of the month / year that contains [d], for jumping straight to it. */
    fun offsetOf(unit: StatsUnit, d: LocalDate, now: Long): Int {
        val today = DayClock.date(now)
        return when (unit) {
            StatsUnit.MONTH -> ((d.year - today.year) * 12 + (d.monthValue - today.monthValue))
            StatsUnit.YEAR -> d.year - today.year
            StatsUnit.WEEK -> (java.time.temporal.ChronoUnit.WEEKS.between(startOf(StatsUnit.WEEK, today), startOf(StatsUnit.WEEK, d))).toInt()
            StatsUnit.ALL -> 0
        }
    }

    /** % change, null when there is nothing to compare with. */
    fun changePct(cur: Long, prev: Long): Int? = if (prev <= 0L) null else (((cur - prev) * 100.0) / prev).roundToInt()
}

/** One listening session of one song, as the statistics see it. */
class StatRow(
    val track: TrackMeta,
    /** when the listen ended (the recorded time) */
    val end: Long,
    /** listened millis: the real played time when recorded, else the song length (estimated = true) */
    val ms: Long,
    val estimated: Boolean,
    val skip: Boolean,
    val full: Boolean,
    /** a "play": 30 s or more (the Spotify convention, as in Replay) */
    val counted: Boolean,
    val artistKey: String,
    val artistName: String,
    val albumKey: String?,
    /** the song's length is known, so completion can be judged */
    val known: Boolean = true,
) {
    val start: Long get() = end - ms
}

data class ArtistRow(
    val key: String, val name: String, val ms: Long, val plays: Int, val songs: Int, val share: Double,
    val firstHeard: Long?, val lastHeard: Long?, val skips: Int, val events: Int, val thumb: String?,
) { val skipRate: Double get() = if (events > 0) skips.toDouble() / events else 0.0 }

data class SongRow(
    val track: TrackMeta, val ms: Long, val plays: Int, val skips: Int, val events: Int,
    val firstHeard: Long?, val lastHeard: Long?, val artistKey: String,
) { val skipRate: Double get() = if (events > 0) skips.toDouble() / events else 0.0 }

data class AlbumRow(val key: String, val name: String, val artist: String, val ms: Long, val plays: Int, val songs: Int, val thumb: String?, val share: Double)

data class StatBar(val label: String, val from: Long, val value: Long)

data class MonthTop(val month: YearMonth, val artist: String?, val ms: Long)

data class Streak(val days: Int, val from: LocalDate?, val to: LocalDate?)

data class StatSession(val start: Long, val end: Long, val ms: Long, val songs: Int) { val spanMs: Long get() = end - start }

data class Overview(
    val totalMs: Long, val plays: Int, val events: Int, val songs: Int, val artists: Int, val albums: Int,
    val days: Int, val activeDays: Int, val dailyAvgMs: Long, val prevTotalMs: Long?, val prevPlays: Int?,
    val timeChangePct: Int?, val playsChangePct: Int?, val estimatedMs: Long, val skips: Int,
)

class StatsReport(
    val period: StatsPeriod,
    val label: String,
    val window: StatsWindow,
    val overview: Overview,
    val artists: List<ArtistRow>,
    val songs: List<SongRow>,
    val albums: List<AlbumRow>,
    val daily: List<StatBar>,
    val weekly: List<StatBar>,
    val monthly: List<StatBar>,
    val newArtistsPerMonth: List<StatBar>,
    val monthTops: List<MonthTop>,
    /** minutes, [weekday 0 = Sunday .. 6 = Saturday][hour 0..23] */
    val heat: Array<LongArray>,
    val longestStreak: Streak,
    val longestSession: StatSession?,
    val sessions: Int,
    val mostSkippedSongs: List<SongRow>,
    val mostSkippedArtists: List<ArtistRow>,
    /** finished listens / listens of a known length */
    val completionRate: Double?,
    val newMs: Long,
    val familiarMs: Long,
    val discovered: List<ArtistRow>,
    val oneHit: List<ArtistRow>,
) {
    val newShare: Double get() = if (newMs + familiarMs > 0) newMs.toDouble() / (newMs + familiarMs) else 0.0
    val isEmpty: Boolean get() = overview.events == 0
}

data class ArtistDetail(
    val key: String, val name: String, val totalMs: Long, val plays: Int, val songs: Int,
    val firstHeard: Long?, val lastHeard: Long?, val topSongs: List<SongRow>, val monthly: List<StatBar>, val skipRate: Double,
)

data class SongDetail(val row: SongRow, val totalMs: Long, val monthly: List<StatBar>, val fullRate: Double?)

/**
 * Everything the statistics need from one profile's history, prepared once (classification, first-heard dates);
 * a [report] per period is then one pass over the rows.
 */
class StatsIndex(val rows: List<StatRow>, val firstTrack: Map<String, Long>, val firstArtist: Map<String, Long>,
                 val lastTrack: Map<String, Long>, val lastArtist: Map<String, Long>, val artistPlaysAll: Map<String, Int>,
                 val artistEventsAll: Map<String, Int>,
                 val albumNames: Map<String, String>) {
    val firstPlay: Long? get() = rows.firstOrNull()?.end
}

object FullStats {
    const val SESSION_GAP_MS = 30 * 60_000L
    const val PLAY_MS = 30_000L

    fun artistKeyOf(t: TrackMeta): String = t.mainArtist
    fun artistNameOf(t: TrackMeta): String =
        t.artistNames.firstOrNull { n -> n.any { it.isLetterOrDigit() } } ?: t.artistIds.firstOrNull() ?: "?"

    /**
     * [albumNameBySong]: album title per song id (Metrolist keeps it on the song row). An album is keyed by its id,
     * or by its title when the song has no album id.
     */
    fun index(h: History, albumNameBySong: Map<String, String> = emptyMap(), cfg: DiscoverConfig = DiscoverConfig()): StatsIndex {
        val th = ProfileBuilder.thresholds(h, cfg)
        val rows = ArrayList<StatRow>(h.plays.size)
        val albumNames = HashMap<String, String>()
        for (e in h.sortedPlays) {
            val t = h.tracks[e.trackId] ?: continue
            val dur = h.duration(e)
            val estimated = e.playedMs <= 0L
            val ms = if (!estimated) e.playedMs else maxOf(0L, dur)
            val o = ProfileBuilder.outcome(e.copy(playedMs = ms), h, th.skipFraction, th.skipSeconds, th.fullFraction)
            val an = albumNameBySong[t.id]?.takeIf { it.isNotBlank() }
            val ak = t.albumId?.takeIf { it.isNotBlank() } ?: an?.let { "n:$it" }
            if (ak != null && an != null) albumNames.putIfAbsent(ak, an)
            rows += StatRow(t, e.timestampMs, ms, estimated, o == Outcome.SKIP, o == Outcome.FULL, ms >= PLAY_MS,
                artistKeyOf(t), artistNameOf(t), ak, dur > 0)
        }
        val firstT = HashMap<String, Long>(); val firstA = HashMap<String, Long>()
        val lastT = HashMap<String, Long>(); val lastA = HashMap<String, Long>(); val aPlays = HashMap<String, Int>(); val aEvents = HashMap<String, Int>()
        // first heard = the first real play (30 s+); a song only ever skipped counts from its first session
        for (r in rows) {
            if (r.counted) {
                firstT.putIfAbsent(r.track.id, r.end); firstA.putIfAbsent(r.artistKey, r.end)
                aPlays[r.artistKey] = (aPlays[r.artistKey] ?: 0) + 1
            }
            lastT[r.track.id] = r.end; lastA[r.artistKey] = r.end
            aEvents[r.artistKey] = (aEvents[r.artistKey] ?: 0) + 1
        }
        for (r in rows) { firstT.putIfAbsent(r.track.id, r.end); firstA.putIfAbsent(r.artistKey, r.end) }
        return StatsIndex(rows, firstT, firstA, lastT, lastA, aPlays, aEvents, albumNames)
    }

    private class Agg { var ms = 0L; var plays = 0; var events = 0; var skips = 0; val songs = HashSet<String>(); var thumb: String? = null; var name = "" }

    fun report(ix: StatsIndex, p: StatsPeriod, now: Long): StatsReport {
        val w = StatsPeriods.window(p, now, ix.firstPlay)
        val prevW = StatsPeriods.previous(p, now, ix.firstPlay)
        val inW = ix.rows.filter { it.end in w }
        var total = 0L; var plays = 0; var skips = 0; var est = 0L; var fullN = 0; var knownN = 0
        val byArtist = HashMap<String, Agg>(); val bySong = HashMap<String, Agg>(); val byAlbum = HashMap<String, Agg>()
        val heat = Array(7) { LongArray(24) }
        val dayMs = HashMap<LocalDate, Long>()
        var newMs = 0L; var famMs = 0L
        for (r in inW) {
            total += r.ms; if (r.counted) plays++; if (r.skip) skips++; if (r.estimated) est += r.ms
            if (r.known) { knownN++; if (r.full) fullN++ }
            fun put(m: HashMap<String, Agg>, k: String, name: String) = m.getOrPut(k) { Agg().also { it.name = name } }.let { a ->
                a.ms += r.ms; a.events++; if (r.counted) a.plays++; if (r.skip) a.skips++; a.songs += r.track.id
                if (a.thumb == null) a.thumb = r.track.thumbnailUrl
            }
            put(byArtist, r.artistKey, r.artistName)
            put(bySong, r.track.id, r.track.title)
            r.albumKey?.let { k -> put(byAlbum, k, ix.albumNames[k] ?: "") }
            val dt = DayClock.dateTime(r.end)
            heat[dt.dayOfWeek.value % 7][dt.hour] += r.ms
            val d = dt.toLocalDate(); dayMs[d] = (dayMs[d] ?: 0L) + r.ms
            val first = ix.firstTrack[r.track.id]
            if (first != null && first >= w.from) newMs += r.ms else famMs += r.ms
        }
        val prevRows = prevW?.let { pw -> ix.rows.filter { it.end in pw } }
        val prevTotal = prevRows?.sumOf { it.ms }
        val prevPlays = prevRows?.count { it.counted }
        val endTs = minOf(w.to, DayClock.tsOf(DayClock.date(now).plusDays(1)))
        // a period that began before this profile's first listen counts (and charts) from that first day
        val startTs = maxOf(w.from, ix.firstPlay?.let { DayClock.tsOf(DayClock.date(it)) } ?: w.from).coerceAtMost(maxOf(w.from, endTs - 1))
        val elapsedDays = maxOf(1, Math.floorDiv(endTs - startTs + DayClock.DAY_MS - 1, DayClock.DAY_MS).toInt())
        val ov = Overview(total, plays, inW.size, bySong.size, byArtist.size, byAlbum.size, elapsedDays, dayMs.size,
            total / elapsedDays, prevTotal, prevPlays,
            prevTotal?.let { StatsPeriods.changePct(total, it) }, if (prevPlays != null) StatsPeriods.changePct(plays.toLong(), prevPlays.toLong()) else null,
            est, skips)

        val share = { ms: Long -> if (total > 0) ms.toDouble() / total else 0.0 }
        val artists = byArtist.entries.map { (k, a) ->
            ArtistRow(k, a.name, a.ms, a.plays, a.songs.size, share(a.ms), ix.firstArtist[k], ix.lastArtist[k], a.skips, a.events, a.thumb)
        }.sortedWith(compareByDescending<ArtistRow> { it.ms }.thenByDescending { it.plays }.thenBy { it.name })
        val trackById = HashMap<String, TrackMeta>(); for (r in inW) trackById.putIfAbsent(r.track.id, r.track)
        val songs = bySong.entries.map { (k, a) ->
            val t = trackById.getValue(k)
            SongRow(t, a.ms, a.plays, a.skips, a.events, ix.firstTrack[k], ix.lastTrack[k], artistKeyOf(t))
        }.sortedWith(compareByDescending<SongRow> { it.plays }.thenByDescending { it.ms }.thenBy { it.track.title })
        val albumArtist = HashMap<String, HashMap<String, Long>>()
        for (r in inW) r.albumKey?.let { k -> albumArtist.getOrPut(k) { HashMap() }.let { m -> m[r.artistName] = (m[r.artistName] ?: 0L) + r.ms } }
        val albums = byAlbum.entries.map { (k, a) ->
            AlbumRow(k, a.name.ifBlank { "?" }, albumArtist[k]?.maxByOrNull { it.value }?.key ?: "", a.ms, a.plays, a.songs.size, a.thumb, share(a.ms))
        }.sortedWith(compareByDescending<AlbumRow> { it.ms }.thenBy { it.name })

        // trends inside the window (never past today)
        val firstDay = DayClock.date(startTs)
        val lastDay = DayClock.date(endTs - 1)
        val daily = ArrayList<StatBar>()
        run { var d = firstDay; while (!d.isAfter(lastDay) && daily.size < 400) { daily += StatBar("${d.dayOfMonth}.${d.monthValue}", DayClock.tsOf(d), dayMs[d] ?: 0L); d = d.plusDays(1) } }
        val weekly = ArrayList<StatBar>()
        run {
            var s = StatsPeriods.startOf(StatsUnit.WEEK, firstDay)
            while (!s.isAfter(lastDay) && weekly.size < 600) {
                var v = 0L; for (i in 0 until 7) v += dayMs[s.plusDays(i.toLong())] ?: 0L
                weekly += StatBar("${s.dayOfMonth}.${s.monthValue}", DayClock.tsOf(s), v); s = s.plusWeeks(1)
            }
        }
        val months = monthsOf(p, firstDay, lastDay, ix)
        val monthMs = HashMap<YearMonth, Long>(); for ((d, v) in dayMs) YearMonth.from(d).let { monthMs[it] = (monthMs[it] ?: 0L) + v }
        val monthly = ArrayList<StatBar>()
        run { var m = YearMonth.from(firstDay); val e = YearMonth.from(lastDay); while (!m.isAfter(e) && monthly.size < 600) { monthly += monthBar(m, monthMs[m] ?: 0L); m = m.plusMonths(1) } }
        // new artists per month and the top artist of each month (a "your month" timeline)
        val newPerMonth = HashMap<YearMonth, Long>()
        for ((_, ts) in ix.firstArtist) YearMonth.from(DayClock.date(ts)).let { newPerMonth[it] = (newPerMonth[it] ?: 0L) + 1 }
        val newArtistsPerMonth = months.map { monthBar(it, newPerMonth[it] ?: 0L) }
        val monthTops = topPerMonth(ix, months)

        // habits
        val streak = longestStreak(dayMs.keys)
        val sess = sessions(inW)
        val longest = sess.maxByOrNull { it.spanMs }
        val mostSkippedSongs = songs.filter { it.skips >= 2 }.sortedWith(compareByDescending<SongRow> { it.skips }.thenByDescending { it.skipRate }).take(20)
        val mostSkippedArtists = artists.filter { it.skips >= 3 }.sortedWith(compareByDescending<ArtistRow> { it.skips }.thenByDescending { it.skipRate }).take(20)

        // discovery
        val discovered = artists.filter { (it.firstHeard ?: Long.MIN_VALUE) in w }.sortedBy { it.firstHeard }
        // heard exactly once, ever (one listening session in this profile's whole history)
        val oneHit = artists.filter { (ix.artistEventsAll[it.key] ?: 0) == 1 }.sortedBy { it.firstHeard }

        return StatsReport(p, StatsPeriods.label(p, now), w, ov, artists, songs, albums, daily, weekly, monthly, newArtistsPerMonth, monthTops,
            heat.map { row -> LongArray(24) { row[it] / 60_000L } }.toTypedArray(), streak, longest, sess.size,
            mostSkippedSongs, mostSkippedArtists, if (knownN > 0) fullN.toDouble() / knownN else null, newMs, famMs, discovered, oneHit)
    }

    private fun monthBar(m: YearMonth, v: Long) = StatBar("${StatsPeriods.monthShort(m.monthValue)}${if (m.monthValue == 1) " " + (m.year % 100) else ""}", DayClock.tsOf(m.atDay(1)), v)

    /** The months the month-by-month cards cover: the period's own months, or the last 12 up to it for a week or a month. */
    fun monthsOf(p: StatsPeriod, firstDay: LocalDate, lastDay: LocalDate, ix: StatsIndex): List<YearMonth> {
        val end = YearMonth.from(lastDay)
        var start = when (p.unit) {
            StatsUnit.YEAR, StatsUnit.ALL -> YearMonth.from(firstDay)
            else -> end.minusMonths(11)
        }
        ix.firstPlay?.let { fp -> val f = YearMonth.from(DayClock.date(fp)); if (start.isBefore(f)) start = f }
        if (start.isAfter(end)) start = end
        val out = ArrayList<YearMonth>(); var m = start
        while (!m.isAfter(end) && out.size < 600) { out += m; m = m.plusMonths(1) }
        return out
    }

    fun topPerMonth(ix: StatsIndex, months: List<YearMonth>): List<MonthTop> {
        if (months.isEmpty()) return emptyList()
        val set = months.toHashSet()
        val per = HashMap<YearMonth, HashMap<String, Long>>()
        val names = HashMap<String, String>()
        for (r in ix.rows) {
            val m = YearMonth.from(DayClock.date(r.end)); if (m !in set) continue
            names.putIfAbsent(r.artistKey, r.artistName)
            per.getOrPut(m) { HashMap() }.let { a -> a[r.artistKey] = (a[r.artistKey] ?: 0L) + r.ms }
        }
        return months.map { m ->
            val best = per[m]?.entries?.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })?.firstOrNull()
            MonthTop(m, best?.let { names[it.key] }, best?.value ?: 0L)
        }
    }

    /** Longest run of consecutive days with any listening. */
    fun longestStreak(days: Collection<LocalDate>): Streak {
        if (days.isEmpty()) return Streak(0, null, null)
        val sorted = days.toSortedSet().toList()
        var best = 1; var bFrom = sorted[0]; var bTo = sorted[0]
        var run = 1; var rFrom = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1].plusDays(1)) run++ else { run = 1; rFrom = sorted[i] }
            if (run > best) { best = run; bFrom = rFrom; bTo = sorted[i] }
        }
        return Streak(best, bFrom, bTo)
    }

    /** Listening sessions: a pause of [SESSION_GAP_MS] or more between one song's end and the next one's start opens a new session. */
    fun sessions(rows: List<StatRow>): List<StatSession> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedBy { it.start }
        val out = ArrayList<StatSession>()
        var s = sorted[0].start; var e = sorted[0].end; var ms = sorted[0].ms; var n = 1
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            if (r.start - e >= SESSION_GAP_MS) { out += StatSession(s, e, ms, n); s = r.start; e = r.end; ms = r.ms; n = 1 }
            else { e = maxOf(e, r.end); ms += r.ms; n++ }
        }
        out += StatSession(s, e, ms, n)
        return out
    }

    fun songRowsOf(rows: List<StatRow>, ix: StatsIndex): List<SongRow> {
        val m = LinkedHashMap<String, MutableList<StatRow>>()
        for (r in rows) m.getOrPut(r.track.id) { ArrayList() } += r
        return m.map { (id, l) ->
            SongRow(l[0].track, l.sumOf { it.ms }, l.count { it.counted }, l.count { it.skip }, l.size, ix.firstTrack[id], ix.lastTrack[id], l[0].artistKey)
        }.sortedWith(compareByDescending<SongRow> { it.plays }.thenByDescending { it.ms }.thenBy { it.track.title })
    }

    private fun monthlyOf(rows: List<StatRow>, now: Long, value: (StatRow) -> Long, maxMonths: Int = 24): List<StatBar> {
        if (rows.isEmpty()) return emptyList()
        val per = HashMap<YearMonth, Long>()
        for (r in rows) YearMonth.from(DayClock.date(r.end)).let { per[it] = (per[it] ?: 0L) + value(r) }
        val end = YearMonth.from(DayClock.date(now))
        var start = YearMonth.from(DayClock.date(rows.minOf { it.end }))
        if (start.isBefore(end.minusMonths((maxMonths - 1).toLong()))) start = end.minusMonths((maxMonths - 1).toLong())
        val out = ArrayList<StatBar>(); var m = start
        while (!m.isAfter(end)) { out += monthBar(m, per[m] ?: 0L); m = m.plusMonths(1) }
        return out
    }

    /** All-time detail of one artist (minutes per month for the last 24 months). */
    fun artist(ix: StatsIndex, key: String, now: Long): ArtistDetail? {
        val rows = ix.rows.filter { it.artistKey == key }
        if (rows.isEmpty()) return null
        val songs = songRowsOf(rows, ix)
        return ArtistDetail(key, rows[0].artistName, rows.sumOf { it.ms }, rows.count { it.counted }, songs.size,
            ix.firstArtist[key], ix.lastArtist[key], songs.take(25), monthlyOf(rows, now, { it.ms / 60_000L }), rows.count { it.skip }.toDouble() / rows.size)
    }

    /** All-time detail of one song (plays per month for the last 24 months). */
    fun song(ix: StatsIndex, trackId: String, now: Long): SongDetail? {
        val rows = ix.rows.filter { it.track.id == trackId }
        if (rows.isEmpty()) return null
        val row = songRowsOf(rows, ix).first()
        val known = rows.filter { it.known }
        return SongDetail(row, rows.sumOf { it.ms }, monthlyOf(rows, now, { if (it.counted) 1L else 0L }),
            if (known.isEmpty()) null else known.count { it.full }.toDouble() / known.size)
    }

    fun minutes(ms: Long): Long = ms / 60_000L

    /** "12 שע׳ 5 דק׳" / "45 דק׳" */
    fun hm(ms: Long): String {
        val m = ms / 60_000L
        return if (m >= 60) "${m / 60} שע׳ ${m % 60} דק׳" else "$m דק׳"
    }
}

/** CSV of one profile's plays (UTF-8 with a BOM so spreadsheet apps read Hebrew right; RFC 4180 quoting). */
object StatsCsv {
    const val HEADER = "date,time,song,artist,album,seconds,skipped"

    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun line(r: StatRow, album: String): String {
        val dt = DayClock.dateTime(r.end)
        return listOf(
            String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", dt.year, dt.monthValue, dt.dayOfMonth),
            String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", dt.hour, dt.minute, dt.second),
            escape(r.track.title), escape(r.artistName), escape(album),
            (r.ms / 1000L).toString(), if (r.skip) "1" else "0",
        ).joinToString(",")
    }

    /** Rows inside [w] (null = all), oldest first. */
    fun build(ix: StatsIndex, w: StatsWindow? = null, bom: Boolean = true): String {
        val b = StringBuilder()
        if (bom) b.append('\uFEFF')
        b.append(HEADER).append("\r\n")
        for (r in ix.rows) {
            if (w != null && r.end !in w) continue
            b.append(line(r, r.albumKey?.let { ix.albumNames[it] } ?: "")).append("\r\n")
        }
        return b.toString()
    }
}
