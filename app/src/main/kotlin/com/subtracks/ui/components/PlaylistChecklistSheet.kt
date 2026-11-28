package com.subtracks.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtracks.R
import com.subtracks.data.model.Playlist
import com.subtracks.data.repo.PlaylistChanges

const val PLAYLIST_CHECKLIST_TAG = "playlistChecklist"

/** The edits that turn the playlists in [holding] into the boxes the user left in [chosen]. */
fun playlistChanges(
    playlists: List<Playlist>,
    holding: Set<String>,
    chosen: Map<String, Boolean>,
): PlaylistChanges {
    val changed = playlists.filter { playlist -> chosen[playlist.id]?.let { it != playlist.id in holding } == true }
    return PlaylistChanges(
        addTo = changed.filter { chosen[it.id] == true },
        removeFrom = changed.filter { chosen[it.id] == false },
    )
}

/** The star [chosen] in the checklist when it differs from the song's [starred] state, else null. */
fun starChange(
    starred: Boolean,
    chosen: Boolean?,
): Boolean? = chosen?.takeUnless { it == starred }

/**
 * Lists the user's playlists with a box each, checked where the playlist already holds the song
 * [title]. Checking and unchecking only marks the change: closing the sheet hands them all to
 * [onSave] at once, while Cancel drops them.
 *
 * With [starred] set, a Starred row comes first and closing hands a change of it to [onSetStar].
 * Without [playlistsEditable] (offline) the playlist rows only show where the song is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistChecklistSheet(
    title: String,
    playlists: List<Playlist>,
    holding: Set<String>?,
    onSave: (PlaylistChanges) -> Unit,
    onClose: () -> Unit,
    starred: Boolean? = null,
    onSetStar: (Boolean) -> Unit = {},
    playlistsEditable: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    var chosenStar by remember { mutableStateOf<Boolean?>(null) }
    val changes = holding?.let { playlistChanges(playlists, it, chosen) }

    ModalBottomSheet(
        onDismissRequest = {
            changes?.takeUnless { it.isEmpty() }?.let(onSave)
            starred?.let { starChange(it, chosenStar) }?.let(onSetStar)
            onClose()
        },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag(PLAYLIST_CHECKLIST_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.add_to_playlist), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.actions_cancel))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            if (starred != null) {
                item(key = "starred") {
                    val checked = chosenStar ?: starred
                    ChecklistRow(
                        stringResource(R.string.playlist_checklist_starred),
                        checked,
                        enabled = true,
                        leading = { Icon(Icons.Rounded.Star, contentDescription = null) },
                    ) { chosenStar = !checked }
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                val checked = chosen[playlist.id] ?: (holding?.contains(playlist.id) == true)
                ChecklistRow(playlist.name, checked, enabled = holding != null && playlistsEditable) {
                    chosen[playlist.id] = !checked
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ChecklistRow(
    name: String,
    checked: Boolean,
    enabled: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = leading,
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
    )
}
