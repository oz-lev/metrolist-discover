/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Playlist Merge & Power Tools ("ניהול ומיזוג פלייליסטים"):
 *  - Merge 2..N playlists (Append, Interleave, Smart Shuffle, Sort by Artist/Title/Date/Plays; Dedupe toggle; Live preview; New or Existing target)
 *  - Clean duplicates from any single playlist
 *  - Split / Filter playlist by language or selected artists
 *  - Set Operations between two playlists (Intersection A∩B, Difference A\B, Symmetric Difference)
 *  - Clone playlist
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.metrolist.music.LocalDatabase
import com.metrolist.music.discover.engine.LangMode
import com.metrolist.music.discover.engine.MergeMode
import com.metrolist.music.discover.engine.PlaylistEntry
import com.metrolist.music.discover.engine.PlaylistTools
import com.metrolist.music.discover.engine.SetOp
import com.metrolist.music.discover.v2.ui.BusyOverlay
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Hint
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.SectionTitle
import com.metrolist.music.discover.v2.ui.TonalActionButton
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Page
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.LocalPlaylistInfo
import com.metrolist.music.discover.v21.PlaylistActions
import kotlinx.coroutines.launch

private enum class ToolsTab(val he: String) {
    MERGE("מיזוג"),
    DEDUPE("כפילויות"),
    SPLIT("פיצול וסינון"),
    SETOPS("חיתוך והפרש"),
    CLONE("שכפול"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistToolsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(ToolsTab.MERGE) }
    var playlists by remember { mutableStateOf<List<LocalPlaylistInfo>>(emptyList()) }
    val entriesCache = remember { mutableStateMapOf<String, List<PlaylistEntry>>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    // Merge state
    val mergePicked = remember { mutableStateListOf<String>() }
    var mergeMode by remember { mutableStateOf(MergeMode.APPEND) }
    var mergeDedupe by remember { mutableStateOf(true) }
    var mergeIntoExisting by remember { mutableStateOf(false) }
    var mergeTargetId by remember { mutableStateOf<String?>(null) }
    var mergeNewName by remember { mutableStateOf("") }

    // Split/Filter state
    var splitSourceId by remember { mutableStateOf<String?>(null) }
    var splitLang by remember { mutableStateOf(LangMode.ALL) }
    val splitArtists = remember { mutableStateListOf<String>() }
    var splitExcludeArtists by remember { mutableStateOf(false) }
    var splitNewName by remember { mutableStateOf("") }

    // SetOp state
    var setA by remember { mutableStateOf<String?>(null) }
    var setB by remember { mutableStateOf<String?>(null) }
    var setOp by remember { mutableStateOf(SetOp.INTERSECT) }
    var setNewName by remember { mutableStateOf("") }

    // Clone state
    var cloneSourceId by remember { mutableStateOf<String?>(null) }
    var cloneName by remember { mutableStateOf("") }
    var cloneDedupe by remember { mutableStateOf(true) }

    LaunchedEffect(reloadTick) {
        playlists = PlaylistActions.loadPlaylists(db)
        entriesCache.clear()
    }

    fun ensureLoaded(id: String) {
        if (id in entriesCache) return
        scope.launch {
            entriesCache[id] = runCatching { PlaylistActions.loadEntries(ctx, db, id) }.getOrDefault(emptyList())
        }
    }

    val byId = remember(playlists) { playlists.associateBy { it.id } }

    V2Page(
        title = "ניהול ומיזוג פלייליסטים",
        subtitle = "מיזוג, ניקוי כפילויות, פיצול, חיתוך והפרש בפרופיל הפעיל",
        onBack = onBack,
    ) {
        // Tab selector
        item(key = "pt_tabs") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                ToolsTab.entries.forEach { t ->
                    Pill(t.he, selected = tab == t) { tab = t }
                }
            }
        }

