/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.ListQueue
import kotlin.math.abs

/** Vivid accents of the v2 look (dark theme, colours from the art where we have it). */
object V2Colors {
    val purple = Color(0xFF8B5CF6)
    val pink = Color(0xFFEC4899)
    val cyan = Color(0xFF22D3EE)
    val orange = Color(0xFFF97316)
    val lime = Color(0xFFA3E635)
    val gold = Color(0xFFFACC15)
    val blue = Color(0xFF3B82F6)
    val hero = Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFF97316)))
    val cool = Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)))
    val night = Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF581C87)))
    private val pairs = listOf(
        Color(0xFF7C3AED) to Color(0xFFDB2777), Color(0xFF0EA5E9) to Color(0xFF6366F1), Color(0xFFF97316) to Color(0xFFE11D48),
        Color(0xFF10B981) to Color(0xFF0EA5E9), Color(0xFFF59E0B) to Color(0xFFEF4444), Color(0xFF8B5CF6) to Color(0xFF22D3EE),
    )
    fun forKey(key: String): Brush { val p = pairs[abs(key.hashCode()) % pairs.size]; return Brush.linearGradient(listOf(p.first, p.second)) }
}

@Composable
fun V2Rtl(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
}

fun bigThumb(url: String?, px: Int = 544): String? = url?.replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px")

fun TrackMeta.mediaItem(): MediaItem = SongItem(
    id = id,
    title = title,
    artists = artistNames.mapIndexed { i, n -> Artist(n, artistIds.getOrNull(i)?.takeUnless { it.startsWith("name:") }) },
    duration = durationSec.takeIf { it > 0 },
    thumbnail = thumbnailUrl ?: "",
).toMediaItem()

fun PlayerConnection.playTracks(list: List<TrackMeta>, title: String, start: Int = 0) {
    if (list.isEmpty()) return
    playQueue(ListQueue(title = title, items = list.map { it.mediaItem() }, startIndex = start.coerceIn(0, list.size - 1)))
}

fun PlayerConnection.playMix(mix: BuiltMix, context: Context) {
    if (mix.tracks.isEmpty()) { toast(context, "לא מצאתי שירים שמתאימים 😅 נסה לשנות את ההגדרות"); return }
    playTracks(mix.tracks, mix.title)
    toast(context, "▶️ ${mix.title} · ${mix.tracks.size} שירים" + (mix.note?.let { " · $it" } ?: ""))
}

/** Keeps the current song, replaces everything after it (vibe buttons). */
fun PlayerConnection.replaceUpcoming(list: List<TrackMeta>) {
    val p = player
    val idx = p.currentMediaItemIndex
    val count = p.mediaItemCount
    if (count > idx + 1) p.removeMediaItems(idx + 1, count)
    addToQueue(list.map { it.mediaItem() })
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** Padding for full-screen v2 pages: status bar on top, mini player / nav bar at the bottom. */
@Composable
fun v2ContentPadding(): PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    return PaddingValues(top = top + 8.dp, bottom = bottom + 32.dp)
}

/** A full-screen v2 page: RTL, own header with a back button, lazy content. */
@Composable
fun V2Page(title: String, subtitle: String? = null, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    V2Rtl {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = v2ContentPadding(), modifier = Modifier.fillMaxSize()) {
                item(key = "v2_header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                        ) { Text("→", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
fun V2Card(modifier: Modifier = Modifier, brush: Brush? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    var m = modifier.clip(shape)
    m = if (brush != null) m.background(brush) else m.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m) { content() }
}

@Composable
fun Art(url: String?, key: String, modifier: Modifier, corner: Int = 14) {
    val shape = RoundedCornerShape(corner.dp)
    Box(modifier.clip(shape).background(V2Colors.forKey(key))) {
        if (!url.isNullOrBlank()) AsyncImage(model = bigThumb(url, 226), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun TrackRow(t: TrackMeta, extra: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Art(t.thumbnailUrl, t.id, Modifier.size(48.dp), 10)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(t.artistLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (extra != null) Text(extra, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Pill(text: String, selected: Boolean = false, brush: Brush? = null, onClick: () -> Unit) {
    val bg = when {
        selected -> brush ?: V2Colors.hero
        else -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

/** A list of tracks inside a lazy page, with a "play all" header. */
fun LazyListScope.trackList(keyPrefix: String, tracks: List<TrackMeta>, extra: (TrackMeta) -> String? = { null }, onPlay: (Int) -> Unit) {
    tracks.forEachIndexed { i, t ->
        item(key = "${keyPrefix}_${i}_${t.id}") { TrackRow(t, extra(t)) { onPlay(i) } }
    }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, brush: Brush = V2Colors.hero, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (enabled) brush else V2Colors.night)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
