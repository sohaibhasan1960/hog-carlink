package com.yegorkapomidorka.headswap.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    @Test
    fun macKeepsVendorOnly() = assertEquals(
        "btMAC=50:26:EF:xx:xx:xx done",
        AutoReport.redact("btMAC=50:26:EF:12:AB:9C done"),
    )

    @Test
    fun prayerPlaceRoundedToTenthDegree() = assertEquals(
        "21:00 намаз: место 31.9, 35.9 Amman JO",
        AutoReport.redact("21:00 намаз: место 31.953, 35.910 Amman JO"),
    )

    @Test
    fun teslaKeyHidden() {
        val r = AutoReport.redact("Tesla: страница работает: http://192.168.43.1:8686/?k=abcdefgh23456789")
        assertFalse(r.contains("abcdefgh"))
        assertEquals("Tesla: страница работает: http://192.168.43.1:8686/?k=***", r)
    }

    @Test
    fun ordinaryLinesUntouched() {
        val l = "19:58:33.101 MIC phone: блок 10, пик 24219, time 12:30:45"
        assertEquals(l, AutoReport.redact(l))
    }
}
