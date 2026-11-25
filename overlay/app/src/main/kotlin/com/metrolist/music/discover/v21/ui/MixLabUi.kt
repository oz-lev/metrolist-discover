/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Custom Mix Builder ("בונה מיקסים"):
 *  - Up to 25 artists with live search over all library & listened artists
 *  - Multi-select chips with tap-to-cycle weight tier (Low / Normal / Boost) & remove (✕)
 *  - Full controls: size (15..100), familiar <-> discovery (0..100%), mood, energy, day-part bias, language, heard/liked filter
 *  - Saved custom mixes per profile (1-tap play, load/edit, delete)
 *  - Export generated mix directly into a real Room playlist ("שמור כפלייליסט")
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.discover.engine.ArtistWeight
import com.metrolist.music.discover.engine.DayPart
import com.metrolist.music.discover.engine.HeardFilter
import com.metrolist.music.discover.engine.LangMode
import com.metrolist.music.discover.engine.MixLab
import com.metrolist.music.discover.engine.MixRecipe
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.engine.SavedMixRecipe
import com.metrolist.music.discover.v2.BuiltMix
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.TonalActionButton
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.playMix
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.PlaylistActions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LAB_SIZES = listOf(15, 25, 40, 60, 100)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixLabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val version by V2Settings.version.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf<String?>(null) }
    var artists by remember { mutableStateOf<List<V2Engine.ArtistPick>>(emptyList()) }
    var artistQuery by remember { mutableStateOf("") }
    var savedList by remember { mutableStateOf<List<SavedMixRecipe>>(emptyList()) }
    var editingSavedId by remember { mutableStateOf<String?>(null) }

    val picked = remember { mutableStateListOf<String>() }
    val weights = remember { mutableStateMapOf<String, ArtistWeight>() }
    var mood by remember { mutableStateOf<MoodPreset?>(null) }
    var energyAuto by remember { mutableStateOf(true) }
    var energy by remember { mutableFloatStateOf(0.6f) }
    var newShare by remember { mutableFloatStateOf(0.3f) }
    var size by remember { mutableStateOf(40) }
    var lang by remember { mutableStateOf(LangMode.ALL) }
    var heardFilter by remember { mutableStateOf(HeardFilter.ALL) }
    var dayPart by remember { mutableStateOf<DayPart?>(null) }

    var showSaveRecipeDialog by remember { mutableStateOf(false) }
    var showSavePlaylistDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        artists = runCatching { V2Engine.allArtists(ctx, db, 500) }.getOrDefault(emptyList())
    }
    LaunchedEffect(version) {
        savedList = V2Engine.savedMixes(ctx)
    }

    val artistNames = remember(artists) { artists.associate { it.id to it.name } }

    fun currentRecipe(): MixRecipe = MixRecipe(
        seedArtists = picked.toList(),
        mood = mood,
        energy = if (energyAuto) null else energy.toDouble(),
        newShare = newShare.toDouble(),
        size = size,
        lang = lang,
        artistWeights = weights.filterKeys { it in picked }.mapValues { it.value.tier },
        heardFilter = heardFilter,
        dayPartBias = dayPart,
    )

    fun loadRecipe(sm: SavedMixRecipe) {
        editingSavedId = sm.id
        picked.clear(); picked.addAll(sm.recipe.seedArtists.take(MixLab.MAX_SEEDS))
        weights.clear()
        sm.recipe.artistWeights.forEach { (k, v) -> weights[k] = ArtistWeight.of(v) }
        mood = sm.recipe.mood
        energyAuto = sm.recipe.energy == null
        energy = (sm.recipe.energy ?: 0.6).toFloat()
        newShare = sm.recipe.newShare.toFloat()
        size = sm.recipe.size
        lang = sm.recipe.lang
        heardFilter = sm.recipe.heardFilter
        dayPart = sm.recipe.dayPartBias
        toast(ctx, "נטען המתכון: ${sm.name}")
    }

    fun suggestedName(): String {
        val parts = ArrayList<String>()
        mood?.let { parts += it.he }
        if (picked.isNotEmpty()) {
            val firstNames = picked.take(2).mapNotNull { artistNames[it] }
            if (firstNames.isNotEmpty()) {
                parts += if (picked.size <= 2) firstNames.joinToString(" + ")
                else "${firstNames.joinToString(" + ")} + עוד ${picked.size - 2}"
            }
        }
        return parts.joinToString(" · ").ifBlank { "המיקס שלי" }
    }

    fun runPlay(label: String, block: suspend () -> BuiltMix) {
        if (busy != null) return
        val p = player ?: run { toast(ctx, "הנגן לא מוכן עדיין"); return }
        busy = label
        scope.launch {
            val mix = runCatching { block() }.getOrNull()
            busy = null
            if (mix == null) toast(ctx, "משהו השתבש, נסה שוב") else p.playMix(mix, ctx)
        }
    }

    V2Page(
        title = "בונה מיקסים",
        subtitle = "עד ${MixLab.MAX_SEEDS} אמנים עם משקלים, סינונים, שמירת מיקסים וייצוא לפלייליסט",
        onBack = onBack,
    ) {
        // 0. Saved Custom Mixes Shelf
        if (savedList.isNotEmpty()) {
            item(key = "ml_saved_title") { SectionTitle("המיקסים השמורים שלי (${savedList.size})") }
            item(key = "ml_saved_list") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    savedList.forEach { sm ->
                        V2Card(Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Column(Modifier.weight(1f).clickable { loadRecipe(sm) }) {
                                    Text(sm.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${sm.recipe.seedArtists.size} אמנים · ${sm.recipe.size} שירים · ${(sm.recipe.newShare * 100).roundToInt()}% חדש",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Pill("▶ נגן", selected = true) {
                                    runPlay("בונה ${sm.name}…") { V2Engine.lab(ctx, db, sm.recipe).copy(title = sm.name) }
                                }
                                Spacer(Modifier.width(6.dp))
                                Pill("✕", selected = false) {
                                    V2Engine.deleteSavedMix(ctx, sm.id)
                                    if (editingSavedId == sm.id) editingSavedId = null
                                    toast(ctx, "נמחק: ${sm.name}")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1. Multi-Artist Picker (up to 25!)
        item(key = "ml_artists_title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            ) {
                Text(
                    "בחירת אמנים (${picked.size} / ${MixLab.MAX_SEEDS})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (picked.isNotEmpty()) {
                    TextButton(onClick = { picked.clear(); weights.clear() }) {
                        Text("נקה הכל")
                    }
                }
            }
        }

        item(key = "ml_artists_card") {
            val q = artistQuery.trim()
            val matches = remember(artists, q, picked.size) {
                val baseList = if (q.isEmpty()) artists.take(36)
                else artists.filter { it.name.contains(q, ignoreCase = true) }.take(40)
                baseList.filter { it.id !in picked }
            }
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Selected artists with tap-to-cycle weight + X remove
                    if (picked.isNotEmpty()) {
                        Text(
                            "נבחרו (לחץ על תג לשינוי משקל: רגיל / מודגש / נמוך):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            picked.forEach { aid ->
                                val name = artistNames[aid] ?: aid.removePrefix("name:")
                                val w = weights[aid] ?: ArtistWeight.NORMAL
                                val badge = when (w) {
                                    ArtistWeight.BOOST -> " ★"
                                    ArtistWeight.LOW -> " ↓"
                                    ArtistWeight.NORMAL -> ""
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                                        Text(
                                            "$name$badge",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable {
                                                val nxt = w.next()
                                                weights[aid] = nxt
                                                toast(ctx, "$name: משקל ${nxt.he}")
                                            },
                                        )
                                        Text(
                                            "  ✕ ",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                                picked.remove(aid)
                                                weights.remove(aid)
                                            }.padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }

                    // Search bar over ALL library & history artists
                    OutlinedTextField(
                        value = artistQuery,
                        onValueChange = { artistQuery = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("חפש אמן מהספרייה וההיסטוריה (${artists.size} אמנים)...") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (artists.isEmpty()) {
                        Hint("שמע כמה שירים בפרופיל הזה כדי שהאמנים יופיעו כאן")
                    } else if (matches.isEmpty()) {
                        Hint("לא נמצאו אמנים נוספים תואמים לחיפוש")
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            matches.forEach { a ->
                                val lbl = if (a.plays > 0) "${a.name} (${a.plays})" else a.name
                                Pill(lbl, selected = false) {
                                    if (picked.size < MixLab.MAX_SEEDS) {
                                        picked += a.id
                                        weights[a.id] = ArtistWeight.NORMAL
                                    } else {
                                        toast(ctx, "הגעת למקסימום של ${MixLab.MAX_SEEDS} אמנים")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Quick 1-artist Radio / 2-artist Blend shortcuts
        if (picked.size == 1 || picked.size == 2) {
            item(key = "ml_shortcuts") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (picked.size == 1) {
                        val id = picked[0]; val nm = artistNames[id] ?: "האמן"
                        TonalActionButton("רדיו של $nm", Modifier.fillMaxWidth()) {
                            runPlay("רדיו: $nm…") { V2Engine.artistRadio(ctx, db, id, nm) }
                        }
                    } else {
                        val aId = picked[0]; val bId = picked[1]
                        val aNm = artistNames[aId] ?: "אמן א"; val bNm = artistNames[bId] ?: "אמן ב"
                        TonalActionButton("Blend: $aNm × $bNm", Modifier.fillMaxWidth()) {
                            runPlay("$aNm × $bNm…") { V2Engine.blendArtists(ctx, db, aId to aNm, bId to bNm) }
                        }
                    }
                }
            }
        }

        // 3. Mood & Day-part bias
        item(key = "ml_mood_title") { SectionTitle("אווירה ושעת יום") }
        item(key = "ml_mood_card") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("אוטומטי", selected = mood == null) { mood = null }
                        MoodPreset.entries.forEach { m ->
                            Pill("${m.emoji} ${m.he}", selected = mood == m) { mood = if (mood == m) null else m }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Text("הטיה לפי שעת יום", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("אוטומטי", selected = dayPart == null) { dayPart = null }
                        DayPart.entries.forEach { dp ->
                            Pill("${dp.emoji} ${dp.he}", selected = dayPart == dp) { dayPart = if (dayPart == dp) null else dp }
                        }
                    }
                }
            }
        }

        // 4. Discovery %, Energy, Length, Language, Heard/Liked Filter
        item(key = "ml_tuning_title") { SectionTitle("התאמה אישית וסינון") }
        item(key = "ml_tuning_card") {
            V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Familiar vs Discovery
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            Text("מוכר מול גילוי חדש", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${(newShare * 100).roundToInt()}% חדש", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = newShare, onValueChange = { newShare = it }, valueRange = 0f..1f, steps = 19)
                    }

                    // Energy
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("אנרגיה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Pill(if (energyAuto) "אוטומטי" else "${(energy * 100).roundToInt()}%", selected = !energyAuto) { energyAuto = !energyAuto }
                        }
                        if (!energyAuto) Slider(value = energy, onValueChange = { energy = it }, valueRange = 0f..1f)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Mix size
                    Text("אורך המיקס", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LAB_SIZES.forEach { n -> Pill("$n שירים", selected = size == n) { size = n } }
                    }

                    // Language
                    Text("שפה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LangMode.entries.forEach { l -> Pill(l.he, selected = lang == l) { lang = l } }
                    }

                    // Heard / Liked filter
                    Text("סינון שירים", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeardFilter.entries.forEach { hf -> Pill(hf.he, selected = heardFilter == hf) { heardFilter = hf } }
                    }
                }
            }
        }

        // 5. Actions: Play / Save Recipe / Save as Playlist
        item(key = "ml_actions") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                GradientButton("▶ בנה ונגן", Modifier.fillMaxWidth()) {
                    runPlay("בונה מיקס (${picked.size} אמנים)…") { V2Engine.lab(ctx, db, currentRecipe()) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TonalActionButton("💾 שמור מיקס", Modifier.weight(1f)) {
                        dialogName = suggestedName()
                        showSaveRecipeDialog = true
                    }
                    TonalActionButton("📋 שמור כפלייליסט", Modifier.weight(1f)) {
                        dialogName = suggestedName()
                        showSavePlaylistDialog = true
                    }
                }
            }
        }
    }

    if (showSaveRecipeDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRecipeDialog = false },
            title = { Text("שמירת מתכון מיקס") },
            text = {
                OutlinedTextField(
                    value = dialogName,
                    onValueChange = { dialogName = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("שם המיקס...") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = V2Engine.saveMixRecipe(ctx, dialogName, currentRecipe(), editingSavedId)
                    editingSavedId = saved.id
                    showSaveRecipeDialog = false
                    toast(ctx, "נשמר: ${saved.name}")
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { showSaveRecipeDialog = false }) { Text("ביטול") } },
        )
    }

    if (showSavePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showSavePlaylistDialog = false },
            title = { Text("ייצוא המיקס לפלייליסט") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("המנוע יבנה את המיקס וישמור אותו כפלייליסט מקומי בפרופיל הפעיל.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("שם הפלייליסט...") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val plName = dialogName
                    showSavePlaylistDialog = false
                    busy = "שומר כפלייליסט…"
                    scope.launch {
                        val mix = runCatching { V2Engine.lab(ctx, db, currentRecipe()) }.getOrNull()
                        val created = if (mix != null && mix.tracks.isNotEmpty()) {
                            PlaylistActions.createPlaylistWithTracks(ctx, db, plName, mix.tracks)
                        } else null
                        busy = null
                        if (created != null) toast(ctx, "✓ נשמר פלייליסט ״${created.name}״ (${created.songCount} שירים)")
                        else toast(ctx, "לא נמצאו שירים ליצירת הפלייליסט")
                    }
                }) { Text("שמור פלייליסט") }
            },
            dismissButton = { TextButton(onClick = { showSavePlaylistDialog = false }) { Text("ביטול") } },
        )
    }

    busy?.let { BusyOverlay(it) }
}
