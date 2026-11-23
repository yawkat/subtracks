package com.subtracks.playback

import com.subtracks.ui.playback.sleepTimerStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class FakeTarget : SleepTimerTarget {
        override var isStopped = false
        var pauses = 0
        var pausesAtEndOfItem = false

        override fun pause() {
            pauses++
        }

        override fun setPauseAtEndOfItem(enabled: Boolean) {
            pausesAtEndOfItem = enabled
        }
    }

    private fun TestScope.driven(block: TestScope.(SleepTimer, SleepTimerDriver, FakeTarget) -> Unit) {
        val timer = SleepTimer { testScheduler.currentTime }
        val target = FakeTarget()
        val driver = SleepTimerDriver(timer, target)
        driver.start(backgroundScope)
        runCurrent()
        block(timer, driver, target)
    }

    @Test
    fun aCountdownPausesWhenItRunsOut() =
        runTest {
            driven { timer, _, target ->
                timer.start(15 * 60_000L)
                runCurrent()

                advanceTimeBy(15 * 60_000L - 1)
                runCurrent()
                assertEquals(0, target.pauses)

                advanceTimeBy(1)
                runCurrent()
                assertEquals(1, target.pauses)
                assertEquals(SleepTimerState.Off, timer.state.value)
            }
        }

    @Test
    fun aCancelledCountdownNeverPauses() =
        runTest {
            driven { timer, _, target ->
                timer.start(5 * 60_000L)
                runCurrent()
                advanceTimeBy(60_000L)
                timer.cancel()
                runCurrent()

                advanceTimeBy(10 * 60_000L)
                runCurrent()
                assertEquals(0, target.pauses)
            }
        }

    @Test
    fun restartingTheCountdownMovesTheDeadline() =
        runTest {
            driven { timer, _, target ->
                timer.start(5 * 60_000L)
                runCurrent()
                advanceTimeBy(4 * 60_000L)
                timer.start(5 * 60_000L)
                runCurrent()

                advanceTimeBy(2 * 60_000L)
                runCurrent()
                assertEquals(0, target.pauses)

                advanceTimeBy(3 * 60_000L)
                runCurrent()
                assertEquals(1, target.pauses)
            }
        }

    @Test
    fun endOfTrackPausesAtTheEndOfTheItemOnce() =
        runTest {
            driven { timer, driver, target ->
                timer.startEndOfTrack()
                runCurrent()
                assertTrue(target.pausesAtEndOfItem)

                driver.onTrackFinished()
                runCurrent()
                assertEquals(SleepTimerState.Off, timer.state.value)
                assertFalse(target.pausesAtEndOfItem)
            }
        }

    @Test
    fun aFinishedTrackLeavesACountdownRunning() =
        runTest {
            driven { timer, driver, target ->
                timer.start(5 * 60_000L)
                runCurrent()

                driver.onTrackFinished()
                runCurrent()
                assertTrue(timer.state.value is SleepTimerState.Countdown)
                assertFalse(target.pausesAtEndOfItem)
            }
        }

    @Test
    fun cancellingEndOfTrackStopsPausingAtTheEndOfItems() =
        runTest {
            driven { timer, _, target ->
                timer.startEndOfTrack()
                runCurrent()
                timer.cancel()
                runCurrent()

                assertFalse(target.pausesAtEndOfItem)
            }
        }

    @Test
    fun playingAfterAnOverdueCountdownDoesNotPause() =
        runTest {
            var now = 0L
            val timer = SleepTimer { now }
            val target = FakeTarget()
            val driver = SleepTimerDriver(timer, target)
            driver.start(backgroundScope)
            timer.start(5 * 60_000L)
            runCurrent()

            now += 60 * 60_000L
            driver.onPlay()
            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(0, target.pauses)
            assertEquals(SleepTimerState.Off, timer.state.value)
        }

    @Test
    fun playingWithTimeLeftKeepsTheCountdown() =
        runTest {
            driven { timer, driver, target ->
                timer.start(5 * 60_000L)
                runCurrent()
                driver.onPlay()
                advanceTimeBy(5 * 60_000L)
                runCurrent()

                assertEquals(1, target.pauses)
            }
        }

    @Test
    fun endOfTrackIsDroppedWhenTheQueueHasAlreadyEnded() =
        runTest {
            driven { timer, _, target ->
                target.isStopped = true
                timer.startEndOfTrack()
                runCurrent()

                assertEquals(SleepTimerState.Off, timer.state.value)
                assertFalse(target.pausesAtEndOfItem)
            }
        }

    @Test
    fun stoppingTheDriverTurnsTheTimerOff() =
        runTest {
            val timer = SleepTimer { testScheduler.currentTime }
            val job = SleepTimerDriver(timer, FakeTarget()).start(backgroundScope)
            timer.start(5 * 60_000L)
            runCurrent()

            job.cancel()
            runCurrent()

            assertEquals(SleepTimerState.Off, timer.state.value)
        }

    @Test
    fun sleepTimerStepsSnapToFiveMinutes() {
        assertEquals(5, sleepTimerStep(0f))
        assertEquals(15, sleepTimerStep(13.2f))
        assertEquals(10, sleepTimerStep(12.4f))
        assertEquals(120, sleepTimerStep(500f))
    }
}
