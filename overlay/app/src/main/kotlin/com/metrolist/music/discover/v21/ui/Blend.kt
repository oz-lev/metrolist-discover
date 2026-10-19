/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.Blend
import com.metrolist.music.discover.engine.TasteFile
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.playMix
import com.metrolist.music.discover.v2.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Blend with a friend, fully offline: export a small taste file (top artists + songs, no history), send it on WhatsApp;
 * the friend opens it in their Metrolist Discover and gets a shared mix (shared artists first, then alternating).
 */
@Composable
fun BlendScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val s = remember { V2Settings.get(ctx) }
    var name by remember { mutableStateOf(s.blendName) }
    var friend by remember { mutableStateOf<TasteFile?>(null) }
    var match by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }?.let { Blend.decode(it) } }.getOrNull() }
            if (f == null) { toast(ctx, "זה לא קובץ טעם של Metrolist Discover 🤔"); return@launch }
            friend = f
            match = runCatching { Blend.matchPct(V2Engine.blendMine(ctx, db, name), f) }.getOrNull()
        }
    }

    V2Page(title = "Blend עם חבר 🤝", subtitle = "מיקס משותף, בלי שרת ובלי חשבון", onBack = onBack) {
        item(key = "me") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Group("📤", "1. שלח את הטעם שלך", Brush.linearGradient(listOf(Color(0xFF065F46), Color(0xFF10B981)))) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24); s.blendName = name }, singleLine = true, label = { Text("השם שלך בקובץ") }, modifier = Modifier.fillMaxWidth())
                    GradientButton("📤 ייצא ושלח בוואטסאפ", Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        busy = "מכין את קובץ הטעם…"
                        scope.launch {
                            val ok = runCatching {
                                val f = V2Engine.blendMine(ctx, db, name.ifBlank { "חבר" })
                                val file = withContext(Dispatchers.IO) {
                                    val dir = File(ctx.cacheDir, "discover_share").apply { mkdirs() }
                                    File(dir, "taste-${f.name.filter { it.isLetterOrDigit() }.ifBlank { "me" }}.mdblend.txt").apply { writeText(Blend.encode(f)) }
                                }
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".FileProvider", file)
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_TEXT, "🤝 הטעם המוזיקלי שלי ל-Blend. פתח את הקובץ ב-Metrolist Discover ← Blend ← פתח קובץ של חבר")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(Intent.createChooser(send, "שלח את הטעם שלך").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.isSuccess
                            busy = null
                            if (!ok) toast(ctx, "לא הצלחתי לייצא 😕")
                        }
                    }
                    Text("בקובץ יש רק האמנים והשירים הכי שמועים שלך. לא היסטוריה, לא שעות.", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item(key = "friend") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Group("📥", "2. פתח את הקובץ של החבר", Brush.linearGradient(listOf(Color(0xFF581C87), Color(0xFFDB2777)))) {
                    GradientButton("📂 בחר קובץ של חבר", Modifier.fillMaxWidth()) { runCatching { picker.launch(arrayOf("*/*")) } }
                    friend?.let { f ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Text("🎧 ${f.name}: ${f.artists.size} אמנים, ${f.tracks.size} שירים", color = Color.White, fontWeight = FontWeight.Bold)
                            match?.let { Text("התאמה ביניכם: $it%", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black) }
                            Text("אמנים שלו: " + f.artists.take(6).joinToString(", ") { it.first }, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                            GradientButton("▶ נגן את ה-Blend", Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                busy = "מערבב את שניכם…"
                                scope.launch {
                                    val mix = runCatching { V2Engine.blendMix(ctx, db, f, name.ifBlank { "אני" }) }.getOrNull()
                                    busy = null
                                    if (mix == null) toast(ctx, "לא הצלחתי, נסה שוב") else pc?.playMix(mix, ctx)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "hint") { Hint("הכל בטלפון: אין שרת, אין חשבון. לחבר צריך גם את Metrolist Discover.", Modifier.padding(vertical = 8.dp)) }
    }
    busy?.let { BusyOverlay(it) }
}
