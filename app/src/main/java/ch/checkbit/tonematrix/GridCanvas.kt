package ch.checkbit.tonematrix

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity


private const val SAWTOOTH_BIT = 2

private const val W = ToneMatrixViewModel.WIDTH
private const val H = ToneMatrixViewModel.HEIGHT

/**
 * Draws the 16×16 ToneMatrix grid with full touch interaction and particle effects.
 *
 * Rendering — mirrors JS GridRenderer.draw() exactly:
 *   Off tile   (sprite 0): alpha ≈ 0.2, no blur, brightened by particle heatmap
 *   Armed tile (sprite 1): alpha 0.85, blur 1 dp
 *   Active tile (sprite 2, playhead col): alpha 1.0, blur 2 dp
 *   Sawtooth tiles: orange tint instead of white (same geometry)
 *   Hovered tile: alpha 0.3, no blur
 *
 * Touch interaction — mirrors JS arming logic:
 *   Finger-down determines arming direction (arm if tile was off, disarm if on).
 *   All subsequent tiles visited in the same drag stroke get the same treatment.
 *
 * Particles — mirrors JS ParticleSystem + GridRenderer burst logic:
 *   A burst of 20 particles fires from every active tile in the playhead column
 *   whenever the playhead advances. Particles drift, bounce off edges, and
 *   decay over ~667 ms, illuminating nearby off-tiles via a heatmap.
 *
 * @param tiles          Bitmask per tile (bit 0 = sine, bit 1 = sawtooth)
 * @param playheadX      Current playhead column
 * @param onTileChanged  Called when a tile is toggled by touch (x, y, newOnState)
 * @param getTileValue   Returns current on/off state for the active instrument
 */
