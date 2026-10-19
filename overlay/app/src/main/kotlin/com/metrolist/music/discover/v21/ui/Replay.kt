/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.DailyChallenge
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.History
import com.metrolist.music.discover.engine.Replay
import com.metrolist.music.discover.engine.TasteStats
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.TrackRow
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.playTracks
import java.time.LocalDate

/** Replay (Apple Music idea) + listening clock and obscurity score (Last.fm ideas) + the daily challenge. */
@Composable
fun ReplayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current
    var h by remember { mutableStateOf<History?>(null) }
    var period by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { h = runCatching { V2Engine.history(ctx, db) }.getOrNull() }
    val now = V2Engine.now()
    val periods = listOf("החודש", "30 יום", "השנה", "כל הזמנים")
    val from = when (period) {
        0 -> DayClock.tsOf(DayClock.date(now).withDayOfMonth(1))
        1 -> now - 30 * DayClock.DAY_MS
        2 -> DayClock.tsOf(LocalDate.of(DayClock.date(now).year, 1, 1))
        else -> 0L
    }
    val hist = h
    V2Page(title = "Replay 🔁", subtitle = "הטופ שלך, שעון ההאזנה ומדד הייחודיות", onBack = onBack) {
        item(key = "periods") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                items(periods.size) { i -> Pill(periods[i], selected = i == period) { period = i } }
            }
        }
        if (hist == null) { item(key = "loading") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }; return@V2Page }
        val top = Replay.top(hist, from, now + 1, 25)
        val artists = Replay.topArtists(hist, from, now + 1, 10)
        item(key = "artists") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Group("🎤", "אמני הטופ", Brush.linearGradient(listOf(Color(0xFF7C2D12), Color(0xFFEA580C)))) {
                    if (artists.isEmpty()) Text("אין עדיין נתונים לתקופה הזו", color = Color.White)
                    artists.forEachIndexed { i, (name, n) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${i + 1}. $name", color = Color.White, fontWeight = if (i < 3) FontWeight.ExtraBold else FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("$n", color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
        item(key = "songs_title") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 16.dp)) {
                SectionTitle("🎵 השירים של ${periods[period]}", Modifier.weight(1f))
                if (top.isNotEmpty()) Pill("▶ נגן הכל", selected = true) { pc?.playTracks(top.map { it.track }, "🔁 Replay · ${periods[period]}") }
            }
        }
        top.forEachIndexed { i, e -> item(key = "s_${i}_${e.track.id}") { TrackRow(e.track, "${e.plays}×") { pc?.playTracks(top.map { it.track }, "🔁 Replay", i) } } }
        item(key = "clock") {
            val clock = TasteStats.clock(hist, now, 90)
            val cs = MaterialTheme.colorScheme
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Group("🕐", "שעון ההאזנה (90 יום)", Brush.linearGradient(listOf(Color(0xFF0C4A6E), Color(0xFF0284C7)))) {
                    val max = (clock.maxOrNull() ?: 0).coerceAtLeast(1)
                    val peak = clock.indices.maxByOrNull { clock[it] } ?: 0
                    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                        val w = size.width / 24f
                        for (i in 0 until 24) {
                            val hh = size.height * (clock[i].toFloat() / max).coerceAtLeast(0.03f)
                            // RTL: hour 0 on the right
                            val x = size.width - (i + 1) * w
                            drawRoundRect(if (i == peak) cs.tertiary else Color.White.copy(alpha = 0.75f), topLeft = Offset(x + w * 0.15f, size.height - hh), size = Size(w * 0.7f, hh), cornerRadius = CornerRadius(w * 0.3f))
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("00", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("12", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("23", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                    Text("השעה הכי חמה שלך: ${"%02d".format(peak)}:00", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        item(key = "obscurity") {
            val o = TasteStats.obscurity(hist)
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Group("🕶️", "מדד הייחודיות", Brush.linearGradient(listOf(Color(0xFF3B0764), Color(0xFF9333EA)))) {
                    Text("$o/100", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
                    Text(TasteStats.obscurityLabel(o), color = Color.White, fontWeight = FontWeight.Bold)
                    Text("כמה הטעם שלך מפוזר: 0 = תמיד אותם אמנים, 100 = זנב ארוך של אמנים.", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "challenge") {
            val c = DailyChallenge.compute(hist, now)
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Group("🎯", "האתגר היומי", Brush.linearGradient(listOf(Color(0xFF14532D), Color(0xFF16A34A)))) {
                    Text(if (c.doneToday) "✅ בוצע היום: ${c.newArtistToday ?: "אמן חדש"}" else "היום: תשמע אמן אחד שלא שמעת אף פעם", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("רצף: ${c.streak} ימים · סה״כ ${c.totalDone} אתגרים", color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}
