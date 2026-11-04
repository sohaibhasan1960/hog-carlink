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
                out.add(item(TIMES, getString(R.string.pr_car_times), PrayerPrefs(this).place.ifBlank { null }, playable = false))
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
        result.sendResult(out)
    }

    private fun item(id: String, title: String, sub: String?, playable: Boolean): MediaBrowser.MediaItem =
        MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
            if (playable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE)

    companion object {
        private const val ROOT = "root"
        private const val TIMES = "times"
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
                val what = PrayerPlayer.What.values().firstOrNull { it.name == mediaId.substringBefore(':') } ?: return
                val prayer = mediaId.substringAfter(':', "").let { n -> Prayer.values().firstOrNull { it.name == n } }
                PrayerPlayer.start(app, what, prayer)
            }

            override fun onPlay() {
                // «Играть» без выбора: азкары по времени суток
                val morning = java.time.LocalTime.now().hour < 12
                PrayerPlayer.start(app, if (morning) PrayerPlayer.What.MORNING else PrayerPlayer.What.EVENING)
            }

            override fun onStop() = stopPlayer(app)
            override fun onPause() = stopPlayer(app)
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

    fun playing(ctx: Context, title: String, text: String) {
        val s = get(ctx)
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

    fun stopped(s: MediaSession) {
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(PlaybackState.STATE_STOPPED, 0, 0f)
            .build())
    }

    fun stopped(ctx: Context) = stopped(get(ctx))
}
