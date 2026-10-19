/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.metrolist.music.discover.engine.AutoRule
import com.metrolist.music.discover.engine.DProfile
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.Profiles
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.V2Rtl
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.V21Profiles
import kotlin.math.roundToInt

private val PROFILE_EMOJIS = listOf("😎", "💪", "🎧", "🌙", "🚗", "🥳", "👤", "🔥", "💔", "🧘", "🎮", "📚", "☕", "🏖️", "🕺", "👑")
private val PROFILE_COLORS = listOf(0xFF8B5CF6, 0xFFEF4444, 0xFF0EA5E9, 0xFF6366F1, 0xFF22D3EE, 0xFFF59E0B, 0xFF94A3B8, 0xFFEC4899, 0xFF22C55E, 0xFFF97316)

/** Quick profile switcher: a row of profile chips (Home header and the Mixes hub). */
@Composable
fun ProfileSwitcherRow(modifier: Modifier = Modifier, onManage: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val list = remember(version) { V21Profiles.list(ctx) }
    val active = remember(version) { ProfileScope.active }
    LazyRow(modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list.size) { i ->
            val p = list[i]
            val on = p.id == active
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Brush.linearGradient(listOf(Color(p.color), Color(p.color).copy(alpha = 0.6f))) else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest)))
                    .clickable { if (!on) { V21Profiles.switchTo(ctx, p.id); toast(ctx, "${p.emoji} עברת לפרופיל ${p.name}" + if (p.guest) " (לא נרשם כלום)" else "") } }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(p.emoji, fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Text(p.name, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onManage != null) item {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onManage).padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text("⚙️ ניהול", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ProfilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by V2Settings.version.collectAsStateWithLifecycle()
    val s = remember(version) { V2Settings.get(ctx) }
    val list = remember(version) { V21Profiles.list(ctx) }
    var edit by remember { mutableStateOf<DProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var addRule by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DProfile?>(null) }

    V2Page(title = "פרופילים 👥", subtitle = "לכל פרופיל הגדרות, ערכה ולמידה משלו", onBack = onBack) {
        list.forEach { p ->
            item(key = "p_${p.id}") {
                val on = p.id == ProfileScope.active
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(p.color).copy(alpha = if (on) 0.95f else 0.45f), Color(p.color).copy(alpha = if (on) 0.55f else 0.2f))))
                    .clickable { V21Profiles.switchTo(ctx, p.id) }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.emoji, fontSize = 30.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (on) "  ✓" else "", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                p.guest -> "אורח: ההשמעות לא נרשמות ולא משנות את הלמידה שלך"
                                p.id == ProfileScope.DEFAULT -> "הפרופיל הראשי (כל הלמידה הקיימת)"
                                else -> "הגדרות, ערכה ולמידה נפרדות"
                            }, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("✏️", fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable { edit = p }.padding(8.dp))
                        Text("⧉", fontSize = 20.sp, color = Color.White, modifier = Modifier.clip(CircleShape).clickable { V21Profiles.duplicate(ctx, p.id)?.let { toast(ctx, "שוכפל: ${it.name}") } }.padding(8.dp))
                        if (Profiles.canDelete(p.id)) Text("🗑️", fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable { confirmDelete = p }.padding(8.dp))
                    }
                }
            }
        }
        item(key = "new") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Pill("➕ פרופיל חדש", selected = true) { creating = true } }
        }
        item(key = "auto_title") { SectionTitle("⏰ מעבר אוטומטי") }
        item(key = "auto") {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Group("⏰", "לפי יום ושעה", Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF4338CA)))) {
                    Toggle("מעבר אוטומטי", "בתחילת חלון זמן עוברים לפרופיל, בסופו חוזרים ל\"אני\". החלפה ידנית מכובדת עד החלון הבא.", s.autoSwitch) { s.autoSwitch = it }
                    val rules = s.autoRules
                    rules.forEachIndexed { i, r ->
                        val p = list.firstOrNull { it.id == r.profileId }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            val days = if (r.days.size == 7) "כל יום" else DAYS.filter { it.second in r.days }.joinToString(" ") { it.first + "׳" }
                            Text("${p?.emoji ?: "?"} ${p?.name ?: "נמחק"} · $days · ${hhmm(r.fromMinute)}–${hhmm(r.toMinute)}", color = Color.White, modifier = Modifier.weight(1f))
                            Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { s.autoRules = rules.filterIndexed { j, _ -> j != i } }.padding(8.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Pill("➕ חוק חדש", selected = true) { addRule = true }
                        Pill("💼 עבודה א׳–ה׳ 9–17") { s.autoRules = s.autoRules + AutoRule("work", setOf(7, 1, 2, 3, 4), 9 * 60, 17 * 60); s.autoSwitch = true }
                        Pill("🌙 לילה 23–6") { s.autoRules = s.autoRules + AutoRule("night", (1..7).toSet(), 23 * 60, 6 * 60); s.autoSwitch = true }
                    }
                }
            }
        }
        item(key = "hint") { Hint("ההיסטוריה של Metrolist משותפת לכולם (חוץ מאורח, שלא נרשם בכלל). הלמידה של Discover, ההגדרות והערכה נפרדות לכל פרופיל.", Modifier.padding(vertical = 10.dp)) }
    }

    if (creating || edit != null) ProfileDialog(edit, onDismiss = { creating = false; edit = null }) { name, emoji, color ->
        val e = edit
        if (e == null) { val p = V21Profiles.create(ctx, name, emoji, color); V21Profiles.switchTo(ctx, p.id) } else V21Profiles.rename(ctx, e.id, name, emoji, color)
        creating = false; edit = null
    }
    if (addRule) AutoRuleDialog(list, onDismiss = { addRule = false }) { r -> s.autoRules = s.autoRules + r; s.autoSwitch = true; addRule = false }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("למחוק את ${p.name}?") },
            text = { Text("ההגדרות והלמידה של הפרופיל הזה יימחקו. ההיסטוריה שלך נשארת.") },
            confirmButton = { TextButton(onClick = { V21Profiles.delete(ctx, p.id); confirmDelete = null }) { Text("מחק") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun ProfileDialog(p: DProfile?, onDismiss: () -> Unit, onSave: (String, String, Long) -> Unit) {
    var name by remember { mutableStateOf(p?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(p?.emoji ?: "🔥") }
    var color by remember { mutableStateOf(p?.color ?: 0xFFEC4899) }
    V2Rtl {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (p == null) "פרופיל חדש" else "עריכת פרופיל") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("שם") }, modifier = Modifier.fillMaxWidth())
                    Text("אימוג׳י", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(PROFILE_EMOJIS.size) { i ->
                            val e = PROFILE_EMOJIS[i]
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest).clickable { emoji = e }) { Text(e, fontSize = 20.sp) }
                        }
                    }
                    Text("צבע", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(PROFILE_COLORS.size) { i ->
                            val c = PROFILE_COLORS[i]
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(c)).clickable { color = c }) {
                                if (c == color) Text("✓", color = Color.White, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), emoji, color) }) { Text("שמור") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        )
    }
}

