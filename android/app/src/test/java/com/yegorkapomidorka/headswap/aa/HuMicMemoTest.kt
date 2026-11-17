package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuMicMemoTest {
    private var stored = 0
    private val memo = HuMicMemo({ stored }, { stored = it })

    @Test
    fun asksHeadUnitUntilItWasSilentTwice() {
        assertFalse(memo.skipHu())
        memo.verdict(sent = false)
        assertFalse(memo.skipHu())
        memo.verdict(sent = false)
        assertTrue("после двух немых раз - сразу телефон", memo.skipHu())
    }

    @Test
    fun retriesHeadUnitEveryTenthTime() {
        stored = HuMicMemo.SILENT_TO_SKIP
        val asks = (1..30).map { memo.skipHu() }.count { !it }
        assertEquals("из 30 нажатий ГУ спрашиваем 3 раза", 3, asks)
    }

    @Test
    fun headUnitSoundResetsTheMemory() {
        stored = 7
        memo.verdict(sent = true)
        assertEquals(0, stored)
        assertFalse(memo.skipHu())
    }
}
