package com.subtracks.ui.playback

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtracks.TEST_TIMEOUT_MS
import com.subtracks.awaitUntil
import com.subtracks.cancelAndJoinBlocking
import com.subtracks.data.db.SubtracksDatabase
import com.subtracks.data.db.createTestDatabase
import com.subtracks.data.download.ArtworkStore
import com.subtracks.data.download.FakeDownloadEngine
import com.subtracks.data.model.Song
import com.subtracks.data.model.Source
import com.subtracks.data.prefs.fakeUserPreferences
import com.subtracks.data.repo.DownloadRepository
import com.subtracks.data.repo.LibraryRepository
import com.subtracks.data.repo.QueueRepository
import com.subtracks.data.repo.SourceRepository
import com.subtracks.data.source.ServerActionSink
import com.subtracks.data.source.StarType
import com.subtracks.playback.FakePlayerConnection
import com.subtracks.playback.FakePlayerHandle
import com.subtracks.playback.PlaybackController
import com.subtracks.playback.RepeatMode
import com.subtracks.playback.SleepTimer
import com.subtracks.playback.SleepTimerState
import com.subtracks.ui.components.ContextMenuHost
import com.subtracks.ui.components.ItemContextMenu
import com.subtracks.ui.theme.SubtracksTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingRouteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controllerScope = CoroutineScope(SupervisorJob() + dispatcher)

    private lateinit var db: SubtracksDatabase
    private lateinit var handle: FakePlayerHandle
    private lateinit var controller: PlaybackController
    private lateinit var library: LibraryRepository
    private lateinit var downloads: DownloadRepository

    private val prefs = fakeUserPreferences()

    private val serverActions =
        object : ServerActionSink {
            override suspend fun nowPlaying(songId: String) = Unit

            override suspend fun scrobble(
                songId: String,
                time: Long,
            ) = Unit

            override suspend fun setStar(
                type: StarType,
                id: String,
                starred: Boolean,
            ) = Unit
        }

    private fun setUpState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artworkStore = ArtworkStore(File(context.cacheDir, "art"))
        db = createTestDatabase()
        val sources = SourceRepository(db, OkHttpClient(), prefs, artworkStore, scope = repoScope)
        val queues = QueueRepository(db)
        downloads =
            DownloadRepository(
                db,
                sources,
                FakeDownloadEngine(),
                File(context.cacheDir, "downloads-${System.nanoTime()}"),
                artworkStore = artworkStore,
                artworkFetcher = { ByteArray(0) },
                queueRepository = queues,
                scope = repoScope,
            ).also { it.start() }
        library = LibraryRepository(db, sources, serverActions, prefs, queueRepository = queues, scope = repoScope)
        handle = FakePlayerHandle()
        controller =
            PlaybackController(
                sources,
                queues,
                FakePlayerConnection(handle),
                downloads,
                prefs,
                showMessage = {},
                scope = controllerScope,
            )
    }

    private fun seedAlbum(count: Int) {
        runBlocking {
            db.sourcesDao().upsertSource(Source(1, "source 1", "http://localhost:1", true, 1))
            db.libraryDao().upsertSongs(
                (1..count).map { track ->
                    Song(
                        sourceId = 1,
                        id = "s$track",
                        albumId = "al1",
                        artistId = "ar1",
                        title = "Song $track",
                        album = "Album",
                        artist = "Artist",
                        duration = 100,
                        track = track.toLong(),
                        disc = 1,
                        starred = null,
                        genre = null,
                    )
                },
            )
        }
    }

    @After
    fun tearDown() {
        cancelAndJoinBlocking(controllerScope, repoScope)
        db.close()
        dispatcher.close()
    }

    @Test
    fun togglingRepeatRefreshesTheAdjacentArtwork() {
        setUpState()
        seedAlbum(3)
        controller.playAlbum(1, "al1", 0)
        awaitUntil("waiting for the first track") {
            controller.state.value.item
                ?.id == "s1"
        }

        composeRule.setContent {
            SubtracksTheme {
                NowPlayingRoute(
                    onBack = {},
                    onQueue = {},
                    onViewAlbum = {},
                    onViewArtist = {},
                    setStar = { _, _, _ -> },
                    controller = controller,
                    libraryRepository = library,
                    downloadRepository = downloads,
                    userPreferences = prefs,
                    sleepTimer = SleepTimer(),
                )
            }
        }
        composeRule.waitUntil(TEST_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithTag(NOW_PLAYING_NEXT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithTag(NOW_PLAYING_PREVIOUS_TAG, useUnmergedTree = true)
            .assertDoesNotExist()

        controller.cycleRepeat()
        assertEquals(RepeatMode.All, controller.state.value.repeat)

        composeRule.waitUntil(TEST_TIMEOUT_MS) {
            composeRule
                .onAllNodesWithTag(NOW_PLAYING_PREVIOUS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun theSleepTimerIsSetFromTheContextMenu() {
        setUpState()
        seedAlbum(1)
        controller.playAlbum(1, "al1", 0)
        awaitUntil("waiting for the first track") {
            controller.state.value.item
                ?.id == "s1"
        }
        var now = 1_000_000L
        val sleepTimer = SleepTimer { now }
        val menuHost = ContextMenuHost()

        composeRule.setContent {
            SubtracksTheme {
                NowPlayingRoute(
                    onBack = {},
                    onQueue = {},
                    onViewAlbum = {},
                    onViewArtist = {},
                    setStar = { _, _, _ -> },
                    contextMenuHost = menuHost,
                    controller = controller,
                    libraryRepository = library,
                    downloadRepository = downloads,
                    userPreferences = prefs,
                    sleepTimer = sleepTimer,
                )
                menuHost.target?.let { target ->
                    ItemContextMenu(target = target, actions = menuHost.actions, onDismiss = { menuHost.dismiss() })
                }
            }
        }

        fun openSleepTimerSheet() {
            composeRule.waitUntil(TEST_TIMEOUT_MS) {
                if (menuHost.target == null) composeRule.onNodeWithContentDescription("More").performClick()
                menuHost.target != null
            }
            composeRule.onNodeWithText("Sleep timer").performClick()
            composeRule.onNodeWithTag(SLEEP_TIMER_SHEET_TAG).assertExists()
        }

        openSleepTimerSheet()
        composeRule.onNodeWithText("Turn off timer").assertDoesNotExist()
        composeRule.onNodeWithText("30 minutes").assertExists()
        composeRule.onNodeWithTag(SLEEP_TIMER_SLIDER_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(45f) }
        composeRule.onNodeWithText("45 minutes").assertExists()
        composeRule.onNodeWithText("Start").performClick()
        composeRule.waitForIdle()
        assertEquals(SleepTimerState.Countdown(now + 45 * 60_000L), sleepTimer.state.value)
        composeRule.onNodeWithTag(SLEEP_TIMER_SHEET_TAG).assertDoesNotExist()
        assertEquals(45, runBlocking { prefs.sleepTimerMinutes().first() })

        composeRule.waitUntil(TEST_TIMEOUT_MS) {
            if (menuHost.target == null) composeRule.onNodeWithContentDescription("More").performClick()
            menuHost.target != null
        }
        composeRule.onNodeWithText("45:00", useUnmergedTree = true).assertExists()
        now += 1_000L
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithText("44:59", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Sleep timer").performClick()
        composeRule.onNodeWithText("45 minutes").assertExists()
        composeRule.onNodeWithText("End of track").performClick()
        composeRule.waitForIdle()
        assertEquals(SleepTimerState.EndOfTrack, sleepTimer.state.value)

        openSleepTimerSheet()
        composeRule.onNodeWithText("Turn off timer").performClick()
        composeRule.waitForIdle()
        assertEquals(SleepTimerState.Off, sleepTimer.state.value)
    }
}