        if (playlists.isEmpty()) {
            item(key = "pt_empty") {
                V2Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("אין פלייליסטים מקומיים בפרופיל הזה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "צור פלייליסטים בספרייה או דרך כפתור ה-+ במסך הראשי, ואז תוכל למזג, לסנן ולנקות אותם כאן.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else when (tab) {
            // ---------------------------------------------------------- 1. MERGE 2..N
            ToolsTab.MERGE -> {
                item(key = "m_pick_title") { SectionTitle("1. בחר 2 או יותר פלייליסטים (${mergePicked.size} נבחרו)") }
                item(key = "m_pick_list") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        playlists.forEach { pl ->
                            val idx = mergePicked.indexOf(pl.id)
                            val selected = idx >= 0
                            PlaylistSelectRow(
                                pl = pl,
                                selected = selected,
                                badge = if (selected) "#${idx + 1}" else null,
                            ) {
                                if (selected) mergePicked.remove(pl.id)
                                else {
                                    mergePicked += pl.id
                                    ensureLoaded(pl.id)
                                }
                                if (mergeNewName.isBlank() && mergePicked.size >= 2) {
                                    val names = mergePicked.take(2).mapNotNull { byId[it]?.name }
                                    mergeNewName = names.joinToString(" + ") + if (mergePicked.size > 2) " + עוד ${mergePicked.size - 2}" else ""
                                }
                            }
                        }
                    }
                }

                item(key = "m_opts_title") { SectionTitle("2. אופן המיזוג") }
                item(key = "m_opts_card") {
                    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("הסר שירים כפולים", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text("מונע מאותו שיר להופיע פעמיים בתוצאה", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = mergeDedupe, onCheckedChange = { mergeDedupe = it })
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MergeMode.entries.forEach { m ->
                                    Pill(m.he, selected = mergeMode == m) { mergeMode = m }
                                }
                            }
                            Text(mergeMode.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Live Preview Card
                item(key = "m_preview") {
                    val loaded = mergePicked.mapNotNull { entriesCache[it] }
                    val allReady = mergePicked.size >= 2 && loaded.size == mergePicked.size
                    val preview = if (allReady) PlaylistTools.merge(loaded, mergeMode, mergeDedupe) else null
                    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("תצוגה מקדימה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (mergePicked.size < 2) {
                                Text("בחר לפחות 2 פלייליסטים למעלה כדי לראות תצוגה מקדימה.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (preview == null) {
                                Text("מחשב שירים וכפילויות…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    "סה״כ ${preview.entries.size} שירים בתוצאה · הוסרו ${preview.duplicatesRemoved} כפילויות (מתוך ${preview.totalBeforeDedupe})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // Output target
                item(key = "m_out_title") { SectionTitle("3. יעד השמירה") }
                item(key = "m_out_card") {
                    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("צור פלייליסט חדש", selected = !mergeIntoExisting) { mergeIntoExisting = false }
                                Pill("מזג לתוך קיים", selected = mergeIntoExisting) { mergeIntoExisting = true }
                            }
                            if (!mergeIntoExisting) {
                                OutlinedTextField(
                                    value = mergeNewName,
                                    onValueChange = { mergeNewName = it },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    placeholder = { Text("שם הפלייליסט החדש...") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    playlists.forEach { pl ->
                                        Pill(pl.name, selected = mergeTargetId == pl.id) { mergeTargetId = pl.id }
                                    }
                                }
                            }
                            val canRun = mergePicked.size >= 2 && (if (mergeIntoExisting) mergeTargetId != null else mergeNewName.isNotBlank())
                            GradientButton("מזג עכשיו", Modifier.fillMaxWidth(), enabled = canRun) {
                                busy = "ממזג פלייליסטים…"
                                scope.launch {
                                    val lists = mergePicked.map { entriesCache[it] ?: PlaylistActions.loadEntries(ctx, db, it) }
                                    val res = PlaylistTools.merge(lists, mergeMode, mergeDedupe)
                                    val msg = if (!mergeIntoExisting) {
                                        val created = PlaylistActions.saveEntriesAsNewPlaylist(ctx, db, mergeNewName, res.entries)
                                        if (created != null) "✓ נוצר ״${created.name}״ (${res.entries.size} שירים, הוסרו ${res.duplicatesRemoved} כפילויות)"
                                        else "שגיאה ביצירת הפלייליסט"
                                    } else {
                                        val tid = mergeTargetId!!
                                        val tName = byId[tid]?.name ?: "פלייליסט"
                                        val n = PlaylistActions.writeEntriesToExisting(ctx, db, tid, tName, res.entries, replaceAll = true)
                                        "✓ עודכן ״$tName״ ($n שירים, הוסרו ${res.duplicatesRemoved} כפילויות)"
                                    }
                                    busy = null
                                    toast(ctx, msg)
                                    reloadTick++
                                }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 2. DEDUPE SINGLE PLAYLIST
            ToolsTab.DEDUPE -> {
                item(key = "d_title") { SectionTitle("ניקוי כפילויות מפלייליסט") }
                item(key = "d_list") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        playlists.forEach { pl ->
                            V2Card(Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pl.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (pl.duplicateCount > 0) "${pl.songCount} שירים · נמצאו ${pl.duplicateCount} כפילויות"
                                            else "${pl.songCount} שירים · נקי מכפילויות ✓",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (pl.duplicateCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (pl.duplicateCount > 0) {
                                        TonalActionButton("נקה (${pl.duplicateCount})") {
                                            busy = "מנקה כפילויות…"
                                            scope.launch {
                                                val entries = PlaylistActions.loadEntries(ctx, db, pl.id)
                                                val res = PlaylistTools.deduplicate(entries)
                                                PlaylistActions.writeEntriesToExisting(ctx, db, pl.id, pl.name, res.entries, replaceAll = true)
                                                busy = null
                                                toast(ctx, "✓ הוסרו ${res.duplicatesRemoved} כפילויות מ״${pl.name}״")
                                                reloadTick++
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 3. SPLIT / FILTER PLAYLIST
            ToolsTab.SPLIT -> {
                item(key = "sp_title") { SectionTitle("פיצול או סינון פלייליסט לעותק חדש") }
                item(key = "sp_src") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        playlists.forEach { pl ->
                            Pill("${pl.name} (${pl.songCount})", selected = splitSourceId == pl.id) {
                                splitSourceId = pl.id
                                splitArtists.clear()
                                ensureLoaded(pl.id)
                                splitNewName = "${pl.name} (מסונן)"
                            }
                        }
                    }
                }
                splitSourceId?.let { sid ->
                    item(key = "sp_controls") {
                        val entries = entriesCache[sid].orEmpty()
                        val uniqueArtists = remember(entries) {
                            entries.filter { it.artistId != "?" && it.artistName.isNotBlank() }
                                .groupBy { it.artistId }
                                .map { (id, list) -> Triple(id, list.first().artistName, list.size) }
                                .sortedByDescending { it.third }
                                .take(30)
                        }
                        var filtered = PlaylistTools.filterByLanguage(entries, splitLang)
                        if (splitArtists.isNotEmpty()) {
                            filtered = PlaylistTools.filterByArtists(filtered, splitArtists.toSet(), include = !splitExcludeArtists)
                        }
                        V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("סינון לפי שפה", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LangMode.entries.forEach { lm -> Pill(lm.he, selected = splitLang == lm) { splitLang = lm } }
                                }
                                if (uniqueArtists.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("סינון אמנים (${splitArtists.size} נבחרו)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Pill(if (splitExcludeArtists) "הוצא נבחרים" else "כלול רק נבחרים", selected = splitExcludeArtists) {
                                            splitExcludeArtists = !splitExcludeArtists
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        uniqueArtists.forEach { (aid, aname, cnt) ->
                                            val on = aid in splitArtists
                                            Pill("$aname ($cnt)", selected = on) {
                                                if (on) splitArtists.remove(aid) else splitArtists += aid
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                Text(
                                    "תוצאה: ${filtered.size} מתוך ${entries.size} שירים",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                OutlinedTextField(
                                    value = splitNewName,
                                    onValueChange = { splitNewName = it },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    placeholder = { Text("שם הפלייליסט החדש...") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                GradientButton("צור פלייליסט מסונן (${filtered.size})", Modifier.fillMaxWidth(), enabled = filtered.isNotEmpty() && splitNewName.isNotBlank()) {
                                    busy = "יוצר פלייליסט מסונן…"
                                    scope.launch {
                                        val created = PlaylistActions.saveEntriesAsNewPlaylist(ctx, db, splitNewName, filtered)
                                        busy = null
                                        if (created != null) { toast(ctx, "✓ נוצר ״${created.name}״ (${created.songCount} שירים)"); reloadTick++ }
                                        else toast(ctx, "שגיאה ביצירה")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 4. SET OPERATIONS (A ∩ B, A \ B, A Δ B)
            ToolsTab.SETOPS -> {
                item(key = "so_title") { SectionTitle("חיתוך והפרש בין שני פלייליסטים") }
                item(key = "so_card") {
                    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("פלייליסט A", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                playlists.forEach { pl ->
                                    Pill(pl.name, selected = setA == pl.id) { setA = pl.id; ensureLoaded(pl.id) }
                                }
                            }
                            Text("פלייליסט B", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                playlists.forEach { pl ->
                                    Pill(pl.name, selected = setB == pl.id) { setB = pl.id; ensureLoaded(pl.id) }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SetOp.entries.forEach { op -> Pill(op.he, selected = setOp == op) { setOp = op } }
                            }
                            Text(setOp.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            val ready = setA != null && setB != null && setA != setB && setA in entriesCache && setB in entriesCache
                            val res = if (ready) PlaylistTools.applySetOp(entriesCache[setA]!!, entriesCache[setB]!!, setOp) else emptyList()
                            if (ready) {
                                Text(
                                    "נמצאו ${res.size} שירים תואמים",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (setA != null && setA == setB) {
                                Hint("בחר שני פלייליסטים שונים")
                            }
                            OutlinedTextField(
                                value = setNewName,
                                onValueChange = { setNewName = it },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                placeholder = { Text("שם לפלייליסט התוצאה...") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            GradientButton("שמור תוצאה (${res.size} שירים)", Modifier.fillMaxWidth(), enabled = ready && res.isNotEmpty() && setNewName.isNotBlank()) {
                                busy = "יוצר פלייליסט…"
                                scope.launch {
                                    val created = PlaylistActions.saveEntriesAsNewPlaylist(ctx, db, setNewName, res)
                                    busy = null
                                    if (created != null) { toast(ctx, "✓ נוצר ״${created.name}״ (${created.songCount} שירים)"); reloadTick++ }
                                    else toast(ctx, "שגיאה ביצירה")
                                }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 5. CLONE PLAYLIST
            ToolsTab.CLONE -> {
                item(key = "cl_title") { SectionTitle("שכפול פלייליסט") }
                item(key = "cl_card") {
                    V2Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                playlists.forEach { pl ->
                                    Pill("${pl.name} (${pl.songCount})", selected = cloneSourceId == pl.id) {
                                        cloneSourceId = pl.id
                                        cloneName = "${pl.name} (עותק)"
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("נקה כפילויות בעותק", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(checked = cloneDedupe, onCheckedChange = { cloneDedupe = it })
                            }
                            OutlinedTextField(
                                value = cloneName,
                                onValueChange = { cloneName = it },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                placeholder = { Text("שם העותק...") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            GradientButton("שכפל פלייליסט", Modifier.fillMaxWidth(), enabled = cloneSourceId != null && cloneName.isNotBlank()) {
                                val sid = cloneSourceId!!
                                busy = "משכפל…"
                                scope.launch {
                                    val entries = PlaylistActions.loadEntries(ctx, db, sid)
                                    val cloned = PlaylistTools.cloneEntries(entries, dedupe = cloneDedupe)
                                    val created = PlaylistActions.saveEntriesAsNewPlaylist(ctx, db, cloneName, cloned)
                                    busy = null
                                    if (created != null) { toast(ctx, "✓ שוכפל ל״${created.name}״ (${created.songCount} שירים)"); reloadTick++ }
                                    else toast(ctx, "שגיאה בשכפול")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    busy?.let { BusyOverlay(it) }
}

@Composable
private fun PlaylistSelectRow(
    pl: LocalPlaylistInfo,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerLow,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        border = BorderStroke(1.dp, if (selected) cs.primary.copy(alpha = 0.55f) else cs.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dupNote = if (pl.duplicateCount > 0) " · ${pl.duplicateCount} כפילויות" else ""
                Text("${pl.songCount} שירים$dupNote", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (badge != null) {
                Text(badge, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.primary)
            }
        }
    }
}
