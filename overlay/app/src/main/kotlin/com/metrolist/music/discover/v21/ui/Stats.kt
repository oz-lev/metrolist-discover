/**
 * Metrolist Discover 13.7.0.11 (fork addition; extended in 13.7.0.13) - GPL-3.0
 * "📊 סטטיסטיקות": the full listening statistics of the active profile (engine/FullStats.kt does the counting).
 */
package com.metrolist.music.discover.v21.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.engine.ArtistDetail
import com.metrolist.music.discover.engine.ArtistRow
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.FullStats
import com.metrolist.music.discover.engine.History
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.SongDetail
import com.metrolist.music.discover.engine.SongRow
import com.metrolist.music.discover.engine.StatBar
import com.metrolist.music.discover.engine.StatsCsv
import com.metrolist.music.discover.engine.StatsIndex
import com.metrolist.music.discover.engine.StatsPeriod
import com.metrolist.music.discover.engine.StatsPeriods
import com.metrolist.music.discover.engine.StatsReport
import com.metrolist.music.discover.engine.StatsUnit
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.Art
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.isCompactScreen
import com.metrolist.music.discover.v2.ui.playTracks
import com.metrolist.music.discover.v2.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.metrolist.music.discover.engine.ComparePreset
import com.metrolist.music.discover.engine.Comparison
import com.metrolist.music.discover.engine.CompletionReport
import com.metrolist.music.discover.engine.ForgottenSong
import com.metrolist.music.discover.engine.GenreReport
import com.metrolist.music.discover.engine.GoalStats
import com.metrolist.music.discover.engine.Loyalty
import com.metrolist.music.discover.engine.Milestone
import com.metrolist.music.discover.engine.Recap
import com.metrolist.music.discover.engine.StatsPlus

/**
 * The prepared statistics of one profile, computed off the main thread and kept per profile + history snapshot
 * (the engine's 10-minute snapshot) and per period, so switching tabs, periods back and forth or details is instant.
 */
object StatsCache {
    private var key: String? = null
    private var index: StatsIndex? = null
    private val reports = LinkedHashMap<String, StatsReport>()

    private fun albumNames(db: MusicDatabase): Map<String, String> {
        val m = HashMap<String, String>()
        runCatching {
            db.openHelper.readableDatabase.query("SELECT id, albumName FROM song WHERE albumName IS NOT NULL").use { c ->
                while (c.moveToNext()) if (!c.isNull(1)) m[c.getString(0)] = c.getString(1)
            }
        }
        return m
    }

    suspend fun index(ctx: Context, db: MusicDatabase): StatsIndex = withContext(Dispatchers.Default) {
        val h: History = V2Engine.history(ctx, db)
        val k = ProfileScope.historyOwner() + "|" + System.identityHashCode(h) + "|" + h.plays.size
        synchronized(this@StatsCache) { if (k == key) index?.let { return@withContext it } }
        val ix = FullStats.index(h, withContext(Dispatchers.IO) { albumNames(db) })
        synchronized(this@StatsCache) { key = k; index = ix; reports.clear() }
        ix
    }

    suspend fun report(ix: StatsIndex, p: StatsPeriod): StatsReport = withContext(Dispatchers.Default) {
        val now = V2Engine.now()
        val k = p.key + "@" + DayClock.dayKey(now)
        synchronized(this@StatsCache) { if (index === ix) reports[k]?.let { return@withContext it } }
        val r = FullStats.report(ix, p, now)
        synchronized(this@StatsCache) {
            if (index === ix) { reports[k] = r; while (reports.size > 12) reports.remove(reports.keys.first()) }
        }
        r
    }
}

// 13.7.0.13: RECAP, GENRES, COMPLETION, COMPARE, MILESTONES, FORGOTTEN (StatsPlusUi.kt). independent = not tied to the period picker.
private enum class Sec(val title: String, val independent: Boolean = false) {
    OVERVIEW("סקירה"), RECAP("🎁 סיכום"), ARTISTS("אמנים"), SONGS("שירים"), ALBUMS("אלבומים"), GENRES("ז'אנרים"), TRENDS("מגמות"),
    HABITS("הרגלים"), COMPLETION("סיום שירים"), COMPARE("⚖️ השוואה", true), DISCOVERY("גילוי"), MILESTONES("🏅 אבני דרך", true), FORGOTTEN("💔 נשכחו", true),
}

private data class Detail(val artist: Boolean, val id: String)

