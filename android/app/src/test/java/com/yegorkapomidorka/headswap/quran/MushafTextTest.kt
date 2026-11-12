package com.yegorkapomidorka.headswap.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MushafTextTest {
    private val layout = File("src/main/assets/mushaf/layout.txt").reader(Charsets.UTF_8).use { MadaniLayout.parseLayout(it) }
    private val ayahs = File("src/main/assets/mushaf/hafs.tsv").reader(Charsets.UTF_8).use { MadaniLayout.parseText(it) }
    private val byPage = ayahs.groupBy { it.page }

    @Test
    fun everyPageFitsItsLines() {
        assertEquals(6236, ayahs.size)
        assertEquals(QuranPages.PAGES, layout.size)
        for (p in 1..QuranPages.PAGES) {
            val page = MadaniLayout.page(p, layout.getValue(p), byPage[p].orEmpty(), false)
            assertNotNull("страница $p", page)
            assertEquals("страница $p", if (p <= 2) 8 else 15, page!!.lineCount)
        }
    }

    @Test
    fun pagesAgreeWithTanzilPageData() {
        for (p in 1..QuranPages.PAGES) assertEquals("страница $p", QuranPages.ayahs(p), byPage[p].orEmpty().map { it.code })
    }

    @Test
    fun lastPageHasThreeBannersAndBasmalas() {
        val page = MadaniLayout.page(604, layout.getValue(604), byPage.getValue(604), false)!!
        assertEquals(listOf(112, 113, 114), page.lines.filterIsInstance<MadaniLayout.Banner>().map { it.surah })
        assertEquals(listOf(112, 113, 114), page.lines.filterIsInstance<MadaniLayout.Basmala>().map { it.surah })
        val marks = page.lines.filterIsInstance<MadaniLayout.Words>().flatMap { it.tokens }.filter { it.isMark }
        assertEquals(4 + 5 + 6, marks.size)
    }

    @Test
    fun bannerOfAnNisaIsOnThePreviousPage() {
        assertTrue(layout.getValue(76).roles[15] is MadaniLayout.Banner)
        assertTrue(layout.getValue(77).roles[1] is MadaniLayout.Basmala)
    }

    @Test
    fun litCode() {
        assertEquals(2005, MushafActivity.litCode(2005, 2006))
        assertEquals(-113, MushafActivity.litCode(0, 113001))
        assertEquals(0, MushafActivity.litCode(-1, -1))
    }

    @Test
    fun arabicDigits() = assertEquals("٢٨٦", MushafText.arabicDigits(286))
}
