package com.subtracks.data.db

import com.subtracks.data.model.QueueKind
import com.subtracks.data.prefs.PlaylistSongOrder
import com.subtracks.data.prefs.PlaylistSongSort
import com.subtracks.data.prefs.SortNameMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SortedPlaylistQueryTest {
    @Test
    fun aQuerySurvivesEncodingIncludingSeparatorsInThePlaylistId() {
        val query =
            SortedPlaylistQuery(
                playlistId = "a|b|c|d",
                order = PlaylistSongOrder(PlaylistSongSort.Album, descending = true),
                sortNameMode = SortNameMode.NonLatin,
            )

        assertEquals(query, SortedPlaylistQuery.decode(query.encode()))
        assertEquals("a|b|c|d", playlistIdOf(QueueKind.SortedPlaylist, query.encode()))
    }

    @Test
    fun aPlainPlaylistReferenceIsThePlaylistId() {
        assertEquals("a|b", playlistIdOf(QueueKind.Playlist, "a|b"))
    }
}
