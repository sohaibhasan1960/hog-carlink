package com.yegorkapomidorka.headswap.prayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import java.util.Locale

/**
 * HOG CarLink: звук азана, икамы и азкаров. Foreground-сервис mediaPlayback: звук идёт как медиа телефона, поэтому во
 * время Android Auto он уходит в машину (канал MEDIA моста); короткие фразы (икама, зикр) - как подсказка навигации,
 * музыка приглушается. Медиа-сессия общая с AdhkarMediaService (PrayerSession): на экране машины видно, что звучит, и работает «Стоп».
 * Азкары читает синтез речи (арабский голос телефона); нет голоса - текст остаётся в уведомлении.
 */
class PrayerPlayer : Service() {
    enum class What { ADHAN, IQAMA, MORNING, EVENING, DHIKR, TEST_ADHAN }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: List<Pair<String, String>>? = null   // (текст для экрана, текст для речи)
    private var focus: AudioFocusRequest? = null
    private var current: What? = null
    private var title = ""
    private var spoken = 0
    private var total = 0
    private var shortSpeech = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("кнопка Стоп")
            return START_NOT_STICKY
        }
        val what = intent?.getStringExtra(EXTRA_WHAT)?.let { w -> What.values().firstOrNull { it.name == w } }
        if (what == null) {
            startForeground(NOTIF_ID, notification(getString(R.string.pr_title), ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val prayer = intent.getStringExtra(EXTRA_PRAYER)?.let { p -> Prayer.values().firstOrNull { it.name == p } }
        val text = intent.getStringExtra(EXTRA_TEXT)
        // азан важнее: короткий зикр во время азана/азкаров не перебивает
        if (current != null && what == What.DHIKR) {
            // вызвали startForegroundService: подтвердить foreground (повтор безвреден), иначе Android 14+ уронит процесс
            startForeground(NOTIF_ID, notification(title, ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            return START_NOT_STICKY
        }
        stopSound()
        current = what
        title = titleFor(what, prayer)
        startForeground(NOTIF_ID, notification(title, text ?: ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        startSession()
        AppLog.i("намаз: звук $what ${prayer ?: ""}")
        when (what) {
            What.ADHAN, What.TEST_ADHAN -> playAdhan()
            What.IQAMA -> speak(listOf(title to Adhkar.IQAMA), short = true)
            What.DHIKR -> speak(listOf((text ?: "") to (text ?: "")), short = true)
            What.MORNING, What.EVENING -> {
                val all = try {
                    Adhkar.parse(resources.openRawResource(R.raw.adhkar_ar).use { it.readBytes().toString(Charsets.UTF_8) })
                } catch (e: Exception) {
                    AppLog.e("намаз: азкары не прочитаны", e)
                    emptyList()
                }
                val list = if (what == What.MORNING) Adhkar.morning(all) else Adhkar.evening(all)
                val full = PrayerPrefs(this).fullRepeat
                speak(list.flatMap { item -> Adhkar.utterances(item, full).map { item.text to it } }, short = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun titleFor(w: What, p: Prayer?): String = when (w) {
        What.ADHAN, What.TEST_ADHAN -> getString(R.string.pr_adhan_of, prayerName(this, p ?: Prayer.DHUHR))
        What.IQAMA -> getString(R.string.pr_iqama_of, prayerName(this, p ?: Prayer.DHUHR))
        What.MORNING -> getString(R.string.pr_morning)
        What.EVENING -> getString(R.string.pr_evening)
        What.DHIKR -> getString(R.string.pr_dhikr)
    }

    // ---------------- азан ----------------

    private fun playAdhan() {
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)) {
            // идёт звонок: азан не играем поверх разговора, остаётся уведомление
            finish("фокус звука не дан (звонок?)")
            notify(title, getString(R.string.pr_adhan_now), ongoing = false)
            return
        }
        val prefs = PrayerPrefs(this)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC))
            val custom = prefs.adhanUri
            if (custom.isNotBlank()) {
                try {
                    mp.setDataSource(this, Uri.parse(custom))
                } catch (e: Exception) {
                    AppLog.i("намаз: свой файл азана не открылся (${e.message}), встроенная запись")
                    mp.reset()
                    mp.setAudioAttributes(attrs(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC))
                    rawSource(mp)
                }
            } else {
                rawSource(mp)
            }
            mp.setOnCompletionListener { finish("азан доиграл") }
            mp.setOnErrorListener { _, w, x -> AppLog.i("намаз: ошибка плеера $w/$x"); finish("ошибка плеера"); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            AppLog.e("намаз: азан не запустился", e)
            finish("азан не запустился")
        }
    }

    private fun rawSource(mp: MediaPlayer) {
        resources.openRawResourceFd(R.raw.adhan_default).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
    }

    // ---------------- речь ----------------

    private fun speak(lines: List<Pair<String, String>>, short: Boolean) {
        shortSpeech = short
        total = lines.size
        spoken = 0
        if (lines.isEmpty()) {
            finish("нечего читать")
            return
        }
        val usage = if (short) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA
        if (!requestFocus(if (short) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                usage, AudioAttributes.CONTENT_TYPE_SPEECH)) {
            finish("фокус звука не дан (звонок?)")
            return
        }
        if (ttsReady) say(lines) else {
            pendingSpeech = lines
            if (tts == null) tts = TextToSpeech(applicationContext, { status -> main.post { onTtsInit(status) } }, preferredEngine())
        }
    }

    private fun preferredEngine(): String? = try {
        packageManager.getPackageInfo(GOOGLE_TTS, 0)
        GOOGLE_TTS
    } catch (_: Exception) {
        null
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            AppLog.i("намаз: синтез речи не запустился ($status)")
            missingVoice()
            return
        }
        val r = t.setLanguage(Locale("ar"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            AppLog.i("намаз: нет арабского голоса (движок ${t.defaultEngine}, код $r)")
            missingVoice()
            return
        }
        ttsReady = true
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                main.post { onUtterance(id) }
            }

            override fun onDone(id: String) {
                if (id == LAST) main.post { finish("чтение закончено") }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) {
                main.post { finish("ошибка синтеза речи") }
            }
        })
        pendingSpeech?.let { say(it) }
        pendingSpeech = null
    }

    private val shown = HashMap<String, String>()

    private fun say(lines: List<Pair<String, String>>) {
        val t = tts ?: return
        val usage = if (shortSpeech) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA
        t.setAudioAttributes(attrs(usage, AudioAttributes.CONTENT_TYPE_SPEECH))
        t.setSpeechRate(if (shortSpeech) 0.95f else 0.9f)
        shown.clear()
        lines.forEachIndexed { i, (screen, speech) ->
            val id = if (i == lines.lastIndex) LAST else "u$i"
            shown[id] = screen
            t.speak(speech, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (i < lines.lastIndex) t.playSilentUtterance(if (shortSpeech) 200 else 700, TextToSpeech.QUEUE_ADD, "s$i")
        }
    }

    private fun onUtterance(id: String) {
        val text = shown[id] ?: return
        spoken++
        val progress = if (total > 1) " ($spoken/$total)" else ""
        notify(title + progress, text)
        PrayerSession.playing(this, title + progress, text)
    }

    /** Нет арабского голоса: текст в уведомлении, флаг для экрана (кнопка установки голоса). */
    private fun missingVoice() {
        voiceMissing = true
        val text = pendingSpeech?.firstOrNull()?.first ?: ""
        pendingSpeech = null
        stopSound()
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notify(title, getString(R.string.pr_no_voice) + "\n\n" + text, ongoing = false)
        stopSelf()
    }

    // ---------------- общее ----------------

    private fun attrs(usage: Int, content: Int) = AudioAttributes.Builder().setUsage(usage).setContentType(content).build()

    private fun requestFocus(gain: Int, usage: Int, content: Int): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        focus?.let { am.abandonAudioFocusRequest(it) }
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs(usage, content))
            .setOnAudioFocusChangeListener { change ->
                // долгая потеря (звонок, другой плеер по просьбе водителя) - замолкаем
                // потеря (другой плеер по просьбе водителя) или временная (звонок) - замолкаем
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) main.post { finish("фокус звука потерян ($change)") }
            }
            .build()
        focus = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun startSession() = PrayerSession.playing(this, title, "")

    private fun stopSound() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        pendingSpeech = null
        tts?.stop()
        getSystemService(AudioManager::class.java)?.let { am -> focus?.let { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    private fun finish(why: String) {
        AppLog.i("намаз: звук остановлен: $why")
        stopSound()
        current = null
        PrayerSession.stopped(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun notification(title: String, text: String, ongoing: Boolean = true): Notification {
        channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PRAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH_PLAY)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 1, Intent(this, PrayerPlayer::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, getString(R.string.pr_stop), stop).build())
        }
        return b.build()
    }

    private fun notify(title: String, text: String, ongoing: Boolean = true) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(if (ongoing) NOTIF_ID else NOTIF_INFO, notification(title, text, ongoing))
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.hog.carlink.PRAYER_STOP"
        const val EXTRA_WHAT = "what"
        const val EXTRA_PRAYER = "prayer"
        const val EXTRA_TEXT = "text"
        const val CH_PLAY = "prayer_play"
        const val CH_INFO = "prayer_info"
        const val CH_ADHAN = "prayer_adhan"
        const val NOTIF_ID = 7201
        const val NOTIF_INFO = 7202
        private const val LAST = "last"
        private const val GOOGLE_TTS = "com.google.android.tts"

        /** Арабского голоса не оказалось (для подсказки на экране). */
        @Volatile var voiceMissing = false

        fun start(ctx: Context, what: What, prayer: Prayer? = null, text: String? = null) {
            val i = Intent(ctx, PrayerPlayer::class.java).putExtra(EXTRA_WHAT, what.name).putExtra(EXTRA_PRAYER, prayer?.name).putExtra(EXTRA_TEXT, text)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                AppLog.i("намаз: звук не запущен: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun intent(ctx: Context, what: What): PendingIntent = PendingIntent.getForegroundService(ctx, what.ordinal + 10,
            Intent(ctx, PrayerPlayer::class.java).putExtra(EXTRA_WHAT, what.name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH_PLAY, ctx.getString(R.string.pr_ch_play), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_INFO, ctx.getString(R.string.pr_ch_info), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ADHAN, ctx.getString(R.string.pr_ch_adhan), NotificationManager.IMPORTANCE_HIGH))
        }

        fun prayerName(ctx: Context, p: Prayer): String = ctx.getString(when (p) {
            Prayer.FAJR -> R.string.pr_fajr
            Prayer.SUNRISE -> R.string.pr_sunrise
            Prayer.DHUHR -> R.string.pr_dhuhr
            Prayer.ASR -> R.string.pr_asr
            Prayer.MAGHRIB -> R.string.pr_maghrib
            Prayer.ISHA -> R.string.pr_isha
        })
    }
}
