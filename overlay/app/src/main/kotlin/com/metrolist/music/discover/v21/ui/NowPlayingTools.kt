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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 0.75f)
private val SLEEPS = listOf(15, 30, 60, 0)

/** v2.1 tools row under the player's vibe buttons: panic, karaoke, smart shuffle, A-B loop, speed, gentle sleep, save queue. */
@Composable
fun NowPlayingTools() {
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

    val sleepLabel = if (sleepEnd > now) "😴 ${((sleepEnd - now) / 60_000L + 1)} דק׳" else "😴 שינה"
    val abLabel = when { loop != null -> "🔁 A-B ✕"; aMark != null -> "🔁 סמן B"; else -> "🔁 A-B" }
    val items = listOf<Pair<String, () -> Unit>>(
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
        sleepLabel to {
            val curIdx = if (sleepEnd > now) SLEEPS.indexOfFirst { it * 60_000L >= sleepEnd - now }.coerceAtLeast(0) else -1
            val next = SLEEPS[(curIdx + 1) % SLEEPS.size]
            if (next == 0) { V21Playback.cancelSleep(); toast(ctx, "טיימר השינה בוטל") } else { V21Playback.startSleep(ctx, next); now = System.currentTimeMillis(); toast(ctx, "😴 נרדם בעדינות בעוד $next דק׳") }
        },
        "💾 שמור תור" to {
            val q = V21Playback.currentQueue()
            if (q == null) toast(ctx, "אין תור") else {
                val slots = V21Playback.slots(ctx)
                val i = slots.indexOfFirst { it == null }.let { if (it < 0) 0 else it }
                V21Playback.saveSlot(ctx, i, q); toast(ctx, "💾 נשמר במשבצת ${i + 1}")
            }
        },
    )
    LazyRow(contentPadding = PaddingValues(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items.size) { i ->
            val (label, action) = items[i]
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).clickable { tap(); action() }.then(Modifier.padding(horizontal = 12.dp, vertical = 7.dp))) {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (karaoke) Dialog(onDismissRequest = { karaoke = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.metrolist.music.discover.v21.ui.KaraokeScreen(onBack = { karaoke = false })
    }
}
