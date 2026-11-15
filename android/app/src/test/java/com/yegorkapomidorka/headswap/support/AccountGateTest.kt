package com.yegorkapomidorka.headswap.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountGateTest {
    @Test
    fun signInRequiredUntilGoogleEmailKnown() {
        assertTrue(AccountGate.required(34, ""))
        assertTrue(AccountGate.required(36, "  "))
        assertFalse(AccountGate.required(36, "driver@example.com"))
    }

    @Test
    fun phonesWithoutCredentialManagerAreNotLockedOut() {
        assertFalse(AccountGate.required(33, ""))
        assertFalse(AccountGate.required(29, ""))
    }
}
