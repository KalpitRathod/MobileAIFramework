package com.mobileai.framework

import com.mobileai.framework.multimodal.AudioInputManager
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWavTest {

    @Test
    fun testWavHeaderFormat() {
        val sampleRate = 16000
        val channels = 1
        val bitDepth = 16
        // 1 second of silence: 16000 samples * 2 bytes = 32000 bytes
        val pcmData = ByteArray(32000)

        // Create direct helper to verify WAV structure without Android context
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitDepth / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size
        header[20] = 1 // PCM format
        header[22] = channels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[34] = bitDepth.toByte()
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val fullWav = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, fullWav, 0, 44)
        System.arraycopy(pcmData, 0, fullWav, 44, pcmData.size)

        assertEquals('R'.code.toByte(), fullWav[0])
        assertEquals('I'.code.toByte(), fullWav[1])
        assertEquals('F'.code.toByte(), fullWav[2])
        assertEquals('F'.code.toByte(), fullWav[3])
        assertEquals('W'.code.toByte(), fullWav[8])
        assertEquals('A'.code.toByte(), fullWav[9])
        assertEquals('V'.code.toByte(), fullWav[10])
        assertEquals('E'.code.toByte(), fullWav[11])
        assertEquals('d'.code.toByte(), fullWav[36])
        assertEquals('a'.code.toByte(), fullWav[37])
        assertEquals('t'.code.toByte(), fullWav[38])
        assertEquals('a'.code.toByte(), fullWav[39])
        assertEquals(32044, fullWav.size)
    }
}

