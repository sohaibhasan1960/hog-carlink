package com.yegorkapomidorka.headswap.support

import com.yegorkapomidorka.headswap.support.Licensing.DAY_MS
import com.yegorkapomidorka.headswap.support.Licensing.State
import org.junit.Assert.assertEquals
import org.junit.Test

class LicensingTest {
    private val now = 1_800_000_000_000L

    @Test
    fun activeNotExpired() = assertEquals(State.OK, Licensing.state("active", now + DAY_MS, now))

    @Test
    fun activeExpired() = assertEquals(State.EXPIRED, Licensing.state("active", now - 1, now))

    @Test
    fun pendingAndSuspendedAndUnknown() {
        assertEquals(State.PENDING, Licensing.state("pending", 0, now))
        assertEquals(State.SUSPENDED, Licensing.state("suspended", now + DAY_MS, now))
        assertEquals(State.NONE, Licensing.state("", now + DAY_MS, now))
    }

    @Test
    fun freshCacheTrusted() = assertEquals(State.OK, Licensing.cachedState("active", now + 30 * DAY_MS, now - DAY_MS, now))

    @Test
    fun staleCacheNotTrusted() = assertEquals(State.NONE, Licensing.cachedState("active", now + 30 * DAY_MS, now - 8 * DAY_MS, now))

    @Test
    fun clockSetBackNotTrusted() = assertEquals(State.NONE, Licensing.cachedState("active", now + 30 * DAY_MS, now + 10 * DAY_MS, now))

    @Test
    fun smallClockCorrectionTolerated() = assertEquals(State.OK, Licensing.cachedState("active", now + 30 * DAY_MS, now + 10 * 60 * 1000L, now))

    @Test
    fun expiryForTerm() {
        assertEquals(now + 365 * DAY_MS, Licensing.expiryFor(now, 365))
        assertEquals(now, Licensing.expiryFor(now, -5))
    }
}
