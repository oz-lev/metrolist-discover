/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Frictionless Quick-Add to Current Playlist or Another Playlist directly from the Home screen
 * (works on Discover/V2Home cards AND upstream Metrolist HomeScreen Quick Picks / Forgotten Favorites).
 */
package com.metrolist.music.discover.v21.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.db.entities.Song
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.discover.v2.ui.GradientButton
import com.metrolist.music.discover.v2.ui.Pill
import com.metrolist.music.discover.v2.ui.TonalActionButton
import com.metrolist.music.discover.v2.ui.V2Card
import com.metrolist.music.discover.v2.ui.V2Rtl
import com.metrolist.music.discover.v2.ui.rememberTap
import com.metrolist.music.discover.v2.ui.toast
import com.metrolist.music.discover.v21.AddOutcome
import com.metrolist.music.discover.v21.LocalPlaylistInfo
import com.metrolist.music.discover.v21.PlaylistActions
import com.metrolist.music.discover.v21.PlaylistActions.toTrackMeta
import kotlinx.coroutines.launch

/**
 * Compact Quick-Add button on any song card or row:
 *  - Tap: adds immediately to "הפלייליסט הנוכחי" (if set), or opens the playlist picker if none is set yet.
 *  - Long-press: always opens the full playlist picker ("הוסף לפלייליסט אחר..." / create new / change active).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickAddSongIcon(
    track: TrackMeta,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val tap = rememberTap()
    var showPicker by remember { mutableStateOf(false) }
    var justAdded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val sz = if (compact) 40.dp else 44.dp

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sz)
            .clip(CircleShape)
            .background(if (justAdded) cs.primaryContainer else cs.surfaceContainerHigh.copy(alpha = 0.88f))
            .combinedClickable(
                onClick = {
                    tap()
                    scope.launch {
                        val qTitle = player?.queueTitle?.value
                        val cur = PlaylistActions.resolveCurrentPlaylist(ctx, db, qTitle)
                        if (cur == null) {
                            showPicker = true
                        } else {
                            val res = PlaylistActions.addTrackToPlaylist(ctx, db, track, cur.id, cur.name)
                            when (res.outcome) {
                                AddOutcome.ADDED -> {
                                    justAdded = true
                                    toast(ctx, "✓ נוסף ל״${res.playlistName}״ (לחיצה ארוכה לפלייליסט אחר)")
                                }
                                AddOutcome.ALREADY_EXISTS -> {
                                    justAdded = true
                                    toast(ctx, "כבר קיים ב״${res.playlistName}״ · לחיצה ארוכה להחלפה")
                                }
                                AddOutcome.FAILED -> toast(ctx, "לא הצלחתי להוסיף לפלייליסט")
                            }
                        }
                    }
                },
                onLongClick = {
                    tap()
                    showPicker = true
                },
            ),
    ) {
        Text(
            if (justAdded) "✓" else "+",
            fontSize = if (compact) 18.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (justAdded) cs.onPrimaryContainer else cs.primary,
        )
    }

    if (showPicker) {
        QuickPlaylistPickerSheet(
            track = track,
            onDismiss = { showPicker = false },
            onAdded = { name ->
                justAdded = true
                showPicker = false
                toast(ctx, "✓ נוסף ל״$name״")
            },
        )
    }
}

/**
 * HOOK TARGET (hook "home-quick-add" in upstream HomeScreen.kt QuickPicks & ForgottenFavorites):
 * renders a clean Quick-Add button right next to the 3-dots menu on upstream Home song rows.
 */
@Composable
fun UpstreamHomeQuickAddButton(song: Song) {
    QuickAddSongIcon(track = song.toTrackMeta(), compact = true)
}

/**
 * Compact bar on Home showing the active target playlist for 1-tap Quick-Add, with 1-tap "שנה" picker
 * and shortcut to Playlist Merge / Power Tools.
 */
@Composable
fun ActivePlaylistBar(
    modifier: Modifier = Modifier,
    onOpenTools: () -> Unit,
) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val version by V2Settings.version.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf<LocalPlaylistInfo?>(null) }
    var showSwitch by remember { mutableStateOf(false) }

    LaunchedEffect(version, player?.queueTitle?.value) {
        current = runCatching { PlaylistActions.resolveCurrentPlaylist(ctx, db, player?.queueTitle?.value) }.getOrNull()
    }

    V2Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "פלייליסט להוספה מהירה (+)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    current?.let { "${it.name} (${it.songCount})" } ?: "לחץ + על שיר לבחירת פלייליסט",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Pill("שנה", selected = false) { showSwitch = true }
            Spacer(Modifier.width(6.dp))
            Pill("מיזוג", selected = true) { onOpenTools() }
        }
    }

    if (showSwitch) {
        ActivePlaylistSelectorSheet(
            currentId = current?.id,
            onPicked = { p ->
                PlaylistActions.setCurrentPlaylist(ctx, p.id, p.name)
                current = p
                showSwitch = false
                toast(ctx, "פלייליסט פעיל: ${p.name}")
            },
            onDismiss = { showSwitch = false },
        )
    }
}

