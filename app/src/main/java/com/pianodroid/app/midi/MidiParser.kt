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
        var currentTempo = DEFAULT_TEMPO
        var currentTick = 0L
        var currentMs = 0.0
        val notes = mutableListOf<Note>()
        val activeNotes = mutableMapOf<Int, Pair<Long, Long>>()  // pitch -> (startTick, startMs)
        var runningStatus: Int? = null

        while (pos < data.size) {
            val deltaTick = readVarLen(data, pos)
            pos = deltaTick.second
            val tickDelta = deltaTick.first

            currentTick += tickDelta
            val tickDeltaMs = (tickDelta * currentTempo) / (ticksPerQuarter * 1000.0)
            currentMs += tickDeltaMs

            // Update tempo if needed
            val tempoAtTick = getTempoAtTick(tempoMap, currentTick, ticksPerQuarter)
            if (tempoAtTick != currentTempo) {
                currentTempo = tempoAtTick
            }

            val status = if (data[pos].toInt() and 0x80 != 0) {
                runningStatus = data[pos].toInt()
                runningStatus!!
            } else {
                runningStatus ?: return Track(trackName, emptyList())
            }

            when (status and 0xF0) {
                0x90 -> {  // Note On
                    if (pos + 2 >= data.size) break
                    val pitch = data[pos + 1].toInt() and 0xFF
                    val velocity = data[pos + 2].toInt() and 0xFF
                    pos += 3

                    if (velocity > 0) {
                        activeNotes[pitch] = Pair(currentTick, currentMs.toLong())
                    } else {
                        // Velocity 0 = Note Off
                        activeNotes.remove(pitch)?.let { (startTick, startMs) ->
                            notes.add(Note(
                                pitch = pitch,
                                startMs = startMs.toLong(),
                                endMs = currentMs.toLong(),
                                velocity = 64
                            ))
                        }
                    }
                }
                0x80 -> {  // Note Off
                    if (pos + 2 >= data.size) break
                    val pitch = data[pos + 1].toInt() and 0xFF
                    pos += 2

                    activeNotes.remove(pitch)?.let { (startTick, startMs) ->
                        notes.add(Note(
                            pitch = pitch,
                            startMs = startMs.toLong(),
                            endMs = currentMs.toLong(),
                            velocity = 64
                        ))
                    }
                }
                0xFF -> {  // Meta event
                    val metaType = data[pos + 1].toInt() and 0xFF
                    val length = readVarLen(data, pos + 2)
                    pos = length.second

                    when (metaType) {
                        0x03 -> {  // Track name
                            val nameBytes = data.copyOfRange(pos, (pos + length.first).toInt())
                            trackName = String(nameBytes)
                            pos += length.first.toInt()
                        }
                        0x51 -> {  // Tempo
                            if (length.first >= 3) {
                                val tempo = ((data[pos].toInt() and 0xFF) shl 16) or
                                        ((data[pos + 1].toInt() and 0xFF) shl 8) or
                                        (data[pos + 2].toInt() and 0xFF)
                                tempoMap.add(TempoEvent(currentTick, tempo))
                                pos += length.first.toInt()
                            } else {
                                pos += length.first.toInt()
                            }
                        }
                        else -> {
                            pos += length.first.toInt()
                        }
                    }
                }
                else -> {
                    // Skip unknown event
                    pos++
                }
            }
        }

        // Close any remaining active notes
        activeNotes.forEach { (pitch, times) ->
            val (_, startMs) = times

            notes.add(
                Note(
                    pitch = pitch,
                    startMs = startMs,
                    endMs = currentMs.toLong(),
                    velocity = 64
                )
            )
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
