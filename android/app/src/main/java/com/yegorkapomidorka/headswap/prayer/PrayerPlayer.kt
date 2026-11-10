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
    enum class What { ADHAN, IQAMA, MORNING, EVENING, DHIKR, TEST_ADHAN, QURAN, BOOK }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    /** Аудиотека играет через ExoPlayer: точная перемотка в Ogg Opus (у MediaPlayer - до границы страницы, секунды). */
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: List<Pair<String, String>>? = null   // (текст для экрана, текст для речи)
    private var focus: AudioFocusRequest? = null
    private var current: What? = null
    private var title = ""
    private var spoken = 0
    private var total = 0
    private var shortSpeech = false
    /** Играющая сура (QURAN); позиция сохраняется каждые 10 с и при остановке - «Продолжить». */
    private var quranSurah = 0
    private var quranPrepared = false
    private var quranPaused = false
    /** Пауза из-за временной потери фокуса (звонок): по возврату фокуса чтение продолжается само. */
    private var pausedByFocus = false
    /** Аудиокнига (BOOK): файл с главами; глава и позиция - по положению в файле. */
    private var book: AudioBook.Book? = null
    private var bookChapter = -1
    private var bookTicks = 0
    private val savePos = object : Runnable {
        override fun run() {
            if (current == What.BOOK) {
                // раз в секунду: смена главы сразу видна в машине; позиция сохраняется раз в 10 с
                if (++bookTicks % 10 == 0) saveQuranPos()
                if (book?.chapterAt(quranPos()) != bookChapter) quranState()
                main.postDelayed(this, 1_000)
                return
            }
            saveQuranPos()
            quranState()
            main.postDelayed(this, 10_000)
        }
    }

    /** Коран или аудиокнига: пауза, перемотка, скорость и «вперёд/назад» вместо стопа. */
    private val longForm get() = current == What.QURAN || current == What.BOOK

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("кнопка Стоп")
            return START_NOT_STICKY
        }
        if (intent?.action in QURAN_ACTIONS) {
            if (!longForm || (player == null && exo == null)) {
                // сервис поднят только ради команды, а суры нет - не висеть
                if (current == null) stopSelf()
                return START_NOT_STICKY
            }
            when (intent!!.action) {
                ACTION_Q_PAUSE -> pauseQuran(false)
                ACTION_Q_RESUME -> resumeQuran()
                // у книги полоса прокрутки в машине - внутри главы
                ACTION_Q_SEEK -> seekQuran(intent.getLongExtra(EXTRA_POS, 0L) + bookChapterStart())
                ACTION_Q_SPEED -> setQuranSpeed(com.yegorkapomidorka.headswap.quran.Quran.nextSpeed(QuranPrefs(this).speed))
                ACTION_Q_SPEED_SET -> setQuranSpeed(QuranPrefs(this).speed)
                ACTION_Q_NEXT -> if (current == What.BOOK) book?.let { b -> AudioBook.nextStart(b.chapters, quranPos())?.let { seekQuran(it) } }
                    else com.yegorkapomidorka.headswap.quran.Quran.next(quranSurah)?.let { switchSurah(it, 0) }
                ACTION_Q_PREV -> if (current == What.BOOK) book?.let { b -> seekQuran(AudioBook.backTarget(b.chapters, quranPos())) }
                    else com.yegorkapomidorka.headswap.quran.Quran.backTarget(quranSurah, quranPos())?.let { (s, p) ->
                        if (s == quranSurah) seekQuran(p) else switchSurah(s, p)
                    }
            }
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
        val surah = intent.getIntExtra(EXTRA_SURAH, 0)
        // азан важнее: короткий зикр во время азана/азкаров не перебивает
        if (current != null && what == What.DHIKR) {
            // вызвали startForegroundService: подтвердить foreground (повтор безвреден), иначе Android 14+ уронит процесс
            startForeground(NOTIF_ID, notification(title, ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            return START_NOT_STICKY
        }
        stopSound()
        current = what
        if (what == What.BOOK) book = AudioBook.Library(this).book(intent.getStringExtra(EXTRA_BOOK) ?: "")
        title = if (what == What.QURAN) quranTitle(surah) else titleFor(what, prayer)
        startForeground(NOTIF_ID, notification(title, text ?: ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        startSession()
        AppLog.i("намаз: звук $what ${prayer ?: ""}")
        when (what) {
            What.ADHAN, What.TEST_ADHAN -> playAdhan()
            What.IQAMA -> speak(listOf(title to Adhkar.IQAMA), short = true)
            What.DHIKR -> playShortDhikr(text ?: "")
            What.QURAN -> playQuran(surah, intent.getLongExtra(EXTRA_POS, 0L))
            What.BOOK -> playBook(intent.getLongExtra(EXTRA_POS, -1L))
            What.MORNING, What.EVENING -> {
                // своя запись азкаров (файл с телефона) вместо синтеза речи
                val file = PrayerPrefs(this).adhkarUri(what == What.MORNING)
                if (file.isNotBlank()) {
                    playFile(file)
                    return START_NOT_STICKY
                }
                val all = try {
                    Adhkar.parse(resources.openRawResource(R.raw.adhkar_ar).use { it.readBytes().toString(Charsets.UTF_8) })
                } catch (e: Exception) {
                    AppLog.e("намаз: азкары не прочитаны", e)
                    emptyList()
                }
                val list = if (what == What.MORNING) Adhkar.morning(all) else Adhkar.evening(all)
                val full = PrayerPrefs(this).fullRepeat
                // живой голос (записи «Крепости мусульманина»); синтез речи - по выбору или без интернета
                if (PrayerPrefs(this).adhkarHuman) {
                    playHuman(list, full)
                    return START_NOT_STICKY
                }
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
        What.QURAN -> quranTitle(quranSurah)
        What.BOOK -> book?.let { AudioBook.displayName(it.name) } ?: getString(R.string.ab_title)
    }

    private fun quranTitle(surah: Int): String =
        getString(R.string.qr_now, com.yegorkapomidorka.headswap.quran.Quran.name(surah.coerceIn(1, 114)), QuranPrefs(this).reciter.let { r ->
            if (resources.configuration.locales[0].language == "ar") r.nameAr else r.nameEn
        })

    // ---------------- Коран (поток) ----------------

    private fun playQuran(surah0: Int, startMs: Long) {
        val surah = surah0.coerceIn(1, com.yegorkapomidorka.headswap.quran.Quran.SURAHS)
        quranSurah = surah
        quranPrepared = false
        quranPaused = false
        pausedByFocus = false
        quranActive = true
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)) {
            finish("фокус звука не дан (звонок?)")
            return
        }
        val prefs = QuranPrefs(this)
        prefs.lastSurah = surah
        val url = prefs.reciter.url(surah)
        val mp = MediaPlayer()
        player = mp
        quranState(startMs)
        try {
            mp.setAudioAttributes(attrs(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
            mp.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                quranPrepared = true
                if (startMs > 0) it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                it.start()
                applySpeed(it)
                main.removeCallbacks(savePos)
                main.postDelayed(savePos, 10_000)
                notify(title, "")
                quranState()
            }
            mp.setOnSeekCompleteListener { if (player === it) quranState() }
            mp.setOnCompletionListener {
                prefs.lastPosMs = 0
                val next = com.yegorkapomidorka.headswap.quran.Quran.next(surah)
                if (next != null && prefs.continuous) {
                    // следующая сура тем же сервисом: foreground уже есть, фокус запросится заново
                    switchSurah(next, 0)
                } else {
                    finish("сура дочитана")
                }
            }
            mp.setOnErrorListener { _, w, x ->
                AppLog.i("Коран: ошибка потока $w/$x ($url)")
                finish("ошибка потока")
                notify(title, getString(R.string.qr_net_error), ongoing = false)
                true
            }
            notify(title, getString(R.string.qr_loading))
            mp.prepareAsync()
            AppLog.i("Коран: сура $surah, ${prefs.reciter}, с ${startMs / 1000} с")
        } catch (e: Exception) {
            AppLog.e("Коран: поток не запустился", e)
            finish("поток не запустился")
        }
    }

    /** Другая сура тем же сервисом (следующая по окончании, «вперёд»/«назад» в машине). */
    private fun switchSurah(surah: Int, posMs: Long) {
        main.removeCallbacks(savePos)
        saveQuranPos()
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        title = quranTitle(surah)
        playQuran(surah, posMs)
    }

    private fun quranPos(): Long = try {
        if (!quranPrepared) 0L else exo?.currentPosition ?: player?.currentPosition?.toLong() ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** Состояние суры для машины и шторки: буферизация, игра или пауза, позиция, длительность, скорость. */
    private fun quranState(posOverride: Long? = null) {
        if (current == What.BOOK) return bookState(posOverride)
        if (current != What.QURAN) return
        val mp = player
        val state = when {
            !quranPrepared -> android.media.session.PlaybackState.STATE_BUFFERING
            quranPaused -> android.media.session.PlaybackState.STATE_PAUSED
            else -> android.media.session.PlaybackState.STATE_PLAYING
        }
        val dur = try {
            if (quranPrepared) mp?.duration?.toLong()?.takeIf { it > 0 } ?: 0L else 0L
        } catch (_: Exception) {
            0L
        }
        val r = QuranPrefs(this).reciter
        val sub = if (resources.configuration.locales[0].language == "ar") r.nameAr else r.nameEn
        PrayerSession.quran(this, com.yegorkapomidorka.headswap.quran.Quran.name(quranSurah), sub, state,
            posOverride ?: quranPos(), dur, QuranPrefs(this).speed)
    }

    private fun applySpeed(mp: MediaPlayer) {
        val sp = QuranPrefs(this).speed
        try {
            // после start(): на паузе смена скорости запустила бы звук
            if (mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(sp)
        } catch (e: Exception) {
            AppLog.i("Коран: скорость $sp не применилась: ${e.message}")
        }
    }

    private fun pauseQuran(byFocus: Boolean) {
        if (player == null && exo == null) return
        if (!quranPrepared || quranPaused) return
        try {
            exo?.pause() ?: player?.pause()
        } catch (_: Exception) {
        }
        quranPaused = true
        pausedByFocus = byFocus
        saveQuranPos(force = true)
        main.removeCallbacks(savePos)
        notify(title, getString(R.string.qr_paused))
        quranState()
    }

    private fun resumeQuran() {
        if (player == null && exo == null) return
        if (!quranPaused) return
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)) return
        quranPaused = false
        pausedByFocus = false
        try {
            val e = exo
            if (e != null) e.play() else player?.let { it.start(); applySpeed(it) }
        } catch (_: Exception) {
        }
        main.removeCallbacks(savePos)
        main.postDelayed(savePos, if (current == What.BOOK) 1_000 else 10_000)
        notify(title, "")
        quranState()
    }

    private fun seekQuran(posMs: Long) {
        if (player == null && exo == null) return
        if (!quranPrepared) return
        try {
            exo?.seekTo(posMs.coerceAtLeast(0)) ?: player?.seekTo(posMs.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) {
        }
        quranState(posMs)
    }

    private fun setQuranSpeed(v: Float) {
        QuranPrefs(this).speed = v
        val mp = player
        exo?.setPlaybackSpeed(v)
        if (mp != null && quranPrepared && !quranPaused) applySpeed(mp)
        AppLog.i("Коран: скорость ${com.yegorkapomidorka.headswap.quran.Quran.speedLabel(v)}")
        quranState()
    }

    private fun saveQuranPos(force: Boolean = false) {
        if (current == What.BOOK) {
            val b = book ?: return
            val e = exo ?: return
            try {
                if (quranPrepared && (force || e.isPlaying)) AudioBook.Library(this).setPos(b.id, e.currentPosition)
            } catch (_: Exception) {
            }
            return
        }
        if (force) {
            if (current == What.QURAN && quranPrepared) QuranPrefs(this).lastPosMs = quranPos()
            return
        }
        val mp = player ?: return
        if (current != What.QURAN) return
        try {
            // только пока играет: после конца суры позиция уже сброшена в 0
            if (mp.isPlaying) QuranPrefs(this).lastPosMs = mp.currentPosition.toLong()
        } catch (_: Exception) {
        }
    }

    // ---------------- аудиокнига (файл с главами) ----------------

    private fun bookChapterStart(): Long {
        val b = book ?: return 0L
        return if (current == What.BOOK && bookChapter >= 0) b.start(bookChapter) else 0L
    }

    private fun playBook(pos0: Long) {
        val b = book
        if (b == null) {
            finish("книги нет")
            return
        }
        quranPrepared = false
        quranPaused = false
        pausedByFocus = false
        quranActive = true
        bookChapter = -1
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)) {
            finish("фокус звука не дан (звонок?)")
            return
        }
        // дослушанная до конца - сначала
        val startMs = (if (pos0 >= 0) pos0 else b.lastPosMs).let { if (b.durationMs > 0 && it >= b.durationMs - 1_000) 0L else it }
        bookState(startMs)
        try {
            val e = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
            exo = e
            // фокус звука ведём сами (как у Корана): пауза при звонке и продолжение после
            e.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            e.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            e.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (exo !== e) return
                    when (state) {
                        androidx.media3.common.Player.STATE_READY -> if (!quranPrepared) {
                            quranPrepared = true
                            // длительность при добавлении не узнали - взять у плеера (иначе у последней главы 0:00)
                            if (b.durationMs <= 0 && e.duration > 0) {
                                AudioBook.Library(this@PrayerPlayer).setDuration(b.id, e.duration)
                                book = AudioBook.Library(this@PrayerPlayer).book(b.id) ?: book
                            }
                            main.removeCallbacks(savePos)
                            main.postDelayed(savePos, 1_000)
                            bookState()
                        }
                        androidx.media3.common.Player.STATE_ENDED -> {
                            AudioBook.Library(this@PrayerPlayer).setPos(b.id, 0)
                            finish("книга дослушана")
                        }
                    }
                }

                override fun onPositionDiscontinuity(old: androidx.media3.common.Player.PositionInfo, new: androidx.media3.common.Player.PositionInfo, reason: Int) {
                    if (exo === e && quranPrepared) bookState()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (exo !== e) return
                    // файл удалён или разрешение отозвано
                    AppLog.i("книга: ошибка файла ${error.errorCodeName}: ${error.message}")
                    finish("ошибка файла книги")
                    notify(title, getString(R.string.pr_file_failed), ongoing = false)
                }
            })
            e.setMediaItem(androidx.media3.common.MediaItem.fromUri(b.uri), startMs)
            e.setPlaybackSpeed(QuranPrefs(this).speed)
            e.playWhenReady = true
            e.prepare()
            AppLog.i("книга: глав ${b.chapters.size}, с ${startMs / 1000} с")
        } catch (e: Exception) {
            AppLog.e("книга: файл не открылся", e)
            finish("файл книги не открылся")
            notify(title, getString(R.string.pr_file_failed), ongoing = false)
        }
    }

    /** Глава для машины: имя главы, полоса прокрутки и длительность - внутри главы, а не всего файла. */
    private fun bookState(posOverride: Long? = null) {
        val b = book ?: return
        val abs = posOverride ?: quranPos()
        val i = b.chapterAt(abs)
        val state = when {
            !quranPrepared -> android.media.session.PlaybackState.STATE_BUFFERING
            quranPaused -> android.media.session.PlaybackState.STATE_PAUSED
            else -> android.media.session.PlaybackState.STATE_PLAYING
        }
        val dur = b.length(i)
        val chTitle = b.title(i, getString(R.string.ab_chapter))
        if (i != bookChapter) {
            bookChapter = i
            notify(title, chTitle)
        }
        PrayerSession.quran(this, chTitle, "$title · ${i + 1}/${b.chapters.size}", state, (abs - b.start(i)).coerceAtLeast(0), dur, QuranPrefs(this).speed)
    }

    /** Своя запись (азкары утра/вечера) с телефона. */
    private fun playFile(uri: String) {
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)) {
            finish("фокус звука не дан (звонок?)")
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
            mp.setDataSource(this, Uri.parse(uri))
            mp.setOnCompletionListener { finish("запись доиграла") }
            mp.setOnErrorListener { _, w, x -> AppLog.i("намаз: ошибка записи $w/$x"); finish("ошибка записи"); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            AppLog.e("намаз: своя запись не открылась", e)
            finish("своя запись не открылась")
            notify(title, getString(R.string.pr_file_failed), ongoing = false)
        }
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
            // быстрее без смены тона (растяжение времени)
            val speed = prefs.adhanSpeed
            if (speed != 1f) {
                try {
                    mp.playbackParams = mp.playbackParams.setSpeed(speed)
                } catch (e: Exception) {
                    AppLog.i("намаз: скорость азана не задана: ${e.message}")
                }
            }
            mp.start()
        } catch (e: Exception) {
            AppLog.e("намаз: азан не запустился", e)
            finish("азан не запустился")
        }
    }

    private fun rawSource(mp: MediaPlayer) {
        val res = if (PrayerPrefs(this).adhanBuiltin == PrayerPrefs.ADHAN_FAST) R.raw.adhan_fast else R.raw.adhan_default
        resources.openRawResourceFd(res).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
    }

    // ---------------- азкары живым голосом ----------------

    private var humanQueue: List<Pair<Adhkar.Item, Int>> = emptyList()
    private var humanIndex = 0
    private var humanRep = 0
    /** Поколение очереди: после остановки запоздалые колбэки скачивания не играют. */
    private var humanGen = 0

    /**
     * Короткий зикр-напоминание: запись живым голосом (Adhkar.SHORT_AUDIO), если включён «живой голос»;
     * поверх музыки с приглушением, как синтез. Нет записи и сети - синтез речи.
     */
    private fun playShortDhikr(text: String) {
        val url = Adhkar.SHORT_AUDIO[text]
        if (!PrayerPrefs(this).adhkarHuman || url == null) {
            speak(listOf(text to text), short = true)
            return
        }
        val gen = ++humanGen
        val app = applicationContext
        Thread({
            val f = AdhkarAudio.fetch(app, url)
            main.post {
                if (gen != humanGen) return@post
                if (f == null) {
                    AppLog.i("зикр: нет записи, синтез речи")
                    speak(listOf(text to text), short = true)
                } else {
                    playShortFile(gen, f, text)
                }
            }
        }, "dhikr-audio").start()
    }

    private fun playShortFile(gen: Int, file: java.io.File, text: String) {
        val usage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, usage, AudioAttributes.CONTENT_TYPE_SPEECH)) {
            finish("фокус звука не дан (звонок?)")
            return
        }
        notify(title, text)
        PrayerSession.playing(this, title, text)
        player?.release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs(usage, AudioAttributes.CONTENT_TYPE_SPEECH))
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { if (gen == humanGen) finish("зикр прочитан") }
            mp.setOnErrorListener { _, w, x ->
                AppLog.i("зикр: ошибка записи $w/$x")
                file.delete()
                if (gen == humanGen) finish("ошибка записи зикра")
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            AppLog.e("зикр: запись не открылась", e)
            file.delete()
            finish("запись зикра не открылась")
        }
    }

    private fun playHuman(list: List<Adhkar.Item>, full: Boolean) {
        if (!requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)) {
            finish("фокус звука не дан (звонок?)")
            return
        }
        humanQueue = list.filter { it.audio.isNotBlank() }.map { it to Adhkar.plays(it, full) }
        humanIndex = 0
        humanRep = 0
        total = humanQueue.size
        humanNext(++humanGen)
    }

    private fun humanNext(gen: Int) {
        if (gen != humanGen) return
        if (humanIndex >= humanQueue.size) {
            finish("азкары прочитаны")
            return
        }
        val (item, _) = humanQueue[humanIndex]
        val app = applicationContext
        Thread({
            val f = AdhkarAudio.fetch(app, item.audio)
            // следующую запись - заранее, пока играет эта
            humanQueue.getOrNull(humanIndex + 1)?.let { AdhkarAudio.fetch(app, it.first.audio) }
            main.post { if (gen == humanGen) humanPlay(gen, f) }
        }, "adhkar-audio").start()
    }

    private fun humanPlay(gen: Int, file: java.io.File?) {
        val (item, plays) = humanQueue[humanIndex]
        if (file == null) {
            // нет интернета и записи в кэше: остаток - голосом телефона
            AppLog.i("азкары: нет записи, дальше синтез речи с №${humanIndex + 1}")
            notify(title, getString(R.string.pr_human_offline))
            val rest = humanQueue.drop(humanIndex).map { it.first }
            val full = PrayerPrefs(this).fullRepeat
            humanQueue = emptyList()
            speak(rest.flatMap { i -> Adhkar.utterances(i, full).map { i.text to it } }, short = false)
            return
        }
        val progress = " (${humanIndex + 1}/${humanQueue.size})"
        notify(title + progress, item.text)
        PrayerSession.playing(this, title + progress, item.text)
        player?.release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
            mp.setDataSource(file.path)
            mp.setOnCompletionListener {
                if (gen != humanGen) return@setOnCompletionListener
                humanRep++
                if (humanRep < plays) {
                    it.seekTo(0)
                    it.start()
                } else {
                    humanRep = 0
                    humanIndex++
                    humanNext(gen)
                }
            }
            mp.setOnErrorListener { _, w, x ->
                AppLog.i("азкары: ошибка записи $w/$x, пропускаю")
                file.delete()
                humanRep = 0
                humanIndex++
                humanNext(gen)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            AppLog.e("азкары: запись не играет", e)
            file.delete()
            humanIndex++
            humanNext(gen)
        }
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
        // голос из настроек; не выбран - мужской (голос телефона по умолчанию у Google - женский)
        try {
            val want = PrayerPrefs(this).ttsVoice
            val voices = t.voices.orEmpty()
            val v = voices.firstOrNull { it.name == want } ?: TtsVoices.preferredMale(voices.map { it.name })?.let { n -> voices.first { it.name == n } }
            if (v != null) t.voice = v
            AppLog.i("намаз: голос ${v?.name ?: "по умолчанию"}")
        } catch (e: Exception) {
            AppLog.i("намаз: голос не выбран: ${e.message}")
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
                // Коран при звонке - пауза и продолжение после него
                main.post {
                    when {
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && longForm -> pauseQuran(true)
                        change == AudioManager.AUDIOFOCUS_GAIN && longForm && pausedByFocus -> resumeQuran()
                        change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> finish("фокус звука потерян ($change)")
                    }
                }
            }
            .build()
        focus = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun startSession() = PrayerSession.playing(this, title, "")

    private fun stopSound() {
        saveQuranPos()
        main.removeCallbacks(savePos)
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        exo?.release()
        exo = null
        quranActive = false
        quranPrepared = false
        quranPaused = false
        pausedByFocus = false
        bookChapter = -1
        humanGen++
        humanQueue = emptyList()
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
        const val ACTION_Q_PAUSE = "com.hog.carlink.QURAN_PAUSE"
        const val ACTION_Q_RESUME = "com.hog.carlink.QURAN_RESUME"
        const val ACTION_Q_SEEK = "com.hog.carlink.QURAN_SEEK"
        const val ACTION_Q_SPEED = "com.hog.carlink.QURAN_SPEED"
        const val ACTION_Q_NEXT = "com.hog.carlink.QURAN_NEXT"
        const val ACTION_Q_PREV = "com.hog.carlink.QURAN_PREV"
        /** Скорость уже выбрана на телефоне (QuranPrefs.speed) - применить к играющей суре. */
        const val ACTION_Q_SPEED_SET = "com.hog.carlink.QURAN_SPEED_SET"
        private val QURAN_ACTIONS = setOf(ACTION_Q_PAUSE, ACTION_Q_RESUME, ACTION_Q_SEEK, ACTION_Q_SPEED, ACTION_Q_NEXT, ACTION_Q_PREV, ACTION_Q_SPEED_SET)

        /** Сура загружена (играет или на паузе): кнопки машины управляют ею, а не азкарами. */
        @Volatile var quranActive = false

        /** Команда играющей суре; сервис уже работает (foreground), поэтому обычный startService. */
        fun control(ctx: Context, action: String, posMs: Long = 0) {
            try {
                ctx.startService(Intent(ctx, PrayerPlayer::class.java).setAction(action).putExtra(EXTRA_POS, posMs))
            } catch (e: Exception) {
                AppLog.i("Коран: команда $action не дошла: ${e.javaClass.simpleName}")
            }
        }
        const val EXTRA_WHAT = "what"
        const val EXTRA_PRAYER = "prayer"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SURAH = "surah"
        const val EXTRA_POS = "pos"
        const val EXTRA_BOOK = "book"
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

        /** Сура [surah] с позиции [posMs] (0 - с начала). */
        fun quran(ctx: Context, surah: Int, posMs: Long = 0) {
            val i = Intent(ctx, PrayerPlayer::class.java).putExtra(EXTRA_WHAT, What.QURAN.name).putExtra(EXTRA_SURAH, surah).putExtra(EXTRA_POS, posMs)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                AppLog.i("Коран: звук не запущен: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** Аудиокнига [id] с позиции [posMs] в файле; -1 - с сохранённой. */
        fun book(ctx: Context, id: String, posMs: Long = -1) {
            val i = Intent(ctx, PrayerPlayer::class.java).putExtra(EXTRA_WHAT, What.BOOK.name).putExtra(EXTRA_BOOK, id).putExtra(EXTRA_POS, posMs)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                AppLog.i("книга: звук не запущен: ${e.javaClass.simpleName}: ${e.message}")
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
