/**
 * Metrolist Discover 13.7.0.13 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: the extra statistics of the "📊 סטטיסטיקות" screen, all computed from the active
 * profile's StatsIndex (FullStats.index over the isolated history), so another profile never enters these numbers.
 *
 *  - recap: a Wrapped-style summary of one period (top artist, top song, minutes, top genre, streak)
 *  - genres / moods: inferred from Discover's own vibe estimate (artist knowledge, title keywords, learned corrections);
 *    they are NOT official tags, and the UI says so
 *  - completion: finished / partly heard / skipped, overall and per top song and artist
 *  - compare: two periods side by side
 *  - milestones: 1,000th play, 100 hours, first listen of a now-top artist, longest streak... with dates
 *  - forgotten favourites: songs played a lot and not heard for 60+ days
 *  - goal: the daily listening goal, its streak, best streak and the last days
 *  - new-vs-repeat share per month, artist loyalty
 */
package com.metrolist.music.discover.engine

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

// ====================================================================== genres and moods

enum class StatsGenre(val he: String) {
    MIZRAHI("מזרחית"), PHONK("פונק"), EDM("EDM / אלקטרוני"), RAP("ראפ / היפ-הופ"), ROCK("רוק"), LOFI("לו-פיי"),
    CINEMATIC("פסקול / קולנועי"), ACOUSTIC("אקוסטי"), MEDLEY("מחרוזות"), LIVE("הופעות חיות"), REMIX("רמיקסים"),
    SLOWED("Slowed / Reverb"), SPED_UP("Sped up"), HEBREW("עברית (כללי)"), OTHER("לא זוהה"),
}

enum class StatsMood(val he: String) { PARTY("🎉 מסיבה"), INTENSE("⚡ אנרגטי"), HAPPY("😊 שמח"), BALANCED("🙂 מאוזן"), CHILL("😌 רגוע"), SAD("🌧️ עצוב") }

data class ShareRow(val key: String, val he: String, val ms: Long, val share: Double, val songs: Int, val topArtist: String?)

data class GenreReport(
    val genres: List<ShareRow>,
    val moods: List<ShareRow>,
    /** share of the period's time whose genre rests on real evidence (a known artist, a title keyword, a correction) */
    val evidenceShare: Double,
    val totalMs: Long,
) {
    val topGenre: ShareRow? get() = genres.firstOrNull { it.key != StatsGenre.OTHER.name }
}

// ====================================================================== completion

data class Completion(val key: String, val name: String, val events: Int, val known: Int, val finished: Int, val skipped: Int) {
    /** finished listens out of listens whose length is known */
    val finishRate: Double get() = if (known > 0) finished.toDouble() / known else 0.0
    val skipRate: Double get() = if (events > 0) skipped.toDouble() / events else 0.0
    /** neither finished nor skipped (stopped or switched part of the way) */
    val partialRate: Double get() = (1.0 - finishRate - skipRate).coerceIn(0.0, 1.0)
}

data class CompletionReport(val overall: Completion, val songs: List<Completion>, val artists: List<Completion>)

// ====================================================================== compare

data class PeriodSide(
    val label: String, val window: StatsWindow, val totalMs: Long, val plays: Int, val songs: Int, val artists: Int,
    val activeDays: Int, val days: Int, val dailyAvgMs: Long, val newArtists: Int, val newSongs: Int,
    val finishRate: Double?, val topArtist: String?, val topSong: String?,
)

data class CompareRow(val he: String, val a: String, val b: String, val changePct: Int?)

data class Comparison(val a: PeriodSide, val b: PeriodSide, val rows: List<CompareRow>)

enum class ComparePreset(val he: String) {
    WEEK_VS_LAST("השבוע מול שעבר"), MONTH_VS_LAST("החודש מול הקודם"), MONTH_VS_LAST_YEAR("החודש מול אשתקד"),
    YEAR_VS_LAST("השנה מול שעברה"), LAST_MONTH_VS_BEFORE("חודש שעבר מול זה שלפניו"),
}

// ====================================================================== milestones

data class Milestone(
    val id: String, val emoji: String, val he: String, val detail: String,
    /** the day it happened (null = not reached yet) */
    val date: LocalDate?,
    /** 0..1 toward it when not reached yet */
    val progress: Double = 1.0,
) { val reached: Boolean get() = date != null }

