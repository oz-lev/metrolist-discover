/**
 * Metrolist Discover 13.7.0.14 (fork addition) - GPL-3.0
 * "🧪 בונה מיקסים": pick up to 3 artists, a mood, energy, familiar <-> new, length and language -> one queue.
 * Plus artist radio (one artist picked) and a blend (two artists picked). Everything reads the active profile only.
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.LangMode
import com.metrolist.music.discover.engine.MixRecipe
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.playMix
import com.metrolist.music.discover.v2.ui.toast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LAB_SIZES = listOf(20, 30, 50, 100)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixLabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var artists by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val picked = remember { mutableStateListOf<String>() }
    var mood by remember { mutableStateOf<MoodPreset?>(null) }
    var autoEnergy by remember { mutableStateOf(true) }
    var energy by remember { mutableFloatStateOf(0.6f) }
    var newShare by remember { mutableFloatStateOf(0.3f) }
    var size by remember { mutableStateOf(30) }
    var lang by remember { mutableStateOf(LangMode.ALL) }
    LaunchedEffect(Unit) { artists = runCatching { V2Engine.seedArtists(ctx, db, 24) }.getOrDefault(emptyList()) }

    fun run(label: String, block: suspend () -> BuiltMix) {
        if (busy != null) return
        busy = label
        scope.launch {
            val mix = runCatching { block() }.getOrNull()
            busy = null
            if (mix == null) toast(ctx, "משהו השתבש, נסה שוב") else player?.playMix(mix, ctx)
        }
    }
    fun nameOf(id: String) = artists.firstOrNull { it.first == id }?.second ?: "האמן"

    V2Page(title = "🧪 בונה מיקסים", subtitle = "אתה בוחר, אני בונה תור", onBack = onBack) {
        item(key = "lab_artists_t") { SectionTitle("1. אמנים (עד 3, לא חובה)") }
        item(key = "lab_artists") {
            if (artists.isEmpty()) Hint("עוד אין מספיק האזנות בפרופיל הזה כדי להציע אמנים. אפשר לבנות לפי מצב רוח.")
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                artists.forEach { (id, name) ->
                    Pill(name, selected = id in picked) {
                        if (id in picked) picked.remove(id) else if (picked.size < 3) picked.add(id) else toast(ctx, "עד 3 אמנים")
                    }
                }
            }
        }
        item(key = "lab_mood_t") { SectionTitle("2. מצב רוח") }
        item(key = "lab_mood") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                Pill("✨ בלי", selected = mood == null) { mood = null }
                MoodPreset.entries.forEach { p -> Pill("${p.emoji} ${p.he}", selected = mood == p) { mood = p; p.target.energy?.let { e -> energy = e.toFloat() } } }
            }
        }
        item(key = "lab_sliders") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("⚡ אנרגיה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Pill(if (autoEnergy) "אוטומטי" else "${(energy * 100).roundToInt()}%", selected = autoEnergy) { autoEnergy = !autoEnergy }
                    }
                    Slider(value = energy, onValueChange = { energy = it; autoEnergy = false })
                    Row(Modifier.fillMaxWidth()) {
                        Text("רגוע", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("אנרגטי", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("🧭 מוכר ↔ חדש", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${(newShare * 100).roundToInt()}% חדש", style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(value = newShare, onValueChange = { newShare = it })
                    Row(Modifier.fillMaxWidth()) {
                        Text("רק מה שאני מכיר", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("רק חדש", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item(key = "lab_len_t") { SectionTitle("3. אורך ושפה") }
        item(key = "lab_len") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { LAB_SIZES.forEach { n -> Pill("$n שירים", selected = size == n) { size = n } } }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { LangMode.entries.forEach { l -> Pill(l.he, selected = lang == l) { lang = l } } }
            }
        }
        item(key = "lab_go") {
            GradientButton("🧪 בנה ונגן ▶", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                val r = MixRecipe(seedArtists = picked.toList(), mood = mood, energy = if (autoEnergy) null else energy.toDouble(),
                    newShare = newShare.toDouble(), size = size, lang = lang)
                run("בונה את המיקס שלך…") { V2Engine.lab(ctx, db, r) }
            }
        }
        if (picked.size == 1) item(key = "lab_radio") {
            val id = picked[0]
            GradientButton("🎤 רדיו של ${nameOf(id)}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                run("מכוון רדיו…") { V2Engine.artistRadio(ctx, db, id, nameOf(id)) }
            }
        }
        if (picked.size == 2) item(key = "lab_blend") {
            val a = picked[0]; val b = picked[1]
            GradientButton("🤝 Blend: ${nameOf(a)} × ${nameOf(b)}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                run("מערבב את שניהם…") { V2Engine.blendArtists(ctx, db, a to nameOf(a), b to nameOf(b)) }
            }
        }
        item(key = "lab_note") {
            Hint("בחרת אמן אחד? יש גם רדיו שלו. שניים? אפשר Blend שלהם ושל מה שביניהם. מתחת לנגן תראה 💡 למה כל שיר נבחר, ותוכל ללחוץ 👎 או 🚫.", Modifier.padding(top = 10.dp))
        }
    }
    busy?.let { BusyOverlay(it) }
}
