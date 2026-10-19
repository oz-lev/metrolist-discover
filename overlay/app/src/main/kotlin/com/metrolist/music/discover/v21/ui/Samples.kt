/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.ui.V2Colors
import com.metrolist.music.discover.v2.ui.V2Rtl
import com.metrolist.music.discover.v2.ui.bigThumb
import com.metrolist.music.discover.v2.ui.playTracks
import com.metrolist.music.discover.v2.ui.rememberTap
import com.metrolist.music.discover.v2.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Samples (טעימות), inspired by YouTube Music: a vertical swipe feed of 30-second previews of songs Discover thinks
 * you'll like. Each card plays from 30 % into the song; ❤️ likes it (Metrolist's own like), swipe up = next.
 */
@Composable
fun SamplesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val pc = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val tap = rememberTap()
    var recs by remember { mutableStateOf<List<TrackMeta>?>(null) }
    val liked = remember { mutableStateOf(setOf<String>()) }
    var tick by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { recs = runCatching { V2Engine.recs(ctx, db, 30) }.getOrDefault(emptyList()) }
    val list = recs
    V2Rtl {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (list == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (list.isEmpty()) Text("אין עדיין המלצות. תשמע עוד קצת ותחזור 🙂", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            else {
                val pager = rememberPagerState { list.size }
                LaunchedEffect(pager.currentPage) {
                    val t = list[pager.currentPage]
                    pc.playTracks(listOf(t), "👆 טעימות")
                    // wait for the duration, jump to 30 %, then auto-advance after 30 s
                    var waited = 0
                    while (waited < 6_000 && runCatching { pc.player.duration }.getOrDefault(-1L) <= 0L) { delay(200); waited += 200 }
                    val d = runCatching { pc.player.duration }.getOrDefault(-1L)
                    if (d > 60_000L) runCatching { pc.player.seekTo((d * 0.3).toLong()) }
                    tick = 0f
                    for (i in 1..300) { delay(100); tick = i / 300f }
                    if (pager.currentPage + 1 < list.size) pager.animateScrollToPage(pager.currentPage + 1)
                }
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    val t = list[page]
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black, V2Colors.forKeyColor(t.id), Color.Black)))) {
                        Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues()).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth(0.85f).aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(V2Colors.forKey(t.id))) {
                                if (!t.thumbnailUrl.isNullOrBlank()) AsyncImage(model = bigThumb(t.thumbnailUrl, 720), contentDescription = null, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(t.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(t.artistLabel, color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, maxLines = 1, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(18.dp))
                            if (page == pager.currentPage) LinearProgressIndicator(progress = { tick }, modifier = Modifier.fillMaxWidth(0.6f))
                            Spacer(Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                val isLiked = t.id in liked.value
                                Circle(if (isLiked) "❤️" else "🤍") {
                                    tap()
                                    if (page == pager.currentPage) { pc.toggleLike(); liked.value = if (isLiked) liked.value - t.id else liked.value + t.id }
                                }
                                Circle("▶") { tap(); pc.playTracks(list.drop(page), "👆 טעימות: מה שאהבתי"); toast(ctx, "מנגן מכאן ברצף") }
                                Circle("⏭") { tap(); scope.launch { if (page + 1 < list.size) pager.animateScrollToPage(page + 1) } }
                            }
                            Spacer(Modifier.height(24.dp))
                            Text("החלק למעלה לשיר הבא · ${page + 1}/${list.size}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(WindowInsets.systemBars.asPaddingValues()).padding(12.dp).size(48.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onBack)) { Text("✕", color = Color.White, fontSize = 22.sp) }
        }
    }
}

@Composable
private fun Circle(text: String, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(68.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick)) {
        Text(text, fontSize = 28.sp, color = Color.White)
    }
}