// ====================================================================== forgotten favourites

data class ForgottenSong(val track: TrackMeta, val plays: Int, val ms: Long, val lastHeard: Long, val daysSince: Int, val firstHeard: Long)

// ====================================================================== goal

data class GoalDay(val date: LocalDate, val minutes: Int, val met: Boolean)

data class GoalStats(
    val goal: Int, val todayMinutes: Int, val done: Boolean,
    /** consecutive days that reached the goal, ending today (or yesterday while today is not done yet) */
    val streak: Int,
    val best: Streak,
    /** oldest first, ending today */
    val lastDays: List<GoalDay>,
    val metLast30: Int,
) {
    val progress: Float get() = if (goal <= 0) 0f else (todayMinutes.toFloat() / goal).coerceIn(0f, 1f)
    val leftMinutes: Int get() = (goal - todayMinutes).coerceAtLeast(0)
}

// ====================================================================== recap

data class Recap(
    val label: String, val totalMs: Long, val plays: Int, val songs: Int, val artists: Int,
    val topArtist: String?, val topArtistMs: Long, val topArtistThumb: String?,
    val topSong: String?, val topSongArtist: String?, val topSongPlays: Int, val topSongThumb: String?,
    val topGenre: String?, val genreInferred: Boolean, val topMood: String?,
    val streak: Streak, val newArtists: Int, val topArtists: List<String>, val topSongs: List<String>,
    val activeDays: Int, val days: Int,
) { val minutes: Long get() = totalMs / 60_000L }

data class Loyalty(val key: String, val name: String, val monthsActive: Int, val monthsSpan: Int, val ms: Long, val firstHeard: Long?) {
    val ratio: Double get() = if (monthsSpan > 0) monthsActive.toDouble() / monthsSpan else 0.0
}

object StatsPlus {
    const val FORGOTTEN_DAYS = 60
    const val FORGOTTEN_MIN_PLAYS = 5
    fun f(n: Int): String = String.format(java.util.Locale.ROOT, "%,d", n)

    // ------------------------------------------------------------------ genres / moods
    private val GENRE_ORDER = listOf(
        VibeTag.MIZRAHI to StatsGenre.MIZRAHI, VibeTag.PHONK to StatsGenre.PHONK, VibeTag.EDM to StatsGenre.EDM,
        VibeTag.RAP to StatsGenre.RAP, VibeTag.ROCK to StatsGenre.ROCK, VibeTag.LOFI to StatsGenre.LOFI,
        VibeTag.CINEMATIC to StatsGenre.CINEMATIC, VibeTag.ACOUSTIC to StatsGenre.ACOUSTIC, VibeTag.MEDLEY to StatsGenre.MEDLEY,
        VibeTag.LIVE to StatsGenre.LIVE, VibeTag.REMIX to StatsGenre.REMIX, VibeTag.SLOWED to StatsGenre.SLOWED,
        VibeTag.SPED_UP to StatsGenre.SPED_UP, VibeTag.HEBREW to StatsGenre.HEBREW,
    )

    /** One genre per song: the first matching tag in a fixed order (a Mizrahi remix is Mizrahi); a Mizrahi score of 0.5+ counts too. */
    fun genreOf(v: Vibe?): StatsGenre {
        if (v == null) return StatsGenre.OTHER
        if (v.isMizrahi && VibeTag.MIZRAHI !in v.tags && v.tags.none { it == VibeTag.PHONK || it == VibeTag.EDM }) return StatsGenre.MIZRAHI
        for ((tag, g) in GENRE_ORDER) if (tag in v.tags) return g
        return StatsGenre.OTHER
    }

    fun moodOf(v: Vibe?): StatsMood? {
        if (v == null) return null
        return when {
            VibeTag.PARTY in v.tags || (v.energy >= 0.7 && v.valence >= 0.55) -> StatsMood.PARTY
            VibeTag.SAD in v.tags || v.valence < 0.35 -> StatsMood.SAD
            VibeTag.CHILL in v.tags || VibeTag.LOFI in v.tags || v.energy < 0.35 -> StatsMood.CHILL
            v.energy >= 0.65 -> StatsMood.INTENSE
            v.valence >= 0.55 -> StatsMood.HAPPY
            else -> StatsMood.BALANCED
        }
    }

