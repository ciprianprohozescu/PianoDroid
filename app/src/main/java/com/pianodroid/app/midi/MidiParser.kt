package com.pianodroid.app.midi

import com.pianodroid.app.data.Note
import com.pianodroid.app.data.Song
import com.pianodroid.app.data.TempoEvent
import com.pianodroid.app.data.Track
import java.io.InputStream
import kotlin.math.roundToLong

/**
 * Minimal checked SMF parser supporting PPQ format 0 and 1 files.
 */
class MidiParser {
    companion object {
        const val DEFAULT_TEMPO = 500000
    }

    fun parse(inputStream: InputStream): Song {
        val bytes = inputStream.readBytes()
        var pos = 0

        val headerChunk = readChunk(bytes, pos)
        pos += 8 + headerChunk.size
        require(String(headerChunk.type) == "MThd") { "Invalid MIDI file: missing MThd chunk" }
        require(headerChunk.size >= 6) { "Invalid MIDI file: short MThd chunk" }

        val format = readUnsignedShort(headerChunk.data, 0)
        val numTracks = readUnsignedShort(headerChunk.data, 2)
        val division = readUnsignedShort(headerChunk.data, 4)
        require(format == 0 || format == 1) { "Unsupported MIDI format: $format" }
        require((division and 0x8000) == 0) { "Unsupported SMPTE MIDI timing division" }
        require(division > 0) { "Invalid MIDI ticks-per-quarter: $division" }

        val rawTracks = mutableListOf<RawTrack>()
        val tempoEvents = mutableListOf<TempoEvent>()

        repeat(numTracks) {
            val trackChunk = readChunk(bytes, pos)
            pos += 8 + trackChunk.size

            if (String(trackChunk.type) == "MTrk") {
                val rawTrack = parseTrack(trackChunk.data, tempoEvents)
                if (rawTrack.notes.isNotEmpty()) {
                    rawTracks.add(rawTrack)
                }
            }
        }

        val normalizedTempos = normalizeTempoEvents(tempoEvents)
        val tracks = rawTracks.map { rawTrack ->
            Track(
                name = rawTrack.name,
                notes = rawTrack.notes
                    .map { rawNote ->
                        Note(
                            pitch = rawNote.pitch,
                            startMs = tickToMs(rawNote.startTick, normalizedTempos, division),
                            endMs = tickToMs(rawNote.endTick, normalizedTempos, division),
                            velocity = rawNote.velocity
                        )
                    }
                    .sortedBy { it.startMs }
            )
        }

        return Song(
            format = format,
            ticksPerQuarter = division,
            tracks = tracks,
            tempoMap = normalizedTempos
        )
    }

    private fun parseTrack(data: ByteArray, tempoEvents: MutableList<TempoEvent>): RawTrack {
        var pos = 0
        var currentTick = 0L
        var runningStatus: Int? = null
        var trackName = ""
        val notes = mutableListOf<RawNote>()
        val activeNotes = mutableMapOf<Int, ArrayDeque<ActiveNote>>()

        fun noteOff(pitch: Int) {
            val active = activeNotes[pitch]?.removeFirstOrNull() ?: return
            notes.add(
                RawNote(
                    pitch = pitch,
                    startTick = active.startTick,
                    endTick = currentTick.coerceAtLeast(active.startTick),
                    velocity = active.velocity
                )
            )
        }

        while (pos < data.size) {
            val delta = readVarLen(data, pos)
            currentTick += delta.value
            pos = delta.nextPos

            require(pos < data.size) { "Unexpected end of MIDI track" }
            val first = data[pos].toInt() and 0xFF
            val status = if ((first and 0x80) != 0) {
                pos++
                runningStatus = if (first in 0x80..0xEF) first else null
                first
            } else {
                runningStatus ?: throw IllegalArgumentException("Missing MIDI running status")
            }

            when {
                status == 0xFF -> {
                    require(pos < data.size) { "Unexpected end of MIDI meta event" }
                    val metaType = data[pos++].toInt() and 0xFF
                    val len = readVarLen(data, pos)
                    pos = len.nextPos
                    require(pos + len.value <= data.size) { "MIDI meta event exceeds track length" }
                    val metaLength = len.value.toInt()

                    when (metaType) {
                        0x03 -> trackName = String(data, pos, metaLength)
                        0x51 -> {
                            require(metaLength == 3) { "Invalid MIDI tempo event length" }
                            val tempo = ((data[pos].toInt() and 0xFF) shl 16) or
                                    ((data[pos + 1].toInt() and 0xFF) shl 8) or
                                    (data[pos + 2].toInt() and 0xFF)
                            tempoEvents.add(TempoEvent(currentTick, tempo))
                        }
                    }
                    pos += metaLength
                }
                status == 0xF0 || status == 0xF7 -> {
                    val len = readVarLen(data, pos)
                    pos = len.nextPos
                    require(pos + len.value <= data.size) { "MIDI SysEx event exceeds track length" }
                    pos += len.value.toInt()
                }
                else -> {
                    val eventType = status and 0xF0
                    when (eventType) {
                        0x80 -> {
                            require(pos + 1 < data.size) { "Short MIDI note-off event" }
                            val pitch = data[pos].toInt() and 0xFF
                            pos += 2
                            noteOff(pitch)
                        }
                        0x90 -> {
                            require(pos + 1 < data.size) { "Short MIDI note-on event" }
                            val pitch = data[pos].toInt() and 0xFF
                            val velocity = data[pos + 1].toInt() and 0xFF
                            pos += 2
                            if (velocity == 0) {
                                noteOff(pitch)
                            } else {
                                activeNotes.getOrPut(pitch) { ArrayDeque() }
                                    .addLast(ActiveNote(currentTick, velocity))
                            }
                        }
                        0xA0, 0xB0, 0xE0 -> {
                            require(pos + 1 < data.size) { "Short MIDI channel event" }
                            pos += 2
                        }
                        0xC0, 0xD0 -> {
                            require(pos < data.size) { "Short MIDI channel event" }
                            pos += 1
                        }
                        else -> throw IllegalArgumentException("Unsupported MIDI event status: 0x${status.toString(16)}")
                    }
                }
            }
        }

        activeNotes.forEach { (pitch, activeList) ->
            activeList.forEach { active ->
                notes.add(RawNote(pitch, active.startTick, currentTick.coerceAtLeast(active.startTick), active.velocity))
            }
        }

        return RawTrack(trackName, notes.sortedBy { it.startTick })
    }

