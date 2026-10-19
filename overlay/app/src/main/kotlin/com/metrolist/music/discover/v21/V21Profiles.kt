/**
 * Metrolist Discover v2.1 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v21

import android.content.Context
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.discover.DiscoverStore
import com.metrolist.music.discover.engine.AutoSwitch
import com.metrolist.music.discover.engine.DProfile
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.ProfileScope
import com.metrolist.music.discover.engine.Profiles
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit

/**
 * Switchable profiles (פרופילים). Each profile has its own copy of every v2/v2.1 setting (theme included) and its own
 * learning state (self-tuner + vibe corrections) - see ProfileScope for the key mapping. Everything lives in Discover's own
 * kv table; Metrolist's schema is untouched. The guest profile never records: while it is active Metrolist's own
 * "pause listen history" is switched on (and restored afterwards) and Discover's own play log is skipped.
 */
object V21Profiles {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun list(ctx: Context): List<DProfile> = Profiles.decode(DiscoverStore.get(ctx).rawGet("g.profiles"))
    fun active(ctx: Context): DProfile { DiscoverStore.get(ctx); return list(ctx).firstOrNull { it.id == ProfileScope.active } ?: Profiles.PRESETS[0] }

    private fun saveList(ctx: Context, l: List<DProfile>) { DiscoverStore.get(ctx).rawPut("g.profiles", Profiles.encode(l)); V2Settings.get(ctx).bump() }

    /** Switch now. Preset profiles get their starting settings the first time they are used. */
    fun switchTo(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val store = DiscoverStore.get(app)
        val l = list(app)
        val p = l.firstOrNull { it.id == id } ?: return
        if (p.id == ProfileScope.active) return
        val wasGuest = ProfileScope.guest
        if (store.rawGet("g.init.${p.id}") == null) {
            Profiles.presetDefaults(p.id).forEach { (k, v) -> if (store.rawGet(ProfileScope.key(k, p.id)) == null) store.rawPut(ProfileScope.key(k, p.id), v) }
            store.rawPut("g.init.${p.id}", "1")
        }
        ProfileScope.active = p.id
        ProfileScope.guest = p.guest
        store.rawPut("g.activeProfile", p.id)
        if (p.guest != wasGuest) guestHistory(app, entering = p.guest)
        V2Engine.resetLearning(app)
        V2Settings.get(app).bump()
    }

    /** Guest: Metrolist's own listen history is paused while the guest is active, then restored to what it was. */
    private fun guestHistory(ctx: Context, entering: Boolean) {
        io.launch {
            runCatching {
                val store = DiscoverStore.get(ctx)
                if (entering) {
                    val was = ctx.dataStore.data.first()[PauseListenHistoryKey] ?: false
                    store.rawPut("g.guestPrevPause", was.toString())
                    ctx.dataStore.edit { it[PauseListenHistoryKey] = true }
                } else {
                    val was = store.rawGet("g.guestPrevPause")?.toBooleanStrictOrNull() ?: false
                    ctx.dataStore.edit { it[PauseListenHistoryKey] = was }
                }
            }
        }
    }

    /** Secret mode uses the same switch (Metrolist's pause-history), so nothing is written anywhere while it is on. */
    fun applySecret(ctx: Context, on: Boolean) {
        io.launch {
            runCatching {
                val store = DiscoverStore.get(ctx)
                if (on) {
                    val was = ctx.dataStore.data.first()[PauseListenHistoryKey] ?: false
                    if (store.rawGet("g.secretPrevPause") == null) store.rawPut("g.secretPrevPause", was.toString())
                    ctx.dataStore.edit { it[PauseListenHistoryKey] = true }
                } else {
                    val was = store.rawGet("g.secretPrevPause")?.toBooleanStrictOrNull() ?: false
                    store.rawPut("g.secretPrevPause", null)
                    if (!ProfileScope.guest) ctx.dataStore.edit { it[PauseListenHistoryKey] = was }
                }
            }
        }
    }

    fun create(ctx: Context, name: String, emoji: String, color: Long): DProfile {
        val l = list(ctx)
        val p = DProfile(Profiles.newId(l.map { it.id }), name.take(24).ifBlank { "פרופיל חדש" }, emoji.ifBlank { "🎵" }, color)
        saveList(ctx, l + p)
        DiscoverStore.get(ctx).rawPut("g.init.${p.id}", "1")
        return p
    }

    /** Copies every setting and the learning state of [id] into a new profile. */
    fun duplicate(ctx: Context, id: String): DProfile? {
        val src = list(ctx).firstOrNull { it.id == id } ?: return null
        val store = DiscoverStore.get(ctx)
        val p = create(ctx, src.name + " 2", src.emoji, src.color).copy(guest = src.guest)
        saveList(ctx, list(ctx).map { if (it.id == p.id) p else it })
        val entries = if (src.id == ProfileScope.DEFAULT) {
            listOf("v2.", "v21.", "tuner.").flatMap { store.rawEntries(it).entries }.filter { ProfileScope.scoped(it.key) }.associate { it.key to it.value }
        } else store.rawEntries("p.${src.id}.").mapKeys { it.key.removePrefix("p.${src.id}.") }
        entries.forEach { (k, v) -> store.rawPut(ProfileScope.key(k, p.id), v) }
        return p
    }

    fun rename(ctx: Context, id: String, name: String, emoji: String?, color: Long?) = saveList(ctx, Profiles.rename(list(ctx), id, name, emoji, color))

    fun delete(ctx: Context, id: String) {
        if (!Profiles.canDelete(id)) return
        if (ProfileScope.active == id) switchTo(ctx, ProfileScope.DEFAULT)
        saveList(ctx, Profiles.delete(list(ctx), id))
        DiscoverStore.get(ctx).rawDeletePrefix("p.$id.")
        DiscoverStore.get(ctx).rawPut("g.init.$id", null)
    }

    /** Called every 30 s by the service: applies the auto-switch schedule (edge triggered). */
    fun autoTick(ctx: Context) {
        val s = V2Settings.get(ctx)
        if (!s.autoSwitch) return
        val store = DiscoverStore.get(ctx)
        val now = V2Engine.now()
        val pick = AutoSwitch.pick(s.autoRules, list(ctx).map { it.id }.toSet(), DayClock.dayOfWeek(now), DayClock.minuteOfDay(now))
        val last = store.rawGet("g.autoLast")?.ifBlank { null }
        val target = AutoSwitch.decide(last, pick, ProfileScope.active)
        if (pick != last) store.rawPut("g.autoLast", pick ?: "")
        if (target != null) switchTo(ctx, target)
    }
}
