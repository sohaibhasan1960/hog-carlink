package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdhkarTest {
    /** Без огласовок: порядок знаков в наборе может отличаться. */
    private fun bare(s: String) = s.replace(Regex("[\u064B-\u0652\u0670]"), "")
    private val all = Adhkar.parse(File("src/main/res/raw/adhkar_ar.json").readText(Charsets.UTF_8))

    @Test
    fun dataset() {
        assertEquals(34, all.size)
        val m = Adhkar.morning(all)
        val e = Adhkar.evening(all)
        assertTrue(m.any { bare(it.text).startsWith("أصبحنا وأصبح") })
        assertFalse(m.any { bare(it.text).startsWith("أمسينا وأمسى") })
        assertTrue(e.any { bare(it.text).startsWith("أمسينا وأمسى") })
        // аят аль-Курси - и утром, и вечером
        assertTrue(m.any { "الحي القيوم" in bare(it.text) } && e.any { "الحي القيوم" in bare(it.text) })
        assertTrue(all.all { it.count >= 1 && it.text.isNotBlank() })
    }

    @Test
    fun readingPlan() {
        val kursi = all.first { "الحي القيوم" in bare(it.text) }
        assertFalse(Adhkar.speakable(kursi.text).contains("﴿"))
        val hundred = all.first { it.count == 100 }
        assertEquals(2, Adhkar.utterances(hundred, fullRepeat = false).size)
        assertEquals(100, Adhkar.utterances(hundred, fullRepeat = true).size)
        val seven = all.first { it.count == 7 }
        assertEquals(7, Adhkar.utterances(seven, fullRepeat = false).size)
        assertTrue(Adhkar.speakable("بِمُحَمَّدٍ ﷺ نَبِيًّا").contains("صلى الله عليه وسلم"))
    }

    @Test
    fun shortDhikrHaveRealVoice() {
        // каждый короткий зикр - с записью, и это та же запись, что у этого зикра в азкарах утра/вечера
        assertTrue(Adhkar.SHORT.size >= 6)
        for (t in Adhkar.SHORT) {
            val url = Adhkar.SHORT_AUDIO[t]!!
            assertTrue(url, url.startsWith("https://www.hisnmuslim.com/audio/ar/") && url.endsWith(".mp3"))
            val item = all.firstOrNull { it.text == t }
            assertTrue("нет в adhkar_ar.json: $t", item != null)
            assertEquals(t, item!!.audio, url)
        }
    }
}
