package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Test

class HifzPageAudioTest {
    private val rate = 1000      // 1 сэмпл = 1 мс: границы читаются прямо в миллисекундах

    private fun clip(leadMs: Int, voiceMs: Int, tailMs: Int): ShortArray =
        ShortArray(leadMs + voiceMs + tailMs) { i -> if (i in leadMs until leadMs + voiceMs) 5000 else 30 }

    @Test
    fun longSilenceAtBothEndsIsCutToTheKeptMargins() {
        // как у «المعلّم»: 1.5 с до аята, 4 с после
        val (a, b) = HifzPageAudio.trim(clip(1500, 3000, 4000), rate)
        assertEquals(1500 - HifzPageAudio.LEAD_KEEP_MS, a)
        assertEquals(1500 + 3000 + HifzPageAudio.TAIL_KEEP_MS, b)
    }

    @Test
    fun pausesInsideTheVerseStay() {
        val s = ShortArray(5000) { i -> if (i in 200 until 2000 || i in 3000 until 4800) 5000 else 0 }
        val (a, b) = HifzPageAudio.trim(s, rate)
        assertEquals(200 - HifzPageAudio.LEAD_KEEP_MS, a)
        assertEquals(4800 + HifzPageAudio.TAIL_KEEP_MS, b)       // после последнего звука - только запас
    }

    @Test
    fun allSilenceIsKeptWhole() {
        val (a, b) = HifzPageAudio.trim(ShortArray(800), rate)
        assertEquals(0, a)
        assertEquals(800, b)
    }

    @Test
    fun wavHeaderMatchesStreamedPcm() {
        val h = java.nio.ByteBuffer.wrap(HifzPageAudio.wavHeader(1_000_000L, 22050)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(h.array(), 0, 4))
        assertEquals(36 + 1_000_000, h.getInt(4))
        assertEquals(22050, h.getInt(24))
        assertEquals(44100, h.getInt(28))
        assertEquals("data", String(h.array(), 36, 4))
        assertEquals(1_000_000, h.getInt(40))
    }
}
