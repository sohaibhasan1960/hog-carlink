package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPrintShareTest {
    @Test fun completeNeedsChannelAndHuInfo() {
        assertFalse(CarPrint(channel = "123").complete())
        assertFalse(CarPrint(manufacturer = "X", model = "Y").complete())
        assertTrue(CarPrint(manufacturer = "X", channel = "123").complete())
    }

    @Test fun bluetoothNameDigitsMasked() {
        val s = CarPrint(manufacturer = "VW BT 5075", model = "VW BT 5075", channel = "20082122").shareable()
        assertEquals("VW BT ####", s.manufacturer)
        assertNull(s.model)
        assertEquals("20082122", s.channel)
    }

    @Test fun modelKeptWhenDifferent() {
        val s = CarPrint(manufacturer = "Pioneer", model = "RM56E8B", channel = "1").shareable()
        assertEquals("RM56E8B", s.model)
    }

    @Test fun keySameForSameCarDifferentBluetoothName() {
        val a = CarPrint(manufacturer = "VW BT 5075", model = "VW BT 5075", product = "p", board = "b", channel = "9").shareable()
        val b = CarPrint(manufacturer = "VW BT 9491", model = "VW BT 9491", product = "p", board = "b", channel = "9").shareable()
        assertEquals(a.key(), b.key())
        assertEquals(24, a.key().length)
    }

    @Test fun keyDiffersByChannel() {
        val a = CarPrint(model = "m", channel = "1").shareable()
        val b = CarPrint(model = "m", channel = "2").shareable()
        assertNotEquals(a.key(), b.key())
    }
}
