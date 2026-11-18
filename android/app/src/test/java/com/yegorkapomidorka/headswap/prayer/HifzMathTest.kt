package com.yegorkapomidorka.headswap.prayer

import com.yegorkapomidorka.headswap.quran.QuranPages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HifzMathTest {
    private val starts = longArrayOf(0, 5_000, 12_000, 20_000)

    @Test
    fun verseAtBeginning() = assertEquals(0, HifzMath.verseAt(starts, 0))

    @Test
    fun verseExactlyAtStartBelongsToIt() = assertEquals(1, HifzMath.verseAt(starts, 5_000))

    @Test
    fun verseJustBeforeNext() = assertEquals(2, HifzMath.verseAt(starts, 19_999))

    @Test
    fun verseAfterEndIsLast() = assertEquals(3, HifzMath.verseAt(starts, 99_000))

    @Test
    fun indexWithoutWholePageIsMediaIndex() = assertEquals(2, HifzMath.indexNow(5, emptyList(), 2, null, 0))

    @Test
    fun indexClampedToQueue() = assertEquals(4, HifzMath.indexNow(5, emptyList(), 9, null, 0))

    @Test
    fun wholePageSecondRepeatThirdVerse() {
        // очередь: страница 4 аята x 2 повтора; второй повтор начинается с элемента 4
        assertEquals(6, HifzMath.indexNow(8, listOf(0, 4), 1, starts, 13_000))
    }

    @Test
    fun wholePageWithoutWavFallsBackToRepeatStart() = assertEquals(4, HifzMath.indexNow(8, listOf(0, 4), 1, null, 13_000))

    @Test
    fun emptyQueue() = assertEquals(0, HifzMath.indexNow(0, emptyList(), 3, null, 0))

    @Test
    fun segmentsRestartOnNewRepeatAndKeepAyahRepeats() {
        val a = QuranPages.Item(3, 0, "002001.mp3", 2001)
        val b = QuranPages.Item(3, 0, "002002.mp3", 2002)
        val a2 = QuranPages.Item(3, 1, "002001.mp3", 2001)
        val c = QuranPages.Item(4, 0, "002010.mp3", 2010)
        // a, a (повтор аята), b, второй повтор страницы a2, новая страница c
        assertArrayEquals(intArrayOf(0, 0, 1, 0, 0), HifzMath.segments(listOf(a, a, b, a2, c)))
    }
}
