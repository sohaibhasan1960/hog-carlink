package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoicesTest {
    @Test
    fun maleFirst() {
        val all = listOf("ar-language", "ar-xa-x-arc-local", "ar-xa-x-ard-local", "ar-xa-x-are-local", "ar-xa-x-arz-local")
        assertEquals("ar-xa-x-ard-local", TtsVoices.preferredMale(all))
        assertEquals("ar-xa-x-are-local", TtsVoices.preferredMale(all - "ar-xa-x-ard-local"))
        assertNull(TtsVoices.preferredMale(listOf("ar-language")))
        assertEquals(true, TtsVoices.male("ar-xa-x-ard-local"))
        assertEquals(false, TtsVoices.male("ar-xa-x-arc-local"))
        assertEquals(false, TtsVoices.male("ar-language"))
    }
}
