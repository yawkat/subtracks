package com.subtracks.playback

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Pauses once [elapsedRealtimeMs][SystemClock.elapsedRealtime] reaches [endsAtMs]. */
    data class Countdown(
        val endsAtMs: Long,
    ) : SleepTimerState

    /** Pauses when the playing track finishes. */
    data object EndOfTrack : SleepTimerState
}

/** The armed sleep timer, shared by the UI that sets it and the playback service that acts on it. */
class SleepTimer(
    val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state

    fun start(durationMs: Long) {
        _state.value = SleepTimerState.Countdown(clock() + durationMs)
    }

    fun startEndOfTrack() {
        _state.value = SleepTimerState.EndOfTrack
    }

    fun cancel() {
        _state.value = SleepTimerState.Off
    }

    fun remainingMs(state: SleepTimerState.Countdown): Long = (state.endsAtMs - clock()).coerceAtLeast(0)

    internal fun expire(state: SleepTimerState) {
        _state.compareAndSet(state, SleepTimerState.Off)
    }
}

/** What [SleepTimerDriver] needs from the player it stops. */
interface SleepTimerTarget {
    /** Whether the player ran out of queue, so no track is left to finish. */
    val isStopped: Boolean

    fun pause()

    fun setPauseAtEndOfItem(enabled: Boolean)
}

/** Runs [timer] against the player in the playback service. */
class SleepTimerDriver(
    private val timer: SleepTimer,
    private val target: SleepTimerTarget,
) {
    /** Runs until [scope] ends, and turns the timer off with it, since nothing would act on it after. */
    fun start(scope: CoroutineScope): Job =
        scope
            .launch {
                timer.state.collectLatest { state ->
                    if (state == SleepTimerState.EndOfTrack && target.isStopped) {
                        timer.expire(state)
                        return@collectLatest
                    }
                    target.setPauseAtEndOfItem(state == SleepTimerState.EndOfTrack)
                    if (state is SleepTimerState.Countdown) {
                        // Short steps, so time the device spent asleep while paused is not added on top.
                        while (true) {
                            val remaining = timer.remainingMs(state)
                            if (remaining <= 0) break
                            delay(remaining.coerceAtMost(MAX_STEP_MS))
                        }
                        target.pause()
                        timer.expire(state)
                    }
                }
            }.also { job -> job.invokeOnCompletion { timer.cancel() } }

    /** The player stopped at the end of a track, by pausing there or by running out of queue. */
    fun onTrackFinished() {
        timer.expire(SleepTimerState.EndOfTrack)
    }

    /**
     * Playback (re)started. A countdown that already ran out, while the device slept with playback
     * paused, is dropped here so its overdue step cannot pause what the user just started.
     */
    fun onPlay() {
        val state = timer.state.value as? SleepTimerState.Countdown ?: return
        if (timer.remainingMs(state) == 0L) timer.expire(state)
    }

    private companion object {
        const val MAX_STEP_MS = 10_000L
    }
}
