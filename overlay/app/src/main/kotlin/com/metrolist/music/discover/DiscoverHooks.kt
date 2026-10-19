/**
 * Metrolist Discover (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover

import android.content.Context
import androidx.media3.common.MediaItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.engine.DiscoverConfig
import com.metrolist.music.discover.engine.History
import com.metrolist.music.discover.engine.Outcome
import com.metrolist.music.discover.engine.PlayEvent
import com.metrolist.music.discover.engine.ProfileBuilder
import com.metrolist.music.discover.engine.SelfTuner
import com.metrolist.music.discover.engine.TunerState
import com.metrolist.music.extensions.metadata
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors

/**
 * The ONLY entry point upstream code calls (one line in MusicService.onPlaybackStatsReady).
 * Everything runs off the player thread; any failure here is swallowed so playback is never affected.
 */
object DiscoverHooks {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "discover-io").apply { isDaemon = true } }

    /** Same encoding as Metrolist's Event.timestamp: local wall-clock time stored as UTC millis. */
    fun nowTs(): Long = LocalDateTime.now().atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

    @JvmStatic
    fun onPlaybackStats(context: Context, database: MusicDatabase, mediaItem: MediaItem, playedMs: Long, historyPaused: Boolean) {
        if (historyPaused || playedMs <= 0) return
        val songId = mediaItem.mediaId
        val durSec = runCatching { mediaItem.metadata?.duration ?: -1 }.getOrDefault(-1)
        val ts = nowTs()
        val app = context.applicationContext
        io.execute {
            runCatching {
                val store = DiscoverStore.get(app)
                val durationMs = if (durSec > 0) durSec * 1000L else songDurationMs(database, songId)
                val th = DiscoverRepository.thresholds(store)
                val e = PlayEvent(songId, ts, playedMs, durationMs)
                val outcome = ProfileBuilder.outcome(e, History(emptyMap(), emptyList()), th.first, th.second, th.third)
                val rec = store.rec(songId, ts)
                store.addPlay(songId, ts, playedMs, durationMs, rec != null, outcome.name)
                runCatching { com.metrolist.music.discover.v2.V2Engine.onPlayed(app, songId, playedMs, durationMs) } // v2: vibe self-correction
                if (rec != null) {
                    val tuner = SelfTuner(DiscoverConfig().weights, DiscoverConfig().exploreRatio)
                    val state = TunerState.decode(store.get(DiscoverStore.K_TUNER)) ?: tuner.reset()
                    store.put(DiscoverStore.K_TUNER, tuner.update(state, rec.second, rec.first, tuner.reward(outcome, false)).encode())
                }
            }
        }
    }

    private fun songDurationMs(db: MusicDatabase, songId: String): Long = runCatching {
        db.openHelper.readableDatabase.query("SELECT duration FROM song WHERE id = ?", arrayOf<Any?>(songId)).use { c ->
            if (c.moveToFirst()) c.getInt(0).toLong() * 1000L else -1L
        }
    }.getOrDefault(-1L)

    @Suppress("unused")
    private fun outcomeName(o: Outcome) = o.name
}
