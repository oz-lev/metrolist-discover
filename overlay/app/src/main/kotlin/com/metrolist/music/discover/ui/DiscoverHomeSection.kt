/**
 * Metrolist Discover (fork addition) - GPL-3.0
 * Home-screen UI: "Discover for you" row, auto mixes, the stats bubble and the Discover settings sheet.
 * Entry point: discoverForYouSection(scope) - one line inside HomeScreen's LazyColumn.
 */
package com.metrolist.music.discover.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.discover.DiscoverItem
import com.metrolist.music.discover.engine.Evaluator
import com.metrolist.music.discover.engine.MixBuilder
import com.metrolist.music.discover.engine.Reason
import com.metrolist.music.discover.engine.StatsFacts
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Called from HomeScreen's LazyColumn (the single UI hook). */
fun discoverForYouSection(scope: LazyListScope) {
    scope.item(key = "metrolist_discover_section") { DiscoverForYouSection() }
}

private fun isHebrewUi(): Boolean = Locale.getDefault().language.let { it == "iw" || it == "he" }

private fun bigThumb(url: String?): String? = url?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")

private fun TrackMeta.toMediaItem() = SongItem(
    id = id,
    title = title,
    artists = artistNames.mapIndexed { i, n -> Artist(n, artistIds.getOrNull(i)?.takeUnless { it.startsWith("name:") }) },
    duration = durationSec.takeIf { it > 0 },
    thumbnail = thumbnailUrl ?: "",
).toMediaItem()

@Composable
fun DiscoverForYouSection() {
    val vm: DiscoverViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current ?: return
    var showSheet by remember { mutableStateOf(false) }
    val title = stringResource(R.string.discover_title)

    fun play(list: List<TrackMeta>, start: Int, queueTitle: String) {
        if (list.isEmpty()) return
        playerConnection.playQueue(ListQueue(title = queueTitle, items = list.map { it.toMediaItem() }, startIndex = start))
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.discover_subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
            else IconButton(onClick = vm::refresh) { Icon(painterResource(R.drawable.discover_ic_refresh), contentDescription = null) }
            IconButton(onClick = { vm.loadStats(); showSheet = true }) { Icon(painterResource(R.drawable.discover_ic_tune), contentDescription = null) }
        }

        if (state.items.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(if (state.coldStart) R.string.discover_cold else R.string.discover_loading),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            FilledTonalButton(
                onClick = { play(state.items.map { it.track }, 0, title) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(painterResource(R.drawable.discover_ic_sparkle), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.discover_smart_radio))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.items, key = { _, it -> "d_" + it.track.id }) { i, item ->
                    DiscoverCard(item) { play(state.items.map { it.track }, i, title) }
                }
            }
        }

        if (state.mixes.isNotEmpty()) {
            Text(
                stringResource(R.string.discover_mixes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.mixes, key = { i, m -> "m_${m.kind}_$i" }) { _, mix ->
                    val name = if (isHebrewUi()) mix.titleHe else mix.titleEn
                    MixCard(mix, name) { play(mix.tracks, 0, name) }
                }
            }
        }
    }

    state.fact?.let { StatsBubble(it, onDismiss = vm::dismissFact, onNext = vm::nextFact) }
    if (showSheet) DiscoverSheet(state, vm) { showSheet = false }
}

@Composable
private fun reasonText(item: DiscoverItem): String = when (item.reason) {
    Reason.FORGOTTEN_FAVORITE -> stringResource(R.string.discover_reason_forgotten)
    Reason.NEW_FROM_ARTIST -> stringResource(R.string.discover_reason_artist)
    Reason.SIMILAR_ARTIST -> stringResource(R.string.discover_reason_similar)
    Reason.EXPLORE -> stringResource(R.string.discover_reason_explore)
    Reason.FITS_THIS_HOUR -> stringResource(R.string.discover_reason_hour)
    Reason.BECAUSE_YOU_PLAY -> item.seed?.let { stringResource(R.string.discover_reason_because, it) } ?: stringResource(R.string.discover_reason_for_you)
}