@Composable
private fun AutoRuleDialog(list: List<DProfile>, onDismiss: () -> Unit, onAdd: (AutoRule) -> Unit) {
    var pid by remember { mutableStateOf(list.firstOrNull { it.id != ProfileScope.DEFAULT }?.id ?: ProfileScope.DEFAULT) }
    var days by remember { mutableStateOf(setOf(7, 1, 2, 3, 4)) }
    var from by remember { mutableFloatStateOf(9f) }
    var to by remember { mutableFloatStateOf(17f) }
    V2Rtl {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("חוק מעבר חדש") },
            text = {
                Column {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list.size) { i ->
                            val p = list[i]
                            Pill("${p.emoji} ${p.name}", selected = p.id == pid) { pid = p.id }
                        }
                    }
                    Text("באילו ימים", modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        DAYS.forEach { (l, iso) ->
                            val on = iso in days
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { days = if (on) days - iso else days + iso }) { Text(l, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    Text("משעה ${hhmm(from.roundToInt() * 60)} עד ${hhmm(to.roundToInt() * 60)}", modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                    Slider(value = from, onValueChange = { from = it }, valueRange = 0f..23f, steps = 22)
                    Slider(value = to, onValueChange = { to = it }, valueRange = 0f..23f, steps = 22)
                }
            },
            confirmButton = { TextButton(enabled = days.isNotEmpty(), onClick = { onAdd(AutoRule(pid, days, from.roundToInt() * 60, to.roundToInt() * 60)) }) { Text("הוסף") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        )
    }
}
