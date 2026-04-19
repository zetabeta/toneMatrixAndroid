package ch.checkbit.tonematrix

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central ViewModel for ToneMatrix Redux.
 *
 * Owns the three domain objects and co-ordinates between them:
 *   GridState     — which tiles are on/off per instrument
 *   SynthInstrument × 2 — pre-rendered audio playback (sine + sawtooth)
 *   Sequencer     — drift-correcting timing loop
 *
 * Threading model
 * ───────────────
 * • GridState writes  → main thread only (all public mutating methods).
 * • GridState reads   → sequencer thread reads the immutable [gridTiles] snapshot
 *                       (StateFlow.value), never the live GridState directly.
 * • SynthInstrument.playNote() → Dispatchers.Default (sequencer thread); SoundPool is
 *                       thread-safe so no lock is needed.
 *
 * This mirrors the JS architecture:
 *   ToneMatrix  ↔  this ViewModel  (UI events, URL state)
 *   Grid        ↔  GridState       (data model)
 *   SynthInstrument ↔  SynthInstrument (audio engine)
 *   Tone.Transport  ↔  Sequencer   (timing)
 */
class ToneMatrixViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val WIDTH  = 16
        const val HEIGHT = 16
        private const val NUM_INSTRUMENTS = 2
    }

    // ── Domain objects ────────────────────────────────────────────────────────

    private val gridState = GridState(WIDTH, HEIGHT)

    private val sequencer = Sequencer(gridWidth = WIDTH, bpm = 120f)

    /**
     * Instrument 0 — sine oscillator, 1 s release.
     * Instrument 1 — sawtooth oscillator, 2 s release (orange tint in the UI).
     * Matches the two instruments created in the JS Grid constructor.
     */
    private val instruments = listOf(
        SynthInstrument(
            gridWidth = WIDTH, gridHeight = HEIGHT,
            waveform  = SynthInstrument.Waveform.SINE,
            releaseMs = 1000f,
        ),
        SynthInstrument(
            gridWidth = WIDTH, gridHeight = HEIGHT,
            waveform  = SynthInstrument.Waveform.SAWTOOTH,
            releaseMs = 2000f,
        ),
    )

    // ── Exposed UI state ──────────────────────────────────────────────────────

    /** Current playhead column (0 … WIDTH-1). Driven by Sequencer. */
    val playheadX: StateFlow<Int> = sequencer.playheadX

    /**
     * Per-tile instrument bitmask (one Int per tile, row-major order).
     *   bit 0 set → instrument 0 (sine) note present
     *   bit 1 set → instrument 1 (sawtooth) note present; render with orange tint
     *   0         → tile is off
     *
     * This is the immutable snapshot read by the sequencer and the UI Canvas.
     * Rebuilt atomically on every grid mutation via [commitGridUpdate].
     */
    private val _gridTiles = MutableStateFlow(IntArray(WIDTH * HEIGHT))
    val gridTiles: StateFlow<IntArray> = _gridTiles.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _currentInstrument = MutableStateFlow(0)
    /** Active instrument index (0 = sine, 1 = sawtooth). New tiles are added here. */
    val currentInstrument: StateFlow<Int> = _currentInstrument.asStateFlow()

    /** True while SynthInstrument.initialize() is running. Show a loading indicator. */
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    // ── Initialisation ────────────────────────────────────────────────────────

    private var eventIdCounter = 0
    private fun nextEventId() = ++eventIdCounter

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Both instruments synthesise their PCM buffers concurrently to halve startup time
            instruments
                .map { instrument -> async { instrument.initialize(getApplication()) } }
                .awaitAll()

            _isLoading.value = false
            startSequencer()
        }
    }

    private fun startSequencer() {
        sequencer.start(viewModelScope) { column ->
            if (_isMuted.value) return@start

            // Read the immutable snapshot — safe from Dispatchers.Default
            val tiles = _gridTiles.value

            instruments.forEachIndexed { instrumentId, instrument ->
                val bit = 1 shl instrumentId

                // Count active notes in this column for volume scaling
                val polyphony = (0 until HEIGHT).count { row ->
                    tiles[GridMath.coordToIndex(column, row, HEIGHT)] and bit != 0
                }
                if (polyphony == 0) return@forEachIndexed

                val volume = SynthInstrument.columnVolume(polyphony, HEIGHT)

                for (row in 0 until HEIGHT) {
                    if (tiles[GridMath.coordToIndex(column, row, HEIGHT)] and bit != 0) {
                        instrument.playNote(row, volume)
                    }
                }
            }
        }
    }

    // ── Grid mutation (main thread) ───────────────────────────────────────────

    /**
     * Sets the tile at (x, y) on or off for the currently active instrument.
     * No-op if the tile is already in the requested state.
     *
     * Mirrors JS Grid.setTileValue(). Must be called from the main thread.
     */
    fun setTileValue(x: Int, y: Int, on: Boolean) {
        val instrumentId = _currentInstrument.value
        val tile = gridState.getTile(x, y)
        if (on == tile.hasNote(instrumentId)) return
        if (on) tile.addNote(instrumentId, nextEventId())
        else    tile.removeNote(instrumentId)
        commitGridUpdate()
    }

    /**
     * Returns true if (x, y) currently has a note for the active instrument.
     * Used by the touch handler to determine the arming direction at drag-start
     * (mirrors the JS `arming` variable logic).
     */
    fun getTileValue(x: Int, y: Int): Boolean =
        gridState.hasTileNote(x, y, _currentInstrument.value)

    /** Clears all tiles across all instruments. Mirrors JS Grid.clearAllTiles(). */
    fun clearAll() {
        gridState.clearAllTiles()
        commitGridUpdate()
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    /**
     * Switches the active instrument (0–9 on a hardware keyboard, matching JS).
     * Silently ignores ids outside [0, NUM_INSTRUMENTS).
     */
    fun setCurrentInstrument(id: Int) {
        if (id in 0 until NUM_INSTRUMENTS) _currentInstrument.value = id
    }

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Rebuilds the immutable [_gridTiles] snapshot from the
     * current [gridState]. Must only be called from the main thread.
     *
     * Replacing [_gridTiles].value with a new IntArray is the only write the
     * sequencer thread ever sees — StateFlow guarantees a consistent read.
     */
    private fun commitGridUpdate() {
        val snapshot = IntArray(WIDTH * HEIGHT)
        for (i in snapshot.indices) {
            val tile = gridState.tiles[i]
            var mask = 0
            for (id in 0 until NUM_INSTRUMENTS) {
                if (tile.hasNote(id)) mask = mask or (1 shl id)
            }
            snapshot[i] = mask
        }
        _gridTiles.value = snapshot
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        sequencer.stop()
        instruments.forEach { it.release() }
    }
}
