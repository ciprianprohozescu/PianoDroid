package com.pianodroid.app.midi

import com.pianodroid.app.data.*
import java.io.InputStream

/**
 * Minimal SMF (Standard MIDI File) parser supporting formats 0 and 1.
 * Handles tempo map, track names, Note On/Off, and running status.
 */
class MidiParser {
    companion object {
        const val DEFAULT_TEMPO = 500000  // microseconds per quarter (120 BPM)
    }

    fun parse(inputStream: InputStream): Song {
        val bytes = inputStream.readBytes()
        var pos = 0

        // Read header
        val headerChunk = readChunk(bytes, pos)
        pos += 8 + headerChunk.size

        if (!headerChunk.type.contentEquals("MThd".toByteArray())) {
            throw IllegalArgumentException("Invalid MIDI file: missing MThd chunk")
        }

        val format = readShort(headerChunk.data, 0).toInt()
        val numTracks = readShort(headerChunk.data, 2).toInt()
        val ticksPerQuarter = readShort(headerChunk.data, 4).toInt()

        // Read tracks
        val tracks = mutableListOf<Track>()
        var tempoMap = mutableListOf<TempoEvent>()

        for (i in 0 until numTracks) {
            val trackChunk = readChunk(bytes, pos)
            pos += 8 + trackChunk.size

            if (!trackChunk.type.contentEquals("MTrk".toByteArray())) {
                continue
            }

            val trackData = parseTrack(trackChunk.data, ticksPerQuarter, tempoMap, i == 0)
            if (trackData.notes.isNotEmpty()) {
                tracks.add(trackData)
            }
        }

        // Sort tempo map
        tempoMap.sortBy { it.tick }

        // Convert tempo map to absolute time
        var currentTempo = DEFAULT_TEMPO
        var currentTick = 0L
        var currentMs = 0.0

        val convertedTempoMap = mutableListOf<TempoEvent>()

        for (tempoEvent in tempoMap) {
            if (tempoEvent.tick > currentTick) {
                val tickDelta = tempoEvent.tick - currentTick
                val msDelta = (tickDelta * currentTempo) / (ticksPerQuarter * 1000.0)
                currentMs += msDelta
                currentTick = tempoEvent.tick
            }
            currentTempo = tempoEvent.tempo
            convertedTempoMap.add(TempoEvent(
                tick = currentTick,
                tempo = currentTempo
            ))
        }

        return Song(
            format = format,
            ticksPerQuarter = ticksPerQuarter,
            tracks = tracks,
            tempoMap = convertedTempoMap.ifEmpty { listOf(TempoEvent(0, DEFAULT_TEMPO)) }
        )
    }

