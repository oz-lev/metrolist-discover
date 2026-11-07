/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.DiscoverCounters
import com.metrolist.music.discover.DiscoverHooks
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.DiscoverItem
import com.metrolist.music.discover.DiscoverRepository
import com.metrolist.music.discover.engine.Evaluator
import com.metrolist.music.discover.engine.MixBuilder
import com.metrolist.music.discover.engine.StatsFacts
import com.metrolist.music.discover.engine.TunerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DiscoverUiState(
    val items: List<DiscoverItem> = emptyList(),
    val mixes: List<MixBuilder.Mix> = emptyList(),
    val loading: Boolean = false,
    val coldStart: Boolean = false,
    val fact: StatsFacts.Fact? = null,
    val facts: List<StatsFacts.Fact> = emptyList(),
    val counters: DiscoverCounters? = null,
    val tuner: TunerState? = null,
    val exploreRatio: Double = 0.3,
    val bubbleEnabled: Boolean = true,
    val evaluating: Boolean = false,
    val evaluation: List<Evaluator.Metric>? = null,
    val hiddenCount: Int = 0,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    private val repo = DiscoverRepository(context, database)
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() to repo.cacheAgeMs() }
            _state.value = _state.value.copy(items = cached.first, bubbleEnabled = withContext(Dispatchers.IO) { repo.bubbleEnabled() })
            if (cached.first.isEmpty() || cached.second > 3L * 3_600_000L) refresh()
            launch { runCatching { repo.mixes() }.onSuccess { _state.value = _state.value.copy(mixes = it) } }
            if (!bubbleShownThisProcess && withContext(Dispatchers.IO) { bubbleGate() }) {
                bubbleShownThisProcess = true
                runCatching { repo.nextFact() }.getOrNull()?.let { f ->
                    _state.value = _state.value.copy(fact = f)
                    launch { runCatching { repo.allFacts() }.onSuccess { _state.value = _state.value.copy(facts = it) } }
                }
            }
        }
    }

    /**
     * 13.7.0.10: the profile changed. Show that profile's own cached Discover list and mixes at once (each profile has
     * its own cache now), and refresh only if it has none or it is stale. The previous profile's list is never shown.
     */
    @Volatile private var shownFor: String = com.metrolist.music.discover.engine.ProfileScope.active
    fun onProfile(profileId: String) {
        if (profileId == shownFor) return
        shownFor = profileId
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() to repo.cacheAgeMs() }
            val mixes = withContext(Dispatchers.IO) { runCatching { repo.mixes() }.getOrDefault(emptyList()) }
            if (com.metrolist.music.discover.engine.ProfileScope.active != profileId) return@launch
            _state.value = _state.value.copy(items = cached.first, mixes = mixes, coldStart = false, hiddenCount = withContext(Dispatchers.IO) { repo.hiddenCount() })
            if (cached.first.isEmpty() || cached.second > 3L * 3_600_000L) { _state.value = _state.value.copy(loading = false); refresh() }
        }
    }

    /** v2.1 "stats bubble frequency": every open / every 3rd open / once a day / never. */
    private fun bubbleGate(): Boolean = runCatching {
        val store = DiscoverStore.get(context)
        val set = com.metrolist.music.discover.v2.V2Settings.get(context)
        val opens = (store.get("v21.opens")?.toIntOrNull() ?: 0) + 1
        store.put("v21.opens", opens.toString())
        val today = com.metrolist.music.discover.engine.DayClock.dayKey(DiscoverHooks.nowTs())
        val show = com.metrolist.music.discover.engine.BubbleGate.shouldShow(set.bubbleFreq, opens, store.get("v21.bubbleDay")?.toLongOrNull() ?: -1L, today)
        if (show) store.put("v21.bubbleDay", today.toString())
        show
    }.getOrDefault(true)

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val forProfile = com.metrolist.music.discover.engine.ProfileScope.active
            val r = runCatching { repo.refresh(online = true) }.recoverCatching { repo.refresh(online = false) }.getOrNull()
            if (com.metrolist.music.discover.engine.ProfileScope.active != forProfile) { _state.value = _state.value.copy(loading = false); if (_state.value.items.isEmpty()) refresh(); return@launch }
            _state.value = _state.value.copy(
                loading = false,
                items = r?.items?.ifEmpty { _state.value.items } ?: _state.value.items,
                coldStart = r?.coldStart ?: _state.value.coldStart,
            )
            runCatching { repo.mixes(force = true) }.onSuccess { _state.value = _state.value.copy(mixes = it) }
        }
    }

    /** 13.7.0.8 "not for me": gone from the row at once; stored and learned from in the background. */
    fun hide(item: DiscoverItem, wholeArtist: Boolean) {
        val s = _state.value
        _state.value = s.copy(items = s.items.filter { it.track.id != item.track.id && !(wholeArtist && it.track.mainArtist == item.track.mainArtist) })
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.hide(item.track, wholeArtist) } }
    }

    fun clearHidden() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.clearHidden() } }
            loadStats()
        }
    }

    fun dismissFact() { _state.value = _state.value.copy(fact = null) }

    fun nextFact() {
        val s = _state.value
        val cur = s.fact ?: return
        val others = s.facts.filter { it.type != cur.type }
        if (others.isEmpty()) return dismissFact()
        val i = others.indexOfFirst { it.type.ordinal > cur.type.ordinal }.let { if (it < 0) 0 else it }
        _state.value = s.copy(fact = others[i])
    }

    fun loadStats() {
        viewModelScope.launch {
            val (c, t) = withContext(Dispatchers.IO) { repo.counters() to repo.tunerState() }
            val hidden = withContext(Dispatchers.IO) { runCatching { repo.hiddenCount() }.getOrDefault(0) }
            _state.value = _state.value.copy(counters = c, tuner = t, hiddenCount = hidden, exploreRatio = withContext(Dispatchers.IO) { repo.exploreRatio() })
        }
    }

    fun setBubble(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repo.setBubbleEnabled(on) }
        _state.value = _state.value.copy(bubbleEnabled = on)
    }

    fun resetLearning() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.resetLearning() }
            loadStats()
        }
    }

    fun evaluate() {
        if (_state.value.evaluating) return
        _state.value = _state.value.copy(evaluating = true, evaluation = null)
        viewModelScope.launch {
            val m = runCatching { repo.evaluate() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(evaluating = false, evaluation = m)
        }
    }

    companion object {
        @Volatile var bubbleShownThisProcess = false
    }
}
