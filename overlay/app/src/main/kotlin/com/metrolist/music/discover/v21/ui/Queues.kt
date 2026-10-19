/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.SavedQueue
import com.metrolist.music.discover.engine.SmartShuffle
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.Art
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.playTracks
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.V21Playback
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Smart Shuffle (Spotify idea): shuffles the current queue and slips a recommendation in after every N songs. */
suspend fun smartShuffle(ctx: android.content.Context, db: com.metrolist.music.db.MusicDatabase, pc: PlayerConnection): Boolean {
    val q = V21Playback.currentQueue() ?: return false
    val every = V2Settings.get(ctx).smartShuffleEvery
    val recs = runCatching { V2Engine.recs(ctx, db, q.tracks.size / every + 1, q.tracks.map { it.id }.toSet()) }.getOrDefault(emptyList())
    val mixed = SmartShuffle.mix(q.tracks, recs, every, Random(System.nanoTime()))
    pc.playTracks(mixed, "🔀 ערבוב חכם")
    toast(ctx, "🔀 ערבוב חכם: ${recs.size.coerceAtMost(q.tracks.size / every)} המלצות שולבו")
    return true
}

/** Multiple queues (Musicolet idea): 5 named slots + the history of the last 20 queues. */
@Composable
fun QueuesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val slots = remember(refresh) { V21Playback.slots(ctx) }
    val history = remember(refresh) { V21Playback.queueHistory(ctx) }
    var busy by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("d.M HH:mm", Locale.getDefault()) }

    fun play(q: SavedQueue) { pc.playTracks(q.tracks, q.name, q.startIndex); toast(ctx, "▶ ${q.name}") }

    V2Page(title = "התורים שלי 🗂️", subtitle = "5 תורים שמורים + היסטוריית תורים", onBack = onBack) {
        item(key = "smart") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Pill("🔀 ערבוב חכם לתור הנוכחי", selected = true) {
                    busy = "מערבב בחוכמה…"
                    scope.launch { val ok = smartShuffle(ctx, db, pc); busy = null; if (!ok) toast(ctx, "התור ריק") }
                }
            }
        }
        item(key = "slots_title") { SectionTitle("💾 תורים שמורים") }
        slots.forEachIndexed { i, q ->
            item(key = "slot_$i") {
                Group("${i + 1}", q?.name ?: "משבצת ריקה", Brush.linearGradient(if (q == null) listOf(Color(0xFF1F2937), Color(0xFF374151)) else listOf(Color(0xFF4C1D95), Color(0xFF7C3AED)))) {
                    if (q != null) Text("${q.tracks.size} שירים · נשמר ${fmt.format(Date(q.savedAt))}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Pill("💾 שמור כאן") {
                            val cur = V21Playback.currentQueue()
                            if (cur == null) toast(ctx, "אין תור לשמור") else { V21Playback.saveSlot(ctx, i, cur); refresh++; toast(ctx, "נשמר במשבצת ${i + 1}") }
                        }
                        if (q != null) {
                            Pill("▶ נגן", selected = true) { play(q) }
                            Pill("🗑️") { V21Playback.saveSlot(ctx, i, null); refresh++ }
                        }
                    }
                }
            }
        }
        item(key = "hist_title") { SectionTitle("🕘 היסטוריית תורים") }
        if (history.isEmpty()) item(key = "hist_empty") { Hint("עוד אין. כל תור חדש שתתחיל יופיע כאן.") }
        history.forEachIndexed { i, q ->
            item(key = "h_$i") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { play(q) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Art(q.tracks.firstOrNull()?.thumbnailUrl, q.tracks.firstOrNull()?.id ?: "q$i", Modifier.size(48.dp), 10)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${q.tracks.size} שירים · ${fmt.format(Date(q.savedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("▶", fontSize = 20.sp, modifier = Modifier.clip(CircleShape).padding(8.dp))
                }
            }
        }
    }
    busy?.let { BusyOverlay(it) }
}