private fun num(n: Long): String = String.format(java.util.Locale.ROOT, "%,d", n)
private fun num(n: Int): String = num(n.toLong())
private fun pct(x: Double): String = "${Math.round(x * 100)}%"
private fun date(ts: Long?): String = ts?.let { val d = DayClock.date(it); "${d.dayOfMonth}.${d.monthValue}.${d.year % 100}" } ?: "—"
private fun date(d: LocalDate?): String = d?.let { "${it.dayOfMonth}.${it.monthValue}.${it.year % 100}" } ?: "—"
private fun hm(ms: Long): String = FullStats.hm(ms)
private fun change(p: Int?): String? = p?.let { if (it > 0) "↑ $it%" else if (it < 0) "↓ ${-it}%" else "= 0%" }

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val compact = isCompactScreen()
    val version by V2Settings.version.collectAsStateWithLifecycle()
    var ix by remember { mutableStateOf<StatsIndex?>(null) }
    var report by remember { mutableStateOf<StatsReport?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf(StatsUnit.MONTH) }
    var offset by remember { mutableIntStateOf(0) }
    var sec by remember { mutableStateOf(Sec.OVERVIEW) }
    var artistLimit by remember { mutableIntStateOf(20) }
    var songLimit by remember { mutableIntStateOf(25) }
    var albumLimit by remember { mutableIntStateOf(20) }
    var discLimit by remember { mutableIntStateOf(20) }
    var oneLimit by remember { mutableIntStateOf(20) }
    var trendGrain by remember { mutableIntStateOf(-1) }
    var exporting by remember { mutableStateOf(false) }
    val stack = remember { mutableStateListOf<Detail>() }
    var artistDetail by remember { mutableStateOf<ArtistDetail?>(null) }
    var songDetail by remember { mutableStateOf<SongDetail?>(null) }
    val period = StatsPeriod(unit, offset)
    // 13.7.0.13
    var goal by remember { mutableStateOf<GoalStats?>(null) }
    var genres by remember { mutableStateOf<GenreReport?>(null) }
    var completion by remember { mutableStateOf<CompletionReport?>(null) }
    var cmpPreset by remember { mutableStateOf(ComparePreset.MONTH_VS_LAST) }
    var comparison by remember { mutableStateOf<Comparison?>(null) }
    var milestones by remember { mutableStateOf<List<Milestone>?>(null) }
    var forgotten by remember { mutableStateOf<List<ForgottenSong>?>(null) }
    var forgottenLimit by remember { mutableIntStateOf(30) }
    var newShare by remember { mutableStateOf<List<StatBar>?>(null) }
    var loyalty by remember { mutableStateOf<List<Loyalty>?>(null) }
    var recap by remember { mutableStateOf<Recap?>(null) }
    var recapImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val goalMin = remember(version) { V2Settings.get(ctx).dailyGoalMin }
    val recapC1 = MaterialTheme.colorScheme.primary.toArgb(); val recapC2 = MaterialTheme.colorScheme.tertiary.toArgb()
    val profileLabel = remember(version) { runCatching { com.metrolist.music.discover.v21.V21Profiles.active(ctx).let { it.emoji + " " + it.name } }.getOrDefault("") }

    LaunchedEffect(version) {
        ix = runCatching { StatsCache.index(ctx, db) }.onFailure { failed = true }.getOrNull()
    }
    LaunchedEffect(ix, period.key) {
        val i = ix ?: return@LaunchedEffect
        report = runCatching { StatsCache.report(i, period) }.getOrNull()
        artistLimit = 20; songLimit = 25; albumLimit = 20; discLimit = 20; oneLimit = 20
    }
    LaunchedEffect(ix, goalMin) {
        val i = ix ?: return@LaunchedEffect
        goal = if (goalMin > 0) withContext(Dispatchers.Default) { runCatching { StatsPlus.goal(i, V2Engine.now(), goalMin) }.getOrNull() } else null
    }
    // period-bound extras: computed when their section is open, kept for the report they were made from
    var extrasFor by remember { mutableStateOf<StatsReport?>(null) }
    LaunchedEffect(report, sec) {
        val r = report ?: return@LaunchedEffect
        val i = ix ?: return@LaunchedEffect
        if (r.period.key != period.key) return@LaunchedEffect
        if (extrasFor !== r) { genres = null; completion = null; recap = null; recapImage = null; extrasFor = r }
        if (sec != Sec.GENRES && sec != Sec.RECAP && sec != Sec.COMPLETION) return@LaunchedEffect
        val rows = withContext(Dispatchers.Default) { i.rows.filter { it.end in r.window } }
        if ((sec == Sec.GENRES || sec == Sec.RECAP) && genres == null) {
            genres = withContext(Dispatchers.Default) {
                runCatching {
                    val est = V2Engine.estimator(ctx)
                    StatsPlus.genres(rows, { t -> synchronized(est) { V2Engine.vibeOf(ctx, t) } }, { t -> synchronized(est) { est.hasOwnEvidence(t) } })
                }.getOrNull()
            }
        }
        if (sec == Sec.RECAP && recap == null) {
            val rc = StatsPlus.recap(r, genres)
            recapImage = withContext(Dispatchers.Default) { runCatching { renderRecap(rc, profileLabel, recapC1, recapC2) }.getOrNull() }
            recap = rc
        }
        if (sec == Sec.COMPLETION && completion == null) completion = withContext(Dispatchers.Default) { StatsPlus.completion(rows) }
    }
    // all-time extras: kept per history snapshot
    var plusFor by remember { mutableStateOf<StatsIndex?>(null) }
    LaunchedEffect(ix, sec, cmpPreset) {
        val i = ix ?: return@LaunchedEffect
        if (plusFor !== i) { milestones = null; forgotten = null; newShare = null; loyalty = null; comparison = null; plusFor = i }
        val now = V2Engine.now()
        when (sec) {
            Sec.COMPARE -> comparison = withContext(Dispatchers.Default) { runCatching { StatsPlus.compare(i, cmpPreset, now) }.getOrNull() }
            Sec.MILESTONES -> if (milestones == null) milestones = withContext(Dispatchers.Default) { runCatching { StatsPlus.milestones(i, now) }.getOrDefault(emptyList()) }
            Sec.FORGOTTEN -> if (forgotten == null) forgotten = withContext(Dispatchers.Default) { runCatching { StatsPlus.forgotten(i, now) }.getOrDefault(emptyList()) }
            Sec.DISCOVERY -> if (newShare == null) {
                newShare = withContext(Dispatchers.Default) { runCatching { StatsPlus.newShareByMonth(i, now) }.getOrNull() }
                loyalty = withContext(Dispatchers.Default) { runCatching { StatsPlus.loyalty(i, now) }.getOrNull() }
            }
            else -> {}
        }
    }
    val top = stack.lastOrNull()
    LaunchedEffect(ix, top) {
        val i = ix; val d = top
        artistDetail = null; songDetail = null
        if (i == null || d == null) return@LaunchedEffect
        if (d.artist) artistDetail = withContext(Dispatchers.Default) { FullStats.artist(i, d.id, V2Engine.now()) }
        else songDetail = withContext(Dispatchers.Default) { FullStats.song(i, d.id, V2Engine.now()) }
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.size - 1) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else onBack() }
    val pad = if (compact) 10.dp else 16.dp

    if (top != null) {
        val title = if (top.artist) "🎤 ${artistDetail?.name ?: ""}" else "🎵 ${songDetail?.row?.track?.title ?: ""}"
        V2Page(title = title, subtitle = if (top.artist) "האמן בכל הזמנים" else "השיר בכל הזמנים", onBack = back) {
            val a = artistDetail; val s = songDetail
            if (top.artist && a != null) artistDetailItems(a, pad, onPlay = { pc?.playTracks(a.topSongs.map { it.track }, "🎤 ${a.name}") },
                onSong = { stack.add(Detail(false, it)) })
            else if (!top.artist && s != null) songDetailItems(s, pad, onPlay = { pc?.playTracks(listOf(s.row.track), "🎵 ${s.row.track.title}") },
                onArtist = { stack.add(Detail(true, it)) })
            else item(key = "d_loading") { Loading() }
        }
        return
    }

    V2Page(title = "📊 סטטיסטיקות", subtitle = "רק הפרופיל הפעיל: ${runCatching { com.metrolist.music.discover.v21.V21Profiles.active(ctx).let { it.emoji + " " + it.name } }.getOrDefault("")}", onBack = back) {
        // period picker: unit pills, then ‹ label › arrows (13.7.0.13: not for sections that ignore the period)
        if (!sec.independent) item(key = "units") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = pad, vertical = 4.dp)) {
                val units = listOf(StatsUnit.WEEK to "שבוע", StatsUnit.MONTH to "חודש", StatsUnit.YEAR to "שנה", StatsUnit.ALL to "הכל")
                items(units.size) { i -> Pill(units[i].second, selected = unit == units[i].first) { unit = units[i].first; offset = 0 } }
            }
        }
        if (!sec.independent) item(key = "arrows") {
            val now = V2Engine.now()
            val first = ix?.firstPlay
            val canBack = unit != StatsUnit.ALL && (first == null || StatsPeriods.window(StatsPeriod(unit, offset), now, first).from > first)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = pad - 4.dp)) {
                ArrowButton("→", enabled = canBack) { offset -= 1 }
                Text(StatsPeriods.label(period, now), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ArrowButton("←", enabled = unit != StatsUnit.ALL && offset < 0) { offset += 1 }
            }
        }
        // direct pick of a month or a year that has listening
        val first = ix?.firstPlay
        if (!sec.independent && first != null && (unit == StatsUnit.MONTH || unit == StatsUnit.YEAR)) item(key = "jump_${unit.name}") {
            val now = V2Engine.now()
            val today = DayClock.date(now)
            val opts: List<Pair<String, Int>> = if (unit == StatsUnit.MONTH) {
                val out = ArrayList<Pair<String, Int>>()
                var m = YearMonth.from(today); val f = YearMonth.from(DayClock.date(first))
                while (!m.isBefore(f) && out.size < 60) { out += "${StatsPeriods.monthShort(m.monthValue)} ${m.year % 100}" to StatsPeriods.offsetOf(StatsUnit.MONTH, m.atDay(1), now); m = m.minusMonths(1) }
                out
            } else (today.year downTo DayClock.date(first).year).map { "$it" to (it - today.year) }
            if (opts.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = pad, vertical = 2.dp)) {
                items(opts.size) { i -> Pill(opts[i].first, selected = opts[i].second == offset) { offset = opts[i].second } }
            }
        }
        item(key = "secs") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = pad, vertical = 8.dp)) {
                val all = Sec.values()
                items(all.size) { i -> Pill(all[i].title, selected = sec == all[i]) { sec = all[i] } }
            }
        }
        val r = report
        val i = ix
        if (failed) { item(key = "fail") { Hint("לא הצלחתי לקרוא את ההיסטוריה 😕") }; return@V2Page }
        if (sec.independent) {
            if (i == null) { item(key = "loading") { Loading() }; return@V2Page }
            when (sec) {
                Sec.COMPARE -> compareItems(cmpPreset, comparison?.takeIf { plusFor === i }, pad) { cmpPreset = it; comparison = null }
                Sec.MILESTONES -> milestoneItems(milestones, pad)
                Sec.FORGOTTEN -> forgottenItems(forgotten, pad, compact, forgottenLimit, { forgottenLimit += 50 },
                    onPlayAll = { forgotten?.let { l -> pc?.playTracks(l.map { it.track }, "💔 אהובים שנשכחו") } },
                    onShuffle = { forgotten?.let { l -> pc?.playTracks(l.take(50).shuffled().map { it.track }, "💔 מיקס אהובים שנשכחו") } },
                    onPlay = { k -> forgotten?.let { l -> pc?.playTracks(l.map { it.track }, "💔 אהובים שנשכחו", k) } })
                else -> {}
            }
            return@V2Page
        }
        if (r == null || i == null || r.period.key != period.key) { item(key = "loading") { Loading() }; return@V2Page }
        if (r.isEmpty) { item(key = "empty") { StatCard("אין האזנות בתקופה הזו", pad) { Hint("נסה תקופה אחרת, או ״הכל״.", Modifier.padding(0.dp)) } }; return@V2Page }
        when (sec) {
            Sec.OVERVIEW -> {
                goalItems(goal, goalMin, pad) { V2Settings.get(ctx).dailyGoalMin = it }
                overviewItems(r, pad, exporting) {
                exporting = true
                scope.launch { val ok = exportCsv(ctx, i, r); exporting = false; if (!ok) toast(ctx, "לא הצלחתי לייצא 😕") }
                }
            }
            Sec.RECAP -> recapItems(recap, recapImage?.asImageBitmap(), pad, sharing) {
                val bmp = recapImage
                if (bmp != null && !sharing) { sharing = true; scope.launch { val ok = shareRecap(ctx, bmp, r.label); sharing = false; if (!ok) toast(ctx, "לא הצלחתי לשתף 😕") } }
            }
            Sec.GENRES -> genreItems(genres, pad)
            Sec.COMPLETION -> completionItems(completion, pad)
            Sec.ARTISTS -> {
                item(key = "ar_t") { Hint("${num(r.artists.size)} אמנים · לפי זמן האזנה · לחיצה לפרטים") }
                val n = minOf(artistLimit, r.artists.size)
                items(n, key = { "ar_" + r.artists[it].key }) { k -> ArtistLine(k + 1, r.artists[k], compact, pad) { stack.add(Detail(true, r.artists[k].key)) } }
                if (n < r.artists.size) item(key = "ar_more") { More(r.artists.size - n, pad) { artistLimit += 30 } }
            }
            Sec.SONGS -> {
                item(key = "so_t") { Hint("${num(r.songs.size)} שירים · לפי השמעות · לחיצה לפרטים") }
                item(key = "so_play") { Box(Modifier.padding(horizontal = pad, vertical = 4.dp)) { Pill("▶ נגן את הטופ 50", selected = true) { pc?.playTracks(r.songs.take(50).map { it.track }, "📊 ${r.label}") } } }
                val n = minOf(songLimit, r.songs.size)
                items(n, key = { "so_" + r.songs[it].track.id }) { k -> SongLine(k + 1, r.songs[k], compact, pad) { stack.add(Detail(false, r.songs[k].track.id)) } }
                if (n < r.songs.size) item(key = "so_more") { More(r.songs.size - n, pad) { songLimit += 50 } }
            }
            Sec.ALBUMS -> {
                item(key = "al_t") { Hint("${num(r.albums.size)} אלבומים · לפי זמן האזנה") }
                val n = minOf(albumLimit, r.albums.size)
                items(n, key = { "al_" + r.albums[it].key }) { k ->
                    val a = r.albums[k]
                    Line(k + 1, a.thumb, a.key, a.name, "${a.artist} · ${hm(a.ms)} · ${num(a.plays)} השמעות · ${a.songs} שירים", a.share, compact, pad, null)
                }
                if (n < r.albums.size) item(key = "al_more") { More(r.albums.size - n, pad) { albumLimit += 30 } }
            }
            Sec.TRENDS -> trendItems(r, pad, trendGrain) { trendGrain = it }
            Sec.HABITS -> habitItems(r, pad, compact, onSong = { stack.add(Detail(false, it)) }, onArtist = { stack.add(Detail(true, it)) })
            Sec.DISCOVERY -> {
                discoveryItems(r, pad, compact, discLimit, oneLimit, { discLimit += 30 }, { oneLimit += 30 }) { stack.add(Detail(true, it)) }
                extraDiscoveryItems(newShare, loyalty, pad)
            }
            else -> {}
        }
    }
}

