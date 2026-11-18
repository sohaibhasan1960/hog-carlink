package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    @Test
    fun noneIsSilent() {
        val n = NowPlaying.NONE
        assertFalse(n.active)
        assertFalse(n.playing)
        assertEquals(0, n.surah)
        assertEquals(0, n.page)
        assertEquals(-1, n.ayah)
    }

    @Test
    fun playingNeedsActiveAndNotPaused() {
        assertTrue(NowPlaying(active = true).playing)
        assertFalse(NowPlaying(active = true, paused = true).playing)
        assertFalse(NowPlaying(active = false).playing)
    }

    @Test
    fun stopAfterSurahLeavesNoSurah() {
        // книга после суры: остановка публикует NONE целиком - прежняя сура не остаётся
        val surah = NowPlaying(active = true, surah = 36, posMs = 5000, durMs = 60000)
        assertEquals(36, surah.surah)
        assertEquals(NowPlaying.NONE, NowPlaying.NONE.copy())
    }
}
