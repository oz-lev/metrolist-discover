/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2

import android.content.Context
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.engine.BlockRule
import com.metrolist.music.discover.engine.NeighboursMode
import com.metrolist.music.discover.engine.RuleSet
import com.metrolist.music.discover.engine.TastePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every "weird setting" (הגדרות מוזרות), stored in Discover's own kv table (never in Metrolist's DataStore).
 * [version] ticks on every change so screens and the service pick changes up immediately.
 */
class V2Settings private constructor(private val store: DiscoverStore) {
    private fun d(k: String, def: Double) = store.get(k)?.toDoubleOrNull() ?: def
    private fun i(k: String, def: Int) = store.get(k)?.toIntOrNull() ?: def
    private fun b(k: String, def: Boolean) = store.getBool(k, def)
    private fun save(k: String, v: Any?) { store.put(k, v?.toString()); _version.value = _version.value + 1 }

    /** 0 = safe (familiar only) .. 1 = crazy (mostly unknown). 0.3 = the engine's learned default. */
    var boldness: Double get() = d("v2.boldness", 0.3); set(v) = save("v2.boldness", v.coerceIn(0.0, 1.0))
    var maxMizrahiPct: Int get() = i("v2.maxMizrahi", 100); set(v) = save("v2.maxMizrahi", v.coerceIn(0, 100))
    /** multiplier on the engine's skip penalty weight, 0..2.5 */
    var skipPenalty: Double get() = d("v2.skipPenalty", 1.0); set(v) = save("v2.skipPenalty", v.coerceIn(0.0, 2.5))
    var noRepeatToday: Boolean get() = b("v2.noRepeat", false); set(v) = save("v2.noRepeat", v)
    var shortOnly: Boolean get() = b("v2.shortOnly", false); set(v) = save("v2.shortOnly", v)
    var noRemix: Boolean get() = b("v2.noRemix", false); set(v) = save("v2.noRemix", v)
    var noLive: Boolean get() = b("v2.noLive", false); set(v) = save("v2.noLive", v)
    /** "מצב צב": only calm songs (energy cap) */
    var turtle: Boolean get() = b("v2.turtle", false); set(v) = save("v2.turtle", v)
    /** every 5th song in Discover mixes is a wild card */
    var surpriseEvery5: Boolean get() = b("v2.surprise5", false); set(v) = save("v2.surprise5", v)
    /** the queue guard skips songs that break the rules everywhere in the app (not only in our mixes) */
    var guardEverywhere: Boolean get() = b("v2.guard", true); set(v) = save("v2.guard", v)
    var guardToasts: Boolean get() = b("v2.guardToasts", true); set(v) = save("v2.guardToasts", v)
    var blocks: List<BlockRule> get() = BlockRule.decodeAll(store.get("v2.blocks")); set(v) = save("v2.blocks", BlockRule.encodeAll(v))

    var neighboursOn: Boolean get() = b("v2.nb.on", false); set(v) = save("v2.nb.on", v)
    var neighboursStart: Int get() = i("v2.nb.start", 23 * 60); set(v) = save("v2.nb.start", v.coerceIn(0, 1439))
    var neighboursEnd: Int get() = i("v2.nb.end", 7 * 60); set(v) = save("v2.nb.end", v.coerceIn(0, 1439))
    var neighboursVolume: Int get() = i("v2.nb.vol", 40); set(v) = save("v2.nb.vol", v.coerceIn(5, 100))
    var neighboursBassDb: Int get() = i("v2.nb.bass", -9); set(v) = save("v2.nb.bass", v.coerceIn(-18, 0))

    var tastePreset: TastePreset? get() = store.get("v2.taste")?.let { n -> TastePreset.entries.firstOrNull { it.name == n } }
        set(v) = save("v2.taste", v?.name)
    var tasteUntil: Long get() = store.getLong("v2.tasteUntil", 0L); set(v) = save("v2.tasteUntil", v)

    var offlineMix: Boolean get() = b("v2.offline", true); set(v) = save("v2.offline", v)
    var offlineLastDay: Long get() = store.getLong("v2.offline.day", -1L); set(v) = save("v2.offline.day", v)
    var offlineIds: List<String> get() = store.get("v2.offline.ids").orEmpty().split(",").filter { it.isNotBlank() }; set(v) = save("v2.offline.ids", v.joinToString(","))
    var vibeBar: Boolean get() = b("v2.vibeBar", true); set(v) = save("v2.vibeBar", v)
    var animatedArt: Boolean get() = b("v2.animatedArt", true); set(v) = save("v2.animatedArt", v)
    var homeV2: Boolean get() = b("v2.home", true); set(v) = save("v2.home", v)
    var greetingName: String get() = store.get("v2.name").orEmpty(); set(v) = save("v2.name", v.take(20))

    var corrections: String? get() = store.get("v2.vibe.corr"); set(v) { store.put("v2.vibe.corr", v) }

    fun rules(): RuleSet = RuleSet(
        noRepeatToday = noRepeatToday,
        maxDurationSec = if (shortOnly) 180 else null,
        maxMizrahiPct = maxMizrahiPct,
        noRemix = noRemix,
        noLive = noLive,
        maxEnergy = if (turtle) 0.45 else null,
        blocks = blocks,
    )

    fun neighbours(): NeighboursMode = NeighboursMode(neighboursOn, neighboursStart, neighboursEnd, neighboursVolume / 100f, neighboursBassDb.toDouble())

    /** Maps the boldness slider onto the engine's explore ratio, around what the self-tuner learned. */
    fun effectiveExplore(learned: Double): Double = (learned + (boldness - 0.3) * 1.2).coerceIn(0.0, 0.9)

    fun activeTaste(nowMs: Long = System.currentTimeMillis()): TastePreset? = tastePreset?.takeIf { tasteUntil > nowMs }

    fun bump() { _version.value = _version.value + 1 }

    fun resetAll() {
        listOf("v2.boldness", "v2.maxMizrahi", "v2.skipPenalty", "v2.noRepeat", "v2.shortOnly", "v2.noRemix", "v2.noLive", "v2.turtle",
            "v2.surprise5", "v2.guard", "v2.guardToasts", "v2.blocks", "v2.nb.on", "v2.nb.start", "v2.nb.end", "v2.nb.vol", "v2.nb.bass",
            "v2.taste", "v2.tasteUntil", "v2.offline", "v2.vibeBar", "v2.animatedArt", "v2.home", "v2.vibe.corr").forEach { store.put(it, null) }
        _version.value = _version.value + 1
    }

    companion object {
        private val _version = MutableStateFlow(0)
        val version: StateFlow<Int> get() = _version
        @Volatile private var inst: V2Settings? = null
        fun get(context: Context): V2Settings = inst ?: synchronized(this) { inst ?: V2Settings(DiscoverStore.get(context)).also { inst = it } }
    }
}
