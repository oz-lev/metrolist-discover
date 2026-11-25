/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Unified Material 3 UI foundation matching upstream Metrolist's design system:
 * surfaceContainer tokens, 16.dp card radius, 16.dp horizontal padding, 12.dp spacing, >=48.dp touch targets, RTL-safe.
 */
package com.metrolist.music.discover.v2.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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

/** Accent palette kept for art placeholders & legacy callers; surfaces now follow MaterialTheme.colorScheme. */
object V2Colors {
    val purple = Color(0xFF8B5CF6)
    val pink = Color(0xFFEC4899)
    val cyan = Color(0xFF22D3EE)
    val orange = Color(0xFFF97316)
    val lime = Color(0xFFA3E635)
    val gold = Color(0xFFFACC15)
    val blue = Color(0xFF3B82F6)
    val hero = Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)))
    val cool = Brush.linearGradient(listOf(Color(0xFF0284C7), Color(0xFF4F46E5)))
    val night = Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF312E81)))
    private val pairs = listOf(
        Color(0xFF3B3468) to Color(0xFF5B3A6E), Color(0xFF1E3A5F) to Color(0xFF2E4374), Color(0xFF5C3434) to Color(0xFF6E3B4B),
        Color(0xFF1E4D40) to Color(0xFF1E465E), Color(0xFF5C4426) to Color(0xFF6B3A2E), Color(0xFF3E356B) to Color(0xFF245263),
    )
    fun forKey(key: String): Brush { val p = pairs[abs(key.hashCode()) % pairs.size]; return Brush.linearGradient(listOf(p.first, p.second)) }
    fun forKeyColor(key: String): Color = pairs[abs(key.hashCode()) % pairs.size].first.copy(alpha = 0.65f)
}

/** 13.7.0.15: subtle M3 tonal hero brush built from the active ColorScheme. */
@Composable
fun themeHero(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.primaryContainer, c.secondaryContainer))
}

@Composable
fun themeCool(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.secondaryContainer, c.tertiaryContainer))
}

/** Haptic feedback toggle: buzzes lightly on taps when the setting is on. */
@Composable
fun rememberTap(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    return remember(haptic) { { if (com.metrolist.music.discover.v2.V2Settings.get(ctx).haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** True on narrow phones (< 360 dp wide). */
@Composable
fun isCompactScreen(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360

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

/** Padding for full-screen Discover pages: status bar on top, mini player / nav bar at the bottom. */
@Composable
fun v2ContentPadding(): PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    return PaddingValues(top = top + 8.dp, bottom = bottom + 32.dp)
}

/** Full-screen unified Material 3 page: RTL, consistent header with 48.dp back button, lazy content. */
@Composable
fun V2Page(title: String, subtitle: String? = null, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    V2Rtl {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = v2ContentPadding(), modifier = Modifier.fillMaxSize()) {
                item(key = "v2_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(onClick = onBack),
                        ) {
                            Text("→", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
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
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Unified Material 3 card: 16.dp corner radius, surfaceContainerLow + subtle outlineVariant border when flat. */
@Composable
fun V2Card(modifier: Modifier = Modifier, brush: Brush? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val cs = MaterialTheme.colorScheme
    var m = modifier.clip(shape)
    m = if (brush != null) {
        m.background(brush)
    } else {
        m.background(cs.surfaceContainerLow).border(1.dp, cs.outlineVariant.copy(alpha = 0.45f), shape)
    }
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m) { content() }
}

@Composable
fun Art(url: String?, key: String, modifier: Modifier, corner: Int = 12) {
    val shape = RoundedCornerShape(corner.dp)
    Box(modifier.clip(shape).background(V2Colors.forKey(key))) {
        if (!url.isNullOrBlank()) AsyncImage(model = bigThumb(url, 226), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun TrackRow(
    t: TrackMeta,
    extra: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Art(t.thumbnailUrl, t.id, Modifier.size(48.dp), 10)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                t.artistLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (extra != null) {
            Text(
                extra,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        if (trailing != null) trailing()
    }
}

/** Unified Material 3 FilterChip-style pill: primaryContainer when selected, surfaceContainerHigh when unselected. */
@Composable
fun Pill(text: String, selected: Boolean = false, brush: Brush? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tap = rememberTap()
    val shape = RoundedCornerShape(12.dp)
    if (selected && brush != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.heightIn(min = 38.dp).clip(shape).background(brush).clickable { tap(); onClick() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Surface(
        shape = shape,
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        border = BorderStroke(1.dp, if (selected) cs.primary.copy(alpha = 0.55f) else cs.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.heightIn(min = 38.dp).clip(shape).clickable { tap(); onClick() },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A list of tracks inside a lazy page, with quick-add playlist support. */
fun LazyListScope.trackList(keyPrefix: String, tracks: List<TrackMeta>, extra: (TrackMeta) -> String? = { null }, onPlay: (Int) -> Unit) {
    tracks.forEachIndexed { i, t ->
        item(key = "${keyPrefix}_${i}_${t.id}") {
            TrackRow(
                t = t,
                extra = extra(t),
                trailing = { com.metrolist.music.discover.v21.ui.QuickAddSongIcon(t, compact = true) },
            ) { onPlay(i) }
        }
    }
}

/** Unified Material 3 primary CTA button (keeps [brush] param for compatibility, defaults to M3 primary tonal fill). */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, brush: Brush? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tap = rememberTap()
    val shape = RoundedCornerShape(16.dp)
    if (brush != null && enabled) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.heightIn(min = 48.dp).clip(shape).background(brush)
                .clickable(enabled = enabled) { tap(); onClick() }.padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(text, color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Surface(
        shape = shape,
        color = if (enabled) cs.primary else cs.surfaceContainerHighest,
        contentColor = if (enabled) cs.onPrimary else cs.onSurfaceVariant,
        modifier = modifier.heightIn(min = 48.dp).clip(shape).clickable(enabled = enabled) { tap(); onClick() },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Secondary Material 3 tonal CTA button. */
@Composable
fun TonalActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tap = rememberTap()
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (enabled) cs.secondaryContainer else cs.surfaceContainerHighest,
        contentColor = if (enabled) cs.onSecondaryContainer else cs.onSurfaceVariant,
        modifier = modifier.heightIn(min = 48.dp).clip(shape).clickable(enabled = enabled) { tap(); onClick() },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
