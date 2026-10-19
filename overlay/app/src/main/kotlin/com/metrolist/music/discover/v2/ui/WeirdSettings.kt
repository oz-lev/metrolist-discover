/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.engine.BlockKind
import com.metrolist.music.discover.engine.BlockRule
import com.metrolist.music.discover.engine.TastePreset
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Offline
import com.metrolist.music.discover.v2.V2Service
import com.metrolist.music.discover.v2.V2Settings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Hebrew day letters in week order (Sunday first, as in Israel) with their ISO numbers. */
private val DAYS = listOf("א" to 7, "ב" to 1, "ג" to 2, "ד" to 3, "ה" to 4, "ו" to 5, "ש" to 6)

private fun hhmm(min: Int) = "%02d:%02d".format(min / 60 % 24, min % 60)

private fun ruleLabel(r: BlockRule): String {
    val what = when (r.kind) { BlockKind.ARTIST -> "🎤 ${r.value}"; BlockKind.SAD_SONGS -> "💔 שירי פרידה ועצב"; BlockKind.KEYWORD -> "🔤 \"${r.value}\"" }
    val days = if (r.days.size == 7) "כל יום" else DAYS.filter { it.second in r.days }.joinToString(" ") { it.first + "׳" }
    val time = if (r.fromMinute == r.toMinute) "כל היום" else "${hhmm(r.fromMinute)}–${hhmm(r.toMinute)}"
    return "$what · $days · $time"
}

