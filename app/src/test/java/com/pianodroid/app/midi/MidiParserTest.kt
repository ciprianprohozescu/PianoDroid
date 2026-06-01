package com.pianodroid.app.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MidiParserTest {
    @Test
    fun parsesFormat0RunningStatusAndVelocity() {
        val track = events(
            vlq(0), bytes(0x90, 60, 77),
            vlq(96), bytes(62, 80),
            vlq(96), bytes(0x80, 60, 0),
            vlq(0), bytes(62, 0),
            endOfTrack()
        )

        val song = MidiParser().parse(ByteArrayInputStream(midiFile(format = 0, ticksPerQuarter = 96, tracks = listOf(track))))

        assertEquals(0, song.format)
        assertEquals(1, song.tracks.size)
        assertEquals(listOf(60, 62), song.tracks.first().notes.map { it.pitch })
        assertEquals(77, song.tracks.first().notes[0].velocity)
        assertEquals(0L, song.tracks.first().notes[0].startMs)
        assertEquals(1000L, song.tracks.first().notes[0].endMs)
    }

    @Test
    fun appliesTempoMapAcrossFormat1Tracks() {
        val tempoTrack = events(
            vlq(0), tempo(500000),
            vlq(96), tempo(1000000),
            endOfTrack()
        )
        val noteTrack = events(
            vlq(192), bytes(0x90, 60, 64),
            vlq(96), bytes(0x80, 60, 0),
            endOfTrack()
        )

        val song = MidiParser().parse(ByteArrayInputStream(midiFile(format = 1, ticksPerQuarter = 96, tracks = listOf(tempoTrack, noteTrack))))
        val note = song.tracks.single().notes.single()

        assertEquals(1500L, note.startMs)
        assertEquals(2500L, note.endMs)
        assertEquals(listOf(0L, 96L), song.tempoMap.map { it.tick })
    }

    @Test
    fun rejectsMalformedFiles() {
        val error = runCatching {
            MidiParser().parse(ByteArrayInputStream(byteArrayOf(0x4D, 0x54, 0x68)))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun midiFile(format: Int, ticksPerQuarter: Int, tracks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytes(0x4D, 0x54, 0x68, 0x64))
        out.write(int32(6))
        out.write(int16(format))
        out.write(int16(tracks.size))
        out.write(int16(ticksPerQuarter))
        tracks.forEach { track ->
            out.write(bytes(0x4D, 0x54, 0x72, 0x6B))
            out.write(int32(track.size))
            out.write(track)
        }
        return out.toByteArray()
    }

    private fun events(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun tempo(microsecondsPerQuarter: Int): ByteArray {
        return bytes(
            0xFF, 0x51, 0x03,
            (microsecondsPerQuarter shr 16) and 0xFF,
            (microsecondsPerQuarter shr 8) and 0xFF,
            microsecondsPerQuarter and 0xFF
        )
    }

    private fun endOfTrack(): ByteArray = events(vlq(0), bytes(0xFF, 0x2F, 0x00))

    private fun vlq(value: Int): ByteArray {
        val stack = ArrayDeque<Int>()
        var remaining = value
        stack.addFirst(remaining and 0x7F)
        remaining = remaining shr 7
        while (remaining > 0) {
            stack.addFirst((remaining and 0x7F) or 0x80)
            remaining = remaining shr 7
        }
        return stack.map { it.toByte() }.toByteArray()
    }

    private fun int16(value: Int): ByteArray = bytes((value shr 8) and 0xFF, value and 0xFF)

    private fun int32(value: Int): ByteArray = bytes(
        (value shr 24) and 0xFF,
        (value shr 16) and 0xFF,
        (value shr 8) and 0xFF,
        value and 0xFF
    )

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
}
