package ch.checkbit.tonematrix

/**
 * A single cell in the sequencer grid.
 *
 * Stores one scheduled audio event ID per instrument (instrumentId -> eventId).
 * Event IDs are assigned externally by the ViewModel + SynthInstrument and stored
 * here so they can be retrieved later to unschedule the note.
 *
 * Mirrors the JS Tile class exactly.
 */
class Tile {

    // instrumentId -> scheduled audio event ID
    private val notes = mutableMapOf<Int, Int>()

    fun isEmpty(): Boolean = notes.isEmpty()

    fun hasNote(instrumentId: Int): Boolean = notes.containsKey(instrumentId)

    fun getNote(instrumentId: Int): Int? = notes[instrumentId]

    fun addNote(instrumentId: Int, eventId: Int) {
        notes[instrumentId] = eventId
    }

    fun removeNote(instrumentId: Int) {
        notes.remove(instrumentId)
    }

    fun removeAllNotes() {
        notes.clear()
    }
}
