/**
 * Metrolist Discover 13.7.0.13 (fork addition) - GPL-3.0
 * The new cards of "📊 סטטיסטיקות" (engine/StatsPlus.kt does the counting): Wrapped-style recap as a shareable image,
 * genres and moods (inferred), completion, two periods side by side, milestones, forgotten favourites, the daily goal
 * ring, new-vs-repeat by month and artist loyalty. Also the "💔 אהובים שנשכחו" card on Discover's home.
 * Everything reads the active profile's StatsIndex only.
 */
package com.metrolist.music.discover.v21.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.Completion
import com.metrolist.music.discover.engine.CompletionReport
import com.metrolist.music.discover.engine.ComparePreset
import com.metrolist.music.discover.engine.Comparison
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.ForgottenSong
import com.metrolist.music.discover.engine.FullStats
import com.metrolist.music.discover.engine.GenreReport
import com.metrolist.music.discover.engine.GoalStats
import com.metrolist.music.discover.engine.Loyalty
import com.metrolist.music.discover.engine.Milestone
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.Recap
import com.metrolist.music.discover.engine.StatBar
import com.metrolist.music.discover.engine.StatsPlus
import com.metrolist.music.discover.engine.StatsGenre
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.ui.Art
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.playTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

private fun spNum(n: Int): String = String.format(java.util.Locale.ROOT, "%,d", n)
private fun spPct(x: Double): String = "${(x * 100).roundToInt()}%"
private fun spDate(d: LocalDate?): String = d?.let { "${it.dayOfMonth}.${it.monthValue}.${it.year % 100}" } ?: "—"
private fun spDate(ts: Long): String = spDate(DayClock.date(ts))
private val HE_DOW = listOf("ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳", "א׳")   // ISO Monday..Sunday

private val GOOD = Color(0xFF16A34A)
private val BAD = Color(0xFFDC2626)
private val MID = Color(0xFFF59E0B)

@Composable
private fun SpCard(title: String, pad: Dp, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
private fun SpHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One horizontal share bar with its label above: "name ........ 23%". */
@Composable
private fun ShareBar(label: String, value: String, share: Double, color: Color = MaterialTheme.colorScheme.primary) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
    }
}

/** finished | partly | skipped as one bar (RTL: finished starts on the right). */
@Composable
private fun TriBar(c: Completion, height: Dp = 12.dp) {
    val fin = c.finishRate.toFloat().coerceIn(0f, 1f)
    val skip = c.skipRate.toFloat().coerceIn(0f, 1f - fin)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val w = size.width; val h = size.height
        drawRoundRect(Color(0x33888888), Offset.Zero, Size(w, h), CornerRadius(h / 2))
        val fw = w * fin; val sw = w * skip
        if (fw > 0f) drawRoundRect(GOOD, Offset(w - fw, 0f), Size(fw, h), CornerRadius(h / 2))
        if (sw > 0f) drawRoundRect(BAD, Offset(0f, 0f), Size(sw, h), CornerRadius(h / 2))
        val pw = w - fw - sw
        if (pw > 0f) drawRect(MID.copy(alpha = 0.75f), Offset(sw, 0f), Size(pw, h))
    }
}

