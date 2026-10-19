/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.di.LyricsHelperEntryPoint
import com.metrolist.music.discover.engine.Lang
import com.metrolist.music.discover.v2.ui.V2Rtl
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Karaoke mode (קריוקי): full-screen synced lyrics from Metrolist's own lyrics (its cache, else its own providers),
 * Hebrew RTL, the current line highlighted and filled word by word (real word timings when the provider has them,
 * otherwise an estimate across the line), tap a line to jump there, share a line as a PNG card.
 */
@Composable
fun KaraokeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    val md by pc.mediaMetadata.collectAsState()
    var lines by remember { mutableStateOf<List<LyricsEntry>?>(null) }
    var plain by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(md?.id) {
        val m = md ?: return@LaunchedEffect
        loading = true; lines = null; plain = null
        val text = withContext(Dispatchers.IO) {
            val cached = runCatching { db.lyrics(m.id).first()?.lyrics }.getOrNull()?.takeIf { it.isNotBlank() && it != "LYRICS_NOT_FOUND" }
            cached ?: runCatching {
                EntryPointAccessors.fromApplication(ctx.applicationContext, LyricsHelperEntryPoint::class.java).lyricsHelper().getLyrics(m).lyrics
            }.getOrNull()?.takeIf { it.isNotBlank() && it != "LYRICS_NOT_FOUND" }
        }
        val parsed = text?.let { runCatching { LyricsUtils.parseLyrics(it) }.getOrNull() }?.filter { it.text.isNotBlank() }.orEmpty()
        if (parsed.size >= 2 && parsed.any { it.time > 0 }) lines = parsed else plain = text
        loading = false
    }
    LaunchedEffect(Unit) {
        while (isActive) { pos = runCatching { pc.player.currentPosition }.getOrDefault(0L); delay(50) }
    }
    val l = lines
    val cur = if (l == null) -1 else LyricsUtils.findCurrentLineIndex(l, pos)
    LaunchedEffect(cur) { if (cur >= 0) runCatching { listState.animateScrollToItem((cur - 2).coerceAtLeast(0)) } }

    V2Rtl {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.35f), cs.background, cs.background)))) {
            Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack)) { Text("→", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) {
                        Text("🎤 קריוקי", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text(md?.let { "${it.title} · ${it.artists.joinToString { a -> a.name }}" } ?: "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    if (l != null && cur >= 0) Box(contentAlignment = Alignment.Center, modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primary).clickable {
                        shareLyricCard(ctx, l[cur].text, md?.title.orEmpty(), md?.artists?.joinToString { it.name }.orEmpty(), cs.primary.toArgb(), cs.secondary.toArgb(), cs.tertiary.toArgb())
                    }.padding(horizontal = 14.dp, vertical = 8.dp)) { Text("📤 שתף שורה", color = cs.onPrimary, fontWeight = FontWeight.Bold) }
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    l != null -> LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 120.dp), modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(l, key = { i, e -> "${i}_${e.time}" }) { i, e ->
                            val end = l.getOrNull(i + 1)?.time ?: (e.time + 5_000L)
                            KaraokeLine(e, i == cur, i < cur, pos, end) { runCatching { pc.player.seekTo(e.time) } }
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(24.dp), modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(if (plain == null) "לא מצאתי מילים לשיר הזה 😕" else "אין מילים מסונכרנות לשיר הזה, הנה המילים:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            plain?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KaraokeLine(e: LyricsEntry, current: Boolean, past: Boolean, pos: Long, endMs: Long, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val words = e.words
    val progress = when {
        !current -> if (past) 1f else 0f
        !words.isNullOrEmpty() -> {
            val a = words.first().startTime * 1000; val b = words.last().endTime * 1000
            if (b <= a) 1f else ((pos - a) / (b - a)).toFloat().coerceIn(0f, 1f)
        }
        else -> if (endMs <= e.time) 1f else ((pos - e.time).toFloat() / (endMs - e.time)).coerceIn(0f, 1f)
    }
    val size by animateFloatAsState(if (current) 30f else 22f, label = "size")
    val fill = cs.primary
    val dim = cs.onSurface.copy(alpha = if (current) 0.45f else 0.35f)
    val rtl = Lang.hasHebrew(e.text) || e.text.any { it in '\u0600'..'\u06FF' }
    val p = progress.coerceIn(0.001f, 0.999f)
    val brush = if (rtl) Brush.horizontalGradient(0f to fill, p to fill, (p + 0.001f) to dim, startX = Float.POSITIVE_INFINITY, endX = 0f)
    else Brush.horizontalGradient(0f to fill, p to fill, (p + 0.001f) to dim)
    Text(
        e.text,
        style = TextStyle(brush = if (current) brush else null, fontSize = size.sp, fontWeight = if (current) FontWeight.Black else FontWeight.Bold, lineHeight = (size * 1.3f).sp),
        color = if (current) Color.Unspecified else if (past) fill.copy(alpha = 0.55f) else dim,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp).alpha(if (past && !current) 0.8f else 1f),
    )
}

/** Renders a lyric line as a 1080x1350 PNG card and opens the share sheet (WhatsApp, Instagram story…). */
fun shareLyricCard(ctx: Context, line: String, title: String, artist: String, c1: Int, c2: Int, c3: Int) {
    runCatching {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(c1, c2, c3), null, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = 0x55000000 })
        val font = runCatching { ResourcesCompat.getFont(ctx, R.font.discover_heebo) }.getOrNull()
        fun face(weight: Int): Typeface = if (font != null && Build.VERSION.SDK_INT >= 28) Typeface.create(font, weight, false) else Typeface.DEFAULT_BOLD
        val quote = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 220f; typeface = face(900) }
        canvas.drawText("”", w / 2f - 50f, 330f, quote)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = if (line.length > 60) 64f else 82f; typeface = face(800) }
        val layout = StaticLayout.Builder.obtain(line, 0, line.length, tp, w - 180)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setLineSpacing(0f, 1.15f).build()
        canvas.save(); canvas.translate(90f, (h - layout.height) / 2f); layout.draw(canvas); canvas.restore()
        val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textSize = 44f; typeface = face(600) }
        val credit = "$title · $artist"
        val cl = StaticLayout.Builder.obtain(credit, 0, credit.length, sp, w - 180).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setMaxLines(2).build()
        canvas.save(); canvas.translate(90f, h - 260f); cl.draw(canvas); canvas.restore()
        val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textSize = 34f; typeface = face(500); textAlign = Paint.Align.CENTER }
        canvas.drawText("🎤 Metrolist Discover", w / 2f, h - 90f, fp)
        val dir = File(ctx.cacheDir, "discover_share").apply { mkdirs() }
        val file = File(dir, "lyric_${System.currentTimeMillis() % 100000}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".FileProvider", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "שתף שורה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(ctx, "לא הצלחתי ליצור את הכרטיס 😕") }
}
