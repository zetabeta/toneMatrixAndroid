package ch.checkbit.tonematrix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A drift-correcting step sequencer that advances a playhead across [gridWidth]
 * columns at a tempo of [bpm] beats per minute.
 *
 * Mirrors the role of Tone.Transport in the JS version. The key differences:
 *  - Tone.Transport uses the Web Audio clock (sample-accurate); this uses a
 *    coroutine loop on Dispatchers.Default with nanosecond-based drift correction.
 *  - Tone.Transport fires pre-registered callbacks; this calls [onStep] directly,
 *    so the ViewModel (Step 6) decides which notes to play each column.
 *
 * Timing strategy — drift-correcting absolute deadlines:
 *   Each step's target time is [stepDurationNs] after the *previous* deadline,
 *   never after "now". This means short over-sleeps in delay() don't accumulate
 *   into a gradually drifting tempo.
 */
class Sequencer(
    val gridWidth: Int = 16,
    val bpm: Float = 120f,
) {
    /**
     * Step duration in nanoseconds.
     *
     * Formula: (60 s × 4 beats/measure) / (BPM × steps/measure)
     * At 120 BPM, 16 steps: 240 / 1920 = 0.125 s = 125 000 000 ns
     */
    val stepDurationNs: Long =
        (60_000_000_000.0 * 4.0 / (bpm * gridWidth)).toLong()

    val stepDurationMs: Long = stepDurationNs / 1_000_000L

    private val _playheadX = MutableStateFlow(0)

    /**
     * The column the playhead is currently on (0 … gridWidth-1).
     * Updated on every step tick; collect from the UI layer to drive rendering.
     */
    val playheadX: StateFlow<Int> = _playheadX.asStateFlow()

    private var job: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the sequencer loop inside [scope].
     *
     * [onStep] is invoked on [Dispatchers.Default] once per step with the column
     * index that is about to play. It must complete well within [stepDurationMs]
     * (SoundPool.play() returns instantly, so this is easy to satisfy).
     *
     * Calling [start] while already running cancels the previous loop first.
     */
    fun start(scope: CoroutineScope, onStep: (column: Int) -> Unit) {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            // Anchor the first deadline to "now" so we play the first column
            // immediately rather than waiting a full step before the first sound.
            var nextStepNs = System.nanoTime()

            while (isActive) {
                // Sleep only the remaining time until the next deadline.
                // If we're already past it (e.g. the callback ran long) we skip
                // the sleep and play immediately — the *next* deadline is still
                // stepDurationNs after the one we just missed, so tempo is preserved.
                val sleepMs = (nextStepNs - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0L) delay(sleepMs)

                if (!isActive) break

                val column = _playheadX.value
                onStep(column)
                _playheadX.value = (column + 1) % gridWidth

                nextStepNs += stepDurationNs
            }
        }
    }

    /**
     * Stops the sequencer loop and resets the playhead to column 0.
     * Safe to call even if the sequencer is not running.
     */
    fun stop() {
        job?.cancel()
        job = null
        _playheadX.value = 0
    }
}
