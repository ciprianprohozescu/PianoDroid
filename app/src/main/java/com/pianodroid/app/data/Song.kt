package com.pianodroid.app.data

data class Song(
    val format: Int,  // MIDI format (0 or 1)
    val ticksPerQuarter: Int,
    val tracks: List<Track>,
    val tempoMap: List<TempoEvent>  // tempo changes over time
) {
    fun getDurationMs(): Long {
        val lastNoteEnd = tracks.flatMap { it.notes }.maxOfOrNull { it.endMs } ?: 0L
        return lastNoteEnd
    }
}

data class Track(
    val name: String = "",
    val notes: List<Note>
)

data class TempoEvent(
    val tick: Long,
    val tempo: Int  // microseconds per quarter note
) {
    fun toBpm(): Double = 60000000.0 / tempo
}
