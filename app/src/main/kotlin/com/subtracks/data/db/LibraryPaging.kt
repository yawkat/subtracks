package com.subtracks.data.db

import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import com.subtracks.data.prefs.AlbumSort
import com.subtracks.data.prefs.ArtistSort
import com.subtracks.data.prefs.PlaylistSort
import com.subtracks.data.prefs.SongSort
import com.subtracks.data.prefs.SortNameMode
import com.subtracks.data.prefs.StarredFilter

// The default filters intentionally omit song_downloads: the unfiltered lists do not need the
// join, and their paging sources do not observe download writes (including progress updates).
internal const val ALBUMS_FILTER = """
FROM albums WHERE sourceId = :sourceId
AND (:starredFilter = 0 OR (:starredFilter = 1 AND starred IS NOT NULL) OR (:starredFilter = 2 AND starred IS NULL))
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND rowid IN (SELECT rowid FROM album_search
        WHERE album_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND (instr(lower(name), lower(:search)) > 0
        OR instr(lower(albumArtist), lower(:search)) > 0
        OR instr(lower(sortName), lower(:search)) > 0
        OR instr(lower(albumArtistSort), lower(:search)) > 0)))
"""

internal const val ALBUMS_FILTER_DOWNLOADED = """
FROM albums WHERE sourceId = :sourceId
AND (:starredFilter = 0 OR (:starredFilter = 1 AND starred IS NOT NULL) OR (:starredFilter = 2 AND starred IS NULL))
AND EXISTS (SELECT 1 FROM songs
    JOIN song_downloads sd ON sd.sourceId = songs.sourceId AND sd.songId = songs.id
    WHERE songs.sourceId = albums.sourceId AND songs.albumId = albums.id AND sd.status = 'Completed')
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND rowid IN (SELECT rowid FROM album_search
        WHERE album_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND (instr(lower(name), lower(:search)) > 0
        OR instr(lower(albumArtist), lower(:search)) > 0
        OR instr(lower(sortName), lower(:search)) > 0
        OR instr(lower(albumArtistSort), lower(:search)) > 0)))
"""

internal const val ARTISTS_FILTER = """
FROM artists WHERE sourceId = :sourceId
AND (:starredFilter = 0 OR (:starredFilter = 1 AND starred IS NOT NULL) OR (:starredFilter = 2 AND starred IS NULL))
AND $ARTIST_CREDITED
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND rowid IN (SELECT rowid FROM artist_search
        WHERE artist_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND (instr(lower(name), lower(:search)) > 0
        OR instr(lower(sortName), lower(:search)) > 0)))
"""

internal const val ARTISTS_FILTER_DOWNLOADED = """
FROM artists WHERE sourceId = :sourceId
AND (:starredFilter = 0 OR (:starredFilter = 1 AND starred IS NOT NULL) OR (:starredFilter = 2 AND starred IS NULL))
AND $ARTIST_CREDITED
AND EXISTS (SELECT 1 FROM ($ARTIST_DOWNLOAD_ROWS) dl
    WHERE dl.id = artists.id AND dl.status = 'Completed')
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND rowid IN (SELECT rowid FROM artist_search
        WHERE artist_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND (instr(lower(name), lower(:search)) > 0
        OR instr(lower(sortName), lower(:search)) > 0)))
"""

// The downloaded list counts the albums that actually have a download so the number under an artist
// matches the albums its detail screen lists.
internal val ARTIST_COLUMNS_DOWNLOADED = """
artists.sourceId, artists.id, artists.name,
(SELECT COUNT(*) FROM ($DOWNLOADED_ARTIST_ALBUMS)) AS albumCount,
$ARTIST_COLUMNS_TAIL
"""

internal const val PLAYLISTS_FILTER = """
FROM playlists WHERE sourceId = :sourceId
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND rowid IN (SELECT rowid FROM playlist_search
        WHERE playlist_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND instr(lower(name), lower(:search)) > 0))
"""

