package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.quran.Quran
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HOG CarLink: «Намаз и азкары» на экране машины - медиа-приложение Android Auto. Список: утренние и вечерние азкары
 * (слушать) и время намаза на сегодня (нажатие - азан). Звук играет PrayerPlayer; сессия общая ([PrayerSession]),
 * поэтому кнопка «Стоп» на экране машины работает и для азана по будильнику.
 * Для приложений не из Play в Android Auto нужно включить «Неизвестные источники» (настройки разработчика AA).
 */
class AdhkarMediaService : MediaBrowserService() {
    override fun onCreate() {
        super.onCreate()
        sessionToken = PrayerSession.get(this).sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val out = ArrayList<MediaBrowser.MediaItem>()
        when (parentId) {
            ROOT -> {
                out.add(item(PrayerPlayer.What.MORNING.name, getString(R.string.pr_morning), null, playable = true))
                out.add(item(PrayerPlayer.What.EVENING.name, getString(R.string.pr_evening), null, playable = true))
                out.add(item(QURAN, getString(R.string.qr_title), QuranPrefs(this).reciter.nameAr, playable = false))
                val books = AudioBook.Library(this).books()
                // одна книга - сразу её главы, несколько - список книг
                if (books.size == 1) out.add(item(BOOK + books[0].id, AudioBook.displayName(books[0].name), getString(R.string.ab_chapters, books[0].chapters.size), playable = false))
                else if (books.isNotEmpty()) out.add(item(LIBRARY, getString(R.string.ab_title), getString(R.string.ab_books, books.size), playable = false))
                out.add(item(TIMES, getString(R.string.pr_car_times), PrayerPrefs(this).place.ifBlank { null }, playable = false))
            }
            QURAN -> {
                val q = QuranPrefs(this)
                if (q.lastSurah in 1..Quran.SURAHS) {
                    out.add(item("QURAN:${q.lastSurah}:${q.lastPosMs}", getString(R.string.qr_continue, Quran.name(q.lastSurah)), null, playable = true))
                }
                for (n in 1..Quran.SURAHS) out.add(item("QURAN:$n:0", "$n. ${Quran.name(n)}", null, playable = true))
            }
            LIBRARY -> for (b in AudioBook.Library(this).books()) {
                out.add(item(BOOK + b.id, AudioBook.displayName(b.name), getString(R.string.ab_chapters, b.chapters.size), playable = false))
            }
            TIMES -> {
                val prefs = PrayerPrefs(this)
                val zone = ZoneId.systemDefault()
                val day = prefs.day(LocalDate.now(zone), zone)
                if (day == null) {
                    out.add(item(PrayerPlayer.What.TEST_ADHAN.name, getString(R.string.pr_no_place), null, playable = true))
                } else {
                    val hm = DateTimeFormatter.ofPattern("HH:mm")
                    for (p in Prayer.values()) {
                        val t = hm.format(Instant.ofEpochMilli(day[p]).atZone(zone))
                        val sub = if (p != Prayer.SUNRISE && prefs.iqamaOn)
                            getString(R.string.pr_iqama_short, hm.format(Instant.ofEpochMilli(day[p] + prefs.iqamaMinutes(p) * 60_000L).atZone(zone))) else null
                        out.add(item(PrayerPlayer.What.TEST_ADHAN.name + ":" + p.name, "${PrayerPlayer.prayerName(this, p)}  $t", sub, playable = true))
                    }
                }
            }
        }
        if (parentId.startsWith(BOOK)) AudioBook.Library(this).book(parentId.removePrefix(BOOK))?.let { b ->
            // «Продолжить» с сохранённого места, затем все главы
            if (b.lastPosMs > 0) {
                val i = b.chapterAt(b.lastPosMs)
                out.add(item("BOOK:${b.id}:-1", getString(R.string.qr_continue, b.title(i, getString(R.string.ab_chapter))), null, playable = true))
            }
            val label = getString(R.string.ab_chapter)
            for ((i, c) in b.chapters.withIndex()) {
                out.add(item("BOOK:${b.id}:${c.startMs}", b.title(i, label), b.length(i).takeIf { it > 0 }?.let { AudioBook.clock(it) }, playable = true))
            }
        }
        result.sendResult(out)
    }