@Composable
private fun DiscoverCard(item: DiscoverItem, onClick: () -> Unit) {
    Column(Modifier.width(144.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp)) {
        AsyncImage(
            model = bigThumb(item.track.thumbnailUrl), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(136.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Text(item.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(item.track.artistLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(reasonText(item), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MixCard(mix: MixBuilder.Mix, name: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(156.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c.primaryContainer, c.tertiaryContainer)))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                mix.tracks.take(3).forEach { t ->
                    AsyncImage(model = bigThumb(t.thumbnailUrl), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceContainerHighest))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.onPrimaryContainer, maxLines = 2)
            Text("${mix.tracks.size}", style = MaterialTheme.typography.labelSmall, color = c.onPrimaryContainer)
        }
    }
}

/** The stats bubble: springs in, floats, pops with a small particle burst. Tap = pop, swipe = next fact. */
@Composable
private fun StatsBubble(fact: StatsFacts.Fact, onDismiss: () -> Unit, onNext: () -> Unit) {
    var popping by remember(fact) { mutableStateOf(false) }
    val scale = remember(fact) { Animatable(0.3f) }
    val alpha = remember(fact) { Animatable(0f) }
    val burst = remember(fact) { Animatable(0f) }
    LaunchedEffect(fact) {
        launch { alpha.animateTo(1f, tween(250)) }
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
        delay(7000)
        popping = true
    }
    LaunchedEffect(popping) {
        if (popping) {
            launch { burst.animateTo(1f, tween(600)) }
            launch { scale.animateTo(1.2f, tween(160)); scale.animateTo(0f, tween(240)) }
            alpha.animateTo(0f, tween(420))
            delay(200)
            onDismiss()
        }
    }
    val floatY by rememberInfiniteTransition(label = "discoverBubble").animateFloat(
        initialValue = 0f, targetValue = -8f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float",
    )
    val text = if (isHebrewUi()) fact.he else fact.en
    val particle = MaterialTheme.colorScheme.tertiary
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 24), properties = PopupProperties(focusable = false)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 20.dp)) {
            Canvas(Modifier.size(280.dp, 140.dp)) {
                val b = burst.value
                if (b > 0f) for (i in 0 until 16) {
                    val a = i * (2 * Math.PI / 16)
                    val r = b * size.width / 2f
                    drawCircle(
                        color = particle.copy(alpha = (1f - b).coerceIn(0f, 1f)),
                        radius = 7.dp.toPx() * (1f - 0.6f * b),
                        center = Offset(center.x + (cos(a) * r).toFloat(), center.y + (sin(a) * r * 0.5).toFloat()),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp, shadowElevation = 10.dp,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value
                        translationY = if (popping) 0f else floatY.dp.toPx()
                    }
                    .pointerInput(fact) {
                        var total = 0f
                        detectHorizontalDragGestures(onDragEnd = { if (abs(total) > 60f) onNext(); total = 0f }) { _, d -> total += d }
                    }
                    .clickable { popping = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Icon(painterResource(R.drawable.discover_ic_sparkle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverSheet(state: DiscoverUiState, vm: DiscoverViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.discover_settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            state.counters?.let { c ->
                Text(stringResource(R.string.discover_counters, c.plays, (c.completionRate * 100).roundToInt(), (c.likeRate * 100).roundToInt(), (c.skipRate * 100).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium)
            }
            state.tuner?.let { t ->
                Text(stringResource(R.string.discover_tuning, t.updates, (state.exploreRatio * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp))
                Text(t.weights.toArray().zip(com.metrolist.music.discover.engine.RankWeights.NAMES) { v, n -> "$n %.2f".format(Locale.ROOT, v) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.discover_bubble_toggle), Modifier.weight(1f))
                Switch(checked = state.bubbleEnabled, onCheckedChange = vm::setBubble)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = vm::resetLearning) { Text(stringResource(R.string.discover_reset)) }
                Button(onClick = vm::evaluate, enabled = !state.evaluating) { Text(stringResource(R.string.discover_evaluate)) }
            }
            if (state.evaluating) CircularProgressIndicator(Modifier.padding(12.dp))
            state.evaluation?.let { m ->
                if (m.isEmpty()) Text(stringResource(R.string.discover_eval_none), Modifier.padding(top = 8.dp))
                else EvaluationTable(m)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.discover_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EvaluationTable(m: List<Evaluator.Metric>) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(stringResource(R.string.discover_eval_header), style = MaterialTheme.typography.titleSmall)
        for (mode in Evaluator.Mode.values()) for (k in m.filter { it.mode == mode }.map { it.k }.distinct()) {
            val row = m.filter { it.mode == mode && it.k == k }
            Text(
                "${if (mode == Evaluator.Mode.NEW_ONLY) stringResource(R.string.discover_eval_new) else stringResource(R.string.discover_eval_all)} @$k: " +
                    row.joinToString("  ") { "${it.method} ${(it.recall * 100).roundToInt()}%" },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
