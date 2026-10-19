/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.engine.Sliders
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.discover.v2.V2Engine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val dialColors = listOf(Color(0xFFFACC15), Color(0xFF22D3EE), Color(0xFFEF4444), Color(0xFFEC4899), Color(0xFF6366F1), Color(0xFF10B981))

@Composable
fun MixesHubScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var dial by remember { mutableStateOf(MoodPreset.DRIVE) }
    var energy by remember { mutableFloatStateOf(MoodPreset.DRIVE.target.energy?.toFloat() ?: 0.6f) }
    var mizrahi by remember { mutableFloatStateOf(0.5f) }
    var familiar by remember { mutableFloatStateOf(0.5f) }
    var tripMin by remember { mutableFloatStateOf(25f) }

    fun run(label: String, block: suspend () -> BuiltMix) {
        if (busy != null) return
        busy = label
        scope.launch {
            val mix = runCatching { block() }.getOrNull()
            busy = null
            if (mix == null) toast(ctx, "משהו השתבש, נסה שוב") else player?.playMix(mix, ctx)
        }
    }

    V2Page(title = "מרכז המיקסים 🎛️", subtitle = "מסובבים, מזיזים, מנגנים", onBack = onBack) {
        item(key = "dial") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                MoodDial(selected = dial, onSelect = { dial = it; it.target.energy?.let { e -> energy = e.toFloat() } })
                Spacer(Modifier.height(4.dp))
                Text("${dial.emoji} ${dial.he}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        item(key = "sliders") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    LabeledSlider("⚡ אנרגיה", energy, "רגוע", "מטורף") { energy = it }
                    LabeledSlider("🎚️ מזרחי ↔ EDM", mizrahi, "EDM", "מזרחי") { mizrahi = it }
                    LabeledSlider("🧭 מוכר ↔ חדש", familiar, "חדש", "מוכר") { familiar = it }
                    Spacer(Modifier.height(8.dp))
                    GradientButton("צור מיקס ▶", Modifier.fillMaxWidth()) {
                        run("בונה מיקס ${dial.he}…") {
                            V2Engine.mood(ctx, db, dial, Sliders(energy.toDouble(), mizrahi.toDouble(), familiar.toDouble()))
                        }
                    }
                }
            }
        }
        item(key = "emoji_title") { SectionTitle("איך אתה מרגיש? (לחיצה = מיקס)") }
        item(key = "emoji") {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                MoodPreset.emojis.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(MaterialTheme.shapes.large)
                        .clickable { run("${p.emoji} ${p.he}…") { V2Engine.mood(ctx, db, p) } }.padding(8.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(58.dp).clip(CircleShape).background(V2Colors.forKey(p.name))) {
                            Text(p.emoji, fontSize = 28.sp)
                        }
                        Text(p.he, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item(key = "special_title") { SectionTitle("מיקסים מיוחדים") }
        item(key = "flip") {
            SpecialCard("🔄", "רגש להיפך", "מתחיל בעצוב ונגמר במסיבה", Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFFDB2777), Color(0xFFF59E0B)))) {
                run("הופך רגשות…") { V2Engine.moodFlip(ctx, db) }
            }
        }
        item(key = "drama") {
            SpecialCard("🎬", "מצב דרמה", "פסקול קולנועי לחיים שלך עכשיו", Brush.linearGradient(listOf(Color(0xFF111827), Color(0xFF7F1D1D), Color(0xFFB45309)))) {
                run("מכין סצנה…") { V2Engine.drama(ctx, db) }
            }
        }
        item(key = "trip") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), brush = V2Colors.cool) {
                Column(Modifier.padding(16.dp)) {
                    Text("🚗 אני נוסע ${tripMin.roundToInt()} דקות", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("פלייליסט באורך של הנסיעה, בדיוק", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    Slider(value = tripMin, onValueChange = { tripMin = it }, valueRange = 5f..120f, steps = 22)
                    GradientButton("בנה נסיעה ▶", Modifier.fillMaxWidth(), brush = V2Colors.hero) {
                        val m = tripMin.roundToInt()
                        run("סופר דקות…") { V2Engine.trip(ctx, db, m) }
                    }
                }
            }
        }
        item(key = "roulette") {
            SpecialCard("🎲", "רולטה", "שיר אקראי לגמרי מכל YouTube Music", Brush.linearGradient(listOf(Color(0xFF065F46), Color(0xFF14B8A6)))) {
                if (busy == null) {
                    busy = "מגריל…"
                    scope.launch {
                        val (seed, t) = runCatching { V2Engine.roulette(ctx) }.getOrDefault("" to null)
                        busy = null
                        if (t == null) toast(ctx, "הרולטה נתקעה, נסה שוב 🎲")
                        else { player?.playTracks(listOf(t), "🎲 רולטה"); toast(ctx, "🎲 הגרלתי: \"$seed\" ← ${t.title}") }
                    }
                }
            }
        }
        item(key = "note") { Hint("את האנרגיה ומצב הרוח המנוע מעריך מהאמן, הסגנון, מילים בשם השיר, שירים קשורים, והדילוגים שלך. הוא משתפר ככל שאתה מאזין.", Modifier.padding(top = 12.dp)) }
    }
    busy?.let { BusyOverlay(it) }
}

