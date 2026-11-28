package com.subtracks.data.repo

import android.content.Context
import android.content.res.Resources
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtracks.R
import com.subtracks.cancelAndJoinBlocking
import com.subtracks.data.db.SubtracksDatabase
import com.subtracks.data.db.createTestDatabase
import com.subtracks.data.download.ArtworkStore
import com.subtracks.data.model.Playlist
import com.subtracks.data.prefs.fakeUserPreferences
import com.subtracks.data.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {
    private lateinit var db: SubtracksDatabase
    private lateinit var sourceRepository: SourceRepository
    private lateinit var repository: PlaylistRepository
    private lateinit var scope: CoroutineScope
    private lateinit var server: MockWebServer
    private lateinit var playlists: FakePlaylistServer
    private lateinit var resources: Resources
    private val messages = ArrayList<String>()
    private var sourceId = 0L

    @Volatile
    private var syncRunning = false

    @Volatile
    private var syncRequests = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        resources = context.resources
        db = createTestDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sourceRepository =
            SourceRepository(db, OkHttpClient(), fakeUserPreferences(), ArtworkStore(File(context.cacheDir, "art")), scope = scope)
        repository =
            PlaylistRepository(
                db,
                sourceRepository,
                QueueRepository(db),
                syncRunning = { syncRunning },
                requestSync = { syncRequests++ },
                showMessage = { messages += it.resolve(resources) },
                scope = scope,
            )
        playlists = FakePlaylistServer()
        server = MockWebServer()
        server.dispatcher = playlists
        server.start()
        sourceId = runBlocking { sourceRepository.addSource("nav", server.url("/").toString(), "u", "p", false) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        cancelAndJoinBlocking(scope)
        db.close()
    }

    @Test
    fun addingAppendsOnTheServerAndMirrorsTheLibrary() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1")

            assertTrue(add(playlist("p1"), "s2"))

            assertEquals(listOf("s1", "s2"), playlists.entries["p1"])
            assertEquals(listOf("s1", "s2"), localSongs("p1"))
            assertEquals(2L, localPlaylist("p1").songCount)
            assertEquals(listOf(resources.getString(R.string.playlist_added, "List p1")), messages)
            assertEquals(0, syncRequests)
            assertTrue(db.queueDao().pendingSyncOrigins(sourceId).isEmpty())
        }

    @Test
    fun editingDuringASyncLeavesItsPendingOriginsAndRequestsAFollowUp() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1")
            syncRunning = true

            assertTrue(add(playlist("p1"), "s2"))

            assertEquals(1, syncRequests)
            assertTrue(db.queueDao().pendingSyncOrigins(sourceId).isNotEmpty())
        }

    @Test
    fun aFailedEditRequestsASyncToCatchUpWithWhatReachedTheServer() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf()
            playlists.failUpdatesAfter = 0

            assertFalse(add(playlist("p1"), "s2"))

            assertEquals(1, syncRequests)
            assertEquals(listOf(resources.getString(R.string.playlist_update_failed)), messages)
        }

    @Test
    fun editsAreRefusedOffline() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1")
            sourceRepository.setOfflineMode(true)
            withTimeout(5_000) { while (!sourceRepository.offline.value) delay(10) }

            assertFalse(add(playlist("p1"), "s2"))

            assertEquals(0, playlists.updates)
            assertEquals(listOf(resources.getString(R.string.playlist_edit_offline)), messages)
        }

    @Test
    fun editsAreRefusedForAPlaylistOfAnotherSource() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1")

            assertFalse(add(playlist("p1").copy(sourceId = sourceId + 1), "s2"))

            assertEquals(0, playlists.updates)
        }

    @Test
    fun addingASongThePlaylistAlreadyHoldsChangesNothing() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s2")

            assertTrue(add(playlist("p1"), "s2"))

            assertEquals(0, playlists.updates)
            assertEquals(listOf("s2"), playlists.entries["p1"])
        }

    @Test
    fun uncheckingRemovesEveryOccurrenceOfTheSong() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1", "x", "s1", "", "s2")

            assertTrue(repository.apply(sourceId, "s1", PlaylistChanges(removeFrom = listOf(playlist("p1")))))

            assertEquals(listOf("x", "", "s2"), playlists.entries["p1"])
            assertEquals(listOf("x", "s2"), localSongs("p1"))
            assertEquals(listOf(resources.getString(R.string.playlist_removed, "List p1")), messages)
        }

    @Test
    fun oneApplyAddsToSomePlaylistsAndRemovesFromOthers() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf()
            playlists.entries["p2"] = mutableListOf("s1")

            val changes = PlaylistChanges(addTo = listOf(playlist("p1")), removeFrom = listOf(playlist("p2")))
            assertTrue(repository.apply(sourceId, "s1", changes))

            assertEquals(listOf("s1"), playlists.entries["p1"])
            assertEquals(emptyList<String>(), playlists.entries["p2"])
            assertEquals(listOf(resources.getString(R.string.playlists_updated)), messages)
        }

    @Test
    fun theNowPlayingButtonOnlyCountsPlaylistsTheUserCanEdit() =
        runBlocking {
            playlists.entries["mine"] = mutableListOf("s1")
            playlists.entries["shared"] = mutableListOf("s1", "s2")
            playlists.owners["shared"] = "someone"
            listOf("mine", "shared").forEach { mirror(it) }

            assertEquals(setOf("mine"), repository.editablePlaylistsHolding(sourceId, "s1").first())
            assertEquals(emptySet<String>(), repository.editablePlaylistsHolding(sourceId, "s2").first())
        }

    @Test
    fun theChecklistTicksThePlaylistsHoldingTheSong() =
        runBlocking {
            playlists.entries["a"] = mutableListOf("s1", "s2", "s1")
            playlists.entries["b"] = mutableListOf("s2")
            playlists.entries["c"] = mutableListOf("other")
            listOf("a", "b", "c").forEach { mirror(it) }

            assertEquals(setOf("a"), repository.playlistsHolding(sourceId, "s1").first())
            assertEquals(setOf("a", "b"), repository.playlistsHolding(sourceId, "s2").first())
        }

    @Test
    fun removingDeletesTheChosenOccurrenceAtItsServerIndex() =
        runBlocking {
            // The id-less entry is not mirrored, so library positions skip it: s1@0, s2@1, s1@2.
            playlists.entries["p1"] = mutableListOf("s1", "", "s2", "s1")
            mirror("p1")
            assertEquals(listOf("s1", "s2", "s1"), localSongs("p1"))

            assertTrue(repository.remove(playlist("p1"), position = 2, songId = "s1"))

            assertEquals(listOf("s1", "", "s2"), playlists.entries["p1"])
            assertEquals(listOf("s1", "s2"), localSongs("p1"))
        }

    @Test
    fun removingLeavesTheServerAloneWhenItsPlaylistMovedOn() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1", "s2")
            mirror("p1")
            playlists.entries["p1"] = mutableListOf("s2", "s1")

            assertFalse(repository.remove(playlist("p1"), position = 0, songId = "s1"))

            assertEquals(0, playlists.updates)
            assertEquals(listOf("s2", "s1"), playlists.entries["p1"])
            assertEquals(listOf("s2", "s1"), localSongs("p1"))
            assertEquals(listOf(resources.getString(R.string.playlist_changed_on_server)), messages)
        }

    @Test
    fun removingIsRefusedWhenTheServerHidesEntriesFromTheListing() =
        runBlocking {
            playlists.entries["p1"] = mutableListOf("s1", "gone", "s2")
            playlists.hidden += "gone"
            mirror("p1")

            assertFalse(repository.remove(playlist("p1"), position = 1, songId = "s2"))

            assertEquals(0, playlists.updates)
            assertEquals(listOf(resources.getString(R.string.playlist_entries_hidden)), messages)
        }

    @Test
    fun removingMoreEntriesThanOneRequestHoldsRemovesEachOfThem() =
        runBlocking {
            val songs = (0 until 300).map { "s$it" }
            playlists.entries["p1"] = songs.toMutableList()

            sourceRepository.activeMusicSource()!!.updatePlaylist("p1", entryIndexesToRemove = songs.indices.filter { it % 2 == 0 })

            assertTrue(playlists.updates > 1)
            assertEquals(songs.filterIndexed { index, _ -> index % 2 == 1 }, playlists.entries["p1"])
        }

    @Test
    fun onlyTheUsersOwnPlaylistsAreEditable() =
        runBlocking {
            db.libraryDao().upsertPlaylists(
                listOf(
                    playlist("mine").copy(owner = "U"),
                    playlist("shared").copy(owner = "someone"),
                    playlist("unowned").copy(owner = null),
                ),
            )

            assertEquals(
                listOf("mine", "unowned"),
                repository
                    .editablePlaylists(sourceId)
                    .first()
                    .map { it.id }
                    .sorted(),
            )
        }

    private suspend fun mirror(playlistId: String) {
        val source = sourceRepository.activeMusicSource()!!
        SyncService(db, source).syncPlaylist(source.playlist(playlistId))
    }

    private suspend fun add(
        playlist: Playlist,
        songId: String,
    ) = repository.apply(playlist.sourceId, songId, PlaylistChanges(addTo = listOf(playlist)))

    private suspend fun localSongs(playlistId: String): List<String> =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT songId FROM playlist_songs WHERE sourceId = ? AND playlistId = ? ORDER BY position") {
                it.bindLong(1, sourceId)
                it.bindText(2, playlistId)
                buildList { while (it.step()) add(it.getText(0)) }
            }
        }

    private suspend fun localPlaylist(playlistId: String): Playlist = db.libraryDao().playlist(sourceId, playlistId).first()!!

    private fun playlist(id: String) =
        Playlist(sourceId = sourceId, id = id, name = "List $id", comment = null, coverArt = null, songCount = 0, created = 0, owner = "u")
}

