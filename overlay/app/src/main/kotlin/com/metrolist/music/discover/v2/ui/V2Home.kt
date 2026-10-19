/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.DiscoverItem
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.ui.DiscoverSheet
import com.metrolist.music.discover.ui.DiscoverViewModel
import com.metrolist.music.discover.ui.StatsBubble
import com.metrolist.music.discover.ui.reasonText
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import kotlinx.coroutines.launch

/** Remembered across recompositions / tab switches so the hero does not rebuild every time. */
private object HomeCache { var daily: BuiltMix? = null; var builtAt = 0L; var match: Map<String, Int> = emptyMap() }

@Composable
fun V2HomeSection() {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val nav = LocalNavController.current
    val player = LocalPlayerConnection.current ?: return
    val vm: DiscoverViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val settings = remember(version) { V2Settings.get(ctx) }
    val scope = rememberCoroutineScope()
    var daily by remember { mutableStateOf(HomeCache.daily) }
    var match by remember { mutableStateOf(HomeCache.match) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        if (daily == null || System.currentTimeMillis() - HomeCache.builtAt > 30 * 60_000L || version > 0) {
            runCatching { V2Engine.daily(ctx, db) }.getOrNull()?.let { daily = it; HomeCache.daily = it; HomeCache.builtAt = System.currentTimeMillis() }
        }
    }
    LaunchedEffect(state.items) {
        if (state.items.isNotEmpty()) runCatching { V2Engine.matchPercents(ctx, state.items.map { it.track.id }) }.getOrNull()?.let { match = it; HomeCache.match = it }
    }

    fun run(label: String, block: suspend () -> BuiltMix) {
        if (busy != null) return
        busy = label
        scope.launch {
            val mix = runCatching { block() }.getOrNull()
            busy = null
            if (mix == null) toast(ctx, "משהו השתבש, נסה שוב") else player.playMix(mix, ctx)
        }
    }

    V2Rtl {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            // greeting
            val hour = DayClock.dateTime(V2Engine.now()).hour
            val hello = when (hour) { in 5..11 -> "בוקר טוב"; in 12..16 -> "צהריים טובים"; in 17..20 -> "ערב טוב"; else -> "לילה טוב" }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("$hello${settings.greetingName.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""} 👋",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text("מה בא לך לשמוע?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RoundIcon("🏆") { nav.navigate(V2Routes.PROFILE) }
                RoundIcon("🧪") { nav.navigate(V2Routes.WEIRD) }
            }

            // mood chips
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MoodPreset.chips, key = { "chip_" + it.name }) { p ->
                    Pill("${p.emoji} ${p.he}", selected = false) { run("${p.emoji} ${p.he}…") { V2Engine.mood(ctx, db, p) } }
                }
            }

            // someone else's taste banner
            settings.activeTaste()?.let { t ->
                val left = ((settings.tasteUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), brush = V2Colors.cool) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("${t.emoji} מצב טעם: ${t.he} · עוד $left דק׳", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("עצור", color = Color.White, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { settings.tastePreset = null; settings.tasteUntil = 0L }.padding(8.dp))
                    }
                }
            }

            // hero: the daily mix
            HeroCard(daily, onPlay = { daily?.let { player.playMix(it, ctx) } ?: run("בונה את המיקס היומי…") { V2Engine.daily(ctx, db) } })

            // hub tiles
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Tile("🎛️", "מרכז המיקסים", "חוגה וסליידרים", Modifier.weight(1f), Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFF7C3AED)))) { nav.navigate(V2Routes.MIXES) }
                Tile("🔎", "חיפושים מוזרים", "כמו X אבל…", Modifier.weight(1f), Brush.linearGradient(listOf(Color(0xFF0C4A6E), Color(0xFF0891B2)))) { nav.navigate(V2Routes.SEARCH) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Tile("🎲", "רולטה", "שיר אקראי לגמרי", Modifier.weight(1f), Brush.linearGradient(listOf(Color(0xFF064E3B), Color(0xFF10B981)))) {
                    if (busy == null) {
                        busy = "מגריל…"
                        scope.launch {
                            val (seed, t) = runCatching { V2Engine.roulette(ctx) }.getOrDefault("" to null)
                            busy = null
                            if (t == null) toast(ctx, "הרולטה נתקעה, נסה שוב 🎲") else { player.playTracks(listOf(t), "🎲 רולטה"); toast(ctx, "🎲 \"$seed\" ← ${t.title}") }
                        }
                    }
                }
                Tile("🔄", "רגש להיפך", "מעצוב למסיבה", Modifier.weight(1f), Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFFDB2777)))) {
                    run("הופך רגשות…") { V2Engine.moodFlip(ctx, db) }
                }
            }

            // discover for you, with % match
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("גילוי בשבילך ✨", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("שירים חדשים שהמנוע חושב שתאהב", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else RoundIcon("🔄") { vm.refresh() }
                RoundIcon("⚙️") { vm.loadStats(); showSheet = true }
            }
            if (state.items.isEmpty()) {
                Hint(if (state.coldStart) "צריך עוד קצת היסטוריית האזנה כדי להתחיל לנחש 🙂" else "טוען המלצות…", Modifier.padding(vertical = 8.dp))
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.items, key = { _, it -> "v2d_" + it.track.id }) { i, item ->
                        DiscoverTile(item, match[item.track.id]) { player.playTracks(state.items.map { it.track }, "גילוי בשבילך", i) }
                    }
                }
            }

            // the auto mixes from v1 (daily, forgotten favourites, time of day, "in the style of")
            if (state.mixes.isNotEmpty()) {
                Text("המיקסים שלך", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.mixes, key = { i, m -> "v2m_${m.kind}_$i" }) { _, mix ->
                        MiniMixCard(mix.titleHe, mix.tracks.firstOrNull()?.thumbnailUrl, mix.tracks.size) { player.playTracks(mix.tracks, mix.titleHe) }
                    }
                }
            }
        }
    }

    state.fact?.let { StatsBubble(it, onDismiss = vm::dismissFact, onNext = vm::nextFact) }
    if (showSheet) DiscoverSheet(state, vm) { showSheet = false }
    busy?.let { BusyOverlay(it) }
}

