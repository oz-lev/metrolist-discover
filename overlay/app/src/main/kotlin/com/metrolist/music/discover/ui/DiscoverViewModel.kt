/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.DiscoverCounters
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
            if (!bubbleShownThisProcess) {
                bubbleShownThisProcess = true
                runCatching { repo.nextFact() }.getOrNull()?.let { f ->
                    _state.value = _state.value.copy(fact = f)
                    launch { runCatching { repo.allFacts() }.onSuccess { _state.value = _state.value.copy(facts = it) } }
                }
            }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val r = runCatching { repo.refresh(online = true) }.recoverCatching { repo.refresh(online = false) }.getOrNull()
            _state.value = _state.value.copy(
                loading = false,
                items = r?.items?.ifEmpty { _state.value.items } ?: _state.value.items,
                coldStart = r?.coldStart ?: _state.value.coldStart,
            )
            runCatching { repo.mixes(force = true) }.onSuccess { _state.value = _state.value.copy(mixes = it) }
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
            _state.value = _state.value.copy(counters = c, tuner = t, exploreRatio = withContext(Dispatchers.IO) { repo.exploreRatio() })
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
