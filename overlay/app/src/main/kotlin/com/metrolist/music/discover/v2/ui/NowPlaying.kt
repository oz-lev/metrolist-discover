/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.engine.VibeAction
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hook 5: shown under the player controls (portrait). Vibe buttons that reshape the upcoming queue,
 * and an animated strip in the album art's own colours. The strip is a light animation driven by the
 * play state and the song's estimated energy (not an audio analyser: that would need an audio-effect
 * session or a change to Metrolist's audio pipeline).
 */
@Composable
fun NowPlayingVibeBar() {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val settings = remember(version) { V2Settings.get(ctx) }
    if (!settings.vibeBar && !settings.animatedArt) return
    val md by pc.mediaMetadata.collectAsState()
    val playing by pc.isPlaying.collectAsState()
    val meta = md ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<VibeAction?>(null) }
    var colors by remember { mutableStateOf(listOf(V2Colors.purple, V2Colors.pink)) }
    val track = remember(meta.id) {
        TrackMeta(meta.id, meta.title, meta.artists.map { a -> a.id ?: ("name:" + a.name.lowercase()) }, meta.artists.map { it.name },
            meta.album?.id, meta.duration, meta.thumbnailUrl)
    }
    val energy = remember(meta.id) { runCatching { V2Engine.vibeOf(ctx, track).energy }.getOrDefault(0.5).toFloat() }

    LaunchedEffect(meta.thumbnailUrl) {
        val url = meta.thumbnailUrl ?: return@LaunchedEffect
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(96, 96).allowHardware(false).build()).image?.toBitmap()
                bmp?.let { b ->
                    val p = Palette.from(b).generate()
                    val a = p.getVibrantColor(p.getDominantColor(0xFF8B5CF6.toInt()))
                    val z = p.getLightVibrantColor(p.getMutedColor(0xFFEC4899.toInt()))
                    listOf(Color(a), Color(z))
                }
            }.getOrNull()
        }
        if (c != null) colors = c
    }

    V2Rtl {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            if (settings.animatedArt) ArtPulse(colors, playing, energy)
            if (settings.vibeBar) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    listOf(VibeAction.MORE_LIKE_THIS to "🔁 עוד כזה", VibeAction.CHANGE_VIBE to "🔀 שנה וייב", VibeAction.ENERGY_UP to "⚡ אנרגיה ↑").forEach { (a, label) ->
                        val sel = busy == a
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .background(if (sel) Brush.linearGradient(colors) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.12f))))
                                .clickable(enabled = busy == null) {
                                    busy = a
                                    scope.launch {
                                        val mix = runCatching { V2Engine.reshape(ctx, db, track, a) }.getOrNull()
                                        busy = null
                                        if (mix == null || mix.tracks.isEmpty()) toast(ctx, "לא מצאתי המשך מתאים 😅")
                                        else { pc.replaceUpcoming(mix.tracks); toast(ctx, "✨ ${mix.title}: ${mix.tracks.size} שירים הבאים עודכנו") }
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) { Text(if (sel) "…" else label, color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
                com.metrolist.music.discover.v21.ui.NowPlayingTools()
            }
        }
    }
}

@Composable
private fun ArtPulse(colors: List<Color>, playing: Boolean, energy: Float) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween((2600 - 1400 * energy).toInt().coerceAtLeast(700), easing = LinearEasing)), label = "phase")
    val amp by animateFloatAsState(if (playing) 0.35f + 0.6f * energy else 0.06f, label = "amp")
    val bars = 32
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width / bars
        val brush = Brush.horizontalGradient(colors)
        for (i in 0 until bars) {
            val x = i.toFloat() / bars
            val v = 0.5f + 0.5f * sin(phase * 2 + x * 9f) * sin(phase + x * 23f + i)
            val h = size.height * (0.12f + amp * v).coerceIn(0.08f, 1f)
            drawRoundRect(brush, topLeft = Offset(i * w + w * 0.2f, (size.height - h) / 2f), size = Size(w * 0.6f, h), cornerRadius = CornerRadius(w * 0.3f))
        }
    }
}
