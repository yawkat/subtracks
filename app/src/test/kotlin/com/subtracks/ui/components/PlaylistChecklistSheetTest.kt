package com.subtracks.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtracks.data.model.Playlist
import com.subtracks.data.repo.PlaylistChanges
import com.subtracks.ui.theme.SubtracksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistChecklistSheetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val mix = Playlist(sourceId = 1, id = "mix", name = "Mix", comment = null, coverArt = null, songCount = 0, created = 0)

    private var starSet: Boolean? = null
    private var saved: PlaylistChanges? = null
    private var closed = false

    private fun render(
        starred: Boolean?,
        playlistsEditable: Boolean = true,
    ) {
        composeRule.setContent {
            SubtracksTheme {
                if (!closed) {
                    PlaylistChecklistSheet(
                        title = "Song",
                        playlists = listOf(mix),
                        holding = emptySet(),
                        onSave = { saved = it },
                        onClose = { closed = true },
                        starred = starred,
                        onSetStar = { starSet = it },
                        playlistsEditable = playlistsEditable,
                    )
                }
            }
        }
    }

    private fun closeSheet() {
        composeRule.onNodeWithTag(PLAYLIST_CHECKLIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
    }

    @Test
    fun closingAfterTickingStarredStarsTheSong() {
        render(starred = false)

        composeRule.onNodeWithText("Starred").performClick()
        closeSheet()

        assertEquals(true, starSet)
        assertEquals(true, closed)
    }

    @Test
    fun closingAfterUntickingStarredUnstarsTheSong() {
        render(starred = true)

        composeRule.onNodeWithText("Starred").performClick()
        closeSheet()

        assertEquals(false, starSet)
    }

    @Test
    fun cancelDropsTheStarChange() {
        render(starred = false)

        composeRule.onNodeWithText("Starred").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        assertNull(starSet)
        assertEquals(true, closed)
    }

    @Test
    fun withoutStarredTheRowIsHidden() {
        render(starred = null)

        composeRule.onNodeWithText("Mix").assertIsDisplayed()
        composeRule.onNodeWithText("Starred").assertDoesNotExist()
    }

    @Test
    fun offlineStarringStillWorksWhilePlaylistsAreLocked() {
        render(starred = false, playlistsEditable = false)

        composeRule.onNodeWithText("Mix").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Starred").performClick()
        closeSheet()

        assertEquals(true, starSet)
        assertNull(saved)
    }
}