@Composable
fun BusyOverlay(text: String) {
    V2Rtl {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 120.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 20.dp, vertical = 14.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                Spacer(Modifier.width(12.dp))
                Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, startLabel: String, endLabel: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange)
        Row(Modifier.fillMaxWidth()) {
            Text(startLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(endLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SpecialCard(emoji: String, title: String, sub: String, brush: Brush, onClick: () -> Unit) {
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), brush = brush, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
            Text(emoji, fontSize = 34.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
            Text("▶", color = Color.White, fontSize = 22.sp)
        }
    }
}

/** A round mood dial: six coloured segments, tap one to select it; the knob animates to it. */
@Composable
fun MoodDial(selected: MoodPreset, onSelect: (MoodPreset) -> Unit) {
    val items = MoodPreset.dial
    val n = items.size
    val sel = items.indexOf(selected).coerceAtLeast(0)
    val knob by animateFloatAsState(targetValue = sel.toFloat(), label = "knob")
    val sizeDp = 260.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(sizeDp).pointerInput(n) {
        detectTapGestures { pos ->
            val cx = size.width / 2f; val cy = size.height / 2f
            var a = atan2(pos.y - cy, pos.x - cx) * 180 / PI + 90
            if (a < 0) a += 360.0
            val i = ((a + 180.0 / n) / (360.0 / n)).toInt() % n
            onSelect(items[i])
        }
    }) {
        Canvas(Modifier.size(sizeDp)) {
            val stroke = size.minDimension * 0.16f
            val r = size.minDimension / 2f - stroke / 2f
            val sweep = 360f / n
            for (i in 0 until n) {
                val start = -90f + i * sweep - sweep / 2f + 2f
                drawArc(
                    color = dialColors[i % dialColors.size].copy(alpha = if (i == sel) 1f else 0.35f),
                    startAngle = start, sweepAngle = sweep - 4f, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = Stroke(width = stroke),
                )
            }
            val ang = (-90.0 + knob * sweep) * PI / 180.0
            val kr = r - stroke * 0.9f
            drawCircle(Color.White, radius = stroke * 0.32f, center = Offset(center.x + (kr * cos(ang)).toFloat(), center.y + (kr * sin(ang)).toFloat()))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF312E81), Color(0xFF0F0A1F))), radius = r - stroke * 1.2f)
        }
        // labels on the ring
        val radiusPx = 104
        items.forEachIndexed { i, p ->
            val ang = (-90.0 + i * 360.0 / n) * PI / 180.0
            Text(p.emoji, fontSize = 22.sp, modifier = Modifier.absoluteOffset { IntOffset((radiusPx * density * cos(ang)).roundToInt(), (radiusPx * density * sin(ang)).roundToInt()) })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(selected.emoji, fontSize = 40.sp)
            Text(selected.he, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