@Composable
private fun SimpleBars(bars: List<StatBar>, unit: (Long) -> String, max: Long? = null) {
    if (bars.isEmpty()) { SpHint("אין נתונים"); return }
    val cs = MaterialTheme.colorScheme
    val top = (max ?: bars.maxOf { it.value }).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val n = bars.size; val w = size.width / n
        for (i in 0 until n) {
            val v = bars[i].value
            val hh = if (v <= 0L) 2f else maxOf(3f, size.height * (v.toFloat() / top.toFloat()))
            val x = size.width - (i + 1) * w
            val gap = if (w > 6f) w * 0.18f else 0f
            drawRoundRect(cs.primary.copy(alpha = if (i == n - 1) 1f else 0.55f), Offset(x + gap, size.height - hh), Size(maxOf(1f, w - 2 * gap), hh), CornerRadius(minOf(w * 0.3f, 8f)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(bars.first().label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("${bars.last().label}: ${unit(bars.last().value)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

// ====================================================================== 🎯 daily goal (top of סקירה)

private val GOAL_PRESETS = listOf(15, 30, 45, 60, 90, 120, 0)

fun LazyListScope.goalItems(g: GoalStats?, goalMin: Int, pad: Dp, onGoal: (Int) -> Unit) {
    item(key = "sp_goal") {
        SpCard("🎯 מטרת האזנה יומית", pad, if (goalMin <= 0) "כבויה · בחר מטרה כדי להתחיל רצף" else "רק הפרופיל הפעיל · השמעות של 30 שנ׳ ומעלה") {
            if (g != null && goalMin > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalRing(g.progress, g.done, Modifier.size(104.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${g.todayMinutes}", fontSize = 26.sp, fontWeight = FontWeight.Black)
                            Text("מתוך ${g.goal} דק׳", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (g.done) "✅ עמדת במטרה היום" else "עוד ${g.leftMinutes} דק׳ להיום", fontWeight = FontWeight.Bold)
                        Text("🔥 רצף: ${g.streak} ${if (g.streak == 1) "יום" else "ימים"}", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("שיא: ${g.best.days} ימים${if (g.best.days > 0) " (${spDate(g.best.from)}–${spDate(g.best.to)})" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("ב-30 הימים האחרונים: ${g.metLast30}/30", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // the last 14 days, today on the left (RTL: the oldest on the right)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    g.lastDays.forEach { d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(when {
                                d.met -> GOOD
                                d.minutes > 0 -> MID.copy(alpha = 0.6f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }))
                            Text(HE_DOW[d.date.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(GOAL_PRESETS.size) { i ->
                    val v = GOAL_PRESETS[i]
                    Pill(if (v == 0) "כבוי" else "$v דק׳", selected = v == goalMin) { onGoal(v) }
                }
            }
        }
    }
}

@Composable
private fun GoalRing(progress: Float, done: Boolean, modifier: Modifier, center: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(cs.surfaceContainerHighest, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(if (done) GOOD else cs.primary, -90f, -360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        center()
    }
}

// ====================================================================== 🎁 recap (Wrapped-style)

fun LazyListScope.recapItems(rc: Recap?, image: ImageBitmap?, pad: Dp, sharing: Boolean, onShare: () -> Unit) {
    item(key = "sp_recap") {
        SpCard("🎁 הסיכום שלך · ${rc?.label ?: ""}", pad, "כרטיס לשיתוף בסגנון Wrapped · בחר חודש או שנה למעלה") {
            if (rc == null || image == null) { SpHint("מכין את הכרטיס…"); return@SpCard }
            Image(image, contentDescription = "סיכום ${rc.label}", modifier = Modifier.fillMaxWidth().aspectRatio(1080f / 1350f).clip(RoundedCornerShape(18.dp)))
            Spacer(Modifier.height(10.dp))
            GradientButton(if (sharing) "מכין…" else "📤 שתף את הכרטיס", Modifier.fillMaxWidth(), enabled = !sharing) { onShare() }
            Spacer(Modifier.height(6.dp))
            SpHint("הז'אנר בכרטיס משוער (לפי האמן, מילים בכותרת ותגיות Discover), לא תגית רשמית.")
        }
    }
}

/** Draws the recap as a 1080 x 1350 image (Instagram portrait), Hebrew right-to-left. */
fun renderRecap(rc: Recap, profile: String, c1: Int, c2: Int): Bitmap {
    val w = 1080; val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP) }
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
    val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    val m = 64f
    var y = 70f
    fun text(s: String, sz: Float, bold: Boolean, a: Int = 255, maxLines: Int = 2, width: Int = (w - 2 * m).toInt(), x: Float = m, center: Boolean = false): Float {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.argb(a, 255, 255, 255)
        p.textSize = sz
        p.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
        cv.save(); cv.translate(x, y); l.draw(cv); cv.restore()
        return l.height.toFloat()
    }
    y += text("METROLIST DISCOVER · $profile", 30f, true, 200, 1)
    y += 10f
    y += text("הסיכום שלי · ${rc.label}", 64f, true, 255, 2)
    y += 30f
    // big minutes
    cv.drawRoundRect(RectF(m, y, w - m, y + 250f), 44f, 44f, glass)
    y += 30f
    y += text(StatsPlus.f(rc.minutes.toInt()), 120f, true, 255, 1, center = true)
    y += text("דקות האזנה · ${StatsPlus.f(rc.plays)} השמעות", 36f, false, 230, 1, center = true)
    y += 60f
    fun block(label: String, value: String, sub: String?) {
        cv.drawRoundRect(RectF(m, y, w - m, y + 170f), 36f, 36f, glass)
        val y0 = y
        y += 22f
        val x = m + 36f; val bw = (w - 2 * m - 72f).toInt()
        y += text(label, 30f, false, 210, 1, bw, x)
        y += text(value, 48f, true, 255, 1, bw, x)
        if (sub != null) text(sub, 28f, false, 200, 1, bw, x)
        y = y0 + 190f
    }
    block("🎤 האמן מספר 1", rc.topArtist ?: "—", if (rc.topArtistMs > 0) FullStats.hm(rc.topArtistMs) else null)
    block("🎵 השיר מספר 1", rc.topSong ?: "—", rc.topSongArtist?.let { "$it · ${StatsPlus.f(rc.topSongPlays)} השמעות" })
    val half = ((w - 2 * m - 24f) / 2f)
    run {
        val y0 = y
        cv.drawRoundRect(RectF(w - m - half, y0, w - m, y0 + 200f), 36f, 36f, glass)
        cv.drawRoundRect(RectF(m, y0, m + half, y0 + 200f), 36f, 36f, glass)
        val bw = (half - 56f).toInt()
        y = y0 + 22f; y += text("🎶 ז'אנר מוביל*", 28f, false, 210, 1, bw, w - m - half + 28f); text(rc.topGenre ?: "—", 42f, true, 255, 2, bw, w - m - half + 28f)
        y = y0 + 22f; y += text("🔥 רצף הכי ארוך", 28f, false, 210, 1, bw, m + 28f); text("${rc.streak.days} ימים", 42f, true, 255, 1, bw, m + 28f)
        y = y0 + 220f
    }
    y += text("${StatsPlus.f(rc.songs)} שירים · ${StatsPlus.f(rc.artists)} אמנים · ${StatsPlus.f(rc.newArtists)} אמנים חדשים · ${rc.activeDays}/${rc.days} ימים", 30f, false, 230, 2)
    y = h - 70f
    text("* ז'אנר משוער לפי Discover", 24f, false, 170, 1)
    return bmp
}

suspend fun shareRecap(ctx: Context, bmp: Bitmap, label: String): Boolean = runCatching {
    val owner = ProfileScope.historyOwner().filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifBlank { "me" }
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "discover_share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("metrolist-recap-") }?.forEach { it.delete() }
        File(dir, "metrolist-recap-$owner-${System.currentTimeMillis() / 1000}.png").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".FileProvider", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, "הסיכום שלי · $label 🎧").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "שיתוף הסיכום").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

fun recapColors(primary: Color, tertiary: Color): Pair<Int, Int> = primary.toArgb() to tertiary.toArgb()

// ====================================================================== 🎶 genres and moods

fun LazyListScope.genreItems(g: GenreReport?, pad: Dp) {
    if (g == null) { item(key = "sp_g_load") { SpCard("🎶 ז'אנרים", pad) { SpHint("מחשב…") } }; return }
    item(key = "sp_genres") {
        SpCard("🎶 ז'אנרים (משוער)", pad, "לפי זמן האזנה בתקופה · שיר נספר בז'אנר אחד") {
            g.genres.take(10).forEach { r -> ShareBar(r.he + (r.topArtist?.let { " · $it" } ?: ""), "${spPct(r.share)} · ${FullStats.hm(r.ms)}", r.share,
                if (r.key == StatsGenre.OTHER.name) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(6.dp))
            SpHint("הז'אנרים משוערים: Discover מזהה אותם לפי אמנים שהוא מכיר, מילים בשם השיר והתיקונים שלמד ממך. אין כאן תגיות רשמיות. " +
                "ל-${spPct(g.evidenceShare)} מהזמן יש סימן ממשי; שיר בעברית בלי סימן נספר כ״עברית (כללי)״, ובלי שום סימן כ״לא זוהה״.")
        }
    }
    if (g.moods.isNotEmpty()) item(key = "sp_moods") {
        SpCard("🎭 מצבי רוח (משוער)", pad, "לפי האנרגיה והשמחה ש-Discover מעריך לכל שיר") {
            g.moods.forEach { r -> ShareBar(r.he, "${spPct(r.share)} · ${FullStats.hm(r.ms)}", r.share, MaterialTheme.colorScheme.tertiary) }
        }
    }
}

// ====================================================================== ✅ completion

fun LazyListScope.completionItems(c: CompletionReport?, pad: Dp) {
    if (c == null) { item(key = "sp_c_load") { SpCard("✅ סיום שירים", pad) { SpHint("מחשב…") } }; return }
    item(key = "sp_c_all") {
        SpCard("✅ כמה שירים שמעת עד הסוף", pad, "${spNum(c.overall.events)} האזנות בתקופה") {
            Text(spPct(c.overall.finishRate), fontSize = 40.sp, fontWeight = FontWeight.Black, color = GOOD)
            TriBar(c.overall, 16.dp)
            Spacer(Modifier.height(6.dp))
            Row {
                Text("● עד הסוף ${spPct(c.overall.finishRate)}", color = GOOD, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("● חלקית ${spPct(c.overall.partialRate)}", color = MID, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("● דילוג ${spPct(c.overall.skipRate)}", color = BAD, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            }
            SpHint("״עד הסוף״ ו״דילוג״ לפי הספים ש-Discover למד ממך (כמו ב-Replay).")
        }
    }
    fun list(key: String, title: String, rows: List<Completion>) {
        if (rows.isEmpty()) return
        item(key = key) {
            SpCard(title, pad, "3 האזנות לפחות") {
                rows.forEach { r ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row {
                            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("✅ ${spPct(r.finishRate)} · ⏭️ ${spPct(r.skipRate)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TriBar(r, 8.dp)
                    }
                }
            }
        }
    }
    list("sp_c_songs", "🎵 השירים המובילים", c.songs)
    list("sp_c_artists", "🎤 האמנים המובילים", c.artists)
}

// ====================================================================== ⚖️ compare

fun LazyListScope.compareItems(preset: ComparePreset, cmp: Comparison?, pad: Dp, onPreset: (ComparePreset) -> Unit) {
    item(key = "sp_cmp_presets") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = pad, vertical = 4.dp)) {
            val all = ComparePreset.values()
            items(all.size) { i -> Pill(all[i].he, selected = preset == all[i]) { onPreset(all[i]) } }
        }
    }
    if (cmp == null) { item(key = "sp_cmp_load") { SpCard("⚖️ השוואה", pad) { SpHint("מחשב…") } }; return }
    item(key = "sp_cmp_chart") {
        val running = cmp.a.window.to > V2Engine.now()
        SpCard("⚖️ ${cmp.a.label} מול ${cmp.b.label}", pad, if (running) "התקופה עדיין רצה, אז ההשוואה עד אותה נקודה בתקופה הקודמת" else null) {
            val max = maxOf(cmp.a.totalMs, cmp.b.totalMs).coerceAtLeast(1L)
            ShareBar(cmp.a.label, FullStats.hm(cmp.a.totalMs), cmp.a.totalMs.toDouble() / max)
            ShareBar(cmp.b.label, FullStats.hm(cmp.b.totalMs), cmp.b.totalMs.toDouble() / max, MaterialTheme.colorScheme.outline)
        }
    }
    item(key = "sp_cmp_table") {
        SpCard("זה לצד זה", pad) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Spacer(Modifier.weight(1.1f))
                Text(cmp.a.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text(cmp.b.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            cmp.rows.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.1f)) {
                        Text(r.he, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        r.changePct?.let { p ->
                            Text(if (p > 0) "↑ $p%" else if (p < 0) "↓ ${-p}%" else "= 0%", style = MaterialTheme.typography.labelSmall,
                                color = if (p > 0) GOOD else if (p < 0) BAD else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(r.a, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text(r.b, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ====================================================================== 🏅 milestones

fun LazyListScope.milestoneItems(list: List<Milestone>?, pad: Dp) {
    if (list == null) { item(key = "sp_m_load") { SpCard("🏅 אבני דרך", pad) { SpHint("מחשב…") } }; return }
    if (list.isEmpty()) { item(key = "sp_m_empty") { SpCard("🏅 אבני דרך", pad) { SpHint("עוד אין האזנות בפרופיל הזה.") } }; return }
    val reached = list.filter { it.reached }; val next = list.filter { !it.reached }
    item(key = "sp_m_reached") {
        SpCard("🏅 אבני דרך", pad, "כל הזמנים · מהחדשה לישנה") {
            reached.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.emoji, fontSize = 24.sp, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.he, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (m.detail.isNotBlank()) Text(m.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(spDate(m.date), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (next.isNotEmpty()) item(key = "sp_m_next") {
        SpCard("⏭️ הבאות בתור", pad) {
            next.forEach { m -> ShareBar("${m.emoji} ${m.he}", m.detail, m.progress) }
        }
    }
}

// ====================================================================== 💔 forgotten favourites

fun LazyListScope.forgottenItems(list: List<ForgottenSong>?, pad: Dp, compact: Boolean, limit: Int, onMore: () -> Unit,
                                 onPlayAll: () -> Unit, onShuffle: () -> Unit, onPlay: (Int) -> Unit) {
    if (list == null) { item(key = "sp_f_load") { SpCard("💔 אהובים שנשכחו", pad) { SpHint("מחשב…") } }; return }
    item(key = "sp_f_head") {
        SpCard("💔 אהובים שנשכחו", pad, "שירים ששמעת ${StatsPlus.FORGOTTEN_MIN_PLAYS}+ פעמים ולא שמעת ${StatsPlus.FORGOTTEN_DAYS}+ ימים") {
            if (list.isEmpty()) { SpHint("אין כרגע. כל האהובים שלך עדיין בסיבוב 🙂"); return@SpCard }
            Text("${spNum(list.size)} שירים מחכים לך", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton("▶ נגן הכל", Modifier.weight(1f)) { onPlayAll() }
                GradientButton("🔀 מיקס מעורבב", Modifier.weight(1f)) { onShuffle() }
            }
        }
    }
    val n = minOf(limit, list.size)
    items(n, key = { "sp_f_" + list[it].track.id }) { k ->
        val f = list[k]
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPlay(k) }.padding(horizontal = pad + 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) { Art(f.track.thumbnailUrl, f.track.id, Modifier.size(48.dp), 10); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(f.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.track.artistLabel} · ${spNum(f.plays)} השמעות · לא שמעת ${spNum(f.daysSince)} ימים", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("▶", fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (n < list.size) item(key = "sp_f_more") {
        Box(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 8.dp), contentAlignment = Alignment.Center) { Pill("הצג עוד (${spNum(list.size - n)} נשארו)") { onMore() } }
    }
}

// ====================================================================== 🌱 new vs repeat, 💞 loyalty (in גילוי)

fun LazyListScope.extraDiscoveryItems(newShare: List<StatBar>?, loyalty: List<Loyalty>?, pad: Dp) {
    if (!newShare.isNullOrEmpty()) item(key = "sp_newshare") {
        SpCard("🌱 חדש מול מוכר, לפי חודש", pad, "% מהזמן על שירים ששמעת לראשונה באותו חודש · 12 החודשים האחרונים") {
            SimpleBars(newShare, { "$it%" }, 100L)
        }
    }
    if (!loyalty.isNullOrEmpty()) item(key = "sp_loyalty") {
        SpCard("💞 נאמנות לאמנים", pad, "האמנים המובילים בכל הזמנים: בכמה מהחודשים מאז שגילית אותם חזרת אליהם") {
            loyalty.forEach { l -> ShareBar(l.name, "${l.monthsActive}/${l.monthsSpan} חודשים", l.ratio, MaterialTheme.colorScheme.tertiary) }
        }
    }
}

// ====================================================================== home: "💔 אהובים שנשכחו" card

/** A one-tap mix of the profile's forgotten favourites on Discover's home (hidden when there are fewer than 5). */
@Composable
fun ForgottenHomeCard(profileId: String) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    var list by remember(profileId) { mutableStateOf<List<ForgottenSong>>(emptyList()) }
    LaunchedEffect(profileId) {
        list = runCatching {
            val ix = StatsCache.index(ctx, db)
            withContext(Dispatchers.Default) { StatsPlus.forgotten(ix, V2Engine.now(), limit = 60) }
        }.getOrDefault(emptyList())
    }
    if (list.size < 5) return
    val cs = MaterialTheme.colorScheme
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        brush = Brush.linearGradient(listOf(cs.tertiary, cs.primary)),
        onClick = { pc.playTracks(list.take(40).shuffled().map { it.track }, "💔 אהובים שנשכחו") }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("💔 אהובים שנשכחו", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("${list.size} שירים שאהבת ולא שמעת ${StatsPlus.FORGOTTEN_DAYS}+ ימים · לחיצה אחת ומתנגן", color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Art(list.first().track.thumbnailUrl, "forgotten_" + list.first().track.id, Modifier.size(56.dp), 12)
        }
    }
}
