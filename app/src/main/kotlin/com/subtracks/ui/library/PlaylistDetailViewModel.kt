package com.subtracks.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.subtracks.data.model.BulkDownloadAction
import com.subtracks.data.model.CoverArtRef
import com.subtracks.data.model.DownloadList
import com.subtracks.data.model.ListDownloadStatus
import com.subtracks.data.model.Playlist
import com.subtracks.data.model.PlaylistSongItem
import com.subtracks.data.model.Song
import com.subtracks.data.model.SongDownload
import com.subtracks.data.prefs.PlaylistSongOrder
import com.subtracks.data.prefs.UserPreferences
import com.subtracks.data.repo.DownloadRepository
import com.subtracks.data.repo.LibraryRepository
import com.subtracks.data.repo.PlaylistRepository
import com.subtracks.data.repo.SourceRepository
import com.subtracks.data.repo.editableBy
import com.subtracks.playback.PlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModel(
    private val libraryRepository: LibraryRepository,
    private val sourceRepository: SourceRepository,
    private val playbackController: PlaybackController,
    private val downloadRepository: DownloadRepository,
    private val playlistRepository: PlaylistRepository,
    private val userPreferences: UserPreferences,
    private val playlistId: String,
) : ViewModel() {
    private val sourceId = libraryRepository.activeSourceId.filterNotNull()

    val playlist: StateFlow<Playlist?> =
        sourceId
            .flatMapLatest { libraryRepository.playlist(it, playlistId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The saved song order, or null until it has loaded. */
    val songOrder: StateFlow<PlaylistSongOrder?> =
        userPreferences.playlistSongOrder().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val songs: Flow<PagingData<PlaylistSongItem>> =
        combine(
            sourceId,
            sourceRepository.offline,
            userPreferences.playlistSongOrder(),
        ) { id, offline, order -> Triple(id, offline, order) }
            .flatMapLatest { (id, offline, order) -> libraryRepository.playlistSongs(id, playlistId, order, offline) }
            .cachedIn(viewModelScope)

    fun setSongOrder(order: PlaylistSongOrder) {
        viewModelScope.launch { userPreferences.setPlaylistSongOrder(order) }
    }

    val downloads: StateFlow<Map<String, SongDownload>> =
        downloadRepository.states()

    val offline: StateFlow<Boolean> = sourceRepository.offline

    /** Whether songs can be removed now: the user owns the playlist and the server is reachable. */
    val canEdit: StateFlow<Boolean> =
        combine(playlist, playlistRepository.username, offline) { playlist, username, offline ->
            !offline && playlist?.editableBy(username) == true
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun removeSong(
        position: Long,
        song: Song,
    ) {
        val current = playlist.value ?: return
        playlistRepository.launchRemove(current, position, song.id)
    }

    val downloadStatus: StateFlow<ListDownloadStatus> =
        sourceId
            .flatMapLatest { downloadRepository.status(it, DownloadList.Playlist, playlistId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDownloadStatus())

    suspend fun downloadedBytes(): Long = downloadRepository.downloadedBytes(sourceId.first(), DownloadList.Playlist, playlistId)

    fun onDownloadAction(action: BulkDownloadAction) {
        viewModelScope.launch {
            downloadRepository.applyAction(sourceId.first(), DownloadList.Playlist, playlistId, action)
            if (action == BulkDownloadAction.Delete) playbackController.refreshMediaItems()
        }
    }

    fun download(song: Song) {
        viewModelScope.launch { downloadRepository.download(song.sourceId, song.id) }
    }

    fun cancelDownload(song: Song) {
        viewModelScope.launch { downloadRepository.remove(song.sourceId, song.id) }
    }

    fun deleteDownload(song: Song) {
        viewModelScope.launch {
            downloadRepository.remove(song.sourceId, song.id)
            playbackController.refreshMediaItems()
        }
    }

    fun coverArt(
        coverArt: String?,
        thumbnail: Boolean,
    ): CoverArtRef? = sourceRepository.coverArt(coverArt, thumbnail)

    fun play(position: Long) {
        viewModelScope.launch {
            val sourceId = sourceId.first()
            val order = userPreferences.playlistSongOrder().first()
            if (order.isCustom) {
                playbackController.playPlaylist(sourceId, playlistId, position)
            } else {
                val query = libraryRepository.sortedPlaylistQuery(playlistId, order)
                val ordinal = libraryRepository.sortedPlaylistOrdinal(sourceId, query, position) ?: return@launch
                playbackController.playSortedPlaylist(sourceId, query, ordinal)
            }
        }
    }

    fun playAll() {
        viewModelScope.launch {
            val order = userPreferences.playlistSongOrder().first()
            playbackController.playSortedPlaylistInOrder(sourceId.first(), libraryRepository.sortedPlaylistQuery(playlistId, order))
        }
    }

    fun shuffle() {
        viewModelScope.launch {
            playbackController.shufflePlaylist(sourceId.first(), playlistId)
        }
    }
}
