package dev.lupine.tonematrix

/**
 * Pure coordinate-math utilities.
 * Mirrors the static methods on the JS Util class that relate to grid geometry.
 */
object GridMath {

    /**
     * Converts a 2-D grid coordinate to a flat array index.
     * coordToIndex(x, y, height) = x * height + y
     */
    fun coordToIndex(x: Int, y: Int, gridHeight: Int): Int = x * gridHeight + y

    /**
     * Converts a flat array index back to (x, y) grid coordinates.
     */
    fun indexToCoord(index: Int, gridHeight: Int): Pair<Int, Int> =
        Pair(index / gridHeight, index % gridHeight)

    /**
     * Maps a pixel position on the canvas to a tile coordinate.
     * Returns null when the position falls outside the grid bounds.
     */
    fun pixelCoordsToTileCoords(
        pixelX: Float,
        pixelY: Float,
        gridWidth: Int,
        gridHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
    ): Pair<Int, Int>? {
        val tileX = (pixelX / (canvasWidth / gridWidth)).toInt()
        val tileY = (pixelY / (canvasHeight / gridHeight)).toInt()
        if (tileX < 0 || tileX >= gridWidth || tileY < 0 || tileY >= gridHeight) return null
        return Pair(tileX, tileY)
    }
}
