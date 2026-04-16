package dev.lupine.tonematrix

import kotlin.math.cos
import kotlin.math.sin

/**
 * Fixed-size particle pool with bounce-off-wall physics.
 *
 * Direct port of JS ParticleSystem.js:
 *   - Pool of [POOL_SIZE] particles stored in flat parallel float arrays
 *     (better cache locality than an array of objects)
 *   - Ring buffer: [oldest] wraps around so the pool never allocates after init
 *   - Delta-time normalised to 60 fps so tempo matches the web version
 *   - Particles bounce off the canvas edges (same sign-flip logic as JS)
 *   - [buildHeatmap] maps live particles onto the tile grid to brighten off-tiles
 *
 * @property width  Canvas width in pixels (update on size change)
 * @property height Canvas height in pixels (update on size change)
 */
class ParticleSystem {

    companion object {
        const val POOL_SIZE = 2000
        const val LIFETIME  = 40f   // frames at 60 fps ≈ 667 ms — matches JS
    }

    var width  = 1f
    var height = 1f

    // Flat parallel arrays — one slot per particle
    private val px   = FloatArray(POOL_SIZE)
    private val py   = FloatArray(POOL_SIZE)
    private val pvx  = FloatArray(POOL_SIZE)
    private val pvy  = FloatArray(POOL_SIZE)
    private val life = FloatArray(POOL_SIZE)

    private var oldest     = 0
    private var lastNanos  = 0L

    // ── Physics ───────────────────────────────────────────────────────────────

    /**
     * Advances all live particles by one time step.
     *
     * [frameNanos] is the Compose frame timestamp from [withFrameNanos].
     * Delta time is normalised so that 16.67 ms (one frame at 60 fps) = dt 1.0,
     * matching the JS `deltaTime = (now - lastUpdate) / 16.67` factor.
     */
    fun update(frameNanos: Long) {
        if (lastNanos == 0L) { lastNanos = frameNanos; return }
        val dt = (frameNanos - lastNanos) / 16_666_667f   // 1 frame @ 60fps in ns
        lastNanos = frameNanos

        for (i in 0 until POOL_SIZE) {
            if (life[i] <= 0f) continue

            // Move
            var nx = px[i] + pvx[i] * dt
            var ny = py[i] + pvy[i] * dt

            // Bounce off horizontal walls (mirrors JS x overflow/underflow)
            if (nx > width || nx < 0f) {
                pvx[i] = -pvx[i]
                nx += pvx[i] * dt
            }
            // Bounce off vertical walls
            if (ny > height || ny < 0f) {
                pvy[i] = -pvy[i]
                ny += pvy[i] * dt
            }

            px[i]   = nx
            py[i]   = ny
            life[i] -= dt
        }
    }

    // ── Particle creation ─────────────────────────────────────────────────────

    /**
     * Emits [count] particles in a uniform circle from ([cx], [cy]) with speed [speed].
     * A random angular offset is added so consecutive bursts don't overlay perfectly.
     * Mirrors JS createParticleBurst().
     */
    fun createParticleBurst(cx: Float, cy: Float, speed: Float, count: Int) {
        val angleOffset = (Math.random() * 2.0 * Math.PI).toFloat()
        val step        = (2.0 * Math.PI / count).toFloat()
        repeat(count) { j ->
            val a = j * step + angleOffset
            px[oldest]   = cx
            py[oldest]   = cy
            pvx[oldest]  = cos(a) * speed
            pvy[oldest]  = sin(a) * speed
            life[oldest] = LIFETIME
            oldest = (oldest + 1) % POOL_SIZE
        }
    }

    // ── Heatmap ───────────────────────────────────────────────────────────────

    /**
     * Returns a per-tile float array where each value is the sum of [life] of all
     * particles whose pixel position falls inside that tile.
     *
     * Used by [GridCanvas] to boost the alpha of off-tiles near a burst — mirroring
     * the JS `getParticleHeatMap()` approach in GridRenderer.
     */
    fun buildHeatmap(gridWidth: Int, gridHeight: Int): FloatArray {
        val map = FloatArray(gridWidth * gridHeight)
        for (i in 0 until POOL_SIZE) {
            if (life[i] <= 0f) continue
            val tile = GridMath.pixelCoordsToTileCoords(
                px[i], py[i], gridWidth, gridHeight, width, height
            ) ?: continue
            val idx = GridMath.coordToIndex(tile.first, tile.second, gridHeight)
            if (idx in map.indices) map[idx] += life[i]
        }
        return map
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Kills all particles and resets the frame clock (e.g. after a pause). */
    fun reset() {
        life.fill(0f)
        lastNanos = 0L
    }
}