@Composable
private fun RoundIcon(emoji: String, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick)) { Text(emoji, fontSize = 22.sp) }
}

@Composable
private fun HeroCard(mix: BuiltMix?, onPlay: () -> Unit) {
    val glow = rememberInfiniteTransition(label = "hero")
    val shift by glow.animateFloat(0f, 1f, infiniteRepeatable(tween(6000), RepeatMode.Reverse), label = "shift")
    val brush = Brush.linearGradient(
        listOf(Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFF97316)),
        start = androidx.compose.ui.geometry.Offset(0f + 400f * shift, 0f), end = androidx.compose.ui.geometry.Offset(900f, 700f - 300f * shift),
    )
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), brush = brush, onClick = onPlay) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text("✨ נבנה במיוחד בשבילך", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                Text("המיקס היומי שלך", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                val sub = mix?.let { m ->
                    val min = m.tracks.sumOf { t -> t.durationSec.coerceAtLeast(0) } / 60
                    "${m.tracks.size} שירים · $min דק׳ · " + m.tracks.take(3).joinToString(", ") { it.artistLabel.substringBefore(",") }
                } ?: "מכין לך משהו טוב…"
                Text(sub, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp).clip(CircleShape).background(Color.White)) {
                    Text("▶", color = Color(0xFF7C3AED), fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(12.dp))
            // collage of four covers
            val t = mix?.tracks.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Art(t.getOrNull(0)?.thumbnailUrl, t.getOrNull(0)?.id ?: "a", Modifier.size(58.dp), 12)
                    Art(t.getOrNull(1)?.thumbnailUrl, t.getOrNull(1)?.id ?: "b", Modifier.size(58.dp), 12)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Art(t.getOrNull(2)?.thumbnailUrl, t.getOrNull(2)?.id ?: "c", Modifier.size(58.dp), 12)
                    Art(t.getOrNull(3)?.thumbnailUrl, t.getOrNull(3)?.id ?: "d", Modifier.size(58.dp), 12)
                }
            }
        }
    }
}

@Composable
private fun Tile(emoji: String, title: String, sub: String, modifier: Modifier, brush: Brush, onClick: () -> Unit) {
    V2Card(modifier, brush = brush, onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Text(emoji, fontSize = 26.sp)
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DiscoverTile(item: DiscoverItem, match: Int?, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Box {
            Art(item.track.thumbnailUrl, item.track.id, Modifier.fillMaxWidth().aspectRatio(1f), 18)
            if (match != null) {
                Text("$match% התאמה", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Text(item.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        Text(item.track.artistLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        Text(reasonText(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = V2Colors.pink, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun MiniMixCard(title: String, thumb: String?, count: Int, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box {
            Art(thumb, title, Modifier.fillMaxWidth().aspectRatio(1f), 16)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Text("$count שירים", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
    }
}
