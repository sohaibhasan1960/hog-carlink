package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yegorkapomidorka.headswap.AppLog
import java.io.File
import java.util.Locale

/**
 * HOG CarLink, отладка (adb: --es cmd tts_voices): образец каждым арабским голосом движка в
 * files/tts/<голос>.wav - по высоте тона видно, какой голос мужской.
 */
object TtsProbe {
    fun dump(ctx: Context) {
        val app = ctx.applicationContext
        val dir = File(app.getExternalFilesDir(null), "tts").apply { mkdirs() }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(app, { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                AppLog.i("tts_voices: движок не запустился ($status)")
                return@TextToSpeech
            }
            t.setLanguage(Locale("ar"))
            val voices = t.voices?.filter { it.locale.language == "ar" && !it.isNetworkConnectionRequired }?.sortedBy { it.name }.orEmpty()
            AppLog.i("tts_voices: ${voices.size} голосов: ${voices.joinToString { it.name + (if (it.features?.contains("notInstalled") == true) "(нет)" else "") }}")
            var left = voices.size
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) {
                    AppLog.i("tts_voices: готово $id")
                    if (--left == 0) t.shutdown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) {
                    AppLog.i("tts_voices: ошибка $id")
                    if (--left == 0) t.shutdown()
                }
            })
            for (v in voices) {
                t.voice = v
                t.synthesizeToFile(Adhkar.SHORT[0], null, File(dir, v.name + ".wav"), v.name)
            }
        }, "com.google.android.tts")
    }
}
