/**
 * Metrolist Discover 13.7.0.15 (fork addition) - GPL-3.0
 * Room playlist helpers for Quick-Add from Home, MixLab "Save as Playlist", and Playlist Merge / Power Tools.
 * Always operates on the active profile's isolated MusicDatabase and stores active playlist prefs per profile.
 */
package com.metrolist.music.discover.v21

import android.content.Context
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.discover.engine.PlaylistEntry
import com.metrolist.music.discover.engine.TrackMeta
import com.metrolist.music.discover.v2.V2Engine
import com.metrolist.music.discover.v2.V2Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

data class LocalPlaylistInfo(
    val id: String,
    val name: String,
    val songCount: Int,
    val duplicateCount: Int = 0,
    val isEditable: Boolean = true,
)

enum class AddOutcome { ADDED, ALREADY_EXISTS, FAILED }

data class AddResult(val outcome: AddOutcome, val playlistId: String, val playlistName: String)

object PlaylistActions {

    fun Song.toTrackMeta(): TrackMeta = TrackMeta(
        id = song.id,
        title = song.title,
        artistIds = artists.map { it.id },
        artistNames = artists.map { it.name },
        albumId = song.albumId,
        durationSec = song.duration,
        thumbnailUrl = song.thumbnailUrl,
    )

    /** Lists local/editable playlists in the active profile's song.db, newest updated first. */
    suspend fun loadPlaylists(db: MusicDatabase): List<LocalPlaylistInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<LocalPlaylistInfo>()
        val rdb = db.openHelper.readableDatabase
        val sql = """
            SELECT p.id, p.name, p.isEditable,
                   COUNT(m.songId) AS totalCnt,
                   COUNT(DISTINCT m.songId) AS distinctCnt
            FROM playlist p
            LEFT JOIN playlist_song_map m ON m.playlistId = p.id
            WHERE p.isEditable = 1
            GROUP BY p.id
            ORDER BY COALESCE(p.lastUpdateTime, p.createdAt) DESC, p.name ASC
        """.trimIndent()
        rdb.query(sql).use { c ->
            while (c.moveToNext()) {
                val total = c.getInt(3)
                val distinct = c.getInt(4)
                out += LocalPlaylistInfo(
                    id = c.getString(0),
                    name = c.getString(1) ?: "",
                    songCount = total,
                    duplicateCount = (total - distinct).coerceAtLeast(0),
                    isEditable = c.getInt(2) != 0,
                )
            }
        }
        out
    }

    /** Returns playlist IDs that already contain [songId] (for checkmark badges in the picker). */
    suspend fun playlistsContaining(db: MusicDatabase, songId: String): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        db.openHelper.readableDatabase.query(
            "SELECT DISTINCT playlistId FROM playlist_song_map WHERE songId = ?",
            arrayOf(songId),
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }

    /**
     * Resolves "הפלייליסט הנוכחי" (current playlist) for this profile:
     * 1) If the currently playing queue title matches a local playlist, use that playlist.
     * 2) Else use the profile's saved activePlaylistId (if it still exists).
     * 3) Else null (UI prompts the user to pick/create one).
     */
    suspend fun resolveCurrentPlaylist(
        ctx: Context,
        db: MusicDatabase,
        playingQueueTitle: String? = null,
    ): LocalPlaylistInfo? = withContext(Dispatchers.IO) {
        val all = loadPlaylists(db)
        if (all.isEmpty()) return@withContext null
        val q = playingQueueTitle?.trim().orEmpty()
        if (q.isNotEmpty()) {
            all.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { return@withContext it }
        }
        val savedId = V2Settings.get(ctx).activePlaylistId
        if (!savedId.isNullOrBlank()) {
            all.firstOrNull { it.id == savedId }?.let { return@withContext it }
        }
        all.firstOrNull()
    }

    fun setCurrentPlaylist(ctx: Context, id: String, name: String) {
        val s = V2Settings.get(ctx)
        s.activePlaylistId = id
        s.activePlaylistName = name
    }

    /** Ensures a TrackMeta exists in Room's song (+ artist + song_artist_map) table before playlist_song_map insertion. */
    private fun ensureTrackInDb(db: MusicDatabase, track: TrackMeta) {
        if (db.getSongByIdBlocking(track.id) != null) return
        val now = LocalDateTime.now()
        runCatching {
            db.insert(
                SongEntity(
                    id = track.id,
                    title = track.title.ifBlank { track.id },
                    duration = track.durationSec,
                    thumbnailUrl = track.thumbnailUrl,
                    albumId = null,
                    inLibrary = now,
                ),
            )
        }
        track.artistNames.forEachIndexed { idx, aName ->
            if (aName.isBlank()) return@forEachIndexed
            val aId = track.artistIds.getOrNull(idx)?.takeIf { it.isNotBlank() && it != "?" }
                ?: ("LA" + (aName.lowercase().hashCode().toUInt().toString(36)))
            runCatching { db.insert(ArtistEntity(id = aId, name = aName)) }
            runCatching { db.insert(SongArtistMap(songId = track.id, artistId = aId, position = idx)) }
        }
    }

    /** Adds a single track to [playlistId] without duplicating it. Updates active playlist per profile. */
    suspend fun addTrackToPlaylist(
        ctx: Context,
        db: MusicDatabase,
        track: TrackMeta,
        playlistId: String,
        playlistName: String,
    ): AddResult = withContext(Dispatchers.IO) {
        try {
            val dups = db.playlistDuplicates(playlistId, listOf(track.id))
            if (track.id in dups) {
                setCurrentPlaylist(ctx, playlistId, playlistName)
                return@withContext AddResult(AddOutcome.ALREADY_EXISTS, playlistId, playlistName)
            }
            db.withTransaction {
                ensureTrackInDb(db, track)
                val pos = db.nextPlaylistSongPosition(playlistId)
                db.insert(PlaylistSongMap(songId = track.id, playlistId = playlistId, position = pos))
                db.updatePlaylistLastUpdated(playlistId)
            }
            setCurrentPlaylist(ctx, playlistId, playlistName)
            AddResult(AddOutcome.ADDED, playlistId, playlistName)
        } catch (_: Throwable) {
            AddResult(AddOutcome.FAILED, playlistId, playlistName)
        }
    }

    /** Creates a brand-new local playlist with [tracks] (deduplicated) and selects it as active. */
    suspend fun createPlaylistWithTracks(
        ctx: Context,
        db: MusicDatabase,
        name: String,
        tracks: List<TrackMeta>,
    ): LocalPlaylistInfo? = withContext(Dispatchers.IO) {
        val clean = name.trim().take(80).ifBlank { "פלייליסט חדש" }
        try {
            val entity = PlaylistEntity(name = clean, isLocal = true, isEditable = true)
            val uniq = tracks.distinctBy { it.id }
            db.withTransaction {
                db.insert(entity)
                uniq.forEachIndexed { idx, t ->
                    ensureTrackInDb(db, t)
                    db.insert(PlaylistSongMap(songId = t.id, playlistId = entity.id, position = idx))
                }
                db.updatePlaylistLastUpdated(entity.id)
            }
            setCurrentPlaylist(ctx, entity.id, clean)
            LocalPlaylistInfo(entity.id, clean, uniq.size, 0, true)
        } catch (_: Throwable) {
            null
        }
    }

    /** Reads a playlist's entries enriched with play count and TasteModel affinity for PlaylistTools. */
    suspend fun loadEntries(ctx: Context, db: MusicDatabase, playlistId: String): List<PlaylistEntry> = withContext(Dispatchers.IO) {
        val snap = runCatching { V2Engine.snapshot(ctx, db) }.getOrNull()
        val playCounts = HashMap<String, Int>()
        snap?.history?.plays?.forEach { e -> if (e.playedMs >= 30_000L) playCounts[e.trackId] = (playCounts[e.trackId] ?: 0) + 1 }
        val rows = db.playlistSongsBlocking(playlistId)
        rows.mapIndexed { idx, ps ->
            val s = ps.song.song
            val a = ps.song.artists.firstOrNull()
            val pt = snap?.byId?.get(s.id)
            val aff = ((pt?.affinity ?: 0.5) + (pt?.context ?: 0.0)).coerceIn(0.0, 2.0)
            PlaylistEntry(
                songId = s.id,
                title = s.title,
                artistId = a?.id ?: "?",
                artistName = a?.name ?: "",
                addedOrder = idx,
                playCount = playCounts[s.id] ?: 0,
                affinity = aff,
            )
        }
    }

    /** Saves [entries] into a brand-new local playlist (for Merge / Filter / Set Ops / Clone). */
    suspend fun saveEntriesAsNewPlaylist(
        ctx: Context,
        db: MusicDatabase,
        name: String,
        entries: List<PlaylistEntry>,
    ): LocalPlaylistInfo? = withContext(Dispatchers.IO) {
        val clean = name.trim().take(80).ifBlank { "פלייליסט ממוזג" }
        try {
            val entity = PlaylistEntity(name = clean, isLocal = true, isEditable = true)
            db.withTransaction {
                db.insert(entity)
                entries.forEachIndexed { idx, e ->
                    if (db.getSongByIdBlocking(e.songId) == null) ensureTrackInDb(db, e.asTrackMeta())
                    db.insert(PlaylistSongMap(songId = e.songId, playlistId = entity.id, position = idx))
                }
                db.updatePlaylistLastUpdated(entity.id)
            }
            setCurrentPlaylist(ctx, entity.id, clean)
            LocalPlaylistInfo(entity.id, clean, entries.size, 0, true)
        } catch (_: Throwable) {
            null
        }
    }

    /** Replaces or appends [entries] into an existing local playlist inside one Room transaction. */
    suspend fun writeEntriesToExisting(
        ctx: Context,
        db: MusicDatabase,
        playlistId: String,
        playlistName: String,
        entries: List<PlaylistEntry>,
        replaceAll: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        var written = 0
        db.withTransaction {
            if (replaceAll) {
                db.clearPlaylist(playlistId)
                entries.forEachIndexed { idx, e ->
                    if (db.getSongByIdBlocking(e.songId) != null) {
                        db.insert(PlaylistSongMap(songId = e.songId, playlistId = playlistId, position = idx))
                        written++
                    }
                }
            } else {
                val existing = db.playlistSongsBlocking(playlistId).map { it.map.songId }.toHashSet()
                var pos = db.nextPlaylistSongPosition(playlistId)
                for (e in entries) {
                    if (e.songId in existing) continue
                    if (db.getSongByIdBlocking(e.songId) != null) {
                        db.insert(PlaylistSongMap(songId = e.songId, playlistId = playlistId, position = pos++))
                        existing += e.songId
                        written++
                    }
                }
            }
            db.updatePlaylistLastUpdated(playlistId)
        }
        setCurrentPlaylist(ctx, playlistId, playlistName)
        written
    }
}