internal const val PLAYLISTS_FILTER_DOWNLOADED = """
FROM playlists WHERE sourceId = :sourceId
AND EXISTS (SELECT 1 FROM playlist_songs ps
    JOIN song_downloads sd ON sd.sourceId = ps.sourceId AND sd.songId = ps.songId
    WHERE ps.sourceId = playlists.sourceId AND ps.playlistId = playlists.id AND sd.status = 'Completed')
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND rowid IN (SELECT rowid FROM playlist_search
        WHERE playlist_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND instr(lower(name), lower(:search)) > 0))
"""

// The downloaded list counts the songs that actually have a download so the number under a
// playlist matches the songs its detail screen lists.
internal const val PLAYLISTS_COLUMNS_DOWNLOADED = """
playlists.sourceId, playlists.id, playlists.name, playlists.comment, playlists.coverArt,
(SELECT COUNT(DISTINCT ps.songId)
    FROM playlist_songs ps
    JOIN song_downloads sd ON sd.sourceId = ps.sourceId AND sd.songId = ps.songId AND sd.status = 'Completed'
    WHERE ps.sourceId = playlists.sourceId AND ps.playlistId = playlists.id
) AS songCount,
playlists.created, playlists.changed, playlists.duration, playlists.owner, playlists.shared
"""

internal fun albumsPageQuery(
    sourceId: Long,
    starredFilter: Int,
    search: String,
    sort: AlbumSort,
    descending: Boolean,
    downloaded: Boolean,
    sortNameMode: SortNameMode = SortNameMode.SortName,
): RoomRawQuery =
    rawQuery(
        sql = "SELECT * ${if (downloaded) ALBUMS_FILTER_DOWNLOADED else ALBUMS_FILTER} ORDER BY ${albumOrder(
            sort,
            descending,
            sortNameMode,
        )}",
        bindings =
            mapOf(
                "sourceId" to sourceId,
                "starredFilter" to starredFilter,
                "search" to search,
            ),
    )

internal fun artistsPageQuery(
    sourceId: Long,
    starredFilter: Int,
    search: String,
    sort: ArtistSort,
    descending: Boolean,
    downloaded: Boolean,
    sortNameMode: SortNameMode = SortNameMode.SortName,
): RoomRawQuery =
    rawQuery(
        sql =
            "SELECT ${if (downloaded) ARTIST_COLUMNS_DOWNLOADED else ARTIST_COLUMNS} " +
                "${if (downloaded) ARTISTS_FILTER_DOWNLOADED else ARTISTS_FILTER} " +
                "ORDER BY ${artistOrder(sort, descending, sortNameMode)}",
        bindings =
            mapOf(
                "sourceId" to sourceId,
                "starredFilter" to starredFilter,
                "search" to search,
            ),
    )

internal fun playlistsPageQuery(
    sourceId: Long,
    search: String,
    sort: PlaylistSort,
    descending: Boolean,
    downloaded: Boolean,
): RoomRawQuery =
    rawQuery(
        sql =
            "SELECT ${if (downloaded) PLAYLISTS_COLUMNS_DOWNLOADED else "*"} " +
                "${if (downloaded) PLAYLISTS_FILTER_DOWNLOADED else PLAYLISTS_FILTER} " +
                "ORDER BY ${playlistOrder(sort, descending)}",
        bindings =
            mapOf(
                "sourceId" to sourceId,
                "search" to search,
            ),
    )

internal fun rediscoverAlbumsQuery(
    sourceId: Long,
    starredFilter: Int,
    search: String,
    cutoff: Long,
): RoomRawQuery =
    rawQuery(
        sql =
            "SELECT * $ALBUMS_FILTER AND played IS NOT NULL AND played < :cutoff " +
                "ORDER BY played ASC, sortName COLLATE NOCASE, id",
        bindings =
            mapOf(
                "sourceId" to sourceId,
                "starredFilter" to starredFilter,
                "search" to search,
                "cutoff" to cutoff,
            ),
    )

