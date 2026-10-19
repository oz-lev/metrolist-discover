/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.AskMusic
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.ui.Art
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.V2Rtl
import com.metrolist.music.discover.v2.ui.playMix
import com.metrolist.music.discover.v2.ui.rememberTap
import com.metrolist.music.discover.v2.ui.toast
import kotlinx.coroutines.launch

/**
 * Car mode (מצב רכב): huge buttons, vibe tiles and a voice "play something like…".
 * Voice uses Android's system speech recognizer (RecognizerIntent): the recognizer app records, so this app needs no permission.
 */
@Composable
fun CarModeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    val md by pc.mediaMetadata.collectAsState()
    val playing by pc.isPlaying.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val tap = rememberTap()
    val cs = MaterialTheme.colorScheme

    fun run(label: String, block: suspend () -> BuiltMix) {
        if (busy != null) return
        busy = label
        scope.launch {
            val mix = runCatching { block() }.getOrNull()
            busy = null
            if (mix == null) toast(ctx, "לא הצלחתי, נסה שוב") else pc.playMix(mix, ctx)
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (said.isEmpty()) return@rememberLauncherForActivityResult
        val q = said.replace(Regex("^(תנגן|נגן|תשים|שים|play)\\s+(לי\\s+)?(משהו\\s+)?", RegexOption.IGNORE_CASE), "").trim()
        toast(ctx, "🎙️ \"$said\"")
        if (AskMusic.parse(q).matched.isNotEmpty()) run("🎙️ $q…") { V2Engine.ask(ctx, db, q) }
        else run("🎙️ $q…") { V2Engine.likeXBut(ctx, db, q) }
    }

    V2Rtl {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF05050A), cs.primary.copy(alpha = 0.25f)))).padding(WindowInsets.systemBars.asPaddingValues()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("🚗 מצב רכב", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable { tap(); onBack() }) {
                    Text("✕", color = Color.White, fontSize = 28.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(md?.thumbnailUrl, md?.id ?: "car", Modifier.size(96.dp), 20)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(md?.title ?: "אין שיר", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(md?.artists?.joinToString { it.name }.orEmpty(), color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton("⏮", Modifier.weight(1f)) { tap(); pc.seekToPrevious() }
                BigButton(if (playing) "⏸" else "▶", Modifier.weight(1.3f), cs.primary) { tap(); pc.togglePlayPause() }
                BigButton("⏭", Modifier.weight(1f)) { tap(); pc.seekToNext() }
            }
            Spacer(Modifier.height(14.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.horizontalGradient(listOf(cs.primary, cs.secondary, cs.tertiary))).clickable {
                    tap()
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "תגיד: תנגן משהו כמו…")
                    try { voice.launch(i) } catch (e: ActivityNotFoundException) { toast(ctx, "אין בטלפון זיהוי דיבור 😕") }
                }) {
                Text("🎙️ \"תנגן משהו כמו…\"", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            val tiles = listOf(MoodPreset.DRIVE, MoodPreset.PARTY, MoodPreset.MIZRAHI, MoodPreset.GYM, MoodPreset.CHILL, MoodPreset.NIGHT)
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    row.forEach { p ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).aspectRatio(1.25f).clip(RoundedCornerShape(26.dp))
                            .background(Color.White.copy(alpha = 0.10f)).clickable { tap(); run("${p.emoji} ${p.he}…") { V2Engine.mood(ctx, db, p) } }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(p.emoji, fontSize = 34.sp)
                                Text(p.he, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton("🧯 רגוע", Modifier.weight(1f)) { tap(); run("🧯…") { V2Engine.panic(ctx, db) } }
                BigButton("📅 יומי", Modifier.weight(1f)) { tap(); run("המיקס היומי…") { V2Engine.daily(ctx, db) } }
            }
        }
    }
    busy?.let { BusyOverlay(it) }
}

@Composable
private fun BigButton(text: String, modifier: Modifier, color: Color = Color.White.copy(alpha = 0.12f), onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.height(96.dp).clip(RoundedCornerShape(30.dp)).background(color).clickable(onClick = onClick)) {
        Text(text, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
    }
}