@Composable
fun GridCanvas(
    tiles: IntArray,
    playheadX: Int,
    modifier: Modifier = Modifier,
    onTileChanged: (x: Int, y: Int, on: Boolean) -> Unit = { _, _, _ -> },
    getTileValue: (x: Int, y: Int) -> Boolean = { _, _ -> false },
) {
    val density = LocalDensity.current.density
    val colorSine = MaterialTheme.colorScheme.primary
    val colorSaw  = MaterialTheme.colorScheme.secondary
    val backgroundColor = MaterialTheme.colorScheme.background

    // BlurMaskFilter instances — cached per density, never recreated mid-session
    val blurActive = remember(density) { BlurMaskFilter(2f * density, BlurMaskFilter.Blur.NORMAL) }
    val blurArmed  = remember(density) { BlurMaskFilter(1f * density, BlurMaskFilter.Blur.NORMAL) }

    // Single Paint reused across every tile in the draw loop
    val paint = remember { Paint() }
    val fp    = remember(paint) { paint.asFrameworkPaint() }

    // ── Particle system ───────────────────────────────────────────────────────

    val particles   = remember { ParticleSystem() }
    var canvasSize  by remember { mutableStateOf(Size(1f, 1f)) }
    var frameNanos  by remember { mutableLongStateOf(0L) }

    // Capture the latest tiles for use inside LaunchedEffect(playheadX)
    val latestTiles = rememberUpdatedState(tiles)

    // Drive particle physics at the display frame rate
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { t ->
                particles.update(t)
                frameNanos = t   // writing state triggers Canvas recomposition → redraw
            }
        }
    }

    // Fire particle bursts whenever the playhead advances to a new column.
    // Mirrors: GridRenderer.draw() → if (playheadX !== lastPlayheadX) createParticleBurst(...)
    LaunchedEffect(playheadX) {
        val tileW = canvasSize.width  / W
        val tileH = canvasSize.height / H
        for (row in 0 until H) {
            val idx = GridMath.coordToIndex(playheadX, row, H)
            if (latestTiles.value.getOrElse(idx) { 0 } != 0) {
                particles.createParticleBurst(
                    cx    = tileW * (playheadX + 0.5f),
                    cy    = tileH * (row + 0.5f),
                    speed = 8f * density,   // matches JS: 8 * dpr pixels/frame
                    count = 20,
                )
            }
        }
    }

    // ── Touch / hover state ───────────────────────────────────────────────────

    var hoveredTile by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // ── Canvas ────────────────────────────────────────────────────────────────

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(backgroundColor)
            // Offscreen compositing ensures BlurMaskFilter is applied correctly:
            // each tile's blur and alpha are resolved into the offscreen buffer
            // before the layer is composited onto the screen, preventing blur
            // from leaking through semi-transparent layers incorrectly.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            // Capture pixel size so particle coordinates and touch hit-tests use the
            // same coordinate space as the Canvas draw scope
            .onSizeChanged { size ->
                canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                particles.width  = size.width.toFloat()
                particles.height = size.height.toFloat()
            }
            // Touch: finger-down sets arming direction; drag paints/erases tiles
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var arming: Boolean? = null

                    fun process(x: Float, y: Float) {
                        val tile = GridMath.pixelCoordsToTileCoords(
                            x, y, W, H,
                            size.width.toFloat(), size.height.toFloat(),
                        ) ?: return
                        hoveredTile = tile
                        // Determine arming direction on first contact (mirrors JS `arming` var)
                        if (arming == null) arming = !getTileValue(tile.first, tile.second)
                        onTileChanged(tile.first, tile.second, arming)
                    }

                    process(down.position.x, down.position.y)

                    drag(down.id) { change ->
                        change.consume()
                        process(change.position.x, change.position.y)
                    }

                    hoveredTile = null
                }
            },
    ) {
        // Reading frameNanos subscribes this draw scope to the animation LaunchedEffect,
        // ensuring the Canvas redraws every display frame while particles are alive.
        @Suppress("UNUSED_EXPRESSION")
        frameNanos

        val tileW    = size.width  / W
        val tileH    = size.height / H
        val dp       = this.density
        val heatmap  = particles.buildHeatmap(W, H)

        drawIntoCanvas { canvas ->
            for (col in 0 until W) {
                for (row in 0 until H) {
                    val idx  = GridMath.coordToIndex(col, row, H)
                    val mask = tiles.getOrElse(idx) { 0 }

                    val isOn       = mask != 0
                    val isSawtooth = (mask and SAWTOOTH_BIT) != 0
                    val isActive   = col == playheadX
                    val isHovered  = hoveredTile?.first == col && hoveredTile?.second == row

                    val left = col * tileW
                    val top  = row * tileH

                    // ── Tile state → visual params ────────────────────────────
                    val alpha: Float
                    val margin: Float

                    when {
                        isOn && isActive -> {
                            // Sprite 2: brightest, most blur, full opacity
                            alpha  = 1f
                            margin = 2f * dp
                            fp.maskFilter = blurActive
                        }
                        isOn -> {
                            // Sprite 1: armed, slight glow
                            alpha  = 0.85f
                            margin = 3f * dp
                            fp.maskFilter = blurArmed
                        }
                        isHovered -> {
                            // Highlight tile under finger
                            alpha  = 0.3f
                            margin = 4f * dp
                            fp.maskFilter = null
                        }
                        else -> {
                            // Sprite 0: dim base + particle heat boost
                            // Mirrors JS: (heatmap[i] * 0.05 * (204/255) / LIFETIME) + 51/255
                            val heat = heatmap.getOrElse(idx) { 0f }
                            alpha  = (heat * 0.05f * (204f / 255f) / ParticleSystem.LIFETIME) +
                                     (51f / 255f)
                            margin = 4f * dp
                            fp.maskFilter = null
                        }
                    }

                    paint.color = if (isSawtooth) colorSaw else colorSine
                    paint.alpha = alpha

                    canvas.drawRoundRect(
                        left    = left  + margin,
                        top     = top   + margin,
                        right   = left  + tileW - margin,
                        bottom  = top   + tileH - margin,
                        radiusX = 2f * dp,
                        radiusY = 2f * dp,
                        paint   = paint,
                    )
                }
            }
        }
    }
}
