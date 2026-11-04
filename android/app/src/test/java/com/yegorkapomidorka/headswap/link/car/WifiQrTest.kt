package com.yegorkapomidorka.headswap.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrTest {
    @Test
    fun plainWpaCode() {
        val n = WifiQr.parse("WIFI:T:WPA;S:VW_ID.6_4F2A;P:7hK2-pQ9x;;")!!
        assertEquals("VW_ID.6_4F2A", n.ssid)
        assertEquals("7hK2-pQ9x", n.pass)
        assertFalse(n.hidden)
    }

    @Test
    fun fieldsInAnyOrderAndHidden() {
        val n = WifiQr.parse("WIFI:S:car;T:WPA2;H:true;P:secret;;")!!
        assertEquals("car", n.ssid)
        assertEquals("secret", n.pass)
        assertTrue(n.hidden)
    }

    @Test
    fun escapedSpecialCharacters() {
        val n = WifiQr.parse("""WIFI:S:My\;Net\\;P:p\:w\,d\"q;;""")!!
        assertEquals("My;Net\\", n.ssid)
        assertEquals("p:w,d\"q", n.pass)
    }

    @Test
    fun quotedValues() {
        val n = WifiQr.parse("WIFI:S:\"1234\";P:\"abcd;ef\";;")!!
        assertEquals("1234", n.ssid)
        assertEquals("abcd;ef", n.pass)
    }

    @Test
    fun openNetworkHasEmptyPassword() {
        assertEquals("", WifiQr.parse("WIFI:T:nopass;S:Open;P:ignored;;")!!.pass)
        assertEquals("", WifiQr.parse("WIFI:S:NoPasswordField;;")!!.pass)
    }

    @Test
    fun prefixCaseAndWhitespace() {
        val n = WifiQr.parse("  wifi:S:x;P:y;; \n")!!
        assertEquals("x", n.ssid)
        assertEquals("y", n.pass)
    }

    @Test
    fun missingTrailingSemicolons() {
        val n = WifiQr.parse("WIFI:S:x;P:y")!!
        assertEquals("x", n.ssid)
        assertEquals("y", n.pass)
    }

    @Test
    fun notWifiCodes() {
        assertNull(WifiQr.parse("https://example.com"))
        assertNull(WifiQr.parse("hello"))
        assertNull(WifiQr.parse(""))
        assertNull(WifiQr.parse("WIFI:T:WPA;P:nossid;;"))
        assertNull(WifiQr.parse("WIFI:S:;P:empty;;"))
    }
}
