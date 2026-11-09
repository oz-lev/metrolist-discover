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
import com.metrolist.music.discover.space.ProfileSpace
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit

/**
 * Switchable profiles (פרופילים). Each profile has its own copy of every v2/v2.1 setting (theme included) and its own
 * learning state (self-tuner + vibe corrections) - see ProfileScope for the key mapping. 13.7.0.10: and its own history,
 * taste, Discover list, mixes and caches (ProfileIsolation): a song heard in one profile never reaches another. Everything lives in Discover's own
 * kv table; Metrolist's schema is untouched. 13.7.0.12: and each profile is its own app instance on disk (ProfileSpace). The guest profile never records: while it is active Metrolist's own
 * "pause listen history" is switched on (and restored afterwards) and Discover's own play log is skipped.
 */
object V21Profiles {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val DUP_SKIP = setOf("discover.cache", "discover.cacheTs", "mixes.cache", "mixes.cacheTs", "v21.qhist", "v21.opens", "v21.bubbleDay", "v2.offline.day", "v2.offline.ids")

    fun list(ctx: Context): List<DProfile> = Profiles.decode(DiscoverStore.get(ctx).rawGet("g.profiles"))
    fun active(ctx: Context): DProfile { DiscoverStore.get(ctx); return list(ctx).firstOrNull { it.id == ProfileScope.active } ?: Profiles.PRESETS[0] }

    private fun saveList(ctx: Context, l: List<DProfile>) { DiscoverStore.get(ctx).rawPut("g.profiles", Profiles.encode(l)); V2Settings.get(ctx).bump() }

    /**
     * 13.7.0.12: switching = a separate app instance. The registry is written and the app restarts into the new profile's
     * own storage (a short "עובר לפרופיל…" splash). Discover's bookkeeping (timeline mark, preset defaults, active id)
     * happens when the new process opens Discover's store (DiscoverStore.reconcileSpace). [ctx] should be an Activity.
     */
    fun switchTo(ctx: Context, id: String) {
        val p = list(ctx.applicationContext).firstOrNull { it.id == id } ?: return
        if (p.id == ProfileScope.active) return
        ProfileSpace.switchAndRestart(ctx, p.id, p.name, p.emoji)
    }

    /**
     * Runs once per process start, in the profile's own space: the guest keeps Metrolist's own history paused (in its own
     * settings file now). The main profile gets back the pause setting that the 13.7.0.10/11 guest switch may have left
     * on in what was then the one shared settings file.
     */
    fun onSpaceStart(ctx: Context) {
        io.launch {
            runCatching {
                val store = DiscoverStore.get(ctx)
                if (ProfileScope.guest) {
                    ctx.dataStore.edit { it[PauseListenHistoryKey] = true }
                } else if (ProfileScope.active == ProfileScope.DEFAULT) {
                    val prev = store.rawGet("g.guestPrevPause")
                    if (prev != null) {
                        ctx.dataStore.edit { it[PauseListenHistoryKey] = prev.toBooleanStrictOrNull() ?: false }
                        store.rawPut("g.guestPrevPause", null)
                    }
                }
            }
        }
    }

    private fun secretKey() = if (ProfileScope.active == ProfileScope.DEFAULT) "g.secretPrevPause" else "g.secretPrevPause.${ProfileScope.active}"

    /** Secret mode uses the same switch (Metrolist's pause-history), so nothing is written anywhere while it is on. */
    fun applySecret(ctx: Context, on: Boolean) {
        io.launch {
            runCatching {
                val store = DiscoverStore.get(ctx)
                if (on) {
                    val was = ctx.dataStore.data.first()[PauseListenHistoryKey] ?: false
                    if (store.rawGet(secretKey()) == null) store.rawPut(secretKey(), was.toString())
                    ctx.dataStore.edit { it[PauseListenHistoryKey] = true }
                } else {
                    val was = store.rawGet(secretKey())?.toBooleanStrictOrNull() ?: false
                    store.rawPut(secretKey(), null)
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
            store.rawEntries("").filter { ProfileScope.scoped(it.key) }
        } else store.rawEntries("p.${src.id}.").mapKeys { it.key.removePrefix("p.${src.id}.") }
        // settings and learned weights are copied; the listening history and the caches built from it are not
        // (13.7.0.10: a copy starts with its own, empty history, so nothing of the source leaks into it later)
        entries.filterKeys { it !in DUP_SKIP }.forEach { (k, v) -> store.rawPut(ProfileScope.key(k, p.id), v) }
        return p
    }

    fun rename(ctx: Context, id: String, name: String, emoji: String?, color: Long?) = saveList(ctx, Profiles.rename(list(ctx), id, name, emoji, color))

    /**
     * 13.7.0.12: deleting a profile deletes its whole app instance: its own Metrolist database (history, library, likes,
     * playlists, downloads index), settings and YouTube login, caches, downloads, WebView data, plus its Discover rows.
     * The active profile cannot be deleted (switch away first); the main profile never.
     */
    fun delete(ctx: Context, id: String): Boolean {
        if (!Profiles.canDelete(id) || ProfileScope.active == id) return false
        saveList(ctx, Profiles.delete(list(ctx), id))
        val store = DiscoverStore.get(ctx)
        store.rawDeletePrefix("p.$id.")
        store.rawPut("g.init.$id", null)
        store.rawPut("g.seed.$id", null)
        store.rawPut("g.secretPrevPause.$id", null)
        // its Discover listens go with it; its stretches of the old shared timeline become a tombstone no profile reads
        runCatching { store.deletePlaysOf(id); store.reassignTimeline(id, "x.$id") }
        V2Engine.forget(id)
        if (ProfileSpace.pending(ctx) == id) ProfileSpace.setPending(ctx, null)
        return ProfileSpace.deleteFiles(ctx, id)
    }

    /**
     * Called every 30 s by the service: the auto-switch schedule (edge triggered). 13.7.0.12: a switch restarts the app,
     * so a scheduled one never kills a running app; it is queued and happens at the next cold start.
     */
    fun autoTick(ctx: Context) {
        val s = V2Settings.get(ctx)
        if (!s.autoSwitch) return
        val store = DiscoverStore.get(ctx)
        val now = V2Engine.now()
        val pick = AutoSwitch.pick(s.autoRules, list(ctx).map { it.id }.toSet(), DayClock.dayOfWeek(now), DayClock.minuteOfDay(now))
        val last = store.rawGet("g.autoLast")?.ifBlank { null }
        val target = AutoSwitch.decide(last, pick, ProfileScope.active)
        if (pick != last) store.rawPut("g.autoLast", pick ?: "")
        if (target != null) ProfileSpace.setPending(ctx, target)
    }
}
