package com.yegorkapomidorka.headswap.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {
    @Test
    fun liveValues() {
        assertEquals(60, Elm327.speedKmh("410D3C\r\r>"))
        assertEquals(60, Elm327.speedKmh("SEARCHING...\r41 0D 3C\r\r>"))
        assertEquals(12.708, Elm327.moduleVolts("41 42 31 A4\r>")!!, 0.001)
        assertEquals(34, Elm327.ambientC("41464A>"))
        assertEquals(100.0, Elm327.hybridBatteryPct("415BFF")!!, 0.01)
        assertNull(Elm327.speedKmh("NO DATA\r\r>"))
        assertNull(Elm327.speedKmh("?\r>"))
        assertNull(Elm327.speedKmh("UNABLE TO CONNECT\r>"))
    }

    @Test
    fun troubleCodes() {
        assertEquals(listOf("P0133"), Elm327.dtcs("43 01 33 00 00 00 00\r>"))
        // не-CAN: каждая строка «43» + три пары
        assertEquals(listOf("P0133", "B1234", "U0100"), Elm327.dtcs("43 01 33 92 34 C1 00\r>"))
        // CAN: «43», счётчик кодов, пары
        assertEquals(listOf("P0133", "U0100"), Elm327.dtcs("43 02 01 33 C1 00\r>"))
        assertTrue(Elm327.dtcs("43 00\r>").isEmpty())
        assertTrue(Elm327.dtcs("NO DATA").isEmpty())
        assertEquals("C0300", Elm327.dtc(0x43, 0x00))
        assertEquals("B1234", Elm327.dtc(0x92, 0x34))
    }

    @Test
    fun mebSoc() {
        assertEquals(80.0, Elm327.mebSocPct("62028CC8\r>")!!, 0.01)
        assertNull(Elm327.mebSocPct("7F 22 31\r>"))
    }
}
