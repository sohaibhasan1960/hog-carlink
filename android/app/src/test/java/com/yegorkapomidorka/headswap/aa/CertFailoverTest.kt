package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertFailoverTest {
    private val rejected = "TLS handshake не прошёл: SSLHandshakeException: bad_certificate"

    @Test
    fun switchesAfterTwoRejectionsInARow() {
        val f = CertFailover(listOf("jvc", "hur"), "jvc")
        assertNull(f.onResult(false, rejected))
        assertEquals("hur", f.onResult(false, rejected))
        assertEquals("hur", f.current)
    }

    @Test
    fun otherFailuresDoNotCount() {
        val f = CertFailover(listOf("jvc", "hur"), "jvc")
        assertNull(f.onResult(false, rejected))
        // сервер выключен и таймаут не про сертификат: серия не растёт, но и не обнуляется
        assertNull(f.onResult(false, "AA сервер недоступен: ConnectException"))
        assertNull(f.onResult(false, "TLS handshake: таймаут"))
        assertEquals("hur", f.onResult(false, "TLS: Connection reset"))
    }

    @Test
    fun establishedSessionResetsTheStreak() {
        val f = CertFailover(listOf("jvc", "hur"), "jvc")
        assertNull(f.onResult(false, rejected))
        assertNull(f.onResult(true, "конец"))
        assertNull(f.onResult(false, rejected))
        assertEquals("jvc", f.current)
    }

    @Test
    fun eachSetTriedOnceThenStays() {
        val f = CertFailover(listOf("jvc", "hur"), "jvc")
        f.onResult(false, rejected)
        assertEquals("hur", f.onResult(false, rejected))
        f.onResult(false, rejected)
        // оба отвергнуты: обратно не прыгаем, чтобы не крутиться по кругу
        assertNull(f.onResult(false, rejected))
        assertEquals("hur", f.current)
    }

    @Test
    fun classifiesReasons() {
        assertTrue(CertFailover.isCertRejection(rejected))
        assertTrue(CertFailover.isCertRejection("TLS: Read error"))
        assertFalse(CertFailover.isCertRejection("TLS-ошибка от телефона: SSLException"))
        assertFalse(CertFailover.isCertRejection("TLS handshake: таймаут"))
        assertFalse(CertFailover.isCertRejection("peer closed: телефон закрыл"))
    }
}
