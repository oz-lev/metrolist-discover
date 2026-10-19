/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.ColorBuckets
import com.metrolist.music.discover.engine.QueryRewriter
import com.metrolist.music.discover.engine.TimeMachine
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.V2Engine
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private enum class Lab(val title: String) { LIKE("כמו X אבל…"), TIME("מכונת זמן"), LYRIC("שורה מהשיר"), COLOR("לפי צבע"), HUM("זמזום") }

@Composable
fun SearchLabScreen(onBack: () -> Unit, onRecognize: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var lab by remember { mutableStateOf(Lab.LIKE) }
    var text by remember { mutableStateOf("") }
    var lyric by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<TrackMeta>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var scanDone by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var colorCount by remember { mutableIntStateOf(-1) }

    fun show(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch { runCatching { block() }.onFailure { toast(ctx, "משהו השתבש, נסה שוב") }; busy = null }
    }

    V2Page(title = "חיפושים מוזרים 🔎", subtitle = "מחפשים מוזיקה כמו שזוכרים אותה", onBack = onBack) {
        item(key = "tabs") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Lab.entries, key = { it.name }) { l -> Pill(l.title, selected = lab == l) { lab = l; results = emptyList(); title = null; note = null; counts = emptyMap() } }
            }
        }
        when (lab) {
            Lab.LIKE -> {
                item(key = "like") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("למשל: כמו Faded אבל במזרחית") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                show("מחפש…") { val m = V2Engine.likeXBut(ctx, db, text); results = m.tracks; title = m.title; note = m.note }
                            }),
                        )
                        Spacer(Modifier.height(8.dp))
                        Hint("או בחר שינוי מהיר:", Modifier.padding(horizontal = 0.dp))
                    }
                }
                item(key = "mods") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(QueryRewriter.Modifier.entries, key = { it.name }) { m ->
                            Pill(m.he) {
                                val base = QueryRewriter.parse(text).base.ifBlank { player?.mediaMetadata?.value?.title ?: "" }
                                if (base.isBlank()) toast(ctx, "כתוב קודם שם של שיר או אמן")
                                else {
                                    text = "כמו $base אבל ${m.he}"
                                    show("מחפש…") { val r = V2Engine.likeXBut(ctx, db, text); results = r.tracks; title = r.title; note = r.note }
                                }
                            }
                        }
                    }
                }
                item(key = "like_hint") { Hint("ריק? לוקח את השיר שמתנגן עכשיו. אני משכתב את החיפוש, מחפש ב-YouTube Music ומסנן לפי הווייב.") }
            }
            Lab.TIME -> {
                item(key = "time") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        TimeMachine.Back.entries.reversed().forEach { b ->
                            GradientButton(b.he, Modifier.weight(1f), brush = V2Colors.forKey(b.name)) {
                                show("נוסע בזמן…") {
                                    val r = V2Engine.timeMachine(ctx, db, b)
                                    results = r.tracks.map { it.first }; counts = r.tracks.associate { it.first.id to it.second }
                                    val d = r.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                                    title = if (r.tracks.isEmpty()) "⏳ ב-$d לא שמעת כלום (או שזה לפני שהתחלת)" else "⏳ $d · ${r.minutes} דקות האזנה"
                                    note = if (!r.exact && r.tracks.isNotEmpty()) "באותו יום בדיוק לא היה כלום, אז הנה היום הכי קרוב" else null
                                }
                            }
                        }
                    }
                }
                item(key = "time_hint") { Hint("מה שמעת באותו יום לפני שבוע, חודש או שנה, מההיסטוריה שבטלפון.") }
            }
            Lab.LYRIC -> {
                item(key = "lyric") {
                    OutlinedTextField(
                        value = lyric, onValueChange = { lyric = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text("שורה שאתה זוכר מהשיר") }, minLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { show("מחפש לפי מילים…") { results = V2Engine.lyric(lyric); title = "🎤 \"${lyric.take(40)}\""; note = null } }),
                    )
                }
                item(key = "lyric_btn") {
                    GradientButton("חפש לפי מילים", Modifier.fillMaxWidth().padding(16.dp)) {
                        show("מחפש לפי מילים…") { results = V2Engine.lyric(lyric); title = "🎤 \"${lyric.take(40)}\""; note = null }
                    }
                }
                item(key = "lyric_hint") { Hint("גם אם זוכרים רק חצי שורה, YouTube Music מוצא את השיר הרבה פעמים.") }
            }
            Lab.COLOR -> {
                item(key = "color_scan") {
                    val have = remember(scanDone) { V2Engine.colorMap(ctx).size }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("🎨 $have עטיפות נסרקו", fontWeight = FontWeight.SemiBold)
                        if (scanTotal > 0 && scanDone < scanTotal) LinearProgressIndicator(progress = { scanDone.toFloat() / scanTotal }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        GradientButton("סרוק את העטיפות שלי", Modifier.fillMaxWidth().padding(vertical = 8.dp), brush = V2Colors.cool) {
                            show("סורק עטיפות…") {
                                V2Engine.scanColors(ctx, db, 300) { d, t -> scanDone = d; scanTotal = t }
                                scanDone = scanTotal + 1
                            }
                        }
                    }
                }
                item(key = "swatches") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ColorBuckets.Bucket.entries, key = { it.name }) { b ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                                show("מחפש ${b.he}…") { results = V2Engine.byColor(ctx, db, b); title = "${b.emoji} עטיפות ב${b.he}"; colorCount = results.size; note = if (results.isEmpty()) "עוד לא סרקתי מספיק עטיפות, לחץ על סריקה" else null }
                            }) {
                                Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or b.rgb)).border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape))
                                Text(b.he, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Lab.HUM -> {
                item(key = "hum") {
                    V2Card(Modifier.fillMaxWidth().padding(16.dp), brush = Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF9D174D)))) {
                        Column(Modifier.padding(20.dp)) {
                            Text("🎤 חיפוש בזמזום · בקרוב", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            Text("זיהוי מנגינה מזמזום צריך מודל שרץ על הטלפון ומאגר מנגינות. אין כרגע פתרון חינמי שעובד טוב מספיק בלי שרת, אז זה מחכה לגרסה הבאה.",
                                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                            Text("בינתיים: שיר שמתנגן לידך? הזיהוי המובנה של Metrolist מזהה אותו.", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(10.dp))
                            GradientButton("🎧 זהה שיר שמתנגן לידי", Modifier.fillMaxWidth(), onClick = onRecognize)
                        }
                    }
                }
            }
        }
        title?.let { t ->
            item(key = "res_title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (results.isNotEmpty()) Pill("▶ נגן הכל", selected = true) { player?.playTracks(results, t) }
                }
            }
        }
        if (title != null && results.isEmpty() && busy == null && lab != Lab.TIME && lab != Lab.COLOR) item(key = "empty") { Hint("לא נמצא כלום. נסה ניסוח אחר.") }
        trackList("res", results, extra = { t -> counts[t.id]?.let { "×$it" } }) { i -> player?.playTracks(results, title ?: "חיפוש", i) }
    }
    busy?.let { BusyOverlay(it) }
}
