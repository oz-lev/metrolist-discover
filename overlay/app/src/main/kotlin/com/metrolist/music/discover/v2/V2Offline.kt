/**
 * Metrolist Discover v2 (fork addition) - GPL-3.0
 */
package com.metrolist.music.discover.v2

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.discover.engine.DayClock
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.DownloadUtil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface V2EntryPoint {
    fun database(): MusicDatabase
    fun downloadUtil(): DownloadUtil
}

/**
 * Overnight offline mix: a platform JobScheduler job (Metrolist has no WorkManager; JobScheduler is what
 * WorkManager runs on) that only runs on an unmetered network while charging, at night, once a day.
 * It builds the daily mix and hands the songs to Metrolist's own download manager (same cache, same
 * "Downloaded" library), and removes the previous night's auto-downloads that you never liked or kept.
 */
class V2OfflineJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        val set = V2Settings.get(ctx)
        val now = V2Engine.now()
        val hour = DayClock.dateTime(now).hour
        if (!set.offlineMix || set.offlineLastDay == DayClock.dayKey(now) || hour in 8..21) return false
        job = scope.launch {
            val ok = runCatching { V2Offline.run(ctx) }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}

object V2Offline {
    private const val JOB_ID = 0x0D15C0
    const val SIZE = 25

    fun schedule(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!V2Settings.get(ctx).offlineMix) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, V2OfflineJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true)
                .setPeriodic(2 * 3_600_000L, 3_600_000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }
    }

    private fun TrackMeta.songItem() = SongItem(
        id = id, title = title,
        artists = artistNames.mapIndexed { i, n -> Artist(n, artistIds.getOrNull(i)?.takeUnless { it.startsWith("name:") }) },
        duration = durationSec.takeIf { it > 0 }, thumbnail = thumbnailUrl ?: "",
    )

    /** Builds tonight's daily mix and queues it in Metrolist's download manager. Waits (max ~8 min) while it downloads. */
    suspend fun run(ctx: Context) {
        val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, V2EntryPoint::class.java)
        val db = ep.database()
        // DownloadManager lives on the main looper: create / touch it there
        val dl = withContext(Dispatchers.Main) { ep.downloadUtil() }
        val set = V2Settings.get(ctx)
        val mix = V2Engine.daily(ctx, db).tracks.take(SIZE)
        if (mix.isEmpty()) return
        // tidy last night's batch: remove what was never liked / added to the library
        val keep = mix.map { it.id }.toSet()
        for (old in set.offlineIds) {
            if (old in keep) continue
            val song = runCatching { db.getSongByIdBlocking(old) }.getOrNull()
            val kept = song != null && (song.song.liked || song.song.inLibrary != null)
            if (!kept) withContext(Dispatchers.Main) { runCatching { dl.downloadManager.removeDownload(old) } }
        }
        for (t in mix) {
            try {
                val md = t.songItem().toMediaMetadata()
                if (db.getSongByIdBlocking(t.id) == null) db.insert(md)
                val state = dl.downloadManager.downloadIndex.getDownload(t.id)?.state
                if (state != Download.STATE_COMPLETED) {
                    val req = DownloadRequest.Builder(t.id, t.id.toUri()).setCustomCacheKey(t.id).setData(t.title.toByteArray()).build()
                    withContext(Dispatchers.Main) { dl.downloadManager.addDownload(req) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        withContext(Dispatchers.Main) { dl.downloadManager.resumeDownloads() }
        set.offlineIds = mix.map { it.id }
        set.offlineLastDay = DayClock.dayKey(V2Engine.now())
        // keep the process alive while the manager works (a job may run ~10 minutes)
        var waited = 0
        while (waited < 480 && withContext(Dispatchers.Main) { dl.downloadManager.currentDownloads.isNotEmpty() }) { delay(5_000L); waited += 5 }
    }
}