/** A Subsonic server that only knows playlists, applying edits the way the API defines them. */
private class FakePlaylistServer : Dispatcher() {
    val entries = ConcurrentHashMap<String, MutableList<String>>()
    val owners = ConcurrentHashMap<String, String>()

    /** Song ids getPlaylist leaves out while still counting them, as gonic does for missing files. */
    val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var failUpdatesAfter = Int.MAX_VALUE

    @Volatile
    var updates = 0

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        return when (url.encodedPath) {
            "/rest/getPlaylist.view" -> {
                val id = url.queryParameter("id")!!
                entries[id]?.let { ok(playlistXml(id, it, "List $id", owners[id] ?: "u")) } ?: failed(70)
            }

            "/rest/updatePlaylist.view" -> {
                if (updates >= failUpdatesAfter) return MockResponse().setResponseCode(500)
                updates++
                val list = entries.getValue(url.queryParameter("playlistId")!!)
                url
                    .queryParameterValues("songIndexToRemove")
                    .map { it!!.toInt() }
                    .sortedDescending()
                    .forEach { list.removeAt(it) }
                list += url.queryParameterValues("songIdToAdd").map { it!! }
                ok("")
            }

            else -> {
                MockResponse().setResponseCode(404)
            }
        }
    }

    private fun playlistXml(
        id: String,
        songs: List<String>,
        name: String,
        owner: String = "u",
    ) = "<playlist id=\"$id\" name=\"$name\" owner=\"$owner\" songCount=\"${songs.size}\">" +
        songs.filter { it !in hidden }.joinToString("") { "<entry id=\"$it\" title=\"$it\"/>" } +
        "</playlist>"

    private fun ok(body: String) = MockResponse().setBody("<subsonic-response status=\"ok\" version=\"1.16.1\">$body</subsonic-response>")

    private fun failed(code: Int) =
        MockResponse().setBody("<subsonic-response status=\"failed\"><error code=\"$code\" message=\"nope\"/></subsonic-response>")
}
