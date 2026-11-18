package com.yegorkapomidorka.headswap.quran

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MushafFollowTest {
    @Test
    fun endsByTextShare() = assertArrayEquals(doubleArrayOf(0.25, 0.75, 1.0), MushafFollow.ends(intArrayOf(1, 2, 1)), 1e-9)

    @Test
    fun emptyPageCountsAsOne() = assertArrayEquals(doubleArrayOf(0.5, 1.0), MushafFollow.ends(intArrayOf(0, 1)), 1e-9)

    @Test
    fun unknownDurationGivesNoSlot() = assertNull(MushafFollow.slot(doubleArrayOf(0.5, 1.0), 1000, 0))

    @Test
    fun startIsFirstPage() = assertEquals(0 to 0.0, MushafFollow.slot(doubleArrayOf(0.25, 0.75, 1.0), 0, 100_000))

    @Test
    fun middleOfSecondPage() {
        val (i, inPage) = MushafFollow.slot(doubleArrayOf(0.25, 0.75, 1.0), 50_000, 100_000)!!
        assertEquals(1, i)
        assertEquals(0.5, inPage, 1e-9)
    }

    @Test
    fun endStaysOnLastPage() {
        val (i, inPage) = MushafFollow.slot(doubleArrayOf(0.25, 0.75, 1.0), 120_000, 100_000)!!
        assertEquals(2, i)
        assertEquals(1.0, inPage, 1e-9)
    }

    @Test
    fun pageBoundaryBelongsToEarlierPage() = assertEquals(0, MushafFollow.slot(doubleArrayOf(0.25, 0.75, 1.0), 25_000, 100_000)!!.first)
}
