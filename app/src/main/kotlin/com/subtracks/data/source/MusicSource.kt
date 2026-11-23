package com.subtracks.data.source

import com.subtracks.data.model.Album
import com.subtracks.data.model.Artist
import com.subtracks.data.model.Playlist
import com.subtracks.data.model.PlaylistSong
import com.subtracks.data.model.Song
import kotlinx.coroutines.flow.Flow

const val DEFAULT_FETCH_CONCURRENCY = 4

interface MusicSource {
    val id: Long

    suspend fun ping()

    suspend fun setStar(
        type: StarType,
        id: String,
        starred: Boolean,
    )

    suspend fun scrobble(
        songId: String,
        submission: Boolean,
        time: Long? = null,
    )

    suspend fun artistBiography(artistId: String): String? = null

    fun artists(): Flow<List<Artist>>

    fun albums(): Flow<List<Album>>

    fun songs(): Flow<List<Song>>

    fun playlists(): Flow<List<Playlist>>

    fun playlistSongs(playlistIds: List<String>): Flow<List<PlaylistSong>>

    /** Fetches one playlist as the server holds it now. */
    suspend fun playlist(playlistId: String): PlaylistContents = throw UnsupportedOperationException()

    /**
     * Creates a playlist holding [songId] and returns its id, or null if the server did not report
     * one (servers before API 1.14 answer createPlaylist without the playlist).
     */
    suspend fun createPlaylist(
        name: String,
        songId: String,
    ): String? = throw UnsupportedOperationException()

    /**
     * Appends [songIdsToAdd] and removes the entries at [entryIndexesToRemove], which index
     * [PlaylistContents.entryIds] as the server holds it before this call.
     */
    suspend fun updatePlaylist(
        playlistId: String,
        songIdsToAdd: List<String> = emptyList(),
        entryIndexesToRemove: List<Int> = emptyList(),
    ): Unit = throw UnsupportedOperationException()
}

/**
 * A playlist and its entries in server order. An entry the server sent without an id is kept as an
 * empty string, so indexes into [entryIds] are the server's own entry indexes.
 */
data class PlaylistContents(
    val playlist: Playlist,
    val entryIds: List<String>,
) {
    /** The entries as the library stores them: positions count only entries with an id, as sync does. */
    fun songs(): List<PlaylistSong> =
        entryIds
            .filter { it.isNotEmpty() }
            .mapIndexed { position, songId -> PlaylistSong(playlist.sourceId, playlist.id, songId, position.toLong()) }

    /** The server entry index of the library position [position], or null past the end. */
    fun entryIndexOf(position: Long): Int? {
        var seen = -1L
        entryIds.forEachIndexed { index, id ->
            if (id.isNotEmpty() && ++seen == position) return index
        }
        return null
    }
}

enum class StarType {
    Song,
    Album,
    Artist,
}
