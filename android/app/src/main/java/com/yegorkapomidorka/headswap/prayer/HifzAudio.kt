package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * HOG CarLink: звук плана заучивания (аят за аятом, отдельные файлы everyayah).
 *
 * Паузы между аятами - в самих записях: у «الحصري المعلّم» после аята 3-4 с тишины (время повторить за чтецом),
 * у ряда чтецов 1-2 с в начале файла. Свой SilenceSkippingAudioProcessor: тишина короче [MIN_SILENCE_US] остаётся
 * как есть (паузы самого чтения), длиннее - ужимается до [KEEP_SILENCE_US]. Включается skipSilenceEnabled
 * (HifzPrefs.trimPauses). Тишина конца одного файла и начала следующего идёт подряд и ужимается вместе.
 *
 * Буфер впрок больше стандартного: файлы короткие (5-20 с), сеть в машине рвётся - пусть заранее лежат
 * следующие аяты, а не один-два.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object HifzAudio {
    const val MIN_SILENCE_US = 700_000L
    const val KEEP_SILENCE_US = 500_000L

    fun renderers(ctx: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(),
                    // доля 0.2 от лишней тишины, но не больше KEEP; громкость в ужатой тишине - 10 %; порог - как у Media3
                    SilenceSkippingAudioProcessor(MIN_SILENCE_US, 0.2f, KEEP_SILENCE_US, 10, 1024),
                    SonicAudioProcessor(),
                ))
                .build()
    }

    /** Сокращать тишину (HifzPrefs.trimPauses); можно и во время игры. */
    fun trim(e: androidx.media3.exoplayer.ExoPlayer, on: Boolean) {
        e.skipSilenceEnabled = on
    }

    /** Впрок 1-3 мин вместо 50 с; старт после 2.5 с, как у стандартного. */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(60_000, 180_000, 2_500, 5_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
