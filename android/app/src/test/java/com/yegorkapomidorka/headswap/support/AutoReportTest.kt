package com.yegorkapomidorka.headswap.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReportTest {
    @Test
    fun dayLimit() {
        assertTrue(AutoReport.allowed(day = 100, sentDay = 99, sentCount = 3))
        assertTrue(AutoReport.allowed(day = 100, sentDay = 100, sentCount = AutoReport.DAY_LIMIT - 1))
        assertFalse(AutoReport.allowed(day = 100, sentDay = 100, sentCount = AutoReport.DAY_LIMIT))
    }
}
