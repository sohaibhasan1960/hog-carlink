package com.yegorkapomidorka.headswap.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuranTest {
    private fun lines() = File("src/main/res/raw/quran_uthmani.txt").readLines(Charsets.UTF_8).asSequence()

    @Test
    fun textIsComplete() {
        // 6236 аятов, у каждой суры - свои
        assertEquals(6236, lines().count { it.matches(Regex("""\d+\|\d+\|.+""")) })
        assertEquals(7, Quran.ayat(lines(), 1).size)
        assertEquals(286, Quran.ayat(lines(), 2).size)
        assertEquals(6, Quran.ayat(lines(), 114).size)
        val total = (1..114).sumOf { Quran.ayat(lines(), it).size }
        assertEquals(6236, total)
        // уведомление Tanzil на месте (условие лицензии)
        assertTrue(lines().any { "Tanzil Project" in it })
    }

    @Test
    fun namesAndUrls() {
        assertEquals(114, Quran.NAMES.size)
        assertEquals("الفاتحة", Quran.name(1))
        assertEquals("الناس", Quran.name(114))
        assertTrue(Quran.makki(1))
        assertTrue(!Quran.makki(2))
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/002.mp3", Quran.Reciter.ALAFASY.url(2))
        assertEquals(3, Quran.next(2))
        assertNull(Quran.next(114))
    }
}
