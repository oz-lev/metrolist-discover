/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.ui.playMix
import com.metrolist.music.discover.v2.ui.rememberTap
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.V21Playback
import com.metrolist.music.extensions.metadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 0.75f)

/** v2.1 tools row under the player's vibe buttons: panic, karaoke, smart shuffle, A-B loop, speed, gentle sleep, save queue. */
@Composable
fun NowPlayingTools(track: com.metrolist.music.discover.engine.TrackMeta? = null) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val tap = rememberTap()
    val loop by V21Playback.loop.collectAsState()
    val aMark by V21Playback.aMark.collectAsState()
    val sleepEnd by V21Playback.sleepEndAt.collectAsState()
    var speed by remember { mutableFloatStateOf(V21Playback.speed()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var karaoke by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(sleepEnd) { while (sleepEnd > 0L) { now = System.currentTimeMillis(); delay(15_000) } }

    // 13.7.0.14: "why this song?", radio from this song, 👎 / 🚫 feedback, and the discovery dial of the endless mix
    var why by remember { mutableStateOf<String?>(null) }
    var blockAsk by remember { mutableStateOf(false) }
    var discovery by remember { mutableStateOf(com.metrolist.music.discover.v2.V2Settings.get(ctx).mixNewShare) }
    LaunchedEffect(track?.id) { why = null; if (track != null) why = runCatching { V2Engine.why(ctx, db, track) }.getOrNull() }
    val endOfSong by V21Playback.sleepAtSongEnd.collectAsState()
    var sleepSheet by remember { mutableStateOf(false) }
    val sleepLabel = when {
        endOfSong -> "😴 סוף השיר"
        sleepEnd > now -> "😴 ${((sleepEnd - now) / 60_000L + 1)} דק׳"
        else -> "😴 שינה"
    }
    val abLabel = when { loop != null -> "🔁 A-B ✕"; aMark != null -> "🔁 סמן B"; else -> "🔁 A-B" }
    val discoveryLabel = DISCOVERY_STEPS.minByOrNull { kotlin.math.abs(it.first - discovery) }?.second ?: "🧭 גילוי"
    val trackItems = if (track == null) emptyList() else listOf<Pair<String, () -> Unit>>(
        "📻 רדיו מהשיר" to {
            if (!busy) { busy = true; scope.launch {
                val m = runCatching { V2Engine.songRadio(ctx, db, track) }.getOrNull(); busy = false
                val next = m?.tracks?.filter { it.id != track.id }.orEmpty()
                if (next.isEmpty()) toast(ctx, "לא מצאתי רדיו לשיר הזה 😅") else { pc.replaceUpcoming(next); toast(ctx, "📻 רדיו של ${track.title}: ${next.size} שירים הבאים") }
            } }
        },
        "👎 פחות כזה" to {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { V2Engine.lessLikeThis(ctx, db, track) } }
                runCatching { if (pc.player.currentMediaItem?.mediaId == track.id) pc.player.seekToNext() }
                V21Playback.requestRerank()
                toast(ctx, "👎 הבנתי. השיר לא יחזור במיקסים, ופחות מ${track.artistLabel}")
            }
        },
        "🚫 בלי האמן" to { blockAsk = true },
        discoveryLabel to {
            val i = DISCOVERY_STEPS.indexOfFirst { it.second == discoveryLabel }
            val (v, label) = DISCOVERY_STEPS[(i + 1) % DISCOVERY_STEPS.size]
            V2Engine.setDiscovery(ctx, v); discovery = v
            toast(ctx, "$label: ${(v * 100).toInt()}% שירים חדשים במיקס האינסופי (מהקבוצה הבאה)")
        },
    )
    val items = trackItems + listOf<Pair<String, () -> Unit>>(
        "🧯 פאניקה" to {
            if (!busy) { busy = true; scope.launch { val m = runCatching { V2Engine.panic(ctx, db) }.getOrNull(); busy = false; if (m != null) pc.playMix(m, ctx) else toast(ctx, "נסה שוב") } }
        },
        "🎤 קריוקי" to { karaoke = true },
        "🔀 ערבוב חכם" to { if (!busy) { busy = true; scope.launch { smartShuffle(ctx, db, pc); busy = false } } },
        abLabel to {
            when {
                loop != null -> { V21Playback.clearLoop(); toast(ctx, "הלולאה בוטלה") }
                aMark != null -> if (V21Playback.markB()) toast(ctx, "🔁 לולאה פעילה") else { V21Playback.clearLoop(); toast(ctx, "B צריך להיות אחרי A") }
                else -> { V21Playback.markA(); toast(ctx, "סומן A. לחץ שוב בסוף הקטע") }
            }
        },
        "⏩ ${if (speed == 1f) "מהירות" else "${speed}x"}" to {
            val next = SPEEDS[(SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % SPEEDS.size]
            V21Playback.setSpeed(next); speed = next; toast(ctx, "מהירות ${next}x")
        },
        // 13.7.0.13: the chip opens quick presets + the fade length (the cycling tap of 13.7.0.x is gone)
        sleepLabel to { sleepSheet = true },
        "💾 שמור תור" to {
            val q = V21Playback.currentQueue()
            if (q == null) toast(ctx, "אין תור") else {
                val slots = V21Playback.slots(ctx)
                val i = slots.indexOfFirst { it == null }.let { if (it < 0) 0 else it }
                V21Playback.saveSlot(ctx, i, q); toast(ctx, "💾 נשמר במשבצת ${i + 1}")
            }
        },
    )
    why?.let { w ->
        Text("💡 $w", color = Color.White.copy(alpha = 0.78f), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
    LazyRow(contentPadding = PaddingValues(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items.size) { i ->
            val (label, action) = items[i]
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).clickable { tap(); action() }.then(Modifier.padding(horizontal = 12.dp, vertical = 7.dp))) {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (blockAsk && track != null) com.metrolist.music.discover.v2.ui.V2Rtl {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { blockAsk = false },
            title = { Text("🚫 בלי ${track.artistLabel}?") },
            text = { Text("האמן לא יופיע יותר ב-Discover ובמיקסים של הפרופיל הזה. אפשר להחזיר בחלון Discover (\"להחזיר\").") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    blockAsk = false
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { V2Engine.blockArtist(ctx, db, track) } }
                        val removed = runCatching { removeUpcomingArtist(pc.player, track.mainArtist) }.getOrDefault(0)
                        runCatching { if (pc.player.currentMediaItem?.mediaId == track.id) pc.player.seekToNext() }
                        toast(ctx, "🚫 ${track.artistLabel} לא יחזור" + (if (removed > 0) " · הוצאתי $removed שירים שלו מהתור" else ""))
                    }
                }) { Text("כן, בלי האמן") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { blockAsk = false }) { Text("ביטול") } },
        )
    }
    if (sleepSheet) SleepTimerDialog(active = sleepEnd > now || endOfSong, onDismiss = { sleepSheet = false }) { now = System.currentTimeMillis() }
    if (karaoke) Dialog(onDismissRequest = { karaoke = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.metrolist.music.discover.v21.ui.KaraokeScreen(onBack = { karaoke = false })
    }
}