    private fun parseTrack(
        data: ByteArray,
        ticksPerQuarter: Int,
        tempoMap: MutableList<TempoEvent>,
        isFirstTrack: Boolean
    ): Track {
        var pos = 0
        var trackName = ""
        var currentTempo = DEFAULT_TEMPO           // microseconds per quarter note
        var currentTick = 0L
        var currentMs = 0.0

        val notes = mutableListOf<Note>()
        val activeNotes = mutableMapOf<Int, Long>() // pitch -> startMs
        var runningStatus: Int? = null

        fun addNoteOff(pitch: Int) {
            val startMs = activeNotes.remove(pitch) ?: return
            notes.add(
                Note(
                    pitch = pitch,
                    startMs = startMs,
                    endMs = currentMs.toLong(),
                    velocity = 64
                )
            )
        }

        while (pos < data.size) {
            // 1) Delta-time (VLQ)
            val (tickDelta, afterDeltaPos) = readVarLen(data, pos)
            pos = afterDeltaPos
            currentTick += tickDelta

            // Convert delta ticks -> ms using the tempo that applied BEFORE this event
            val tickDeltaMs = (tickDelta * currentTempo) / (ticksPerQuarter * 1000.0)
            currentMs += tickDeltaMs

            if (pos >= data.size) break

            // 2) Status byte or running status
            val first = data[pos].toInt() and 0xFF
            val hasStatusByte = (first and 0x80) != 0

            val status: Int = if (hasStatusByte) {
                pos += 1
                runningStatus = first
                first
            } else {
                runningStatus ?: break
            }

            // 3) META events (0xFF)  -> running status canceled
            if (status == 0xFF) {
                runningStatus = null
                if (pos >= data.size) break

                val metaType = data[pos].toInt() and 0xFF
                val (len, afterLenPos) = readVarLen(data, pos + 1)
                pos = afterLenPos

                if (pos + len > data.size) break

                when (metaType) {
                    0x03 -> { // Track name
                        val nameBytes = data.copyOfRange(pos, pos + len.toInt())
                        trackName = String(nameBytes)
                    }
                    0x51 -> { // Tempo (3 bytes, microseconds per quarter note)
                        if (len >= 3) {
                            val tempo =
                                ((data[pos].toInt() and 0xFF) shl 16) or
                                        ((data[pos + 1].toInt() and 0xFF) shl 8) or
                                        (data[pos + 2].toInt() and 0xFF)

                            if (isFirstTrack) tempoMap.add(TempoEvent(currentTick, tempo))
                            currentTempo = tempo
                        }
                    }
                }

                pos += len.toInt()
                continue
            }

            // 4) SysEx events (0xF0 / 0xF7) -> running status canceled
            if (status == 0xF0 || status == 0xF7) {
                runningStatus = null
                val (len, afterLenPos) = readVarLen(data, pos)
                pos = afterLenPos + len.toInt()
                continue
            }

            // 5) Channel voice messages
            val eventType = status and 0xF0

            when (eventType) {
                0x80 -> { // Note Off: note, velocity
                    if (pos + 1 >= data.size) break
                    val pitch = data[pos].toInt() and 0xFF
                    // val velocity = data[pos + 1].toInt() and 0xFF
                    pos += 2
                    addNoteOff(pitch)
                }

                0x90 -> { // Note On: note, velocity (velocity 0 => Note Off)
                    if (pos + 1 >= data.size) break
                    val pitch = data[pos].toInt() and 0xFF
                    val velocity = data[pos + 1].toInt() and 0xFF
                    pos += 2

                    if (velocity > 0) {
                        activeNotes[pitch] = currentMs.toLong()
                    } else {
                        addNoteOff(pitch)
                    }
                }

                0xA0, // Poly pressure: note, pressure
                0xB0, // Control change: controller, value
                0xE0  // Pitch bend: lsb, msb
                    -> {
                    if (pos + 1 >= data.size) break
                    pos += 2
                }

                0xC0, // Program change: program
                0xD0  // Channel pressure: pressure
                    -> {
                    if (pos >= data.size) break
                    pos += 1
                }

                else -> {
                    // Unknown/system message in track data: cannot safely advance
                    break
                }
            }
        }

        // Close lingering notes
        val endMs = currentMs.toLong()
        activeNotes.forEach { (pitch, startMs) ->
            notes.add(Note(pitch = pitch, startMs = startMs, endMs = endMs, velocity = 64))
        }

        notes.sortBy { it.startMs }
        return Track(trackName, notes)
    }

    private fun getTempoAtTick(
        tempoMap: List<TempoEvent>,
        tick: Long,
        ticksPerQuarter: Int
    ): Int {
        var currentTempo = DEFAULT_TEMPO
        var currentTick = 0L
        var currentMs = 0.0

        for (event in tempoMap) {
            if (event.tick > tick) break
            if (event.tick > currentTick) {
                val tickDelta = event.tick - currentTick
                val msDelta = (tickDelta * currentTempo) / (ticksPerQuarter * 1000.0)
                currentMs += msDelta
                currentTick = event.tick
            }
            currentTempo = event.tempo
        }
        return currentTempo
    }

    private fun readChunk(bytes: ByteArray, pos: Int): Chunk {
        val type = bytes.copyOfRange(pos, pos + 4)
        val size = readInt(bytes, pos + 4)
        val data = bytes.copyOfRange(pos + 8, pos + 8 + size)
        return Chunk(type, size, data)
    }

    private data class Chunk(val type: ByteArray, val size: Int, val data: ByteArray)

    private fun readShort(bytes: ByteArray, pos: Int): Short {
        return ((bytes[pos].toInt() and 0xFF) shl 8 or (bytes[pos + 1].toInt() and 0xFF)).toShort()
    }

    private fun readInt(bytes: ByteArray, pos: Int): Int {
        return ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
    }

    private fun readVarLen(bytes: ByteArray, pos: Int): Pair<Long, Int> {
        var value: Long = 0
        var currentPos = pos

        while (currentPos < bytes.size) {
            val byte = bytes[currentPos++].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            if ((byte and 0x80) == 0) break
        }

        return Pair(value, currentPos)
    }
}
