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
    fun basmalaShownSeparately() {
        val f11 = Quran.ayat(lines(), 1)[0]
        // аль-Фатиха: басмала - первый аят, остаётся аятом
        assertEquals(null, Quran.splitBasmala(1, Quran.ayat(lines(), 1), f11).first)
        // ат-Тауба без басмалы
        assertEquals(null, Quran.splitBasmala(9, Quran.ayat(lines(), 9), f11).first)
        // все остальные 112 сур (в т.ч. 95 и 97 с шаддой): отделяется, первый аят без неё, число аятов то же
        for (n in (2..114) - 9) {
            val ay = Quran.ayat(lines(), n)
            val (b, rest) = Quran.splitBasmala(n, ay, f11)
            assertTrue("сура $n", b != null && b.endsWith(f11.takeLast(8)))
            assertEquals(ay.size, rest.size)
            assertTrue("сура $n: ${rest[0]}", rest[0].isNotBlank() && !rest[0].contains(f11.takeLast(8)))
        }
        assertEquals(Quran.ayat(lines(), 2)[0].substring(f11.length).trim(), Quran.splitBasmala(2, Quran.ayat(lines(), 2), f11).second[0])
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
