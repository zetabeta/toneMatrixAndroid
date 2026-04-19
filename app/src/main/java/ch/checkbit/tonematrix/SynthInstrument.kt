package ch.checkbit.tonematrix

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Pre-renders all 16 pitches of the pentatonic scale as PCM audio and plays them
 * via SoundPool on demand.
 *
 * Mirrors the JS SynthInstrument class. Instead of Tone.js offline rendering +
 * Transport scheduling, notes are synthesized into WAV files at initialization
 * time and loaded into SoundPool. Playback is then triggered immediately by the
 * sequencer loop (Step 5) rather than via a scheduled transport callback.
 *
 * Two instances are created by the ViewModel — one SINE (instrument 0) and one
 * SAWTOOTH (instrument 1) — matching the two instruments in the JS Grid class.
 */
class SynthInstrument(
    private val gridWidth: Int = 16,
    private val gridHeight: Int = 16,
    val waveform: Waveform = Waveform.SINE,
    private val filterCutoffHz: Float = 1100f,
    private val attackMs: Float = 5f,
    private val decayMs: Float = 100f,
    private val sustainLevel: Float = 0.3f,
    private val releaseMs: Float = 1000f,
) {
    enum class Waveform { SINE, SAWTOOTH }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BPM = 120f
        private val PENTATONIC = listOf("B#", "D", "F", "G", "A")
        private const val BASE_OCTAVE = 3
        private const val OCTAVE_OFFSET = 4

        /**
         * Builds the pentatonic scale frequency array for [gridHeight] rows.
         * Row 0 = highest pitch (top of grid), row (n-1) = lowest.
         *
         * Mirrors the JS scale-building loop:
         *   scale[i] = pentatonic[i%5] + (octave + floor((i+4)/5))
         *   then scale.reverse()
         */
        fun buildScale(gridHeight: Int): List<Float> =
            (0 until gridHeight)
                .map { i ->
                    val note = PENTATONIC[i % PENTATONIC.size]
                    val octave = BASE_OCTAVE + (i + OCTAVE_OFFSET) / PENTATONIC.size
                    noteToHz(note, octave)
                }
                .reversed()

        /**
         * Converts a note name + octave to Hz using equal temperament (A4 = 440 Hz).
         * B# is treated as C of the *next* octave (B#3 = C4 = 261.63 Hz), matching
         * how Tone.js resolves the enharmonic spelling.
         */
        private fun noteToHz(name: String, octave: Int): Float {
            // semitone = offset from C within the octave; octaveShift handles B# → C(n+1)
            val (semitone, octaveShift) = when (name) {
                "C"  -> Pair(0, 0)
                "B#" -> Pair(0, 1)   // enharmonic: B#n = C(n+1)
                "D"  -> Pair(2, 0)
                "E"  -> Pair(4, 0)
                "F"  -> Pair(5, 0)
                "G"  -> Pair(7, 0)
                "A"  -> Pair(9, 0)
                "B"  -> Pair(11, 0)
                else -> Pair(0, 0)
            }
            // Standard MIDI: C-1=0, C0=12, C4=60, A4=69
            val midi = (octave + octaveShift + 1) * 12 + semitone
            return 440f * 2f.pow((midi - 69) / 12f)
        }

        /** Converts dB to a linear amplitude in [0, 1]. */
        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

        /**
         * Returns the SoundPool playback volume for a note in a column that has
         * [polyphonyCount] active notes. Mirrors the JS volume scaling:
         *   highVolume = −10 dB (one note playing)
         *   lowVolume  = −20 dB (all [gridHeight] notes playing)
         */
        fun columnVolume(polyphonyCount: Int, gridHeight: Int): Float {
            val volumeDb =
                ((gridHeight - polyphonyCount).toFloat() / gridHeight) * 10f - 20f
            return dbToLinear(volumeDb)
        }
    }

    /** The 16 note frequencies, index 0 = highest (top row). */
    val scale: List<Float> = buildScale(gridHeight)

    // noteOffset = (1 measure / gridWidth) × 6  — same formula as the JS
    // At 120 BPM: (2s / 16) × 6 = 0.75 s per note buffer
    private val noteOffsetSec: Float = (60f / BPM * 4f / gridWidth) * 6f
    private val noteOffsetSamples: Int = (noteOffsetSec * SAMPLE_RATE).toInt()

    // One step duration: 1 measure / gridWidth. The note is triggered for this
    // long before the release phase begins (mirrors triggerAttackRelease duration).
    // At 120 BPM: 2s / 16 = 0.125 s
    private val stepSamples: Int = (60f / BPM * 4f / gridWidth * SAMPLE_RATE).toInt()

    private var soundPool: SoundPool? = null
    private val soundIds = IntArray(gridHeight) { -1 }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Synthesises all [gridHeight] notes and loads them into SoundPool.
     * Suspends until every sample has finished loading — call this once from a
     * coroutine (e.g. viewModelScope.launch(Dispatchers.IO)) before using [playNote].
     */
    suspend fun initialize(context: Context) {
        val pool = SoundPool.Builder()
            .setMaxStreams(gridHeight * 3) // 3 concurrent voices per pitch (mirrors JS numVoices=3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool = pool

        val pending = mutableMapOf<Int, CompletableDeferred<Unit>>()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) pending[sampleId]?.complete(Unit)
            else pending[sampleId]?.completeExceptionally(
                RuntimeException("SoundPool failed to load sample $sampleId (status=$status)")
            )
        }

        val tempFiles = mutableListOf<File>()
        for (row in 0 until gridHeight) {
            val pcm = synthesizeNote(scale[row])
            val file = writeWav(context, pcm, "${waveform.name.lowercase()}_$row")
            tempFiles.add(file)
            val deferred = CompletableDeferred<Unit>()
            val sid = pool.load(file.path, 1)
            pending[sid] = deferred
            soundIds[row] = sid
        }

        // Suspend until every sample is ready
        pending.values.forEach { it.await() }

        // WAV files are no longer needed once decoded into SoundPool memory
        tempFiles.forEach { it.delete() }
    }

    /**
     * Immediately plays the note at [row] (0 = top/highest) at [volume] (0.0–1.0).
     * Thread-safe; safe to call from the sequencer loop coroutine.
     */
    fun playNote(row: Int, volume: Float) {
        val sid = soundIds.getOrElse(row) { -1 }
        if (sid > 0) soundPool?.play(sid, volume, volume, 1, 0, 1f)
    }

    /** Releases all SoundPool resources. Call from ViewModel.onCleared(). */
    fun release() {
        soundPool?.release()
        soundPool = null
    }

    // ── PCM synthesis ─────────────────────────────────────────────────────────

    private fun synthesizeNote(frequency: Float): FloatArray {
        val samples = FloatArray(noteOffsetSamples)

        // 1. Raw waveform generation
        for (i in samples.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            samples[i] = when (waveform) {
                Waveform.SINE     -> sin(2f * PI.toFloat() * frequency * t)
                Waveform.SAWTOOTH -> 2f * ((frequency * t) % 1f) - 1f
            }
        }

        // 2. 2nd-order Butterworth low-pass filter at filterCutoffHz
        //    Mirrors Tone.Filter({ frequency: 1100, rolloff: -12 })
        applyBiquadLowPass(samples)

        // 3. ADSR envelope
        applyEnvelope(samples)

        return samples
    }

    /**
     * In-place 2nd-order Butterworth low-pass biquad filter.
     * Q = 1/√2 gives a maximally-flat (Butterworth) response, equivalent to
     * Tone.js Filter rolloff −12 dB/octave.
     */
    private fun applyBiquadLowPass(samples: FloatArray) {
        val w0    = 2f * PI.toFloat() * filterCutoffHz / SAMPLE_RATE
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2f * (sqrt(2f) / 2f))   // sin(w0)/(2Q), Q=1/√2
        val a0inv = 1f / (1f + alpha)

        val b0 =  (1f - cosW0) / 2f * a0inv
        val b1 =  (1f - cosW0)       * a0inv
        val b2 =  (1f - cosW0) / 2f * a0inv
        val a1 = (-2f * cosW0)       * a0inv
        val a2 =  (1f - alpha)       * a0inv

        var x1 = 0f; var x2 = 0f
        var y1 = 0f; var y2 = 0f

        for (i in samples.indices) {
            val x0 = samples[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            samples[i] = y0
        }
    }

    /**
     * In-place ADSR envelope.
     *
     * The synth is triggered for [stepSamples] (one column's time-slice), after
     * which the release phase begins — mirroring how Tone.Synth.triggerAttackRelease
     * is used in the JS offline render with duration = 1m/gridWidth.
     */
    private fun applyEnvelope(samples: FloatArray) {
        val attackEnd    = (attackMs  / 1000f * SAMPLE_RATE).toInt()
        val decayEnd     = attackEnd + (decayMs / 1000f * SAMPLE_RATE).toInt()
        val releaseStart = maxOf(decayEnd, stepSamples)
        val releaseEnd   = releaseStart + (releaseMs / 1000f * SAMPLE_RATE).toInt()

        for (i in samples.indices) {
            val env: Float = when {
                i < attackEnd    -> if (attackEnd == 0) 1f else i.toFloat() / attackEnd
                i < decayEnd     -> {
                    val t = (i - attackEnd).toFloat() / (decayEnd - attackEnd)
                    1f + t * (sustainLevel - 1f)           // lerp 1.0 → sustainLevel
                }
                i < releaseStart -> sustainLevel
                i < releaseEnd   -> {
                    val t = (i - releaseStart).toFloat() / (releaseEnd - releaseStart)
                    sustainLevel * (1f - t)                // linear release → 0
                }
                else             -> 0f
            }
            samples[i] *= env
        }
    }

    // ── WAV file writing ──────────────────────────────────────────────────────

    /**
     * Writes [pcm] (float32 in −1..1) as a 16-bit mono PCM WAV to a cache file.
     * The file is only needed long enough for SoundPool.load() to read it.
     */
    private fun writeWav(context: Context, pcm: FloatArray, name: String): File {
        val file = File(context.cacheDir, "tm_$name.wav")
        val dataBytes = pcm.size * 2   // 16-bit mono → 2 bytes per sample

        file.outputStream().buffered().use { out ->
            out.writeAscii("RIFF")
            out.writeInt32LE(dataBytes + 36)   // file size − 8
            out.writeAscii("WAVE")
            // fmt chunk
            out.writeAscii("fmt ")
            out.writeInt32LE(16)               // PCM subchunk size
            out.writeInt16LE(1)                // AudioFormat = PCM
            out.writeInt16LE(1)                // NumChannels = mono
            out.writeInt32LE(SAMPLE_RATE)
            out.writeInt32LE(SAMPLE_RATE * 2)  // ByteRate = SR × channels × bits/8
            out.writeInt16LE(2)                // BlockAlign
            out.writeInt16LE(16)               // BitsPerSample
            // data chunk
            out.writeAscii("data")
            out.writeInt32LE(dataBytes)

            val buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(buf.array())
        }
        return file
    }

    private fun OutputStream.writeAscii(s: String) =
        write(s.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeInt32LE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun OutputStream.writeInt16LE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