    private fun item(id: String, title: String, sub: String?, playable: Boolean): MediaBrowser.MediaItem =
        MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
            if (playable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE)

    companion object {
        private const val ROOT = "root"
        private const val TIMES = "times"
        private const val QURAN = "quran"
        private const val LIBRARY = "library"
        private const val BOOK = "book:"
    }
}

/** Одна медиа-сессия на процесс: её видит Android Auto (AdhkarMediaService) и ею управляет PrayerPlayer. */
object PrayerSession {
    @Volatile private var session: MediaSession? = null

    @Synchronized
    fun get(ctx: Context): MediaSession {
        session?.let { return it }
        val app = ctx.applicationContext
        val s = MediaSession(app, "hog-prayer")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                if (mediaId.startsWith("BOOK:")) {
                    val p = mediaId.split(':')
                    PrayerPlayer.book(app, p.getOrNull(1) ?: return, p.getOrNull(2)?.toLongOrNull() ?: -1)
                    return
                }
                if (mediaId.startsWith("QURAN:")) {
                    val p = mediaId.split(':')
                    PrayerPlayer.quran(app, p.getOrNull(1)?.toIntOrNull() ?: 1, p.getOrNull(2)?.toLongOrNull() ?: 0)
                    return
                }
                val what = PrayerPlayer.What.values().firstOrNull { it.name == mediaId.substringBefore(':') } ?: return
                val prayer = mediaId.substringAfter(':', "").let { n -> Prayer.values().firstOrNull { it.name == n } }
                PrayerPlayer.start(app, what, prayer)
            }

            override fun onPlay() {
                // сура на паузе - продолжить её
                if (PrayerPlayer.controllable) return PrayerPlayer.control(app, PrayerPlayer.ACTION_Q_RESUME)
                // «Играть» без выбора: азкары по времени суток
                val morning = java.time.LocalTime.now().hour < 12
                PrayerPlayer.start(app, if (morning) PrayerPlayer.What.MORNING else PrayerPlayer.What.EVENING)
            }

