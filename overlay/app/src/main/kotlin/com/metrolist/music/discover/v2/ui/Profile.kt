/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.discover.engine.Achievements
import com.metrolist.music.discover.v2.V2Engine

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    var profile by remember { mutableStateOf<Achievements.Profile?>(null) }
    LaunchedEffect(Unit) { profile = runCatching { V2Engine.profile(ctx, db) }.getOrNull() }
    val p = profile

    V2Page(title = "הפרופיל שלי 🏆", subtitle = "רמות, רצפים והישגים מההאזנה שלך", onBack = onBack) {
        if (p == null) {
            item(key = "loading") { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@V2Page
        }
        item(key = "level") { LevelCard(p) }
        item(key = "stats") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Stat("🔥", "${p.currentStreak}", "ימים ברצף", Modifier.weight(1f))
                Stat("🏅", "${p.bestStreak}", "הרצף הכי ארוך", Modifier.weight(1f))
                Stat("⏱️", "${p.totalMinutes / 60}", "שעות האזנה", Modifier.weight(1f))
            }
        }
        item(key = "weekly") { WeeklyCard(p.weekly) }
        item(key = "badges_title") { SectionTitle("הישגים · ${p.badges.count { it.earned }} מתוך ${p.badges.size}") }
        p.badges.chunked(2).forEachIndexed { i, row ->
            item(key = "badges_$i") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    row.forEach { BadgeCard(it, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item(key = "note") { Hint("הכל מחושב מההיסטוריה שבטלפון. שום דבר לא נשלח לשום מקום.", Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun LevelCard(p: Achievements.Profile) {
    val prog by animateFloatAsState(p.levelProgress.toFloat(), tween(1200), label = "lvl")
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), brush = themeHero()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                Canvas(Modifier.size(110.dp)) {
                    val st = 12.dp.toPx()
                    val tl = Offset(st / 2, st / 2); val sz = Size(size.width - st, size.height - st)
                    drawArc(Color.White.copy(alpha = 0.25f), -90f, 360f, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
                    drawArc(Color.White, -90f, 360f * prog, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("רמה", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    Text("${p.level}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(levelName(p.level), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("${"%,d".format(p.xp)} XP", color = Color.White, fontWeight = FontWeight.Bold)
                Text("עוד ${"%,d".format(p.xpToNext)} XP לרמה ${p.level + 1}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("${p.distinctArtists} אמנים שונים", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun levelName(l: Int) = when {
    l < 3 -> "מאזין מתחיל 🎧"
    l < 6 -> "חובב מוזיקה 🎶"
    l < 10 -> "צייד שירים 🏹"
    l < 15 -> "DJ ביתי 🎛️"
    l < 22 -> "אגדת פלייליסטים 👑"
    else -> "אל המוזיקה ⚡"
}

@Composable
private fun Stat(emoji: String, value: String, label: String, modifier: Modifier) {
    V2Card(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(emoji, fontSize = 22.sp)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WeeklyCard(w: Achievements.Weekly) {
    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), brush = Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1E3A8A)))) {
        Column(Modifier.padding(18.dp)) {
            Text("📊 השבוע שלך", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Line("🎵", "${w.plays} שירים · ${w.minutes} דקות" + (w.changePct?.let { c -> if (c >= 0) " · ↑$c% משבוע שעבר" else " · ↓${-c}% משבוע שעבר" } ?: ""))
            w.topArtist?.let { Line("👑", "האמן של השבוע: $it") }
            w.topSong?.let { Line("🔁", "השיר של השבוע: $it") }
            Line("🧭", if (w.newArtists > 0) "גילית ${w.newArtists} אמנים חדשים" else "לא גילית אמנים חדשים השבוע, נסה את \"גילוי בשבילך\"")
        }
    }
}

@Composable
private fun Line(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BadgeCard(b: Achievements.Badge, modifier: Modifier) {
    V2Card(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp).clip(CircleShape)
                .background(if (b.earned) themeHero() else Brush.linearGradient(listOf(Color.Gray.copy(alpha = 0.3f), Color.Gray.copy(alpha = 0.2f))))) {
                Text(b.emoji, fontSize = 28.sp, modifier = Modifier.alpha(if (b.earned) 1f else 0.45f))
            }
            Text(b.he, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text(b.descHe, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { b.progress.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)))
            Text(if (b.earned) "✅ הושג" else "${(b.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
