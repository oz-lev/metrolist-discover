/**
 * Metrolist Discover 13.7.0.10 (fork addition) - GPL-3.0
 * Pure Kotlin, no Android imports: runs on the phone and in plain JVM unit tests.
 */
package com.metrolist.music.discover.engine

/** The moment a profile became active. The list of marks is the profile timeline (kv g.timeline). */
data class SwitchMark(val ts: Long, val profileId: String)

/**
 * 13.7.0.10: hard per-profile isolation.
 *
 * Every signal now has an owner profile:
 *  - Discover's own play log (discover.db plays) carries the profile id it was recorded in (authoritative);
 *  - a row in Metrolist's own tables (event, song.likedDate / dateDownload / inLibrary) is owned by the profile
 *    that was active at its timestamp, read off the timeline; anything older than the first mark, and anything
 *    without a date (playlists), belongs to the main profile (that is the migration: no data is lost or moved).
 *
 * [isolate] then gives the engine a History in which a profile only sees its own plays and signals, and a song
 * (or an artist) that only ever happened in other profiles is not even a candidate.
 */
object ProfileTimeline {
    fun encode(marks: List<SwitchMark>): String = marks.joinToString(";") { "${it.ts}:${it.profileId}" }
    fun decode(raw: String?): List<SwitchMark> = raw.orEmpty().split(";").mapNotNull { s ->
        val i = s.indexOf(':'); if (i <= 0) return@mapNotNull null
        val ts = s.substring(0, i).toLongOrNull() ?: return@mapNotNull null
        val id = s.substring(i + 1).trim(); if (id.isEmpty()) null else SwitchMark(ts, id)
    }.sortedBy { it.ts }

    /** Adds a mark (a switch at [ts] to [profileId]); consecutive marks of the same profile collapse; bounded. */
    fun append(marks: List<SwitchMark>, ts: Long, profileId: String, max: Int = 4000): List<SwitchMark> {
        val l = marks.sortedBy { it.ts }
        if (l.lastOrNull()?.profileId == profileId) return l
        return (l + SwitchMark(maxOf(ts, l.lastOrNull()?.ts ?: Long.MIN_VALUE), profileId)).takeLast(max)
    }

    /** Owner of an instant: the profile of the last mark at or before [ts]; [default] before the first mark or without a date. */
    fun owner(marks: List<SwitchMark>, ts: Long?, default: String = ProfileScope.DEFAULT): String {
        if (ts == null || marks.isEmpty() || ts < marks[0].ts) return default
        var lo = 0; var hi = marks.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (marks[mid].ts <= ts) lo = mid else hi = mid - 1 }
        return marks[lo].profileId
    }

    /** A deleted profile's marks become the main profile's, so its old Metrolist rows are not orphaned into nobody. */
    fun reassign(marks: List<SwitchMark>, fromId: String, toId: String = ProfileScope.DEFAULT): List<SwitchMark> {
        val out = ArrayList<SwitchMark>()
        for (m in marks.map { if (it.profileId == fromId) it.copy(profileId = toId) else it }) if (out.lastOrNull()?.profileId != m.profileId) out += m
        return out
    }
}

data class OwnedPlay(val play: PlayEvent, val owner: String)
data class OwnedSignal(val signal: ExplicitSignal, val owner: String)

object ProfileIsolation {
    /**
     * Metrolist's event rows and Discover's own log describe the same listens. Which rows make up the History is exactly
     * as before 13.7.0.10 (every event row, plus the Discover rows with no event of the same song in the same or a
     * neighbouring quarter hour: the short, skipped sessions Metrolist does not keep), so the main profile sees the same
     * history it always did. New is the owner: a Discover row carries its own; an event row takes the owner of the
     * nearest Discover row of the same song within [matchMs], else the timeline's.
     */
    fun mergePlays(events: List<PlayEvent>, own: List<OwnedPlay>, marks: List<SwitchMark>, matchMs: Long = 15 * 60_000L): List<OwnedPlay> {
        val byId = HashMap<String, MutableList<OwnedPlay>>()
        for (p in own) byId.getOrPut(p.play.trackId) { ArrayList() } += p
        val eventKeys = HashSet<String>()
        val out = ArrayList<OwnedPlay>(events.size + own.size)
        for (e in events) {
            eventKeys += e.trackId + "@" + e.timestampMs / 900_000L
            var owner: String? = null; var bestD = Long.MAX_VALUE
            for (o in byId[e.trackId].orEmpty()) {
                val d = kotlin.math.abs(o.play.timestampMs - e.timestampMs)
                if (d <= matchMs && d < bestD) { owner = o.owner; bestD = d }
            }
            out += OwnedPlay(e, owner ?: ProfileTimeline.owner(marks, e.timestampMs))
        }
        for (p in own) {
            val k = p.play.timestampMs / 900_000L
            val id = p.play.trackId
            val dup = eventKeys.contains("$id@$k") || eventKeys.contains("$id@${k - 1}") || eventKeys.contains("$id@${k + 1}")
            if (!dup) out += p
        }
        return out
    }

    fun ownSignals(signals: List<ExplicitSignal>, marks: List<SwitchMark>): List<OwnedSignal> =
        signals.map { OwnedSignal(it, ProfileTimeline.owner(marks, it.timestampMs)) }

    /**
     * The History [profile] is allowed to see. [strictArtists]: an artist heard only in other profiles is hidden too
     * (none of its songs become candidates here), so "my" favourite artist does not turn up in the gym profile.
     */
    fun isolate(tracks: Map<String, TrackMeta>, plays: List<OwnedPlay>, signals: List<OwnedSignal>, related: Map<String, List<String>>,
                profile: String, strictArtists: Boolean = true): History {
        val owners = HashMap<String, MutableSet<String>>()
        for (p in plays) owners.getOrPut(p.play.trackId) { HashSet(2) } += p.owner
        for (s in signals) owners.getOrPut(s.signal.trackId) { HashSet(2) } += s.owner
        val foreign = HashSet<String>()
        for ((id, o) in owners) if (profile !in o) foreign += id
        if (strictArtists) {
            val mine = HashSet<String>(); val others = HashSet<String>()
            for ((id, o) in owners) {
                val a = tracks[id]?.mainArtist ?: continue
                if (profile in o) mine += a else others += a
            }
            val foreignArtists = others - mine
            if (foreignArtists.isNotEmpty()) for (t in tracks.values) if (t.mainArtist in foreignArtists && owners[t.id]?.contains(profile) != true) foreign += t.id
        }
        val keptTracks = if (foreign.isEmpty()) tracks else tracks.filterKeys { it !in foreign }
        val keptRelated = HashMap<String, List<String>>(related.size)
        for ((k, v) in related) {
            if (k in foreign) continue
            val vv = if (foreign.isEmpty()) v else v.filter { it !in foreign }
            if (vv.isNotEmpty()) keptRelated[k] = vv
        }
        return History(
            keptTracks,
            plays.filter { it.owner == profile }.map { it.play },
            signals.filter { it.owner == profile }.map { it.signal },
            keptRelated,
        )
    }

    /** Ids a profile must never be offered (songs that only exist in other profiles), for filtering online/cached lists. */
    fun foreignTracks(plays: List<OwnedPlay>, signals: List<OwnedSignal>, profile: String): Set<String> {
        val owners = HashMap<String, MutableSet<String>>()
        for (p in plays) owners.getOrPut(p.play.trackId) { HashSet(2) } += p.owner
        for (s in signals) owners.getOrPut(s.signal.trackId) { HashSet(2) } += s.owner
        return owners.filterValues { profile !in it }.keys
    }
}