            /** Голосом в машине: «включи азкары вечера в HOG CarLink». */
            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                // «شغّل أذكار النوم»: файл из аудиотеки по имени важнее встроенных азкаров
                AudioBook.Library(app).books().let { books -> AudioBook.match(books.map { it.name }, query)?.let { return PrayerPlayer.book(app, books[it].id) } }
                // «أذان الفجر» - азан, а не сура аль-Фаджр: ключевые слова азана/азкаров важнее названий сур
                val kw = keyword(query)
                val surah = if (kw == null) surahFor(query) else null
                when {
                    kw != null -> PrayerPlayer.start(app, kw)
                    surah != null -> PrayerPlayer.quran(app, surah)
                    else -> PrayerPlayer.start(app, whatFor(query))
                }
            }

            override fun onStop() = stopPlayer(app)

            // Коран, книга и азкары записью: настоящая пауза (позиция и полоса прокрутки остаются); азан и синтез речи - стоп
            override fun onPause() = if (PrayerPlayer.controllable) PrayerPlayer.control(app, PrayerPlayer.ACTION_Q_PAUSE) else stopPlayer(app)

            override fun onSeekTo(pos: Long) {
                if (PrayerPlayer.controllable) PrayerPlayer.control(app, PrayerPlayer.ACTION_Q_SEEK, pos)
            }

            override fun onSkipToNext() {
                if (PrayerPlayer.controllable) PrayerPlayer.control(app, PrayerPlayer.ACTION_Q_NEXT)
            }

            override fun onSkipToPrevious() {
                if (PrayerPlayer.controllable) PrayerPlayer.control(app, PrayerPlayer.ACTION_Q_PREV)
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == CA_SPEED && PrayerPlayer.quranActive) PrayerPlayer.control(app, PrayerPlayer.ACTION_Q_SPEED)
            }
        })
        stopped(s)
        session = s
        return s
    }

    private fun stopPlayer(ctx: Context) {
        try {
            ctx.startService(Intent(ctx, PrayerPlayer::class.java).setAction(PrayerPlayer.ACTION_STOP))
        } catch (_: Exception) {
        }
    }

    /** Что включить по голосовому запросу: азан, утренние или вечерние азкары; без уточнения - по времени суток. */
    fun whatFor(query: String?): PrayerPlayer.What = keyword(query)
        ?: if (java.time.LocalTime.now().hour < 12) PrayerPlayer.What.MORNING else PrayerPlayer.What.EVENING

    /** Явная просьба: азан, азкары вечера или утра; иначе null. */
    fun keyword(query: String?): PrayerPlayer.What? {
        val q = query?.lowercase() ?: return null
        return when {
            listOf("أذان", "اذان", "adhan", "azan", "азан").any { it in q } -> PrayerPlayer.What.TEST_ADHAN
            listOf("مساء", "evening", "вечер").any { it in q } -> PrayerPlayer.What.EVENING
            listOf("صباح", "morning", "утр").any { it in q } -> PrayerPlayer.What.MORNING
            else -> null
        }
    }

    /**
     * «سورة الكهف» / «الكهف» / «كهف» в голосовом запросе -> номер суры. Сравнение целыми словами (иначе «ق» найдётся
     * внутри любого слова), без огласовок и с одинаковыми алифами/та-марбута; длинные названия первыми.
     */
    fun surahFor(query: String?): Int? {
        fun norm(x: String) = x.replace(Regex("[\u064B-\u0652\u0670\u0640]"), "")
            .replace('ة', 'ه').replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        val words = norm(query ?: return null).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        fun same(q: String, n: String) = q == n || (n.startsWith("ال") && n.length > 3 && q == n.removePrefix("ال"))
        return Quran.NAMES.withIndex().sortedByDescending { it.value.length }.firstOrNull { (_, name) ->
            val nw = norm(name).split(' ')
            (0..words.size - nw.size).any { s -> nw.indices.all { k -> same(words[s + k], nw[k]) } }
        }?.index?.plus(1)
    }

    fun playing(ctx: Context, title: String, text: String) {
        val s = get(ctx)
        quranMetaKey = ""
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text.take(120))
            .build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .build())
        s.isActive = true
    }

    const val CA_SPEED = "hog.quran.speed"
    private var quranMetaKey = ""

    /**
     * Сура: длительность и точная позиция со скоростью - Android Auto рисует полосу прокрутки и сам ведёт её между
     * обновлениями; перемотка, пауза, следующая/предыдущая сура и кнопка скорости (×1 -> ×1.5 -> ×2).
     */
    fun quran(ctx: Context, title: String, sub: String, state: Int, posMs: Long, durMs: Long, speed: Float) {
        val s = get(ctx)
        val key = "$title|$sub|$durMs"
        if (key != quranMetaKey) {
            quranMetaKey = key
            s.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                .apply { if (durMs > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, durMs) }
                .build())
        }
        val label = ctx.getString(R.string.qr_speed_btn, Quran.speedLabel(speed))
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
            .addCustomAction(PlaybackState.CustomAction.Builder(CA_SPEED, label, R.drawable.ic_speed).build())
            .setState(state, posMs, if (state == PlaybackState.STATE_PLAYING) speed else 0f)
            .build())
        s.isActive = true
    }

    /** Азкары записью: полоса прокрутки текущего зикра, пауза, следующий/предыдущий зикр (без кнопки скорости). */
    fun adhkar(ctx: Context, title: String, text: String, state: Int, posMs: Long, durMs: Long) {
        val s = get(ctx)
        val key = "adhkar|$title|$text|$durMs"
        if (key != quranMetaKey) {
            quranMetaKey = key
            s.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text.take(120))
                .apply { if (durMs > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, durMs) }
                .build())
        }
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
            .setState(state, posMs, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build())
        s.isActive = true
    }

    fun stopped(s: MediaSession) {
        quranMetaKey = ""
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
            .setState(PlaybackState.STATE_STOPPED, 0, 0f)
            .build())
        // неактивна, пока молчит: кнопка «Играть» на руле достаётся музыкальному приложению
        s.isActive = false
    }

    fun stopped(ctx: Context) = stopped(get(ctx))
}
