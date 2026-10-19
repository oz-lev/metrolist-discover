/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 * UX concepts borrowed from other apps, re-implemented in our own code: Duolingo's streak flame + daily goal,
 * Spotify's "jump back in" shelf, Shazam's big pulsing button (for Metrolist's own song recognition).
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.discover.engine.DailyGoal
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.ui.Art
import com.metrolist.music.discover.v2.ui.V2Card

/** Duolingo-style card: a progress ring around a flame, minutes today vs the goal, and the streak. */
@Composable
fun GoalFlameCard(g: DailyGoal.State, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val flame = if (g.done) listOf(Color(0xFFF97316), Color(0xFFFACC15)) else listOf(Color(0xFF475569), Color(0xFF64748B))
    val pulse = rememberInfiniteTransition(label = "flame")
    val sc by pulse.animateFloat(1f, if (g.done) 1.12f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sc")
    V2Card(modifier, brush = Brush.linearGradient(listOf(Color(0xFF1C1917), Color(0xFF431407))), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = 7.dp.toPx()
                    drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, topLeft = Offset(st / 2, st / 2), size = Size(size.width - st, size.height - st), style = Stroke(st))
                    drawArc(Brush.sweepGradient(flame), -90f, 360f * g.progress, false, topLeft = Offset(st / 2, st / 2), size = Size(size.width - st, size.height - st), style = Stroke(st, cap = StrokeCap.Round))
                }
                Text("🔥", fontSize = 26.sp, modifier = Modifier.scale(sc))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (g.done) "היעד היומי הושלם! 🎉" else "יעד יומי: ${g.minutesToday}/${g.goal} דק׳", color = Color.White, fontWeight = FontWeight.ExtraBold)
                Text(if (g.streak > 0) "🔥 ${g.streak} ימים ברצף" + if (!g.done) " · עוד ${(g.goal - g.minutesToday).coerceAtLeast(0)} דק׳ לשמור עליו" else "" else "תשמע ${g.goal} דק׳ היום כדי להדליק את הלהבה",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Spotify-style "jump back in" shelf. */
@Composable
fun JumpBackShelf(tracks: List<TrackMeta>, onPlay: (Int) -> Unit) {
    if (tracks.isEmpty()) return
    Text("⏪ קפוץ בחזרה", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tracks.size) { i ->
            val t = tracks[i]
            Column(Modifier.width(112.dp).clip(MaterialTheme.shapes.large).clickable { onPlay(i) }) {
                Art(t.thumbnailUrl, t.id, Modifier.size(112.dp), 16)
                Text(t.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(t.artistLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Shazam-style big pulsing button that opens Metrolist's own song recognition. */
@Composable
fun PulsingRecognizeButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "rec")
    val ring by t.animateFloat(0.85f, 1.25f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring")
    val alpha by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "alpha")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
            Box(Modifier.size(76.dp).scale(ring).clip(CircleShape).background(cs.primary.copy(alpha = alpha)))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary))).clickable(onClick = onClick)) {
                Text("🎵", fontSize = 32.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("מה מתנגן פה?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text("לחץ על הכפתור והטלפון יזהה את השיר (הזיהוי של Metrolist)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
