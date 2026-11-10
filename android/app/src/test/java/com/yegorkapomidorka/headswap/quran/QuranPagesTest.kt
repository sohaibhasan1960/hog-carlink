package com.yegorkapomidorka.headswap.quran

import com.yegorkapomidorka.headswap.prayer.PrayerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranPagesTest {
    @Test
    fun allAyahsOnExactlyOnePage() {
        val all = (1..QuranPages.PAGES).flatMap { QuranPages.ayahs(it) }
        assertEquals(6236, all.size)
        assertEquals(all.size, all.toSet().size)
        assertEquals(1001, all.first())
        assertEquals(114006, all.last())
        assertTrue((1..QuranPages.PAGES).all { QuranPages.ayahs(it).isNotEmpty() })
    }

    @Test
    fun knownPages() {
        assertEquals((1..7).map { 1000 + it }, QuranPages.ayahs(1))        // аль-Фатиха
        assertEquals(listOf(2001, 2002, 2003, 2004, 2005), QuranPages.ayahs(2))
        assertEquals(2, QuranPages.pageOf(2, 5))
        assertEquals(3, QuranPages.pageOf(2, 6))
        assertEquals(293, QuranPages.pageOf(18, 1))                      // аль-Кахф
        assertEquals(604, QuranPages.pageOf(114, 6))
        assertEquals(listOf(112, 113, 114), QuranPages.surahs(604))
        assertEquals(1, QuranPages.juzOf(1))
        assertEquals(30, QuranPages.juzOf(604))
        assertEquals(2, QuranPages.juzOf(22))
    }

    @Test
    fun pageOfIsInverseOfAyahs() {
        for (p in listOf(1, 2, 50, 293, 300, 582, 604)) for (c in QuranPages.ayahs(p)) assertEquals(p, QuranPages.pageOf(c / 1000, c % 1000))
    }

    @Test
    fun basmalaBeforeSurahStartsExceptFatihaAndTawba() {
        assertEquals(listOf("001001.mp3", "001002.mp3"), QuranPages.files(1).take(2))  // басмала - сам аят 1
        assertEquals(listOf("001001.mp3", "002001.mp3"), QuranPages.files(2).take(2))
        val tawba = QuranPages.pageOf(9, 1)
        val f = QuranPages.files(tawba)
        assertTrue("009001.mp3" in f)
        val i = f.indexOf("009001.mp3")
        assertTrue("перед ат-Таубой басмалы нет", i == 0 || f[i - 1] != QuranPages.BASMALA)
        // последняя страница: три суры - три басмалы
        assertEquals(3, QuranPages.files(604).count { it == QuranPages.BASMALA })
    }

    @Test
    fun queueRepeatsEachPageBeforeTheNext() {
        val one = QuranPages.ayahs(2).size + 1                           // + басмала
        val q = QuranPages.queue(2, 2, 3)
        assertEquals(3 * one + 3 * (QuranPages.files(3).size), q.size)
        assertEquals(listOf(1, 2, 3), q.filter { it.page == 2 }.map { it.rep }.distinct())
        assertTrue(q.indexOfFirst { it.page == 3 } > q.indexOfLast { it.page == 2 })
        assertEquals(0, q.first().ayah)                                  // басмала
        // «без конца» - одна страница, по кругу её крутит плеер
        assertEquals(one, QuranPages.queue(2, 5, 0).size)
        // за 604-й не идём
        assertEquals(setOf(603, 604), QuranPages.queue(603, 5, 1).map { it.page }.toSet())
    }

    @Test
    fun blockStartIsTheStartOfTheSameRepeat() {
        val q = QuranPages.queue(2, 1, 2)
        val secondRep = q.indexOfFirst { it.rep == 2 }
        assertEquals(secondRep, QuranPages.blockStart(q, secondRep + 3))
        assertEquals(0, QuranPages.blockStart(q, 2))
    }

    @Test
    fun voiceForReciter() {
        assertEquals(QuranPages.Voice.ALAFASY, QuranPages.Voice.forReciter(Quran.Reciter.ALAFASY))
        assertEquals(QuranPages.Voice.HUSARY_MUALLIM, QuranPages.Voice.forReciter(Quran.Reciter.JALIL))
        assertEquals("https://everyayah.com/data/Husary_Muallim_128kbps/002001.mp3", QuranPages.Voice.HUSARY_MUALLIM.url("002001.mp3"))
    }

    @Test
    fun voiceSearchForThePlan() {
        assertEquals(23, PrayerSession.hifzPage("شغل الصفحة 23"))
        assertEquals(23, PrayerSession.hifzPage("صفحة ٢٣"))
        assertEquals(0, PrayerSession.hifzPage("خطة الحفظ"))
        assertNull(PrayerSession.hifzPage("سورة الكهف"))
        assertNull(PrayerSession.hifzPage("الصفحة 900"))
    }
}
