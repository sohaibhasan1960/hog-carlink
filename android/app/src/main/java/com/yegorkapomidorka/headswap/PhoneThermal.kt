package com.yegorkapomidorka.headswap

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.yegorkapomidorka.headswap.aa.ThermalSource

/** Нагрев телефона из PowerManager: статус (API 29) и прогноз headroom на 10 с (API 30; на старых и без HAL - NaN). */
class PhoneThermal(ctx: Context) : ThermalSource {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)

    /**
     * Звонок: режим аудио IN_CALL (GSM/VoLTE) или IN_COMMUNICATION с идущей записью микрофона (VoIP: WhatsApp и т.п.).
     * HOG CarLink: голосовое сообщение WhatsApp тоже включает IN_COMMUNICATION, но микрофон не пишет - это не звонок
     * (лог 2026-11-03: «звонки» по 6-722 с без SCO, видео зря падало до 15 fps). Записи других приложений видны без разрешений.
     */
    override fun inCall(): Boolean = try {
        val m = am?.mode ?: AudioManager.MODE_NORMAL
        m == AudioManager.MODE_IN_CALL ||
            (m == AudioManager.MODE_IN_COMMUNICATION && am?.activeRecordingConfigurations?.isNotEmpty() == true)
    } catch (_: Exception) {
        false
    }

    override fun status(): Int = try {
        pm?.currentThermalStatus ?: 0
    } catch (_: Exception) {
        0
    }

    override fun headroom(): Float = if (Build.VERSION.SDK_INT < 30) Float.NaN else try {
        pm?.getThermalHeadroom(HEADROOM_FORECAST_S) ?: Float.NaN
    } catch (_: Exception) {
        Float.NaN
    }

    private companion object {
        const val HEADROOM_FORECAST_S = 10
    }
}
