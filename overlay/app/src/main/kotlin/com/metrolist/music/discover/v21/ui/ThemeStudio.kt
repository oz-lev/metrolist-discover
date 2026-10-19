/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v21.theme.ACCENTS
import com.metrolist.music.discover.v21.theme.DTheme
import com.metrolist.music.discover.v21.theme.DiscoverDesign
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt

/** סטודיו עיצוב: theme cards with live previews, accent, roundness, font and text size. Applies to the whole app instantly. */
@Composable
fun ThemeStudioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { V2Settings.get(ctx) }
    val current = remember(version) { DiscoverDesign.currentTheme(s) }
    val (dynamicTheme, setDynamicTheme) = rememberPreference(DynamicThemeKey, true)

    V2Page(title = "סטודיו עיצוב 🎨", subtitle = "משנה את כל האפליקציה, מיד", onBack = onBack) {
        item(key = "themes_title") { SectionTitle("ערכות") }
        DTheme.entries.chunked(2).forEachIndexed { i, row ->
            item(key = "themes_$i") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    row.forEach { t ->
                        ThemeCard(t, selected = !s.randomDailyTheme && t == current, modifier = Modifier.weight(1f)) {
                            s.randomDailyTheme = false
                            s.themeId = t.name
                            if (t == DTheme.DYNAMIC && !dynamicTheme) setDynamicTheme(true)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item(key = "random") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Group("🎲", "ערכה אקראית כל יום", Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF7C3AED)))) {
                    Toggle("הפתעה כל בוקר", if (s.randomDailyTheme) "היום: ${current.emoji} ${current.he}" else "כל יום ערכה אחרת, קבועה לכל היום", s.randomDailyTheme) { s.randomDailyTheme = it }
                }
            }
        }
        item(key = "accent_title") { SectionTitle("צבע הדגשה") }
        item(key = "accent") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Swatch(Brush.sweepGradient(listOf(Color(current.accent), Color(current.accent2), Color(current.accent3), Color(current.accent))), s.accent == 0, "של הערכה") { s.accent = 0 }
                }
                items(ACCENTS.size) { i ->
                    val c = ACCENTS[i]
                    Swatch(Brush.linearGradient(listOf(Color(c), Color(c))), s.accent == c.toInt(), null) { s.accent = c.toInt() }
                }
            }
        }
        item(key = "shape") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Group("🔘", "צורה וטקסט", Brush.linearGradient(listOf(Color(0xFF1F2937), Color(0xFF374151)))) {
                    SettingSlider("עיגול פינות", s.roundness, 0.3f..2f, 0, { v -> when { v < 0.6f -> "חד ◼️"; v < 1.3f -> "רגיל"; else -> "עגול לגמרי ⚪" } }, "חד", "עגול") { s.roundness = it }
                    Choice("גופן", listOf("HEEBO" to "Heebo", "RUBIK" to "Rubik", "SYSTEM" to "של הטלפון"), s.font) { s.font = it }
                    SettingSlider("גודל טקסט", s.fontScale, 0.85f..1.25f, 7, { "${(it * 100).roundToInt()}%" }, "קטן", "גדול") { s.fontScale = it }
                    Toggle("✨ כותרות עם גרדיאנט", "כותרות המסכים של Discover בצבעי הערכה", s.gradientHeaders) { s.gradientHeaders = it }
                }
            }
        }
        item(key = "preview") { Column {
            SectionTitle("ככה זה נראה")
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
                Column {
                    Text("כותרת גדולה", style = MaterialTheme.typography.headlineSmall)
                    Text("טקסט רגיל של האפליקציה, עם עברית ו-English באותה שורה.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 10.dp)) { Text("כפתור", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                        Box(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 10.dp)) { Text("משני", color = MaterialTheme.colorScheme.onSecondaryContainer) }
                        Box(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.tertiary).padding(horizontal = 16.dp, vertical = 10.dp)) { Text("עוד", color = MaterialTheme.colorScheme.onTertiary) }
                    }
                }
            }
        } }
        item(key = "hint") { Hint("הערכות חלות על כל האפליקציה, גם על המסכים המקוריים של Metrolist. \"Metrolist קלאסי\" מחזיר את הצבעים המקוריים (הגופן והפינות נשארים לבחירתך). כל פרופיל זוכר ערכה משלו.", Modifier.padding(vertical = 10.dp)) }
    }
}

@Composable
private fun ThemeCard(t: DTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (t == DTheme.CLASSIC) Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF151515)))
    else Brush.linearGradient(listOf(Color(t.surface), Color(t.bg)))
    Box(
        modifier.clip(RoundedCornerShape(22.dp)).background(bg)
            .border(if (selected) 3.dp else 1.dp, if (selected) Brush.linearGradient(listOf(Color(t.accent), Color(t.accent2))) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.15f))), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.emoji, fontSize = 22.sp)
                Spacer(Modifier.width(6.dp))
                Text(t.he, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            // a tiny fake screen: hero + chips + nav dots
            Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(t.accent), Color(t.accent2), Color(t.accent3)))))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(t.accent, t.accent2, t.accent3).forEach { c -> Box(Modifier.width(28.dp).height(10.dp).clip(RoundedCornerShape(50)).background(Color(c).copy(alpha = 0.8f))) }
            }
            if (selected) Text("✓ פעילה", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, label: String?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(brush)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).clickable(onClick = onClick))
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