// ====================================================================== sections

private fun LazyListScope.overviewItems(r: StatsReport, pad: Dp, exporting: Boolean, onExport: () -> Unit) {
    val o = r.overview
    item(key = "ov_time") {
        StatCard("⏱️ זמן האזנה", pad) {
            Text(hm(o.totalMs), fontSize = 34.sp, fontWeight = FontWeight.Black)
            change(o.timeChangePct)?.let { Text("$it לעומת התקופה הקודמת" + if (o.prevTotalMs != null) " (${hm(o.prevTotalMs)})" else "", color = trendColor(o.timeChangePct), fontWeight = FontWeight.SemiBold) }
            if (o.timeChangePct == null && r.period.unit != StatsUnit.ALL) Hint("אין נתונים לתקופה הקודמת להשוואה", Modifier.padding(0.dp))
            Hint("ממוצע יומי ${hm(o.dailyAvgMs)} · ${o.activeDays} מתוך ${o.days} ימים עם האזנה", Modifier.padding(0.dp))
            if (o.estimatedMs > 0) Hint("${hm(o.estimatedMs)} מזה מוערכים לפי אורך השיר (זמן ההאזנה לא נרשם)", Modifier.padding(0.dp))
        }
    }
    item(key = "ov_counts") {
        Column(Modifier.padding(horizontal = pad, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("השמעות", num(o.plays), change(o.playsChangePct), Modifier.weight(1f))
                Tile("שירים שונים", num(o.songs), null, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("אמנים", num(o.artists), null, Modifier.weight(1f))
                Tile("אלבומים", num(o.albums), null, Modifier.weight(1f))
            }
        }
    }
    r.artists.firstOrNull()?.let { a ->
        item(key = "ov_top") {
            StatCard("🏅 הכי הרבה", pad) {
                Text("אמן: ${a.name} · ${hm(a.ms)}", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                r.songs.firstOrNull()?.let { s -> Text("שיר: ${s.track.title} · ${num(s.plays)} השמעות", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    item(key = "ov_export") {
        StatCard("📤 ייצוא", pad) {
            Hint("קובץ CSV של כל ההשמעות ב${if (r.period.unit == StatsUnit.ALL) "כל הזמנים" else "תקופה: ${r.label}"} (תאריך, שעה, שיר, אמן, אלבום, שניות, דילוג). נפתח באקסל ובגוגל שיטס.", Modifier.padding(0.dp))
            Spacer(Modifier.height(8.dp))
            GradientButton(if (exporting) "מכין…" else "📤 ייצוא ושיתוף", Modifier.fillMaxWidth(), enabled = !exporting) { onExport() }
        }
    }
}

private fun LazyListScope.trendItems(r: StatsReport, pad: Dp, grain: Int, onGrain: (Int) -> Unit) {
    val auto = when (r.period.unit) { StatsUnit.WEEK, StatsUnit.MONTH -> 0; StatsUnit.YEAR -> 1; StatsUnit.ALL -> 2 }
    val g = if (grain < 0) auto else grain
    item(key = "tr_bars") {
        StatCard("📈 דקות האזנה", pad) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("יום", "שבוע", "חודש").forEachIndexed { i, s -> Pill(s, selected = g == i) { onGrain(i) } }
            }
            Spacer(Modifier.height(10.dp))
            val bars = when (g) { 0 -> r.daily; 1 -> r.weekly; else -> r.monthly }
            Bars(bars.map { StatBar(it.label, it.from, it.value / 60_000L) }) { "$it דק׳" }
        }
    }
    item(key = "tr_new") {
        StatCard("🌱 אמנים חדשים בכל חודש", pad, "כמה אמנים שמעת בפעם הראשונה") {
            Bars(r.newArtistsPerMonth) { "$it אמנים" }
        }
    }
    item(key = "tr_months") {
        StatCard("🗓️ החודש שלך", pad, "האמן המוביל בכל חודש") {
            r.monthTops.asReversed().forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${StatsPeriods.monthName(m.month.monthValue)} ${m.month.year % 100}", modifier = Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(m.artist ?: "—", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.ms > 0) Text(hm(m.ms), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun LazyListScope.habitItems(r: StatsReport, pad: Dp, compact: Boolean, onSong: (String) -> Unit, onArtist: (String) -> Unit) {
    item(key = "hb_heat") { StatCard("🕐 מתי אתה שומע", pad, "יום בשבוע × שעה ביום · כהה יותר = יותר דקות") { HeatMap(r.heat) } }
    item(key = "hb_streak") {
        Column(Modifier.padding(horizontal = pad, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("🔥 הרצף הכי ארוך", "${r.longestStreak.days} ימים", if (r.longestStreak.days > 0) "${date(r.longestStreak.from)}–${date(r.longestStreak.to)}" else null, Modifier.weight(1f))
                Tile("✅ השלמה", r.completionRate?.let { pct(it) } ?: "—", "שירים ששמעת עד הסוף", Modifier.weight(1f))
            }
        }
    }
    r.longestSession?.let { s ->
        item(key = "hb_session") {
            StatCard("🎧 הסשן הכי ארוך", pad, "סשן נגמר אחרי הפסקה של 30 דקות ומעלה") {
                Text(hm(s.spanMs), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Hint("${date(s.start)} ${"%02d:%02d".format(DayClock.dateTime(s.start).hour, DayClock.dateTime(s.start).minute)} · ${num(s.songs)} שירים · ${hm(s.ms)} האזנה בפועל · ${num(r.sessions)} סשנים בתקופה", Modifier.padding(0.dp))
            }
        }
    }
    item(key = "hb_skip_t") { Hint("⏭️ הכי מדולגים · ${num(r.overview.skips)} דילוגים בתקופה") }
    if (r.mostSkippedSongs.isEmpty() && r.mostSkippedArtists.isEmpty()) item(key = "hb_noskip") { Hint("כמעט לא דילגת בתקופה הזו 👌") }
    r.mostSkippedSongs.take(10).forEachIndexed { k, s ->
        item(key = "hb_ss_" + s.track.id) {
            Line(k + 1, s.track.thumbnailUrl, s.track.id, s.track.title, "${s.track.artistLabel} · ${s.skips} דילוגים מתוך ${s.events} (${pct(s.skipRate)})", null, compact, pad) { onSong(s.track.id) }
        }
    }
    if (r.mostSkippedArtists.isNotEmpty()) item(key = "hb_sa_t") { Hint("אמנים") }
    r.mostSkippedArtists.take(10).forEachIndexed { k, a ->
        item(key = "hb_sa_" + a.key) { Line(k + 1, a.thumb, a.key, a.name, "${a.skips} דילוגים מתוך ${a.events} (${pct(a.skipRate)})", null, compact, pad) { onArtist(a.key) } }
    }
}

private fun LazyListScope.discoveryItems(r: StatsReport, pad: Dp, compact: Boolean, discLimit: Int, oneLimit: Int, moreDisc: () -> Unit, moreOne: () -> Unit, onArtist: (String) -> Unit) {
    item(key = "ds_share") {
        StatCard("✨ חדש מול מוכר", pad, "שיר ״חדש״ = שמעת אותו לראשונה בתקופה הזו") {
            if (r.period.unit == StatsUnit.ALL) Hint("בכל הזמנים כל שיר היה פעם חדש 🙂 בחר שבוע, חודש או שנה.", Modifier.padding(0.dp))
            else {
                Text("${pct(r.newShare)} מהזמן על שירים חדשים", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Split(r.newShare)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("חדש ${hm(r.newMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text("מוכר ${hm(r.familiarMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    item(key = "ds_t") { Hint("🧭 אמנים שגילית בתקופה (${num(r.discovered.size)}), לפי הסדר") }
    val n = minOf(discLimit, r.discovered.size)
    items(n, key = { "ds_" + r.discovered[it].key }) { k ->
        val a = r.discovered[k]
        Line(k + 1, a.thumb, a.key, a.name, "${date(a.firstHeard)} · ${hm(a.ms)} · ${num(a.plays)} השמעות", null, compact, pad) { onArtist(a.key) }
    }
    if (n < r.discovered.size) item(key = "ds_more") { More(r.discovered.size - n, pad, moreDisc) }
    item(key = "oh_t") { Hint("1️⃣ שמעת רק פעם אחת (${num(r.oneHit.size)})") }
    val m = minOf(oneLimit, r.oneHit.size)
    items(m, key = { "oh_" + r.oneHit[it].key }) { k ->
        val a = r.oneHit[k]
        Line(k + 1, a.thumb, a.key, a.name, date(a.firstHeard), null, compact, pad) { onArtist(a.key) }
    }
    if (m < r.oneHit.size) item(key = "oh_more") { More(r.oneHit.size - m, pad, moreOne) }
}

private fun LazyListScope.artistDetailItems(a: ArtistDetail, pad: Dp, onPlay: () -> Unit, onSong: (String) -> Unit) {
    item(key = "ad_sum") {
        StatCard("⏱️ ${hm(a.totalMs)}", pad) {
            Text("${num(a.plays)} השמעות · ${num(a.songs)} שירים · ${pct(a.skipRate)} דילוגים", fontWeight = FontWeight.SemiBold)
            Hint("שמעת לראשונה ${date(a.firstHeard)} · לאחרונה ${date(a.lastHeard)}", Modifier.padding(0.dp))
            Spacer(Modifier.height(10.dp))
            GradientButton("▶ נגן את השירים שלו", Modifier.fillMaxWidth()) { onPlay() }
        }
    }
    item(key = "ad_month") { StatCard("📈 דקות בכל חודש", pad) { Bars(a.monthly) { "$it דק׳" } } }
    item(key = "ad_songs") { Hint("השירים שלו שהכי שמעת") }
    a.topSongs.forEachIndexed { k, s ->
        item(key = "ad_s_" + s.track.id) { SongLine(k + 1, s, false, pad) { onSong(s.track.id) } }
    }
}

private fun LazyListScope.songDetailItems(d: SongDetail, pad: Dp, onPlay: () -> Unit, onArtist: (String) -> Unit) {
    val s = d.row
    item(key = "sd_sum") {
        StatCard(s.track.artistLabel, pad) {
            Text("${num(s.plays)} השמעות · ${hm(d.totalMs)}", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("${s.skips} דילוגים מתוך ${s.events} (${pct(s.skipRate)})" + (d.fullRate?.let { " · ${pct(it)} עד הסוף" } ?: ""), fontWeight = FontWeight.SemiBold)
            Hint("שמעת לראשונה ${date(s.firstHeard)} · לאחרונה ${date(s.lastHeard)}", Modifier.padding(0.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("▶ נגן", selected = true) { onPlay() }
                Pill("🎤 האמן") { onArtist(s.artistKey) }
            }
        }
    }
    item(key = "sd_month") { StatCard("📈 השמעות בכל חודש", pad) { Bars(d.monthly) { "$it השמעות" } } }
}

// ====================================================================== building blocks

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun StatCard(title: String, pad: Dp, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    V2Card(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = if (sub.startsWith("↑") || sub.startsWith("↓")) trendColor(if (sub.startsWith("↑")) 1 else -1) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun trendColor(p: Int?): Color = when {
    p == null || p == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    p > 0 -> Color(0xFF16A34A)
    else -> Color(0xFFDC2626)
}

@Composable
private fun ArrowButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(enabled = enabled, onClick = onClick)) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
    }
}

@Composable
private fun More(left: Int, pad: Dp, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 8.dp), contentAlignment = Alignment.Center) { Pill("הצג עוד (${num(left)} נשארו)") { onClick() } }
}

/** A ranked row: cover, name, one line of numbers, an optional share bar. 48 dp minimum height. */
@Composable
private fun Line(rank: Int, thumb: String?, key: String, title: String, sub: String, share: Double?, compact: Boolean, pad: Dp, onClick: (() -> Unit)?) {
    var m = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = m.padding(horizontal = pad, vertical = 6.dp)) {
        Text("$rank", modifier = Modifier.width(if (compact) 26.dp else 32.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!compact) { Art(thumb, key, Modifier.size(44.dp), 10); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (share != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(" ${if (share < 0.01) "<1%" else pct(share)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ArtistLine(rank: Int, a: ArtistRow, compact: Boolean, pad: Dp, onClick: () -> Unit) =
    Line(rank, a.thumb, a.key, a.name, "${hm(a.ms)} · ${num(a.plays)} השמעות · ${num(a.songs)} שירים", a.share, compact, pad, onClick)

@Composable
private fun SongLine(rank: Int, s: SongRow, compact: Boolean, pad: Dp, onClick: () -> Unit) =
    Line(rank, s.track.thumbnailUrl, s.track.id, s.track.title,
        "${s.track.artistLabel} · ${num(s.plays)} השמעות · ${hm(s.ms)} · ⏭️ ${s.skips} (${pct(s.skipRate)}) · מאז ${date(s.firstHeard)}", null, compact, pad, onClick)

/** A bar chart in RTL time order: the oldest bar on the right. Labels of the first and last bar, and the peak. */
@Composable
private fun Bars(bars: List<StatBar>, unit: (Long) -> String) {
    if (bars.isEmpty()) { Hint("אין נתונים", Modifier.padding(0.dp)); return }
    val cs = MaterialTheme.colorScheme
    val max = bars.maxOf { it.value }.coerceAtLeast(1L)
    val peak = bars.indices.maxByOrNull { bars[it].value } ?: 0
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val n = bars.size
        val w = size.width / n
        for (i in 0 until n) {
            val v = bars[i].value
            val hh = if (v <= 0L) 2f else maxOf(3f, size.height * (v.toFloat() / max.toFloat()))
            val x = size.width - (i + 1) * w
            val gap = if (w > 6f) w * 0.15f else 0f
            drawRoundRect(color = if (i == peak) cs.primary else cs.primary.copy(alpha = 0.45f), topLeft = Offset(x + gap, size.height - hh),
                size = Size(maxOf(1f, w - 2 * gap), hh), cornerRadius = CornerRadius(minOf(w * 0.3f, 8f)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(bars.first().label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(bars.last().label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
    if (bars[peak].value > 0) Text("שיא: ${bars[peak].label} · ${unit(bars[peak].value)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

/** Weekday (Sunday on top) × hour (00 on the right). */
@Composable
private fun HeatMap(heat: Array<LongArray>) {
    val cs = MaterialTheme.colorScheme
    val max = heat.maxOf { r -> r.maxOrNull() ?: 0L }.coerceAtLeast(1L)
    val days = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
    var peakD = 0; var peakH = 0
    for (d in 0 until 7) for (h in 0 until 24) if (heat[d][h] > heat[peakD][peakH]) { peakD = d; peakH = h }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (d in 0 until 7) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(days[d], modifier = Modifier.width(24.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val w = size.width / 24f
                    for (h in 0 until 24) {
                        val v = heat[d][h]
                        val a = if (v <= 0L) 0.06f else (0.18f + 0.82f * (v.toFloat() / max.toFloat()))
                        drawRoundRect(color = cs.primary.copy(alpha = a), topLeft = Offset(size.width - (h + 1) * w + 1f, 0f),
                            size = Size(maxOf(1f, w - 2f), size.height), cornerRadius = CornerRadius(3f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(24.dp))
            Text("00", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("12", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("23", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (heat[peakD][peakH] > 0) Text("הכי הרבה: יום ${days[peakD]} ב-${"%02d".format(peakH)}:00", fontWeight = FontWeight.SemiBold)
    }
}

/** One bar split new (primary, on the right) / familiar. */
@Composable
private fun Split(newShare: Double) {
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(newShare.toFloat().coerceIn(0f, 1f)).height(14.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** Writes this profile's plays of the period as CSV into the app cache and opens the share sheet (no permission needed). */
private suspend fun exportCsv(ctx: Context, ix: StatsIndex, r: StatsReport): Boolean = runCatching {
    val w = if (r.period.unit == StatsUnit.ALL) null else r.window
    val owner = ProfileScope.historyOwner().filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifBlank { "me" }
    val d0 = DayClock.date(r.window.from); val d1 = DayClock.date(r.window.to - 1)
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "discover_share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("metrolist-plays-") }?.forEach { it.delete() }
        File(dir, "metrolist-plays-$owner-$d0-$d1.csv").apply { writeText(StatsCsv.build(ix, w), Charsets.UTF_8) }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".FileProvider", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Metrolist · ההשמעות שלי · ${r.label}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "ייצוא ההשמעות").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