/**
 * The filter and order of the library's song list. It doubles as the reference of a
 * [com.subtracks.data.model.QueueKind.Library] queue entry, so the queue plays the list exactly as
 * it was shown when a song was tapped.
 */
data class LibrarySongsQuery(
    val sort: SongSort = SongSort.Name,
    val descending: Boolean = false,
    val starred: StarredFilter = StarredFilter.Any,
    val downloaded: Boolean = false,
    val search: String = "",
    val sortNameMode: SortNameMode = SortNameMode.SortName,
) {
    fun encode(): String =
        listOf(
            sort.name,
            if (descending) "1" else "0",
            starred.name,
            if (downloaded) "1" else "0",
            sortNameMode.name,
            search,
        ).joinToString("|")

    companion object {
        fun decode(refId: String): LibrarySongsQuery {
            val parts = refId.split("|", limit = 6)
            return LibrarySongsQuery(
                sort = SongSort.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: SongSort.Name,
                descending = parts.getOrNull(1) == "1",
                starred = StarredFilter.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: StarredFilter.Any,
                downloaded = parts.getOrNull(3) == "1",
                sortNameMode = SortNameMode.entries.firstOrNull { it.name == parts.getOrNull(4) } ?: SortNameMode.SortName,
                search = parts.getOrNull(5).orEmpty(),
            )
        }
    }
}

private const val SONG_COLUMNS = "songs.*, albums.coverArt AS coverArt"

private const val SONGS_FROM = """
FROM songs LEFT JOIN albums ON albums.sourceId = songs.sourceId AND albums.id = songs.albumId
WHERE songs.sourceId = :sourceId
AND (:starredFilter = 0 OR (:starredFilter = 1 AND songs.starred IS NOT NULL) OR (:starredFilter = 2 AND songs.starred IS NULL))
AND (:search = ''
    OR (length(:search) >= $SEARCH_MIN_LENGTH AND songs.rowid IN (SELECT rowid FROM song_search
        WHERE song_search MATCH '"' || replace(:search, '"', '""') || '"'))
    OR (length(:search) < $SEARCH_MIN_LENGTH AND (instr(lower(songs.title), lower(:search)) > 0
        OR instr(lower(songs.artist), lower(:search)) > 0
        OR instr(lower(songs.sortName), lower(:search)) > 0
        OR instr(lower(songs.artistSort), lower(:search)) > 0)))
"""

private const val SONGS_DOWNLOADED_FILTER = """
AND EXISTS (SELECT 1 FROM song_downloads sd
    WHERE sd.sourceId = songs.sourceId AND sd.songId = songs.id AND sd.status = 'Completed')
"""

private fun LibrarySongsQuery.from(): String = if (downloaded) SONGS_FROM + SONGS_DOWNLOADED_FILTER else SONGS_FROM

private fun LibrarySongsQuery.bindings(sourceId: Long): Map<String, Any?> =
    mapOf(
        "sourceId" to sourceId,
        "starredFilter" to starred.ordinal,
        "search" to search,
    )

/** The library's song list, in the order [LibrarySongsQuery] describes. */
internal fun librarySongsPageQuery(
    sourceId: Long,
    query: LibrarySongsQuery,
): RoomRawQuery =
    rawQuery(
        sql = "SELECT $SONG_COLUMNS ${query.from()} ORDER BY ${query.order()}",
        bindings = query.bindings(sourceId),
    )

internal fun librarySongsSliceQuery(
    sourceId: Long,
    query: LibrarySongsQuery,
    offset: Long,
    limit: Int,
): RoomRawQuery =
    rawQuery(
        sql = "SELECT $SONG_COLUMNS ${query.from()} ORDER BY ${query.order()} LIMIT :limit OFFSET :offset",
        bindings = query.bindings(sourceId) + mapOf("limit" to limit, "offset" to offset),
    )

internal fun librarySongsCountQuery(
    sourceId: Long,
    query: LibrarySongsQuery,
): RoomRawQuery =
    rawQuery(
        sql = "SELECT COUNT(*) ${query.from()}",
        bindings = query.bindings(sourceId),
    )

