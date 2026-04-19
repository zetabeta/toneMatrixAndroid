package ch.checkbit.tonematrix

/**
 * The 16×16 data model for the sequencer grid.
 *
 * Mirrors the data-management portion of the JS Grid class. Audio scheduling is
 * intentionally absent here — the ViewModel coordinates between GridState and
 * SynthInstrument so that note event IDs can be stored back into each Tile.
 */
class GridState(
    val width: Int = 16,
    val height: Int = 16,
) {
    val tiles = Array(width * height) { Tile() }

    // ── Tile accessors ────────────────────────────────────────────────────────

    fun getTile(x: Int, y: Int): Tile =
        tiles[GridMath.coordToIndex(x, y, height)]

    /** True if the tile has a note for the given instrument. */
    fun hasTileNote(x: Int, y: Int, instrumentId: Int): Boolean =
        getTile(x, y).hasNote(instrumentId)

    /** True if the tile carries any note at all (any instrument). */
    fun isTileOccupied(x: Int, y: Int): Boolean =
        !getTile(x, y).isEmpty()

    // ── Bulk operations ───────────────────────────────────────────────────────

    /** Clears all tile note data. Caller must cancel scheduled audio first. */
    fun clearAllTiles() {
        tiles.forEach { it.removeAllNotes() }
    }
}
