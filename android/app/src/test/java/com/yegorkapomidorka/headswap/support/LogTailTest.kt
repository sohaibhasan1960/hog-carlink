package com.yegorkapomidorka.headswap.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Строки - из лога поездки 17.11 (VW ID.6): шум протокола уходит, события звука и микрофона остаются. */
class LogTailTest {
    private val noise = listOf(
        "08:34:40.383 AA RX MEDIA_PLAYBACK(9) MEDIA_PLAYBACK_STATUS 0x8001 len=23 | {state=2}",
        "08:34:40.384 AA META MEDIA_PLAYBACK MEDIA_PLAYBACK_STATUS: state=PLAYING source=SoundCloud pos=150s",
        "08:34:40.871 META -> MEDIA_PROGRESS_BAR progressBar=150000 (150 с)",
        "08:34:40.873 TX CMD 0x00010036 MEDIA_PROGRESS_BAR len=4 {progressBar=150000}",
        "08:34:40.874     outer: chan=1 len=12",
        "08:34:41.331 TX VIDEO 0x00020001 VIDEO_DATA len=77178 nal=[5, 5, 5, 5]",
        "08:34:41.853 TX TTS 0x00040003 TTS_DATA len=2048 time=-402647883 #5754",
        "08:49:21.516 AA RX AUDIO_MEDIA(6) MEDIA_DATA 0x0000 len=8192  [ts=185165809302us]",
        "08:49:21.516 AA TX AUDIO_MEDIA(6) MEDIA_ACK 0x8004 len=4 | {session_id=3 ack=1}",
        "19:58:33.101 AA TX MIC(7) MEDIA_DATA 0x0000 len=4106",
        "19:58:33.140 AA RX MIC(7) MEDIA_ACK 0x8004 len=4 | {session_id=9 ack=1}",
        "08:48:58.469 META -> NAVI_NEXT_TURN_INFO (манёвр повторён) action=2 nextTurn=24",
        "08:48:58.470 TX CMD 0x00010030 NAVI_NEXT_TURN_INFO len=3597 {action=2}",
        "    0040  6C 61 77 61 74 20 51 75  72 61 6E 20 4B 61 72 65  |lawat Quran Kare|",
        "16:52:15.701 SRVCHECK | aa RX CONTROL(0) AUDIO_FOCUS_REQUEST 0x0012 len=2 | {request=4}",
    )
    private val keep = listOf(
        "08:34:41.842 AA RX AUDIO_SPEECH(4) MEDIA_START 0x8001 len=4 | {session_id=0 configuration_index=0}",
        "08:34:41.844 BRIDGE AA audio START ch4 (AUDIO_SPEECH) -> CarLife TTS",
        "08:34:41.377 AUDIO плееры (2): usage=NAVIGATION_GUIDANCE content=SONIFICATION, выход ?",
        "08:34:56.602 MIC open от AA: режим=авто, CarLife жив",
        "21:18:35.144 AUDIO A2DP 'VW BT 5075' при звуке через CarLife (AA MEDIA START): подсказка выключить звук мультимедиа по Bluetooth",
        "08:34:49.442 BRIDGE голосовая сессия: старт, MEDIA на паузу, данные MEDIA не пересылаю",
        "08:49:30.475 сессия: закрытие, причина: tcp6 CMD(7240): EOF (ГУ закрыла сокет)",
        "16:53:22.506 حفظ: страница 3, страниц 1, повторов 10, Ahmed_ibn_Ali_al-Ajamy_128kbps_ketaballah.net, страница целиком",
    )

    @Test
    fun protocolChatterIsNoise() = noise.forEach { assertTrue(it, AutoReport.noise(it)) }

    @Test
    fun soundAndMicEventsStay() = keep.forEach { assertFalse(it, AutoReport.noise(it)) }

    @Test
    fun tailKeepsTheLastEventsWithinTheBudget() {
        val f = File.createTempFile("hog-log", ".log")
        try {
            f.writeText((noise + keep + noise).joinToString("\n") + "\n")
            val t = AutoReport.meaningfulTail(f, 10_000)
            assertEquals(keep.joinToString("\n") + "\n", t)
            val small = AutoReport.meaningfulTail(f, 260)
            assertTrue("в малый бюджет - только последние события", small.contains("حفظ:") && !small.contains("MIC open"))
        } finally {
            f.delete()
        }
    }
}