internal fun librarySongRankQuery(
    sourceId: Long,
    query: LibrarySongsQuery,
    songId: String,
): RoomRawQuery =
    rawQuery(
        sql =
            "SELECT songRank FROM (SELECT songs.id AS rankedId, ROW_NUMBER() OVER (ORDER BY ${query.order()}) - 1 AS songRank " +
                "${query.from()}) WHERE rankedId = :songId",
        bindings = query.bindings(sourceId) + mapOf("songId" to songId),
    )

private fun songNameKey(mode: SortNameMode): String =
    when (mode) {
        SortNameMode.SortName -> "songs.sortName COLLATE NOCASE"
        SortNameMode.Name -> "songs.title COLLATE NOCASE"
        SortNameMode.NonLatin -> "(CASE WHEN songs.title GLOB '*[A-Za-z]*' THEN songs.title ELSE songs.sortName END) COLLATE NOCASE"
    }

private fun songArtistKey(mode: SortNameMode): String =
    when (mode) {
        SortNameMode.SortName -> "songs.artistSort COLLATE NOCASE"
        SortNameMode.Name -> "songs.artist COLLATE NOCASE"
        SortNameMode.NonLatin -> "(CASE WHEN songs.artist GLOB '*[A-Za-z]*' THEN songs.artist ELSE songs.artistSort END) COLLATE NOCASE"
    }

private fun songAlbumKey(mode: SortNameMode): String =
    when (mode) {
        SortNameMode.SortName -> {
            "COALESCE(albums.sortName, songs.album) COLLATE NOCASE"
        }

        SortNameMode.Name -> {
            "songs.album COLLATE NOCASE"
        }

        SortNameMode.NonLatin -> {
            "(CASE WHEN songs.album GLOB '*[A-Za-z]*' THEN songs.album ELSE COALESCE(albums.sortName, songs.album) END) COLLATE NOCASE"
        }
    }

private fun LibrarySongsQuery.order(): String {
    val name = songNameKey(sortNameMode)
    val album = listOf(songAlbumKey(sortNameMode) to false, "songs.albumId" to false, "songs.disc" to false, "songs.track" to false)
    val terms =
        when (sort) {
            SongSort.Name -> listOf(name to false)
            SongSort.Artist -> listOf(songArtistKey(sortNameMode) to false) + album
            SongSort.Album -> album
            SongSort.Added -> listOf("songs.created" to true, name to false)
            SongSort.Starred -> listOf("songs.starred" to true, name to false)
        } + ("songs.id" to false)
    return terms.joinToString(", ") { (term, termDescending) ->
        if (termDescending != descending) "$term DESC" else term
    }
}

// RoomRawQuery takes a positional bind function, but SQLite gives a repeated named parameter one
// index, so the SQL can keep its names and only the first-appearance order needs binding.
internal fun rawQuery(
    sql: String,
    bindings: Map<String, Any?>,
): RoomRawQuery {
    val values =
        Regex(":(\\w+)")
            .findAll(sql)
            .map { it.groupValues[1] }
            .distinct()
            .map(bindings::getValue)
            .toList()
    return RoomRawQuery(sql) { statement ->
        values.forEachIndexed { index, value -> statement.bind(index + 1, value) }
    }
}

private fun SQLiteStatement.bind(
    index: Int,
    value: Any?,
) {
    when (value) {
        null -> bindNull(index)
        is Int -> bindLong(index, value.toLong())
        is Long -> bindLong(index, value)
        is String -> bindText(index, value)
        else -> error("Unsupported SQL parameter: ${value::class.qualifiedName}")
    }
}

internal fun albumNameSortKey(mode: SortNameMode): String =
    when (mode) {
        SortNameMode.SortName -> "sortName COLLATE NOCASE"
        SortNameMode.Name -> "name COLLATE NOCASE"
        SortNameMode.NonLatin -> "(CASE WHEN name GLOB '*[A-Za-z]*' THEN name ELSE sortName END) COLLATE NOCASE"
    }

