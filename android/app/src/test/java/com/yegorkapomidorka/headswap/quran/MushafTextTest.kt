package com.yegorkapomidorka.headswap.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafTextTest {
    private fun texts(page: Int) = QuranPages.ayahs(page).associateWith { "آية$it" }

    @Test
    fun fatihaHasHeaderButNoExtraBasmala() {
        val p = MushafText.build(1, texts(1))
        assertEquals(7, p.ayahs.size)
        assertEquals(1, p.headers.size)
        assertTrue(p.basmalas.isEmpty())
        assertTrue(p.text.startsWith("سورة الفاتحة\n"))
        assertEquals("آية1001 ﴿١﴾", p.text.substring(p.ayahs[1001]!!))
    }

    @Test
    fun lastPageHasThreeSurahsWithBasmala() {
        val p = MushafText.build(604, texts(604))
        assertEquals(3, p.headers.size)
        assertEquals(setOf(112, 113, 114), p.basmalas.keys)
        assertEquals(MushafText.BASMALA, p.text.substring(p.basmalas[113]!!))
        // басмала плана (аят 0) подсвечивает басмалу суры следующего аята
        assertEquals(p.basmalas[113], MushafText.highlight(p, 0, 113001))
        assertEquals(p.ayahs[114006], MushafText.highlight(p, 114006, null))
        assertNull(MushafText.highlight(p, 0, null))
    }

    @Test
    fun tawbaHasNoBasmala() {
        val page = QuranPages.pageOf(9, 1)
        val p = MushafText.build(page, texts(page))
        assertTrue(9 !in p.basmalas)
        assertTrue(p.text.contains("سورة التوبة"))
    }

    @Test
    fun arabicDigits() = assertEquals("٢٨٦", MushafText.arabicDigits(286))
}
