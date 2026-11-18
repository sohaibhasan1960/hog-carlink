package com.yegorkapomidorka.headswap.tesla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class TeslaReadLineTest {
    @Test
    fun readsRequestLineWithoutCr() {
        val inp = ByteArrayInputStream("GET /state HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertEquals("GET /state HTTP/1.1", TeslaWeb.readLine(inp))
        assertEquals("Host: x", TeslaWeb.readLine(inp))
        assertEquals("", TeslaWeb.readLine(inp))
    }

    @Test
    fun hugeLineRejected() {
        val inp = ByteArrayInputStream(ByteArray(100_000) { 'a'.code.toByte() })
        assertNull(TeslaWeb.readLine(inp))
    }
}