    private fun normalizeTempoEvents(events: List<TempoEvent>): List<TempoEvent> {
        val byTick = events.sortedBy { it.tick }.fold(linkedMapOf<Long, Int>()) { acc, event ->
            acc[event.tick] = event.tempo
            acc
        }
        if (!byTick.containsKey(0L)) {
            byTick[0L] = DEFAULT_TEMPO
        }
        return byTick.entries
            .sortedBy { it.key }
            .map { TempoEvent(tick = it.key, tempo = it.value) }
    }

    private fun tickToMs(tick: Long, tempoMap: List<TempoEvent>, ticksPerQuarter: Int): Long {
        var currentTick = 0L
        var currentTempo = DEFAULT_TEMPO
        var ms = 0.0

        tempoMap.forEach { event ->
            if (event.tick > tick) return@forEach
            if (event.tick > currentTick) {
                ms += ticksToMs(event.tick - currentTick, currentTempo, ticksPerQuarter)
                currentTick = event.tick
            }
            currentTempo = event.tempo
        }

        if (tick > currentTick) {
            ms += ticksToMs(tick - currentTick, currentTempo, ticksPerQuarter)
        }
        return ms.roundToLong()
    }

    private fun ticksToMs(ticks: Long, tempo: Int, ticksPerQuarter: Int): Double {
        return ticks * tempo / (ticksPerQuarter * 1000.0)
    }

    private fun readChunk(bytes: ByteArray, pos: Int): Chunk {
        require(pos + 8 <= bytes.size) { "Unexpected end of MIDI file while reading chunk" }
        val size = readInt(bytes, pos + 4)
        require(size >= 0) { "Invalid MIDI chunk size" }
        require(pos + 8 + size <= bytes.size) { "MIDI chunk exceeds file length" }
        return Chunk(
            type = bytes.copyOfRange(pos, pos + 4),
            size = size,
            data = bytes.copyOfRange(pos + 8, pos + 8 + size)
        )
    }

    private fun readUnsignedShort(bytes: ByteArray, pos: Int): Int {
        require(pos + 1 < bytes.size) { "Unexpected end of MIDI file while reading short" }
        return ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
    }

    private fun readInt(bytes: ByteArray, pos: Int): Int {
        require(pos + 3 < bytes.size) { "Unexpected end of MIDI file while reading int" }
        return ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
    }

    private fun readVarLen(bytes: ByteArray, pos: Int): VarLen {
        var value = 0L
        var currentPos = pos
        var count = 0

        while (currentPos < bytes.size && count < 4) {
            val byte = bytes[currentPos++].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            count++
            if ((byte and 0x80) == 0) {
                return VarLen(value, currentPos)
            }
        }

        throw IllegalArgumentException("Invalid MIDI variable-length quantity")
    }

    private data class Chunk(val type: ByteArray, val size: Int, val data: ByteArray)
    private data class VarLen(val value: Long, val nextPos: Int)
    private data class ActiveNote(val startTick: Long, val velocity: Int)
    private data class RawNote(val pitch: Int, val startTick: Long, val endTick: Long, val velocity: Int)
    private data class RawTrack(val name: String, val notes: List<RawNote>)
}
