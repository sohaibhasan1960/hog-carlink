package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchTest {
    @Test
    fun keywordsBeforeSurahs() {
        assertEquals(PrayerPlayer.What.TEST_ADHAN, PrayerSession.keyword("شغل أذان الفجر"))
        assertEquals(PrayerPlayer.What.EVENING, PrayerSession.keyword("أذكار المساء"))
        assertEquals(PrayerPlayer.What.MORNING, PrayerSession.keyword("أذكار الصباح"))
        assertNull(PrayerSession.keyword("سورة الكهف"))
    }

    @Test
    fun surahNames() {
        assertEquals(18, PrayerSession.surahFor("شغل سورة الكهف"))
        assertEquals(18, PrayerSession.surahFor("سوره الكهف"))
        assertEquals(3, PrayerSession.surahFor("سورة آل عمران"))
        assertEquals(36, PrayerSession.surahFor("سورة يس"))
        assertEquals(67, PrayerSession.surahFor("الملك"))
        assertEquals(2, PrayerSession.surahFor("البقرة"))
        assertNull(PrayerSession.surahFor("موسيقى هادئة"))
    }
}
