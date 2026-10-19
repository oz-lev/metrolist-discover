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
import com.metrolist.music.discover.engine.AutoRule
import com.metrolist.music.discover.engine.BubbleFreq
import com.metrolist.music.discover.engine.DailyPlan
import com.metrolist.music.discover.engine.LangMode
import com.metrolist.music.discover.engine.MoodPreset
import com.metrolist.music.discover.engine.ShapeOptions
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

    // ------------------------------------------------------------------ v2.1 (all per profile unless noted)
    private fun f(k: String, def: Float) = store.get(k)?.toFloatOrNull() ?: def
    // design studio
    var themeId: String get() = store.get("v21.theme") ?: "NEON"; set(v) = save("v21.theme", v)
    /** 0 = the theme's own accent */
    var accent: Int get() = i("v21.accent", 0); set(v) = save("v21.accent", v)
    var roundness: Float get() = f("v21.round", 1f); set(v) = save("v21.round", v.coerceIn(0.3f, 2f))
    var font: String get() = store.get("v21.font") ?: "HEEBO"; set(v) = save("v21.font", v)
    var fontScale: Float get() = f("v21.fontScale", 1f); set(v) = save("v21.fontScale", v.coerceIn(0.8f, 1.3f))
    var randomDailyTheme: Boolean get() = b("v21.randomTheme", false); set(v) = save("v21.randomTheme", v)
    var navGlow: Boolean get() = b("v21.navGlow", true); set(v) = save("v21.navGlow", v)
    var miniGlow: Boolean get() = b("v21.miniGlow", true); set(v) = save("v21.miniGlow", v)
    var haptics: Boolean get() = b("v21.haptics", true); set(v) = save("v21.haptics", v)
    var motionExpressive: Boolean get() = b("v21.motion", true); set(v) = save("v21.motion", v)
    var gradientHeaders: Boolean get() = b("v21.gradHeaders", true); set(v) = save("v21.gradHeaders", v)
    // algorithm
    var seedLock: Boolean get() = b("v21.seedLock", false); set(v) = save("v21.seedLock", v)
    var seed: Long get() = store.getLong("v21.seed", 7L); set(v) = save("v21.seed", v.coerceIn(0L, 9999L))
    var weekdayMoods: Map<Int, MoodPreset> get() = DailyPlan.decodeWeekday(store.get("v21.weekday")); set(v) = save("v21.weekday", DailyPlan.encodeWeekday(v))
    var defaultMood: MoodPreset? get() = store.get("v21.defaultMood")?.let { n -> MoodPreset.entries.firstOrNull { it.name == n } }; set(v) = save("v21.defaultMood", v?.name)
    var daylist: Boolean get() = b("v21.daylist", true); set(v) = save("v21.daylist", v)
    var morningRamp: Boolean get() = b("v21.ramp", false); set(v) = save("v21.ramp", v)
    var favoriteEvery: Int get() = i("v21.favEvery", 0); set(v) = save("v21.favEvery", v.coerceIn(0, 10))
    var neverHeard: Boolean get() = b("v21.neverHeard", false); set(v) = save("v21.neverHeard", v)
    // queue rules
    /** 0 = off */
    var maxLengthMin: Int get() = i("v21.maxLen", 0); set(v) = save("v21.maxLen", v.coerceIn(0, 15))
    var artistGap: Int get() = i("v21.artistGap", 0); set(v) = save("v21.artistGap", v.coerceIn(0, 10))
    var lang: LangMode get() = store.get("v21.lang")?.let { n -> LangMode.entries.firstOrNull { it.name == n } } ?: LangMode.ALL; set(v) = save("v21.lang", v.name)
    var explicitFilter: Boolean get() = b("v21.explicit", false); set(v) = save("v21.explicit", v)
    // sound
    var autoEq: Boolean get() = b("v21.autoEq", false); set(v) = save("v21.autoEq", v)
    var autoEqIntensity: Float get() = f("v21.autoEqK", 1f); set(v) = save("v21.autoEqK", v.coerceIn(0.2f, 1.5f))
    var flow: Boolean get() = b("v21.flow", false); set(v) = save("v21.flow", v)
    var sleepFadeMin: Int get() = i("v21.sleepFade", 5); set(v) = save("v21.sleepFade", v.coerceIn(1, 20))
    var smartShuffleEvery: Int get() = i("v21.smartEvery", 3); set(v) = save("v21.smartEvery", v.coerceIn(1, 10))
    // privacy & fun
    var secret: Boolean get() = b("v21.secret", false); set(v) = save("v21.secret", v)
    var autoLike: Boolean get() = b("v21.autoLike", false); set(v) = save("v21.autoLike", v)
    var autoLikeAfter: Int get() = i("v21.autoLikeN", 3); set(v) = save("v21.autoLikeN", v.coerceIn(2, 10))
    var djIntros: Boolean get() = b("v21.djIntros", false); set(v) = save("v21.djIntros", v)
    var bubbleFreq: BubbleFreq get() = store.get("v21.bubbleFreq")?.let { n -> BubbleFreq.entries.firstOrNull { it.name == n } } ?: BubbleFreq.EVERY_OPEN; set(v) = save("v21.bubbleFreq", v.name)
    var challengeOnHome: Boolean get() = b("v21.challenge", true); set(v) = save("v21.challenge", v)
    /** Duolingo-style daily listening goal in minutes (0 = off). */
    var dailyGoalMin: Int get() = i("v21.goal", 30); set(v) = save("v21.goal", v.coerceIn(0, 240))
    var jumpBack: Boolean get() = b("v21.jumpBack", true); set(v) = save("v21.jumpBack", v)
    var blendName: String get() = store.get("v21.blendName").orEmpty(); set(v) = save("v21.blendName", v.take(24))
    // global (all profiles): the auto-switch schedule
    var autoSwitch: Boolean get() = store.rawGet("g.autoSwitch")?.toBooleanStrictOrNull() ?: false; set(v) { store.rawPut("g.autoSwitch", v.toString()); bump() }
    var autoRules: List<AutoRule> get() = AutoRule.decodeAll(store.rawGet("g.autoRules")); set(v) { store.rawPut("g.autoRules", AutoRule.encodeAll(v)); bump() }

    fun shape(): ShapeOptions = ShapeOptions(
        lang = lang, maxDurationSec = maxLengthMin.takeIf { it > 0 }?.let { it * 60 }, neverHeard = neverHeard,
        favoriteEvery = favoriteEvery, artistGap = artistGap, morningRamp = morningRamp,
    )

    fun rules(): RuleSet = RuleSet(
        noRepeatToday = noRepeatToday,
        maxDurationSec = listOfNotNull(if (shortOnly) 180 else null, maxLengthMin.takeIf { it > 0 }?.let { it * 60 }).minOrNull(),
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
            "v2.taste", "v2.tasteUntil", "v2.offline", "v2.vibeBar", "v2.animatedArt", "v2.home", "v2.vibe.corr",
            "v21.accent", "v21.round", "v21.font", "v21.fontScale", "v21.randomTheme", "v21.navGlow", "v21.miniGlow", "v21.haptics", "v21.motion", "v21.gradHeaders",
            "v21.seedLock", "v21.seed", "v21.weekday", "v21.defaultMood", "v21.daylist", "v21.ramp", "v21.favEvery", "v21.neverHeard",
            "v21.maxLen", "v21.artistGap", "v21.lang", "v21.explicit", "v21.autoEq", "v21.autoEqK", "v21.flow", "v21.sleepFade",
            "v21.smartEvery", "v21.secret", "v21.autoLike", "v21.autoLikeN", "v21.djIntros", "v21.bubbleFreq", "v21.challenge", "v21.goal", "v21.jumpBack").forEach { store.put(it, null) }
        _version.value = _version.value + 1
    }

    companion object {
        private val _version = MutableStateFlow(0)
        val version: StateFlow<Int> get() = _version
        @Volatile private var inst: V2Settings? = null
        fun get(context: Context): V2Settings = inst ?: synchronized(this) { inst ?: V2Settings(DiscoverStore.get(context)).also { inst = it } }
    }
}
