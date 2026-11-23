package com.subtracks.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.subtracks.R
import com.subtracks.data.model.AlbumSongItem
import com.subtracks.data.model.CoverArtRef
import com.subtracks.data.model.Song
import com.subtracks.data.model.SongDownload
import com.subtracks.ui.components.EmptyState
import com.subtracks.ui.components.FilteredEmptyState
import com.subtracks.ui.components.LoadingState
import com.subtracks.ui.components.MenuTarget
import com.subtracks.ui.components.ResetScrollOnChange
import com.subtracks.ui.components.ScrollToTopOnRequest
import com.subtracks.ui.components.rememberViewportFill

@Composable
fun LibrarySongsContent(
    items: LazyPagingItems<AlbumSongItem>,
    coverArt: (String?, Boolean) -> CoverArtRef?,
    bottomInset: Dp,
    onSongClick: (Song, Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (MenuTarget) -> Unit = {},
    playingSongId: String? = null,
    downloads: Map<String, SongDownload> = emptyMap(),
    filtered: Boolean = false,
    onClearFilters: () -> Unit = {},
    resetKey: Any? = null,
    topInset: Dp = 0.dp,
    onSync: () -> Unit = {},
    scrollToTopKey: Any? = null,
) {
    when {
        items.itemCount == 0 && items.loadState.refresh is LoadState.Loading -> {
            LoadingState(modifier)
        }

        items.itemCount == 0 -> {
            if (filtered) {
                FilteredEmptyState(onClearFilters, modifier)
            } else {
                EmptyState(
                    stringResource(R.string.songs_empty),
                    modifier,
                    actionLabel = stringResource(R.string.sync),
                    onAction = onSync,
                )
            }
        }

        else -> {
            val listState = rememberLazyListState()
            ResetScrollOnChange(resetKey, { items.loadState.refresh }) { listState.scrollToItem(0) }
            ScrollToTopOnRequest(scrollToTopKey, listState)
            val fill = rememberViewportFill(listState)
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topInset, bottom = bottomInset),
            ) {
                items(count = items.itemCount, key = items.itemKey { it.song.id }) { index ->
                    val item = items[index]
                    if (item != null) {
                        SongRow(
                            song = item.song,
                            coverArtId = item.coverArt,
                            coverArt = coverArt,
                            isPlaying = item.song.id == playingSongId,
                            durationSeconds = item.song.duration,
                            download = downloads[item.song.id],
                            modifier =
                                Modifier.combinedClickable(
                                    onClick = { onSongClick(item.song, index) },
                                    onLongClick = {
                                        onLongClick(MenuTarget.Song(item.song, coverArt(item.coverArt, true), downloads[item.song.id]))
                                    },
                                ),
                        )
                    }
                }
                item { Spacer(Modifier.height(fill)) }
            }
        }
    }
}