@Composable
fun WeirdSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { V2Settings.get(ctx) }
    var addRule by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    V2Page(title = "הגדרות מוזרות 🧪", subtitle = "כל הגדרה כאן באמת עובדת. בכיף 😄", onBack = onBack) {
        // ---------------- algorithm
        item(key = "algo") {
            Group("🧠", "האלגוריתם", Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFF6D28D9)))) {
                SettingSlider("כמה האלגוריתם מעז", s.boldness.toFloat(), 0f..1f, 0, { v ->
                    when { v < 0.15f -> "בטוח לגמרי 🛡️"; v < 0.4f -> "רגיל"; v < 0.7f -> "הרפתקן 🧭"; else -> "משוגע 🤪" } }, "בטוח", "משוגע") { s.boldness = it.toDouble(); V2Engine.invalidate() }
                SettingSlider("כמה מזרחית מותר", s.maxMizrahiPct.toFloat(), 0f..100f, 19, { "${it.roundToInt()}%" }, "0%", "100%") { s.maxMizrahiPct = it.roundToInt() }
                SettingSlider("עונש על שירים שדילגת עליהם", s.skipPenalty.toFloat(), 0f..2.5f, 0, { v ->
                    when { v < 0.2f -> "סולח לגמרי 😇"; v < 1.2f -> "רגיל"; v < 2f -> "קשוח"; else -> "לא סולח 😤" } }, "סולח", "נוקם") { s.skipPenalty = it.toDouble(); V2Engine.invalidate() }
            }
        }
        // ---------------- queue rules
        item(key = "queue") {
            Group("🎵", "חוקים לתור", Brush.linearGradient(listOf(Color(0xFF0C4A6E), Color(0xFF0E7490)))) {
                Toggle("🔒 נעילת חזרות", "אף שיר לא מתנגן פעמיים באותו יום", s.noRepeatToday) { s.noRepeatToday = it }
                Toggle("⏱️ רק שירים מתחת ל-3 דקות", "ארוכים מזה מדולגים", s.shortOnly) { s.shortOnly = it }
                Toggle("🚫 בלי רמיקסים", "גם slowed / sped up / nightcore", s.noRemix) { s.noRemix = it }
                Toggle("🎤 בלי הופעות חיות", "רק גרסאות אולפן", s.noLive) { s.noLive = it }
                Toggle("🐢 מצב צב", "רק שירים רגועים (אנרגיה נמוכה)", s.turtle) { s.turtle = it }
                Toggle("🎁 הפתעה כל 5 שירים", "במיקסים: כל שיר חמישי הוא משהו שלא שמעת אף פעם", s.surpriseEvery5) { s.surpriseEvery5 = it }
                Toggle("🛡️ החוקים בכל האפליקציה", "גם ברדיו ובהשמעה אוטומטית של Metrolist (דילוג אוטומטי)", s.guardEverywhere) { s.guardEverywhere = it }
                Toggle("💬 הודעה כשדילגתי", "\"דילגתי: כבר התנגן היום\"", s.guardToasts) { s.guardToasts = it }
            }
        }
        // ---------------- blocks
        item(key = "blocks") {
            Group("📅", "חסימות לפי ימים ושעות", Brush.linearGradient(listOf(Color(0xFF7F1D1D), Color(0xFFBE123C)))) {
                val rules = s.blocks
                if (rules.isEmpty()) Text("אין חסימות. דוגמה: בלי שירי פרידה ביום שישי 😂", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                rules.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(ruleLabel(r), color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { s.blocks = rules.filterIndexed { j, _ -> j != i } }.padding(8.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("➕ חסימה חדשה", selected = true, brush = V2Colors.hero) { addRule = true }
                    Pill("💔 בלי פרידות בשישי") { s.blocks = s.blocks + BlockRule(BlockKind.SAD_SONGS, "", setOf(5)) }
                }
            }
        }
        // ---------------- neighbours
        item(key = "neighbours") {
            Group("🌙", "מצב שכנים", Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF3730A3)))) {
                Toggle("🤫 מצב שכנים", if (V2Service.neighboursActiveNow()) "פעיל עכשיו ✅" else "מגביל עוצמה ומוריד בס בשעות הלילה", s.neighboursOn) { s.neighboursOn = it }
                SettingSlider("מתחיל ב-", s.neighboursStart / 60f, 0f..23f, 22, { hhmm((it.roundToInt()) * 60) }, "00:00", "23:00") { s.neighboursStart = it.roundToInt() * 60 }
                SettingSlider("נגמר ב-", s.neighboursEnd / 60f, 0f..23f, 22, { hhmm((it.roundToInt()) * 60) }, "00:00", "23:00") { s.neighboursEnd = it.roundToInt() * 60 }
                SettingSlider("עוצמה מקסימלית", s.neighboursVolume.toFloat(), 5f..100f, 18, { "${it.roundToInt()}%" }, "5%", "100%") { s.neighboursVolume = it.roundToInt() }
                SettingSlider("הורדת בס", -s.neighboursBassDb.toFloat(), 0f..18f, 17, { "-${it.roundToInt()} dB" }, "0", "-18 dB") { s.neighboursBassDb = -it.roundToInt() }
                Text("הבס יורד דרך האקולייזר של Metrolist, והאקולייזר שלך חוזר בבוקר.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
        // ---------------- someone else's taste
        item(key = "taste") {
            Group("👤", "מצב טעם של מישהו אחר (לשעה)", Brush.linearGradient(listOf(Color(0xFF064E3B), Color(0xFF0F766E)))) {
                val active = s.activeTaste()
                if (active != null) {
                    val left = ((s.tasteUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${active.emoji} פעיל: ${active.he} · עוד $left דק׳", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill("עצור") { s.tastePreset = null; s.tasteUntil = 0L }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                TastePreset.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        row.forEach { p ->
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.large)
                                .background(Color.White.copy(alpha = if (active == p) 0.35f else 0.12f))
                                .clickable(enabled = busy == null) {
                                    s.tastePreset = p; s.tasteUntil = System.currentTimeMillis() + 3_600_000L
                                    busy = "מחליף טעם…"
                                    scope.launch {
                                        val mix = runCatching { V2Engine.tasteMix(ctx, db, p) }.getOrNull()
                                        busy = null
                                        if (mix != null) player?.playMix(mix, ctx) else toast(ctx, "לא הצלחתי למצוא שירים, נסה שוב")
                                    }
                                }.padding(vertical = 14.dp)) {
                                Text("${p.emoji} ${p.he}", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        // ---------------- fun extras
        item(key = "extras") {
            Group("🎲", "עוד שטויות שעובדות", Brush.linearGradient(listOf(Color(0xFF78350F), Color(0xFFD97706)))) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("🎲 רולטה עכשיו", selected = true, brush = V2Colors.hero) {
                        if (busy == null) {
                            busy = "מגריל…"
                            scope.launch {
                                val (seed, t) = runCatching { V2Engine.roulette(ctx) }.getOrDefault("" to null)
                                busy = null
                                if (t != null) { player?.playTracks(listOf(t), "🎲 רולטה"); toast(ctx, "🎲 \"$seed\" ← ${t.title}") } else toast(ctx, "נסה שוב 🎲")
                            }
                        }
                    }
                    Pill("🧽 אפס למידת וייב") { s.corrections = null; V2Engine.estimator(ctx).corrections = com.metrolist.music.discover.engine.VibeCorrections(); V2Engine.estimator(ctx).clearCache(); toast(ctx, "המנוע שכח את התיקונים שלמד") }
                }
                Toggle("📥 מיקס לילה אופליין", "בלילה, בטעינה וב-Wi-Fi: המיקס היומי יורד לבד (25 שירים)", s.offlineMix) { s.offlineMix = it; V2Offline.schedule(ctx) }
                Toggle("🫧 בועת נתונים בפתיחה", "עובדה מפתיעה עליך בכל פתיחה", DiscoverStore.get(ctx).getBool(DiscoverStore.K_BUBBLE, true)) {
                    DiscoverStore.get(ctx).put(DiscoverStore.K_BUBBLE, it.toString()); s.bump()
                }
            }
        }
        // ---------------- looks
        item(key = "looks") {
            Group("🎨", "מראה", Brush.linearGradient(listOf(Color(0xFF831843), Color(0xFFDB2777)))) {
                Toggle("🏠 הבית החדש", "כבוי = הבית הקלאסי של Discover", s.homeV2) { s.homeV2 = it }
                Toggle("🔁 כפתורי וייב בנגן", "עוד כזה / שנה וייב / אנרגיה ↑", s.vibeBar) { s.vibeBar = it }
                Toggle("🌈 פס צבעים חי בנגן", "בצבעים של העטיפה", s.animatedArt) { s.animatedArt = it }
                var name by remember { mutableStateOf(s.greetingName) }
                OutlinedTextField(value = name, onValueChange = { name = it; s.greetingName = it }, singleLine = true,
                    label = { Text("איך לקרוא לך בבית? (רשות)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        item(key = "reset") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Pill("♻️ איפוס כל ההגדרות המוזרות") { confirmReset = true }
            }
        }
        item(key = "later") { Hint("בקרוב: מוזיקה לפי מזג אוויר, קריוקי, מצב רכב, ווידג׳טים, Blend עם חבר, Drop Radar ומעברי DJ.") }
    }

    if (addRule) AddRuleDialog(onDismiss = { addRule = false }, currentArtist = player?.mediaMetadata?.value?.artists?.firstOrNull()?.name) { r -> s.blocks = s.blocks + r; addRule = false }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("לאפס הכל?") },
        text = { Text("כל ההגדרות המוזרות חוזרות לברירת מחדל. ההיסטוריה שלך לא נמחקת.") },
        confirmButton = { TextButton(onClick = { s.resetAll(); V2Offline.schedule(ctx); confirmReset = false; toast(ctx, "אופס! הכל אופס ♻️") }) { Text("אפס") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("ביטול") } },
    )
    busy?.let { BusyOverlay(it) }
}

@Composable
private fun Group(emoji: String, title: String, brush: Brush, content: @Composable () -> Unit) {
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), brush = brush) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 26.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Slider that only writes the setting when the finger lifts (no database write per frame). */
@Composable
private fun SettingSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: (Float) -> String,
                          startLabel: String, endLabel: String, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(label(v), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onCommit(v) })
        Row(Modifier.fillMaxWidth()) {
            Text(startLabel, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(endLabel, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, currentArtist: String?, onAdd: (BlockRule) -> Unit) {
    var kind by remember { mutableStateOf(BlockKind.ARTIST) }
    var value by remember { mutableStateOf(currentArtist.orEmpty()) }
    var days by remember { mutableStateOf(setOf(5)) }
    var from by remember { mutableFloatStateOf(0f) }
    var to by remember { mutableFloatStateOf(0f) }
    V2Rtl {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("חסימה חדשה") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("🎤 אמן", kind == BlockKind.ARTIST) { kind = BlockKind.ARTIST }
                        Pill("💔 עצובים", kind == BlockKind.SAD_SONGS) { kind = BlockKind.SAD_SONGS }
                        Pill("🔤 מילה", kind == BlockKind.KEYWORD) { kind = BlockKind.KEYWORD }
                    }
                    if (kind != BlockKind.SAD_SONGS) {
                        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            label = { Text(if (kind == BlockKind.ARTIST) "שם האמן (בדיוק כמו באפליקציה)" else "מילה בשם השיר") })
                    }
                    Text("באילו ימים", modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        DAYS.forEach { (l, iso) ->
                            val on = iso in days
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape)
                                .background(if (on) V2Colors.purple else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { days = if (on) days - iso else days + iso }) { Text(l, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    Text("משעה ${hhmm(from.roundToInt() * 60)} עד ${hhmm(to.roundToInt() * 60)}" + if (from.roundToInt() == to.roundToInt()) " (כל היום)" else "",
                        modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                    Slider(value = from, onValueChange = { from = it }, valueRange = 0f..23f, steps = 22)
                    Slider(value = to, onValueChange = { to = it }, valueRange = 0f..23f, steps = 22)
                }
            },
            confirmButton = {
                TextButton(enabled = days.isNotEmpty() && (kind == BlockKind.SAD_SONGS || value.isNotBlank()), onClick = {
                    onAdd(BlockRule(kind, value.trim(), days, from.roundToInt() * 60, to.roundToInt() * 60))
                }) { Text("הוסף") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        )
    }
}