private val SLEEP_PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

/** 13.7.0.14: the endless mix's share of new songs, one tap per step. */
private val DISCOVERY_STEPS = listOf(0.1 to "🏠 מוכר", 0.3 to "⚖️ מאוזן", 0.45 to "🧭 גילוי", 0.6 to "🚀 הרבה חדש")

/** Removes the upcoming songs whose first artist is [artistId]; returns how many. */
private fun removeUpcomingArtist(p: androidx.media3.common.Player, artistId: String): Int {
    var n = 0
    val cur = p.currentMediaItemIndex
    for (i in p.mediaItemCount - 1 downTo cur + 1) {
        val md = p.getMediaItemAt(i).metadata
        val a = md?.artists?.firstOrNull()?.let { it.id ?: ("name:" + it.name.lowercase()) }
        if (a == artistId) { p.removeMediaItem(i); n++ }
    }
    return n
}
private val FADE_PRESETS = listOf(1, 3, 5, 10)

/** 13.7.0.13: sleep timer presets (minutes or the end of this song) and the fade-out length, saved per profile. */
@Composable
private fun SleepTimerDialog(active: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val set = com.metrolist.music.discover.v2.V2Settings.get(ctx)
    var fade by remember { mutableStateOf(set.sleepFadeMin) }
    com.metrolist.music.discover.v2.ui.V2Rtl {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("😴 טיימר שינה עדין") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("המוזיקה נחלשת בהדרגה ונעצרת.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                    Text("כמה זמן?", fontWeight = FontWeight.Bold)
                    SLEEP_PRESETS.chunked(3).forEach { row ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            row.forEach { m ->
                                com.metrolist.music.discover.v2.ui.Pill("$m דק׳") {
                                    V21Playback.startSleep(ctx, m); onChanged(); toast(ctx, "😴 נרדם בעדינות בעוד $m דק׳"); onDismiss()
                                }
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Row(modifier = Modifier.padding(top = 6.dp)) {
                        com.metrolist.music.discover.v2.ui.Pill("🎵 בסוף השיר הזה") {
                            if (V21Playback.startSleepAtSongEnd(ctx)) { onChanged(); toast(ctx, "😴 נעצר בסוף השיר הזה"); onDismiss() } else toast(ctx, "אין שיר מתנגן")
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
                    Text("דעיכה: $fade דק׳ אחרונות", fontWeight = FontWeight.Bold)
                    FADE_PRESETS.chunked(3).forEach { row ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            row.forEach { f ->
                                com.metrolist.music.discover.v2.ui.Pill("$f דק׳", selected = f == fade) { fade = f; set.sleepFadeMin = f; V21Playback.updateFade(ctx) }
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("סגור") } },
            dismissButton = {
                if (active) androidx.compose.material3.TextButton(onClick = { V21Playback.cancelSleep(); onChanged(); toast(ctx, "טיימר השינה בוטל"); onDismiss() }) { Text("בטל טיימר") }
            },
        )
    }
}
