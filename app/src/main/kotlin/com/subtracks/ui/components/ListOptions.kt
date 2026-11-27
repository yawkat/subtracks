package com.subtracks.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.subtracks.R
import kotlinx.coroutines.flow.Flow

data class SortOption(
    val value: String,
    @param:StringRes val label: Int,
    val descendingByDefault: Boolean = false,
)

private const val LIST_OPTIONS_WEIGHT = 0.11f
private val LIST_OPTIONS_BAR_WIDTHS = listOf(1f, 0.62f, 0.34f)

@Composable
private fun ListOptionsGlyph(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val thickness = size.height * LIST_OPTIONS_WEIGHT
        val gap = size.height / (LIST_OPTIONS_BAR_WIDTHS.size + 1)
        LIST_OPTIONS_BAR_WIDTHS.forEachIndexed { index, widthFraction ->
            val y = gap * (index + 1)
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width * widthFraction, y),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The list options glyph, with a dot when [filtersActive]; the colours are the button's own. */
@Composable
fun ListOptionsIcon(
    filtersActive: Boolean,
    containerColor: Color,
    contentColor: Color,
) {
    val label = stringResource(R.string.list_options)
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        ListOptionsGlyph(
            modifier =
                Modifier
                    .size(24.dp)
                    .semantics { contentDescription = label },
        )
        if (filtersActive) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 1.dp, y = 2.5.dp)
                        .size(11.dp)
                        .background(containerColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(7.dp)
                            .background(contentColor, CircleShape),
                )
            }
        }
    }
}

/**
 * The "Sort by" heading, direction toggle and sort chips. [descending] is relative to each option's
 * natural direction, as stored in a list query.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortSection(
    sort: String,
    descending: Boolean,
    options: List<SortOption>,
    onSortChange: (String) -> Unit,
    onToggleSortDirection: () -> Unit,
) {
    val naturalDescending = options.firstOrNull { it.value == sort }?.descendingByDefault == true
    val shownDescending = if (naturalDescending) !descending else descending
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.sort_by),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleSortDirection) {
            Icon(
                imageVector = if (shownDescending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription =
                    if (shownDescending) {
                        stringResource(R.string.sort_descending)
                    } else {
                        stringResource(R.string.sort_ascending)
                    },
            )
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = sort == option.value,
                onClick = {
                    if (sort == option.value) onToggleSortDirection() else onSortChange(option.value)
                },
                label = { Text(stringResource(option.label)) },
            )
        }
    }
}

/** A list options button that opens a sheet holding just a [SortSection]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortOptionsFab(
    sort: String,
    descending: Boolean,
    options: List<SortOption>,
    onSortChange: (String) -> Unit,
    onToggleSortDirection: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    dismissals: Flow<Unit>? = null,
) {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    if (dismissals != null) DismissOnRequest(dismissals) { showOptions = false }
    FloatingActionButton(
        onClick = { showOptions = true },
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 12.dp),
        modifier = modifier,
    ) {
        ListOptionsIcon(filtersActive = false, containerColor = containerColor, contentColor = contentColor)
    }
    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                SortSection(sort, descending, options, onSortChange, onToggleSortDirection)
            }
        }
    }
}
