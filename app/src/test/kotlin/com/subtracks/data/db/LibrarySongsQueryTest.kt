package com.subtracks.data.db

import com.subtracks.data.prefs.SongSort
import com.subtracks.data.prefs.SortNameMode
import com.subtracks.data.prefs.StarredFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySongsQueryTest {
    @Test
    fun aQuerySurvivesEncodingIncludingSeparatorsInTheSearch() {
        val query =
            LibrarySongsQuery(
                sort = SongSort.Added,
                descending = true,
                starred = StarredFilter.NotStarred,
                downloaded = true,
                search = "a|b|c",
                sortNameMode = SortNameMode.NonLatin,
            )

        assertEquals(query, LibrarySongsQuery.decode(query.encode()))
    }

    @Test
    fun anUnreadableReferenceFallsBackToTheDefaultList() {
        assertEquals(LibrarySongsQuery(), LibrarySongsQuery.decode(""))
    }
}
