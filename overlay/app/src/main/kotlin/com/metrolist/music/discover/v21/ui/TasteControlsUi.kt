/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Wider Taste & Algorithm Controls ("שליטה באלגוריתם ובטעם"):
 *  - Off-switches for TasteModel learner & MixContext
 *  - Sliders/chips: Familiar <-> Discovery (0-100%), Recency/Momentum weight, Skip-avoidance strictness,
 *    Artist diversity / max per artist / artist gap, Language preference
 *  - Explicit Boosted / Less / Hidden artists & songs manager (all in one place, per profile)
 *  - Live Learned Taste summary card
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.discover.DiscoverRepository
import com.metrolist.music.discover.engine.LangMode
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.TonalActionButton
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.toast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteControlsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { V2Settings.get(ctx) }
    val repo = remember(ctx, db) { DiscoverRepository(ctx.applicationContext, db) }

    var summary by remember { mutableStateOf<V2Engine.TasteSummary?>(null) }
    var allArtists by remember { mutableStateOf<List<V2Engine.ArtistPick>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(version, reloadTick) {
        summary = runCatching { V2Engine.tasteSummary(ctx, db) }.getOrNull()
        if (allArtists.isEmpty()) allArtists = runCatching { V2Engine.allArtists(ctx, db, 400) }.getOrDefault(emptyList())
    }

    fun applyChange() {
        V2Engine.invalidate()
        reloadTick++
    }

    V2Page(
        title = "שליטה באלגוריתם ובטעם",
        subtitle = "כוונון עדין של למידת הטעם, גיוון, דילוגים ואמנים (לפרופיל הפעיל בלבד)",
        onBack = onBack,
    ) {
        // 1. Live learned taste summary card
        summary?.learned?.let { lt ->
            item(key = "tc_summary") {
                V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("מה המנוע למד עליך", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val compPct = (lt.avgCompletion * 100).roundToInt()
                        val peak = lt.peakDayPart?.he ?: "—"
                        Text(
                            "סיום ממוצע: $compPct% · שעת שיא: $peak · ריפלייז: ${lt.replayCount} · קשרי סשן: ${lt.coListenEdges}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val top = summary?.topArtistNames.orEmpty()
                        if (top.isNotEmpty()) {
                            Text(
                                "אמנים מובילים לאחרונה: ${top.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 2. Algorithm Switches
        item(key = "tc_switches_title") { SectionTitle("מנועי למידה") }
        item(key = "tc_switches") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    TasteSwitchRow(
                        title = "למידת טעם מתקדמת (v15)",
                        desc = "לומד אחוז סיום, דילוגים מהירים, ריפלייז, מעברים בין שירים בסשן ושעות יום",
                        checked = s.tasteModel,
                    ) { s.tasteModel = it; applyChange() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    TasteSwitchRow(
                        title = "הקשר האזנה (Momentum & Cooldown)",
                        desc = "מקדם מה שאתה שומע עכשיו ומונע חזרה על שירים מהשעות האחרונות",
                        checked = s.mixContext,
                    ) { s.mixContext = it; applyChange() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    TasteSwitchRow(
                        title = "למידה בזמן אמת בתור",
                        desc = "דילוג מוקדם משנה את שאר המיקס מיד, האזנה מלאה מקדמת דומים",
                        checked = s.liveLearning,
                    ) { s.liveLearning = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    TasteSwitchRow(
                        title = "סידור גלי אנרגיה (DJ)",
                        desc = "מסדר את המיקס בגלים חלקים בלי אותו אמן ברצף",
                        checked = s.djOrder,
                    ) { s.djOrder = it }
                }
            }
        }

        // 3. Core Tuning Sliders & Chips
        item(key = "tc_sliders_title") { SectionTitle("איזון, עדכניות וגיוון") }
        item(key = "tc_sliders") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Familiar vs Discovery (0-100%)
                    TasteSlider(
                        title = "מוכר מול גילוי חדש",
                        value = (s.mixNewShare * 100).toFloat(),
                        range = 0f..100f,
                        steps = 19,
                        label = { "${it.roundToInt()}% חדש · ${100 - it.roundToInt()}% מוכר" },
                        lo = "רק מוכר",
                        hi = "רק חדש",
                    ) { V2Engine.setDiscovery(ctx, it / 100.0) }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Recency / Momentum weight (0-200%)
                    TasteSlider(
                        title = "משקל עדכניות ומומנטום",
                        value = (s.recencyWeight * 100).toFloat(),
                        range = 0f..200f,
                        steps = 19,
                        label = {
                            val p = it.roundToInt()
                            when {
                                p < 30 -> "$p% (טעם ארוך טווח)"
                                p <= 130 -> "$p% (מאוזן)"
                                else -> "$p% (חדש ועכשווי)"
                            }
                        },
                        lo = "כל הזמנים",
                        hi = "השבוע האחרון",
                    ) { s.recencyWeight = it / 100.0; applyChange() }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Skip avoidance strictness (0-200%)
                    TasteSlider(
                        title = "קשיחות הימנעות מדילוגים",
                        value = (s.skipStrictness * 100).toFloat(),
                        range = 0f..200f,
                        steps = 19,
                        label = {
                            val p = it.roundToInt()
                            when {
                                p < 30 -> "$p% (סלחני)"
                                p <= 130 -> "$p% (רגיל)"
                                else -> "$p% (מחמיר)"
                            }
                        },
                        lo = "סלחני",
                        hi = "מחמיר",
                    ) { s.skipStrictness = it / 100.0; s.skipPenalty = (it / 100.0).coerceIn(0.0, 2.5); applyChange() }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Artist diversity: max per artist + artist gap
                    Text("מקסימום שירים לאותו אמן במיקס", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "אוטומטי", 1 to "1 (שיא גיוון)", 2 to "עד 2", 3 to "עד 3", 4 to "עד 4").forEach { (v, lbl) ->
                            Pill(lbl, selected = s.maxPerArtist == v) { s.maxPerArtist = v; applyChange() }
                        }
                    }

                    TasteSlider(
                        title = "מרווח מינימלי בין שירים של אותו אמן",
                        value = s.artistGap.toFloat(),
                        range = 0f..10f,
                        steps = 9,
                        label = { if (it < 0.5f) "אוטומטי" else "${it.roundToInt()} שירים בין לבין" },
                        lo = "כבוי",
                        hi = "10 שירים",
                    ) { s.artistGap = it.roundToInt() }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Language preference
                    Text("העדפת שפה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LangMode.entries.forEach { lm ->
                            Pill(lm.he, selected = s.lang == lm) { s.lang = lm; applyChange() }
                        }
                    }
                }
            }
        }

        // 4. Explicit Boost / Mute Artist & Song Manager
        item(key = "tc_artists_title") { SectionTitle("ניהול אמנים: קידום והחלשה") }
        item(key = "tc_boost_card") {
            val boosted = summary?.boosted.orEmpty()
            val boostedIds = boosted.map { it.first }.toSet()
            val q = search.trim()
            val filtered = remember(allArtists, q, boostedIds) {
                val base = if (q.isEmpty()) allArtists.take(18)
                else allArtists.filter { it.name.contains(q, ignoreCase = true) }.take(24)
                base.filter { it.id !in boostedIds }
            }
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("אמנים מקודמים (${boosted.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "אמנים שתסמן כאן יקבלו עדיפות גבוהה יותר בכל המיקסים של הפרופיל הזה",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (boosted.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            boosted.forEach { (id, name) ->
                                Pill("★ $name  ✕", selected = true) {
                                    repo.setBoostedArtist(id, false)
                                    applyChange()
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("חפש אמן להוספה לקידום...") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filtered.forEach { a ->
                            Pill("+ ${a.name}", selected = false) {
                                repo.setBoostedArtist(a.id, true)
                                applyChange()
                                toast(ctx, "★ ${a.name} קודם")
                            }
                        }
                    }
                }
            }
        }

        // 5. Muted / Less / Hidden management
        item(key = "tc_muted_card") {
            val less = summary?.less.orEmpty()
            val hArt = summary?.hiddenArtists.orEmpty()
            val hSong = summary?.hiddenSongs.orEmpty()
            val totalMuted = less.size + hArt.size + hSong.size
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("אמנים מוחלשים וחסומים ($totalMuted)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("לחץ על כל תג כדי להחזיר אותו למיקסים", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (totalMuted > 0) {
                            TonalActionButton("שחזר הכל") {
                                repo.clearHidden()
                                applyChange()
                                toast(ctx, "כל החסימות אופסו")
                            }
                        }
                    }
                    if (less.isNotEmpty()) {
                        Text("פחות מהאמנים האלה (👎)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            less.forEach { (id, name, steps) ->
                                Pill("$name (-$steps)  ✕", selected = false) {
                                    repo.clearLessArtist(id)
                                    applyChange()
                                }
                            }
                        }
                    }
                    if (hArt.isNotEmpty()) {
                        Text("אמנים חסומים (🚫)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hArt.forEach { (id, name) ->
                                Pill("$name  ✕", selected = false) {
                                    repo.unhideArtist(id)
                                    applyChange()
                                }
                            }
                        }
                    }
                    if (hSong.isNotEmpty()) {
                        Text("שירים מוסתרים (${hSong.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            hSong.take(30).forEach { (id, title) ->
                                Pill("${title.take(24)}  ✕", selected = false) {
                                    repo.unhideSong(id)
                                    applyChange()
                                }
                            }
                        }
                    }
                    if (totalMuted == 0) {
                        Hint("אין כרגע אמנים מוחלשים או שירים חסומים בפרופיל הזה")
                    }
                }
            }
        }
    }
}

@Composable
private fun TasteSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TasteSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: (Float) -> String,
    lo: String,
    hi: String,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(label(v), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onCommit(v) })
        Row(Modifier.fillMaxWidth()) {
            Text(lo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(hi, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
