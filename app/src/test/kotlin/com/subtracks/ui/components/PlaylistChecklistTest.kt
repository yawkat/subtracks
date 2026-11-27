package com.subtracks.ui.components

import com.subtracks.data.model.Playlist
import com.subtracks.data.repo.PlaylistChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistChecklistTest {
    private val holds = playlist("holds")
    private val lacks = playlist("lacks")
    private val playlists = listOf(holds, lacks)
    private val holding = setOf("holds")

    @Test
    fun boxesLeftAsTheyStartedChangeNothing() {
        assertEquals(PlaylistChanges(), playlistChanges(playlists, holding, mapOf("holds" to true, "lacks" to false), emptyList()))
    }

    @Test
    fun checkingAddsAndUncheckingRemoves() {
        assertEquals(
            PlaylistChanges(addTo = listOf(lacks), removeFrom = listOf(holds), create = listOf("Mix")),
            playlistChanges(playlists, holding, mapOf("holds" to false, "lacks" to true), listOf("Mix")),
        )
    }

    @Test
    fun starLeftAsItStartedChangesNothing() {
        assertNull(starChange(starred = true, chosen = null))
        assertNull(starChange(starred = true, chosen = true))
    }

    @Test
    fun flippingTheStarReportsTheNewState() {
        assertEquals(false, starChange(starred = true, chosen = false))
        assertEquals(true, starChange(starred = false, chosen = true))
    }

    private fun playlist(id: String) =
        Playlist(sourceId = 1, id = id, name = id, comment = null, coverArt = null, songCount = 0, created = 0)
}
