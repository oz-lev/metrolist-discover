/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.DailyChallenge
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Routes
import com.metrolist.music.discover.v2.ui.playTracks
import com.metrolist.music.discover.v2.ui.rememberTap
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.WidgetActions
import com.metrolist.music.LocalNavController
import kotlinx.coroutines.launch

private data class Tool(val emoji: String, val title: String, val c1: Long, val c2: Long, val action: () -> Unit)

/**
 * v2.1 additions to the Discover home: "ask music" box, daily challenge card, the toolbox grid, and widget actions.
 * [run] is the home's own "build a mix and play it" runner (with its busy overlay).
 */
@Composable
fun V21HomeExtras(run: (String, suspend () -> BuiltMix) -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val nav = LocalNavController.current
    val pc = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { V2Settings.get(ctx) }
    var ask by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<DailyChallenge.State?>(null) }
    var goal by remember { mutableStateOf<com.metrolist.music.discover.engine.DailyGoal.State?>(null) }
    var recent by remember { mutableStateOf<List<com.metrolist.music.discover.engine.TrackMeta>>(emptyList()) }
    val tap = rememberTap()

    // home-screen widget buttons arrive here
    LaunchedEffect(Unit) {
        when (WidgetActions.consume(ctx)) {
            "daily" -> run("המיקס היומי…") { V2Engine.daily(ctx, db) }
            "panic" -> run("🧯 מרגיע…") { V2Engine.panic(ctx, db) }
            "roulette" -> scope.launch {
                val (_, t) = runCatching { V2Engine.roulette(ctx) }.getOrDefault("" to null)
                if (t != null) pc.playTracks(listOf(t), "🎲 רולטה")
            }
            "car" -> runCatching { nav.navigate(V2Routes.CAR) }
        }
    }
    LaunchedEffect(version) {
        val h = runCatching { V2Engine.history(ctx, db) }.getOrNull() ?: return@LaunchedEffect
        if (s.challengeOnHome) challenge = DailyChallenge.compute(h, V2Engine.now())
        goal = if (s.dailyGoalMin > 0) com.metrolist.music.discover.engine.DailyGoal.compute(h, V2Engine.now(), s.dailyGoalMin) else null
        recent = if (s.jumpBack) com.metrolist.music.discover.engine.JumpBack.recent(h, 12) else emptyList()
    }

    Column(Modifier.fillMaxWidth()) {
        // ask music (YouTube Music idea)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = ask, onValueChange = { ask = it }, singleLine = true, shape = RoundedCornerShape(50),
                placeholder = { Text("🗣️ תבקש: \"שירים עצובים לנסיעה בלילה\"") },
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    Box(Modifier.padding(4.dp).clickable { val q = ask.trim(); if (q.isNotEmpty()) run("🗣️ $q…") { V2Engine.ask(ctx, db, q) } }.padding(8.dp)) {
                        Text("▶", fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { val q = ask.trim(); if (q.isNotEmpty()) run("🗣️ $q…") { V2Engine.ask(ctx, db, q) } }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }
        if (ask.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp)) {
            com.metrolist.music.discover.v2.ui.Pill("🗣️ בנה לי מיקס מזה", selected = true) { val q = ask.trim(); run("🗣️ $q…") { V2Engine.ask(ctx, db, q) } }
        }

        // Spotify-style "jump back in"
        JumpBackShelf(recent) { i -> pc.playTracks(recent, "⏪ קפוץ בחזרה", i) }

        // Duolingo-style daily goal
        goal?.let { GoalFlameCard(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { nav.navigate(V2Routes.PROFILE) } }

        // daily challenge
        val c = challenge
        if (s.challengeOnHome && c != null) {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), brush = Brush.linearGradient(listOf(Color(0xFF14532D), Color(0xFF16A34A))),
                onClick = { nav.navigate(V2Routes.REPLAY) }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("🎯", fontSize = 26.sp)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(if (c.doneToday) "אתגר היום בוצע! ✅" else "אתגר היום: אמן שלא שמעת אף פעם", color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Text(if (c.doneToday) "גילית את ${c.newArtistToday ?: "אמן חדש"} · רצף ${c.streak}" else "רצף נוכחי: ${c.streak} · לחץ על 🎲 רולטה או 👆 טעימות", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Shazam-style recognise button (opens Metrolist's own recognition screen)
        PulsingRecognizeButton { runCatching { nav.navigate("recognition") } }

        // toolbox
        Text("ארגז הכלים 🧰", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
        val tools = listOf(
            Tool("🎤", "קריוקי", 0xFFDB2777, 0xFF7C3AED) { nav.navigate(V2Routes.KARAOKE) },
            Tool("🚗", "מצב רכב", 0xFF0EA5E9, 0xFF1E3A8A) { nav.navigate(V2Routes.CAR) },
            Tool("👆", "טעימות", 0xFFF97316, 0xFFDB2777) { nav.navigate(V2Routes.SAMPLES) },
            Tool("🤝", "Blend", 0xFF10B981, 0xFF0F766E) { nav.navigate(V2Routes.BLEND) },
            Tool("🧯", "פאניקה", 0xFF64748B, 0xFF0EA5E9) { run("🧯 מרגיע…") { V2Engine.panic(ctx, db) } },
            Tool("🔀", "ערבוב חכם", 0xFF8B5CF6, 0xFFEC4899) { scope.launch { if (!smartShuffle(ctx, db, pc)) toast(ctx, "התור ריק, תתחיל משהו קודם") } },
            Tool("📻", "חוגת עשורים", 0xFFF59E0B, 0xFF92400E) { decadeIndex = (decadeIndex + 1) % DECADES.size; val d = DECADES[decadeIndex]; run("📻 שנות ה-$d…") { V2Engine.decade(ctx, db, d) } },
            Tool("🔁", "Replay", 0xFFEA580C, 0xFF7C2D12) { nav.navigate(V2Routes.REPLAY) },
            Tool("🗂️", "התורים שלי", 0xFF6366F1, 0xFF312E81) { nav.navigate(V2Routes.QUEUES) },
            Tool("🎨", "עיצוב", 0xFFEC4899, 0xFF22D3EE) { nav.navigate(V2Routes.THEME) },
            Tool("👥", "פרופילים", 0xFF94A3B8, 0xFF334155) { nav.navigate(V2Routes.PROFILES) },
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tools.size) { i ->
                val t = tools[i]
                V2Card(Modifier.width(104.dp), brush = Brush.linearGradient(listOf(Color(t.c1), Color(t.c2))), onClick = { tap(); t.action() }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.emoji, fontSize = 28.sp)
                        Text(t.title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val DECADES = listOf(1980, 1990, 2000, 2010, 2020)
private var decadeIndex = 1
