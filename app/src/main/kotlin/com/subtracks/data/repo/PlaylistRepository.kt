package com.subtracks.data.repo

import com.subtracks.R
import com.subtracks.UiMessage
import com.subtracks.data.db.SubtracksDatabase
import com.subtracks.data.model.Playlist
import com.subtracks.data.model.QueueOrigin
import com.subtracks.data.source.MusicSource
import com.subtracks.data.source.PlaylistContents
import com.subtracks.data.sync.SyncService
import com.subtracks.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Edits picked in the playlist checklist, applied together. */
data class PlaylistChanges(
    val addTo: List<Playlist> = emptyList(),
    val removeFrom: List<Playlist> = emptyList(),
) {
    fun isEmpty(): Boolean = addTo.isEmpty() && removeFrom.isEmpty()
}

/** Whether [username] may change this playlist: servers only let the owner edit one. */
fun Playlist.editableBy(username: String?): Boolean = username != null && (owner == null || owner.equals(username, ignoreCase = true))

/**
 * Edits playlists on the server, then mirrors the edited playlist into the library straight away
 * rather than waiting for the next sync. Edits need the server, so they are refused offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRepository(
    private val db: SubtracksDatabase,
    private val sourceRepository: SourceRepository,
    private val queueRepository: QueueRepository,
    private val syncRunning: () -> Boolean,
    private val requestSync: () -> Unit,
    private val showMessage: (UiMessage) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Mutex()

    /** The active user's name, which decides which playlists they may edit. */
    val username: Flow<String?> = sourceRepository.activeConfig().map { it?.username }

    fun editablePlaylists(sourceId: Long): Flow<List<Playlist>> =
        sourceRepository.activeConfig().flatMapLatest { config ->
            if (config == null ||
                config.id != sourceId
            ) {
                flowOf(emptyList())
            } else {
                db.libraryDao().playlistsOwnedBy(sourceId, config.username)
            }
        }

    /** The ids of the playlists that hold [songId], for the playlist checklist. */
    fun playlistsHolding(
        sourceId: Long,
        songId: String,
    ): Flow<Set<String>> = db.libraryDao().playlistIdsHoldingSong(sourceId, songId).map { it.toSet() }

    /** The ids of the playlists the user may edit that hold [songId], which the checklist would show ticked. */
    fun editablePlaylistsHolding(
        sourceId: Long,
        songId: String,
    ): Flow<Set<String>> =
        combine(playlistsHolding(sourceId, songId), editablePlaylists(sourceId)) { holding, editable ->
            editable.mapNotNullTo(HashSet()) { playlist -> playlist.id.takeIf { it in holding } }
        }

    fun launchApply(
        sourceId: Long,
        songId: String,
        changes: PlaylistChanges,
    ) {
        scope.launch { apply(sourceId, songId, changes) }
    }

    fun launchRemove(
        playlist: Playlist,
        position: Long,
        songId: String,
    ) {
        scope.launch { remove(playlist, position, songId) }
    }

    /**
     * Applies the checklist's [changes] for [songId]: playlists checked get it unless they already hold it, and playlists unchecked lose every occurrence of it.
     * Reports the outcome in one message.
     */
    suspend fun apply(
        sourceId: Long,
        songId: String,
        changes: PlaylistChanges,
    ): Boolean {
        val outcomes = ArrayList<Outcome>()
        for (playlist in changes.addTo) {
            outcomes +=
                runEdit(sourceId, "add $songId to ${playlist.id}") { source ->
                    val current = source.playlist(playlist.id)
                    if (songId in current.entryIds) {
                        mirror(source, current)
                    } else {
                        source.updatePlaylist(playlist.id, songIdsToAdd = listOf(songId))
                        mirror(source, source.playlist(playlist.id))
                    }
                    Outcome(true, UiMessage(R.string.playlist_added, listOf(playlist.name)))
                }
        }
        for (playlist in changes.removeFrom) {
            outcomes +=
                runEdit(sourceId, "remove $songId from ${playlist.id}") { source ->
                    removeEntries(source, playlist, UiMessage(R.string.playlist_removed, listOf(playlist.name))) { _, id -> id == songId }
                }
        }
        val failed = outcomes.firstOrNull { !it.done }
        val message = failed?.message ?: outcomes.singleOrNull()?.message ?: UiMessage(R.string.playlists_updated)
        if (outcomes.isNotEmpty()) showMessage(message)
        return failed == null
    }

    /**
     * Removes the one entry the library holds at [position]. The server removes by index, so the
     * entry is first checked against the server's current playlist; if that moved on since the last
     * sync, nothing is removed and the library catches up instead.
     */
    suspend fun remove(
        playlist: Playlist,
        position: Long,
        songId: String,
    ): Boolean {
        val outcome =
            runEdit(playlist.sourceId, "remove $songId at $position from ${playlist.id}") { source ->
                val current = source.playlist(playlist.id)
                val index = current.entryIndexOf(position)
                if (index == null || current.entryIds[index] != songId) {
                    mirror(source, current)
                    return@runEdit Outcome(false, UiMessage(R.string.playlist_changed_on_server))
                }
                removeEntries(source, playlist, UiMessage(R.string.playlist_removed, listOf(playlist.name)), current) { i, _ ->
                    i == index
                }
            }
        showMessage(outcome.message)
        return outcome.done
    }

    /** Removes the entries of [playlist] matching [remove] by server index and entry id. */
    private suspend fun removeEntries(
        source: MusicSource,
        playlist: Playlist,
        done: UiMessage,
        current: PlaylistContents? = null,
        remove: (Int, String) -> Boolean,
    ): Outcome {
        val before = current ?: source.playlist(playlist.id)
        val indexes = before.entryIds.indices.filter { remove(it, before.entryIds[it]) }
        if (indexes.isEmpty()) {
            mirror(source, before)
            return Outcome(true, done)
        }
        // Some servers leave entries they cannot resolve out of getPlaylist but still count them
        // in updatePlaylist's indexes, so an index read from the listing could hit another entry.
        if (before.playlist.songCount > 0 && before.playlist.songCount != before.entryIds.size.toLong()) {
            return Outcome(false, UiMessage(R.string.playlist_entries_hidden))
        }
        source.updatePlaylist(playlist.id, entryIndexesToRemove = indexes)
        val after = source.playlist(playlist.id)
        mirror(source, after)
        if (after.entryIds != before.entryIds.filterIndexed { i, id -> !remove(i, id) }) {
            return Outcome(false, UiMessage(R.string.playlist_removed_unexpected))
        }
        return Outcome(true, done)
    }

    private class Outcome(
        val done: Boolean,
        val message: UiMessage,
    )

    /** Runs one edit against [sourceId], which must still be the active source, and returns how it went. */
    private suspend fun runEdit(
        sourceId: Long,
        description: String,
        block: suspend (MusicSource) -> Outcome,
    ): Outcome =
        lock.withLock {
            if (sourceRepository.offline.value) return@withLock Outcome(false, UiMessage(R.string.playlist_edit_offline))
            var reachedServer = false
            try {
                val source = sourceRepository.activeMusicSource()?.takeIf { it.id == sourceId }
                if (source == null) {
                    Log.w("playlist", "$description: source $sourceId is no longer active")
                    return@withLock Outcome(false, UiMessage(R.string.playlist_update_failed))
                }
                reachedServer = true
                block(source).also { outcome ->
                    if (outcome.done) Log.i("playlist", description) else Log.w("playlist", "$description: not done")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w("playlist", "$description failed", failure)
                // Part of the edit may have reached the server (an earlier chunk, or a request
                // whose response was lost), so let a sync bring the library back in line.
                if (reachedServer) requestSync()
                Outcome(false, UiMessage(R.string.playlist_update_failed))
            }
        }

    private suspend fun mirror(
        source: MusicSource,
        contents: PlaylistContents,
    ) {
        val point = queueRepository.captureResetPoint()
        val changed = SyncService(db, source).syncPlaylist(contents)
        if (syncRunning()) {
            // The running sync owns the pending origins and clears them when it finishes. It may
            // also have fetched this playlist before the edit and would write the old entries back,
            // so a follow-up sync settles it.
            queueRepository.resetChangedOrigins(changed, point)
            requestSync()
        } else {
            val pending = db.queueDao().pendingSyncOrigins(source.id).map { QueueOrigin(it.kind, it.sourceId, it.refId) }
            queueRepository.resetChangedOriginsAndClearPendingOrigins(pending.toSet() + changed, point, source.id)
        }
    }
}