    fun genres(rows: List<StatRow>, vibe: (TrackMeta) -> Vibe?, hasEvidence: (TrackMeta) -> Boolean = { true }): GenreReport {
        class A { var ms = 0L; val songs = HashSet<String>(); val artists = HashMap<String, Long>() }
        val g = HashMap<String, A>(); val m = HashMap<String, A>()
        val vibeCache = HashMap<String, Vibe?>(); val evCache = HashMap<String, Boolean>()
        var total = 0L; var evMs = 0L
        for (r in rows) {
            val v = vibeCache.getOrPut(r.track.id) { runCatching { vibe(r.track) }.getOrNull() }
            val ev = evCache.getOrPut(r.track.id) { runCatching { hasEvidence(r.track) }.getOrDefault(false) }
            total += r.ms; if (ev) evMs += r.ms
            // without evidence of its own, a Hebrew title only says "Hebrew": the language default (Mizrahi 0.72) is not a genre
            val genre = if (ev) genreOf(v) else genreOf(v?.copy(mizrahi = 0.0, tags = v.tags - VibeTag.MIZRAHI))
            fun add(map: HashMap<String, A>, k: String) = map.getOrPut(k) { A() }.let { a -> a.ms += r.ms; a.songs += r.track.id; a.artists[r.artistName] = (a.artists[r.artistName] ?: 0L) + r.ms }
            add(g, genre.name)
            moodOf(v)?.let { add(m, it.name) }
        }
        fun rowsOf(map: HashMap<String, A>, he: (String) -> String) = map.entries.map { (k, a) ->
            ShareRow(k, he(k), a.ms, if (total > 0) a.ms.toDouble() / total else 0.0, a.songs.size, a.artists.maxByOrNull { it.value }?.key)
        }.sortedWith(compareByDescending<ShareRow> { if (it.key == StatsGenre.OTHER.name) -1L else it.ms }.thenBy { it.key })
        return GenreReport(rowsOf(g) { StatsGenre.valueOf(it).he }, rowsOf(m) { StatsMood.valueOf(it).he },
            if (total > 0) evMs.toDouble() / total else 0.0, total)
    }

    // ------------------------------------------------------------------ completion
    fun completion(rows: List<StatRow>, top: Int = 10, minEvents: Int = 3): CompletionReport {
        class A(val name: String) { var events = 0; var known = 0; var fin = 0; var skip = 0; var plays = 0; var ms = 0L }
        val all = A("הכל"); val s = HashMap<String, A>(); val a = HashMap<String, A>()
        for (r in rows) {
            for (x in listOf(all, s.getOrPut(r.track.id) { A(r.track.title) }, a.getOrPut(r.artistKey) { A(r.artistName) })) {
                x.events++; if (r.known) { x.known++; if (r.full) x.fin++ }; if (r.skip) x.skip++; if (r.counted) x.plays++; x.ms += r.ms
            }
        }
        fun c(k: String, x: A) = Completion(k, x.name, x.events, x.known, x.fin, x.skip)
        val songs = s.entries.filter { it.value.events >= minEvents }.sortedWith(compareByDescending<Map.Entry<String, A>> { it.value.plays }.thenByDescending { it.value.ms }.thenBy { it.key })
            .take(top).map { c(it.key, it.value) }
        val artists = a.entries.filter { it.value.events >= minEvents }.sortedWith(compareByDescending<Map.Entry<String, A>> { it.value.ms }.thenBy { it.key })
            .take(top).map { c(it.key, it.value) }
        return CompletionReport(c("all", all), songs, artists)
    }