/**
 * Full bottom sheet for adding [track] to the current playlist, any other local playlist,
 * or creating a brand-new playlist inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPlaylistPickerSheet(
    track: TrackMeta,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<LocalPlaylistInfo>>(emptyList()) }
    var current by remember { mutableStateOf<LocalPlaylistInfo?>(null) }
    var containing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(track.id) {
        playlists = PlaylistActions.loadPlaylists(db)
        current = PlaylistActions.resolveCurrentPlaylist(ctx, db, player?.queueTitle?.value)
        containing = PlaylistActions.playlistsContaining(db, track.id)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        V2Rtl {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("הוספה לפלייליסט", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${track.title} · ${track.artistLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )

                // Prominent 1-tap action for the current playlist
                current?.let { cur ->
                    val hasIt = cur.id in containing
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                            scope.launch {
                                val res = PlaylistActions.addTrackToPlaylist(ctx, db, track, cur.id, cur.name)
                                if (res.outcome == AddOutcome.ALREADY_EXISTS) {
                                    toast(ctx, "כבר קיים ב״${cur.name}״")
                                    onDismiss()
                                } else if (res.outcome == AddOutcome.ADDED) {
                                    onAdded(cur.name)
                                }
                            }
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("הפלייליסט הנוכחי", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${cur.name} (${cur.songCount} שירים)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (hasIt) "✓ קיים" else "+ הוסף",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Inline create new playlist
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("שם לפלייליסט חדש...") },
                        modifier = Modifier.weight(1f),
                    )
                    TonalActionButton(
                        text = "צור והוסף",
                        enabled = !creating && newName.isNotBlank(),
                    ) {
                        creating = true
                        scope.launch {
                            val created = PlaylistActions.createPlaylistWithTracks(ctx, db, newName, listOf(track))
                            creating = false
                            if (created != null) onAdded(created.name)
                            else toast(ctx, "לא הצלחתי ליצור פלייליסט")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "פלייליסטים בפרופיל זה (${playlists.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                if (playlists.isEmpty()) {
                    Text(
                        "אין עדיין פלייליסטים בפרופיל הזה — צור אחד למעלה",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(playlists, key = { it.id }) { pl ->
                            val hasIt = pl.id in containing
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch {
                                            val res = PlaylistActions.addTrackToPlaylist(ctx, db, track, pl.id, pl.name)
                                            when (res.outcome) {
                                                AddOutcome.ADDED -> onAdded(pl.name)
                                                AddOutcome.ALREADY_EXISTS -> {
                                                    toast(ctx, "כבר קיים ב״${pl.name}״")
                                                    onDismiss()
                                                }
                                                AddOutcome.FAILED -> toast(ctx, "שגיאה בהוספה")
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${pl.songCount} שירים", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    if (hasIt) "✓ קיים" else "הוסף",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (hasIt) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivePlaylistSelectorSheet(
    currentId: String?,
    onPicked: (LocalPlaylistInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val db = LocalDatabase.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<LocalPlaylistInfo>>(emptyList()) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { playlists = PlaylistActions.loadPlaylists(db) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        V2Rtl {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("בחירת פלייליסט נוכחי להוספה מהירה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "לחיצה על + ליד כל שיר במסך הראשי תוסיף ישירות לכאן",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("צור פלייליסט חדש...") },
                        modifier = Modifier.weight(1f),
                    )
                    GradientButton("צור", enabled = newName.isNotBlank()) {
                        scope.launch {
                            PlaylistActions.createPlaylistWithTracks(ctx, db, newName, emptyList())?.let { onPicked(it) }
                        }
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        val selected = pl.id == currentId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { onPicked(pl) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${pl.songCount} שירים", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) Text("✓ פעיל", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * HOOK TARGET (optional in LibraryPlaylistsScreen & shown in MixesHub):
 * clean M3 banner linking to Playlist Merge & Power Tools.
 */
@Composable
fun LibraryPlaylistToolsBanner(onOpen: () -> Unit) {
    V2Rtl {
        V2Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            onClick = onOpen,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("מיזוג וכלי פלייליסטים", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "מיזוג 2+ פלייליסטים, ניקוי כפילויות, פיצול לפי שפה/אמן, חיתוך והפרש",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
