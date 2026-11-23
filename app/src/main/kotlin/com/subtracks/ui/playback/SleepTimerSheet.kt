package com.subtracks.ui.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtracks.R
import com.subtracks.data.prefs.MAX_SLEEP_TIMER_MINUTES
import com.subtracks.data.prefs.MIN_SLEEP_TIMER_MINUTES
import com.subtracks.data.prefs.SLEEP_TIMER_STEP_MINUTES
import com.subtracks.playback.SleepTimer
import com.subtracks.playback.SleepTimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

const val SLEEP_TIMER_SHEET_TAG = "sleepTimerSheet"
const val SLEEP_TIMER_SLIDER_TAG = "sleepTimerSlider"

/** What the Now Playing screen shows of the sleep timer. */
sealed interface SleepTimerStatus {
    data object Off : SleepTimerStatus

    data object EndOfTrack : SleepTimerStatus

    data class Remaining(
        val ms: Long,
    ) : SleepTimerStatus
}

/** The short state shown next to the sleep timer entry, or null while it is off. */
@Composable
fun sleepTimerLabel(status: SleepTimerStatus): String? =
    when (status) {
        SleepTimerStatus.Off -> null
        SleepTimerStatus.EndOfTrack -> stringResource(R.string.sleep_timer_end_of_track)
        is SleepTimerStatus.Remaining -> formatTime(status.ms + 999)
    }

/** [sleepTimer]'s state, ticking once a second while a countdown runs. */
@OptIn(ExperimentalCoroutinesApi::class)
fun sleepTimerStatus(sleepTimer: SleepTimer): Flow<SleepTimerStatus> =
    sleepTimer.state.transformLatest { timer ->
        when (timer) {
            SleepTimerState.Off -> {
                emit(SleepTimerStatus.Off)
            }

            SleepTimerState.EndOfTrack -> {
                emit(SleepTimerStatus.EndOfTrack)
            }

            is SleepTimerState.Countdown -> {
                while (true) {
                    val remaining = sleepTimer.remainingMs(timer)
                    emit(SleepTimerStatus.Remaining(remaining))
                    if (remaining <= 0) break
                    delay((remaining - 1) % 1_000 + 1)
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(
    status: SleepTimerStatus,
    initialMinutes: Int,
    onMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag(SLEEP_TIMER_SHEET_TAG),
    ) {
        Text(
            text = stringResource(R.string.sleep_timer),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        var minutes by remember(initialMinutes) { mutableIntStateOf(initialMinutes) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        ) {
            Icon(Icons.Rounded.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            FilledTonalButton(onClick = { onMinutes(minutes) }) {
                Text(stringResource(R.string.sleep_timer_start))
            }
        }
        Slider(
            value = minutes.toFloat(),
            onValueChange = { minutes = sleepTimerStep(it) },
            valueRange = MIN_SLEEP_TIMER_MINUTES.toFloat()..MAX_SLEEP_TIMER_MINUTES.toFloat(),
            steps = (MAX_SLEEP_TIMER_MINUTES - MIN_SLEEP_TIMER_MINUTES) / SLEEP_TIMER_STEP_MINUTES - 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(SLEEP_TIMER_SLIDER_TAG),
        )
        SleepTimerOption(
            text = stringResource(R.string.sleep_timer_end_of_track),
            icon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
            selected = status == SleepTimerStatus.EndOfTrack,
            onClick = onEndOfTrack,
        )
        if (status != SleepTimerStatus.Off) {
            SleepTimerOption(
                text = stringResource(R.string.sleep_timer_off),
                icon = { Icon(Icons.Rounded.TimerOff, contentDescription = null) },
                onClick = onCancel,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

internal fun sleepTimerStep(value: Float): Int =
    (Math.round(value / SLEEP_TIMER_STEP_MINUTES) * SLEEP_TIMER_STEP_MINUTES)
        .coerceIn(MIN_SLEEP_TIMER_MINUTES, MAX_SLEEP_TIMER_MINUTES)

@Composable
private fun SleepTimerOption(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = icon,
        trailingContent = if (selected) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
