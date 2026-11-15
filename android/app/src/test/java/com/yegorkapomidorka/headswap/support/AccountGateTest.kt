package com.yegorkapomidorka.headswap.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountGateTest {
    @Test
    fun signInRequiredUntilGoogleEmailKnown() {
        assertTrue(AccountGate.signInRequired(34, ""))
        assertTrue(AccountGate.signInRequired(36, "  "))
        assertFalse(AccountGate.signInRequired(36, "driver@example.com"))
    }

    @Test
    fun phonesWithoutCredentialManagerAreNotLockedOutOfHog() {
        assertFalse(AccountGate.signInRequired(33, ""))
        assertFalse(AccountGate.required(29, "", seller = false, licenseOk = false))
    }

    @Test
    fun sellerBuildAlsoNeedsALicence() {
        assertFalse(AccountGate.required(36, "a@b.c", seller = false, licenseOk = false))
        assertTrue(AccountGate.required(36, "a@b.c", seller = true, licenseOk = false))
        assertFalse(AccountGate.required(36, "a@b.c", seller = true, licenseOk = true))
        assertTrue(AccountGate.required(33, "", seller = true, licenseOk = false))
    }

    @Test
    fun licenceStates() {
        val now = 1_000_000_000_000L
        assertEquals(Licensing.State.OK, Licensing.state("active", now + 1, now))
        assertEquals(Licensing.State.EXPIRED, Licensing.state("active", now, now))
        assertEquals(Licensing.State.PENDING, Licensing.state("pending", 0, now))
        assertEquals(Licensing.State.SUSPENDED, Licensing.state("suspended", now + 1, now))
        assertEquals(Licensing.State.NONE, Licensing.state("", 0, now))
        assertEquals(now + 30 * Licensing.DAY_MS, Licensing.expiryFor(now, 30))
        assertEquals(now, Licensing.expiryFor(now, -5))
        // без сверки с сервером больше недели кэш не действует
        assertEquals(Licensing.State.OK, Licensing.cachedState("active", now + 30 * Licensing.DAY_MS, now - Licensing.DAY_MS, now))
        assertEquals(Licensing.State.NONE, Licensing.cachedState("active", now + 30 * Licensing.DAY_MS, now - 8 * Licensing.DAY_MS, now))
    }

    @Test
    fun firestoreTimesParse() {
        val t = SupportCloud.parseTime("2026-11-15T10:00:00Z")
        assertEquals(t, SupportCloud.parseTime("2026-11-15T10:00:00.123456Z"))
        assertTrue(t > 0)
        assertEquals(0L, SupportCloud.parseTime(""))
        assertEquals(SupportCloud.parseTime(SupportCloud.rfc3339(java.util.Date(t))), t)
    }
}