internal fun albumArtistSortKey(mode: SortNameMode): String =
    when (mode) {
        SortNameMode.SortName -> {
            "albumArtistSort COLLATE NOCASE"
        }

        SortNameMode.Name -> {
            "albumArtist COLLATE NOCASE"
        }

        SortNameMode.NonLatin -> {
            "(CASE WHEN albumArtist GLOB '*[A-Za-z]*' THEN albumArtist ELSE albumArtistSort END) COLLATE NOCASE"
        }
    }

internal fun artistNameSortKey(mode: SortNameMode): String =
    when (mode) {
        SortNameMode.SortName -> "sortName COLLATE NOCASE"
        SortNameMode.Name -> "name COLLATE NOCASE"
        SortNameMode.NonLatin -> "(CASE WHEN name GLOB '*[A-Za-z]*' THEN name ELSE sortName END) COLLATE NOCASE"
    }

private fun albumOrder(
    sort: AlbumSort,
    descending: Boolean,
    mode: SortNameMode,
): String =
    when (sort) {
        AlbumSort.Name -> {
            val name = albumNameSortKey(mode)
            if (descending) "$name DESC, id DESC" else "$name, id"
        }

        AlbumSort.Artist -> {
            val artist = albumArtistSortKey(mode)
            val name = albumNameSortKey(mode)
            if (descending) "$artist DESC, year DESC, $name DESC, id DESC" else "$artist, year, $name, id"
        }

        AlbumSort.Year -> {
            if (descending) ALBUM_ORDER_BY_YEAR_REVERSED else ALBUM_ORDER_BY_YEAR
        }

        AlbumSort.Added -> {
            if (descending) ALBUM_ORDER_BY_ADDED_REVERSED else ALBUM_ORDER_BY_ADDED
        }

        AlbumSort.Starred -> {
            if (descending) ALBUM_ORDER_BY_STARRED_REVERSED else ALBUM_ORDER_BY_STARRED
        }

        AlbumSort.Frequent -> {
            if (descending) ALBUM_ORDER_BY_FREQUENT_REVERSED else ALBUM_ORDER_BY_FREQUENT
        }

        AlbumSort.Recent -> {
            if (descending) ALBUM_ORDER_BY_RECENT_REVERSED else ALBUM_ORDER_BY_RECENT
        }
    }

private fun artistOrder(
    sort: ArtistSort,
    descending: Boolean,
    mode: SortNameMode,
): String =
    when (sort) {
        ArtistSort.Name -> {
            val name = artistNameSortKey(mode)
            if (descending) "$name DESC, id DESC" else "$name, id"
        }

        ArtistSort.AlbumCount -> {
            if (descending) ARTIST_ORDER_BY_ALBUM_COUNT_REVERSED else ARTIST_ORDER_BY_ALBUM_COUNT
        }

        ArtistSort.Starred -> {
            if (descending) ARTIST_ORDER_BY_STARRED_REVERSED else ARTIST_ORDER_BY_STARRED
        }

        ArtistSort.Frequent -> {
            if (descending) ARTIST_ORDER_BY_FREQUENT_REVERSED else ARTIST_ORDER_BY_FREQUENT
        }

        ArtistSort.Recent -> {
            if (descending) ARTIST_ORDER_BY_RECENT_REVERSED else ARTIST_ORDER_BY_RECENT
        }
    }

private fun playlistOrder(
    sort: PlaylistSort,
    descending: Boolean,
): String =
    when (sort) {
        PlaylistSort.Name -> if (descending) PLAYLIST_ORDER_BY_NAME_REVERSED else PLAYLIST_ORDER_BY_NAME
        PlaylistSort.Added -> if (descending) PLAYLIST_ORDER_BY_ADDED_REVERSED else PLAYLIST_ORDER_BY_ADDED
        PlaylistSort.Updated -> if (descending) PLAYLIST_ORDER_BY_UPDATED_REVERSED else PLAYLIST_ORDER_BY_UPDATED
    }