    // ------------------------------------------------------------------ compare
    fun side(ix: StatsIndex, w: StatsWindow, label: String, now: Long): PeriodSide {
        val inW = ix.rows.filter { it.end in w }
        val artistMs = HashMap<String, Long>(); val songPlays = HashMap<String, Int>(); val names = HashMap<String, String>(); val titles = HashMap<String, String>()
        val days = HashSet<LocalDate>()
        var total = 0L; var plays = 0; var known = 0; var fin = 0
        for (r in inW) {
            total += r.ms; if (r.counted) plays++; if (r.known) { known++; if (r.full) fin++ }
            artistMs[r.artistKey] = (artistMs[r.artistKey] ?: 0L) + r.ms; names.putIfAbsent(r.artistKey, r.artistName)
            if (r.counted) songPlays[r.track.id] = (songPlays[r.track.id] ?: 0) + 1
            titles.putIfAbsent(r.track.id, r.track.title)
            days += DayClock.date(r.end)
        }
        val endTs = minOf(w.to, DayClock.tsOf(DayClock.date(now).plusDays(1)))
        val startTs = maxOf(w.from, ix.firstPlay?.let { DayClock.tsOf(DayClock.date(it)) } ?: w.from).coerceAtMost(maxOf(w.from, endTs - 1))
        val elapsed = maxOf(1, Math.floorDiv(endTs - startTs + DayClock.DAY_MS - 1, DayClock.DAY_MS).toInt())
        val newArtists = ix.firstArtist.count { (k, ts) -> ts in w && k in artistMs }
        val newSongs = ix.firstTrack.count { (k, ts) -> ts in w && k in titles }
        val topA = artistMs.maxWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenByDescending { it.key })?.key
        val topS = songPlays.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
        return PeriodSide(label, w, total, plays, titles.size, artistMs.size, days.size, elapsed, total / elapsed, newArtists, newSongs,
            if (known > 0) fin.toDouble() / known else null, topA?.let { names[it] }, topS?.let { titles[it] })
    }

    /** a = the newer period, b = the one it is compared with; changePct is a against b. */
    fun compare(a: PeriodSide, b: PeriodSide): Comparison {
        fun pct(x: Long, y: Long): Int? = StatsPeriods.changePct(x, y)
        fun min(ms: Long) = FullStats.hm(ms)
        fun p(d: Double?) = d?.let { "${(it * 100).roundToInt()}%" } ?: "—"
        val rows = listOf(
            CompareRow("זמן האזנה", min(a.totalMs), min(b.totalMs), pct(a.totalMs, b.totalMs)),
            CompareRow("השמעות", StatsPlus.f(a.plays), StatsPlus.f(b.plays), pct(a.plays.toLong(), b.plays.toLong())),
            CompareRow("ממוצע ליום", min(a.dailyAvgMs), min(b.dailyAvgMs), pct(a.dailyAvgMs, b.dailyAvgMs)),
            CompareRow("ימים עם האזנה", "${a.activeDays}/${a.days}", "${b.activeDays}/${b.days}", pct(a.activeDays.toLong(), b.activeDays.toLong())),
            CompareRow("שירים שונים", StatsPlus.f(a.songs), StatsPlus.f(b.songs), pct(a.songs.toLong(), b.songs.toLong())),
            CompareRow("אמנים שונים", StatsPlus.f(a.artists), StatsPlus.f(b.artists), pct(a.artists.toLong(), b.artists.toLong())),
            CompareRow("אמנים חדשים", StatsPlus.f(a.newArtists), StatsPlus.f(b.newArtists), pct(a.newArtists.toLong(), b.newArtists.toLong())),
            CompareRow("שירים עד הסוף", p(a.finishRate), p(b.finishRate), null),
            CompareRow("אמן מוביל", a.topArtist ?: "—", b.topArtist ?: "—", null),
            CompareRow("שיר מוביל", a.topSong ?: "—", b.topSong ?: "—", null),
        )
        return Comparison(a, b, rows)
    }

    /**
     * The two windows of a preset. While the newer period is still running, the older one is cut to the same elapsed
     * length (the first 9 days of this month against the first 9 days of the other), so the race is fair.
     */
    fun presetWindows(preset: ComparePreset, now: Long, firstPlay: Long?): Pair<Pair<String, StatsWindow>, Pair<String, StatsWindow>> {
        fun win(u: StatsUnit, off: Int) = StatsPeriods.window(StatsPeriod(u, off), now, firstPlay)
        fun lab(u: StatsUnit, off: Int) = StatsPeriods.label(StatsPeriod(u, off), now)
        fun cut(cur: StatsWindow, other: StatsWindow): StatsWindow =
            if (now in cur.from until cur.to) StatsWindow(other.from, minOf(other.to, other.from + (now - cur.from))) else other
        val (u, ao, bo) = when (preset) {
            ComparePreset.WEEK_VS_LAST -> Triple(StatsUnit.WEEK, 0, -1)
            ComparePreset.MONTH_VS_LAST -> Triple(StatsUnit.MONTH, 0, -1)
            ComparePreset.MONTH_VS_LAST_YEAR -> Triple(StatsUnit.MONTH, 0, -12)
            ComparePreset.YEAR_VS_LAST -> Triple(StatsUnit.YEAR, 0, -1)
            ComparePreset.LAST_MONTH_VS_BEFORE -> Triple(StatsUnit.MONTH, -1, -2)
        }
        val a = win(u, ao); val b = cut(a, win(u, bo))
        return (lab(u, ao) to a) to (lab(u, bo) to b)
    }

    fun compare(ix: StatsIndex, preset: ComparePreset, now: Long): Comparison {
        val (a, b) = presetWindows(preset, now, ix.firstPlay)
        return compare(side(ix, a.second, a.first, now), side(ix, b.second, b.first, now))
    }

    // ------------------------------------------------------------------ milestones
    private val PLAY_STEPS = listOf(100, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000)
    private val HOUR_STEPS = listOf(10, 50, 100, 250, 500, 1_000, 2_500)
    private val SONG_STEPS = listOf(100, 500, 1_000, 2_500, 5_000)
    private val ARTIST_STEPS = listOf(50, 100, 250, 500, 1_000)

    private fun fmt(n: Int) = String.format(java.util.Locale.ROOT, "%,d", n)

    /** Reached milestones newest first, then the next goal of each ladder (with progress). */
    fun milestones(ix: StatsIndex, now: Long, topArtists: Int = 3): List<Milestone> {
        val reached = ArrayList<Milestone>(); val next = ArrayList<Milestone>()
        val rows = ix.rows
        if (rows.isEmpty()) return emptyList()
        reached += Milestone("first", "🎧", "ההאזנה הראשונה", rows.first().track.title, DayClock.date(rows.first().end))
        // plays (30 s+)
        run {
            var n = 0; var step = 0
            for (r in rows) if (r.counted) { n++; while (step < PLAY_STEPS.size && n == PLAY_STEPS[step]) { reached += Milestone("plays${PLAY_STEPS[step]}", "▶️", "ההשמעה ה-${fmt(PLAY_STEPS[step])}", r.track.title, DayClock.date(r.end)); step++ } }
            if (step < PLAY_STEPS.size) next += Milestone("plays${PLAY_STEPS[step]}", "▶️", "ההשמעה ה-${fmt(PLAY_STEPS[step])}", "עוד ${fmt(PLAY_STEPS[step] - n)}", null, n.toDouble() / PLAY_STEPS[step])
        }
        // hours of real listening
        run {
            var ms = 0L; var step = 0
            for (r in rows) { ms += r.ms; while (step < HOUR_STEPS.size && ms >= HOUR_STEPS[step] * 3_600_000L) { reached += Milestone("hours${HOUR_STEPS[step]}", "⏱️", "${fmt(HOUR_STEPS[step])} שעות האזנה", "בזמן אמיתי", DayClock.date(r.end)); step++ } }
            if (step < HOUR_STEPS.size) next += Milestone("hours${HOUR_STEPS[step]}", "⏱️", "${fmt(HOUR_STEPS[step])} שעות האזנה", "עוד ${FullStats.hm(HOUR_STEPS[step] * 3_600_000L - ms)}", null, ms.toDouble() / (HOUR_STEPS[step] * 3_600_000L))
        }
        // distinct songs and artists (first real play)
        fun ladder(id: String, emoji: String, word: String, steps: List<Int>, firsts: Collection<Long>) {
            val sorted = firsts.sorted(); var step = 0
            while (step < steps.size && sorted.size >= steps[step]) { reached += Milestone("$id${steps[step]}", emoji, "${fmt(steps[step])} $word", "", DayClock.date(sorted[steps[step] - 1])); step++ }
            if (step < steps.size) next += Milestone("$id${steps[step]}", emoji, "${fmt(steps[step])} $word", "עוד ${fmt(steps[step] - sorted.size)}", null, sorted.size.toDouble() / steps[step])
        }
        ladder("songs", "🎵", "שירים שונים", SONG_STEPS, ix.firstTrack.values)
        ladder("artists", "🎤", "אמנים שונים", ARTIST_STEPS, ix.firstArtist.values)
        // the first listen of the artists that are on top now (all time)
        val msByArtist = HashMap<String, Long>(); val names = HashMap<String, String>()
        for (r in rows) { msByArtist[r.artistKey] = (msByArtist[r.artistKey] ?: 0L) + r.ms; names.putIfAbsent(r.artistKey, r.artistName) }
        msByArtist.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(topArtists).forEachIndexed { i, (k, ms) ->
            ix.firstArtist[k]?.let { reached += Milestone("firstTop$k", if (i == 0) "👑" else "🌟", "הפעם הראשונה ששמעת את ${names[k]}", "מקום ${i + 1} אצלך · ${FullStats.hm(ms)}", DayClock.date(it)) }
        }
        // longest streak, biggest day, longest session
        val days = HashMap<LocalDate, Long>(); for (r in rows) DayClock.date(r.end).let { days[it] = (days[it] ?: 0L) + r.ms }
        val streak = FullStats.longestStreak(days.keys)
        if (streak.days >= 2 && streak.to != null) reached += Milestone("streak", "🔥", "הרצף הכי ארוך: ${streak.days} ימים", "מ-${d(streak.from)} עד ${d(streak.to)}", streak.to)
        days.maxWithOrNull(compareBy<Map.Entry<LocalDate, Long>> { it.value }.thenBy { it.key })?.let { (day, ms) ->
            reached += Milestone("bigday", "📅", "היום הכי מוזיקלי", FullStats.hm(ms), day)
        }
        FullStats.sessions(rows).maxByOrNull { it.ms }?.let { s ->
            if (s.songs >= 2) reached += Milestone("session", "🎢", "הסשן הכי ארוך", "${FullStats.hm(s.spanMs)} · ${s.songs} שירים", DayClock.date(s.start))
        }
        val out = reached.sortedWith(compareByDescending<Milestone> { it.date }.thenBy { it.id })
        return out + next.sortedByDescending { it.progress }
    }

    private fun d(x: LocalDate?) = x?.let { "${it.dayOfMonth}.${it.monthValue}.${it.year % 100}" } ?: "—"

    // ------------------------------------------------------------------ forgotten favourites
    /**
     * Songs with at least [minPlays] real plays (30 s+) whose last listen of any kind (a skip counts: you saw it) is at least
     * [minDays] days ago. Most-played first.
     */
    fun forgotten(ix: StatsIndex, now: Long, minDays: Int = FORGOTTEN_DAYS, minPlays: Int = FORGOTTEN_MIN_PLAYS, limit: Int = 100): List<ForgottenSong> {
        class A(val t: TrackMeta) { var plays = 0; var ms = 0L; var last = 0L; var first = Long.MAX_VALUE }
        val m = HashMap<String, A>()
        for (r in ix.rows) m.getOrPut(r.track.id) { A(r.track) }.let { a ->
            if (r.counted) { a.plays++; a.ms += r.ms; if (r.end < a.first) a.first = r.end }
            if (r.end > a.last) a.last = r.end
        }
        val today = DayClock.date(now)
        return m.values.mapNotNull { a ->
            val since = (today.toEpochDay() - DayClock.date(a.last).toEpochDay()).toInt()
            if (a.plays >= minPlays && since >= minDays) ForgottenSong(a.t, a.plays, a.ms, a.last, since, a.first) else null
        }.sortedWith(compareByDescending<ForgottenSong> { it.plays }.thenByDescending { it.ms }.thenBy { it.track.id }).take(limit)
    }

    // ------------------------------------------------------------------ daily goal
    /**
     * The same rule as DailyGoal (the 🔥 card on the profile screen): minutes of real played time from listens of 30 s or more,
     * floored per day, so both places always show the same numbers.
     */
    fun minutesByDay(ix: StatsIndex): Map<LocalDate, Int> {
        val ms = HashMap<LocalDate, Long>()
        for (r in ix.rows) if (!r.estimated && r.ms >= FullStats.PLAY_MS) DayClock.date(r.end).let { ms[it] = (ms[it] ?: 0L) + r.ms }
        return ms.mapValues { (it.value / 60_000L).toInt() }
    }

    fun goal(ix: StatsIndex, now: Long, goalMin: Int, lastDays: Int = 14): GoalStats {
        val by = minutesByDay(ix)
        val g = goalMin.coerceAtLeast(1)
        val today = DayClock.date(now)
        val m = by[today] ?: 0
        var d = if (m >= g) today else today.minusDays(1)
        var streak = 0
        while ((by[d] ?: 0) >= g && streak < 3650) { streak++; d = d.minusDays(1) }
        val best = FullStats.longestStreak(by.filter { it.value >= g && !it.key.isAfter(today) }.keys)
        val last = (lastDays - 1 downTo 0).map { i -> today.minusDays(i.toLong()).let { day -> GoalDay(day, by[day] ?: 0, (by[day] ?: 0) >= g) } }
        val met30 = (0 until 30).count { (by[today.minusDays(it.toLong())] ?: 0) >= g }
        return GoalStats(goalMin, m, goalMin > 0 && m >= g, streak, best, last, met30)
    }

    // ------------------------------------------------------------------ recap
    fun recap(r: StatsReport, g: GenreReport?): Recap {
        val a = r.artists.firstOrNull(); val s = r.songs.firstOrNull()
        return Recap(r.label, r.overview.totalMs, r.overview.plays, r.overview.songs, r.overview.artists,
            a?.name, a?.ms ?: 0L, a?.thumb, s?.track?.title, s?.track?.artistLabel, s?.plays ?: 0, s?.track?.thumbnailUrl,
            g?.topGenre?.he, true, g?.moods?.firstOrNull()?.he,
            r.longestStreak, r.discovered.size, r.artists.take(5).map { it.name }, r.songs.take(5).map { it.track.title },
            r.overview.activeDays, r.overview.days)
    }

    // ------------------------------------------------------------------ optional extras
    /** % of each month's time spent on songs first heard in that month (the last [months] months with listening). */
    fun newShareByMonth(ix: StatsIndex, now: Long, months: Int = 12): List<StatBar> {
        val newMs = HashMap<YearMonth, Long>(); val all = HashMap<YearMonth, Long>()
        for (r in ix.rows) {
            val ym = YearMonth.from(DayClock.date(r.end))
            all[ym] = (all[ym] ?: 0L) + r.ms
            val f = ix.firstTrack[r.track.id]
            if (f != null && YearMonth.from(DayClock.date(f)) == ym) newMs[ym] = (newMs[ym] ?: 0L) + r.ms
        }
        if (all.isEmpty()) return emptyList()
        val end = YearMonth.from(DayClock.date(now)); val first = all.keys.min()
        val out = ArrayList<StatBar>()
        var m = maxOf(first, end.minusMonths((months - 1).toLong()))
        while (!m.isAfter(end)) {
            val tot = all[m] ?: 0L
            out += StatBar("${StatsPeriods.monthShort(m.monthValue)}${if (m.monthValue == 1) " " + (m.year % 100) else ""}", DayClock.tsOf(m.atDay(1)),
                if (tot > 0) Math.round((newMs[m] ?: 0L) * 100.0 / tot) else 0L)
            m = m.plusMonths(1)
        }
        return out
    }

    /** For the top artists (all time, by time): in how many of the months since you first heard them you came back. */
    fun loyalty(ix: StatsIndex, now: Long, top: Int = 10): List<Loyalty> {
        val ms = HashMap<String, Long>(); val months = HashMap<String, HashSet<YearMonth>>(); val names = HashMap<String, String>()
        for (r in ix.rows) {
            ms[r.artistKey] = (ms[r.artistKey] ?: 0L) + r.ms; names.putIfAbsent(r.artistKey, r.artistName)
            if (r.counted) months.getOrPut(r.artistKey) { HashSet() } += YearMonth.from(DayClock.date(r.end))
        }
        val end = YearMonth.from(DayClock.date(now))
        return ms.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(top).map { (k, v) ->
            val first = ix.firstArtist[k]
            val span = first?.let { (java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(DayClock.date(it)), end) + 1).toInt() } ?: 0
            Loyalty(k, names[k] ?: k, months[k]?.size ?: 0, span, v, first)
        }
    }
}
