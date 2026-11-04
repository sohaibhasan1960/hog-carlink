package com.yegorkapomidorka.headswap.prayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import com.yegorkapomidorka.headswap.AppLog
import java.util.Locale

/**
 * HOG CarLink: место для расчёта намаза. Хватает приблизительного местоположения (сеть/Wi-Fi), точное не нужно.
 * Город и страна - Geocoder (страна выбирает способ расчёта); без сети - страна оператора.
 */
object PrayerLocation {
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun granted(ctx: Context): Boolean = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Определить место и сохранить; [done] на главной нити: true - сохранено. */
    fun refresh(ctx: Context, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        if (!granted(app)) {
            done(false)
            return
        }
        val lm = app.getSystemService(LocationManager::class.java)
        if (lm == null) {
            done(false)
            return
        }
        val save: (Location?) -> Unit = { loc ->
            val l = loc ?: lastKnown(lm)
            if (l == null) {
                main.post { done(false) }
            } else {
                Thread({
                    val (place, country) = describe(app, l)
                    PrayerPrefs(app).setLocation(l.latitude, l.longitude, place, country)
                    AppLog.i("намаз: место %.3f, %.3f %s %s".format(Locale.US, l.latitude, l.longitude, place, country ?: "-"))
                    PrayerAlarms.reschedule(app)
                    main.post { done(true) }
                }, "prayer-location").start()
            }
        }
        // свежая (до 10 мин) последняя точка - сразу, без ожидания спутников
        lastKnown(lm)?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }?.let {
            AppLog.i("намаз: место из последней точки (${it.provider})")
            save(it)
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            save(null)
            return
        }
        // все включённые источники сразу (сеть/Wi-Fi, fused, спутники): кто первый - тот и даёт место
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { p -> try { lm.isProviderEnabled(p) && (p != LocationManager.GPS_PROVIDER || fine(app)) } catch (_: Exception) { false } }
        AppLog.i("намаз: ищу место через $providers")
        if (providers.isEmpty()) {
            save(null)
            return
        }
        val cancel = CancellationSignal()
        var finished = false
        val once: (Location?) -> Unit = { l ->
            if (!finished && (l != null || cancel.isCanceled)) {
                finished = true
                main.removeCallbacksAndMessages(null)
                cancel.cancel()
                save(l)
            }
        }
        main.postDelayed({ AppLog.i("намаз: место не пришло за 30 с"); cancel.cancel(); once(null) }, 30_000)
        for (p in providers) {
            try {
                lm.getCurrentLocation(p, cancel, app.mainExecutor) { l -> if (l != null) once(l) }
            } catch (e: SecurityException) {
                AppLog.i("намаз: $p запрещён: ${e.message}")
            } catch (e: Exception) {
                AppLog.i("намаз: $p: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun fine(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Место по названию города (если геолокация недоступна): Geocoder, нужна сеть. [done] на главной нити. */
    fun byName(ctx: Context, name: String, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val ok = try {
                @Suppress("DEPRECATION")
                val a = if (Geocoder.isPresent()) Geocoder(app, Locale.getDefault()).getFromLocationName(name, 1)?.firstOrNull() else null
                if (a != null) {
                    PrayerPrefs(app).setLocation(a.latitude, a.longitude, a.locality ?: a.featureName ?: name, a.countryCode)
                    AppLog.i("намаз: место по названию «$name»: %.3f, %.3f %s".format(Locale.US, a.latitude, a.longitude, a.countryCode ?: "-"))
                    PrayerAlarms.reschedule(app)
                    true
                } else false
            } catch (e: Exception) {
                AppLog.i("намаз: город «$name» не найден: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            main.post { done(ok) }
        }, "prayer-geocode").start()
    }

    @Suppress("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? = try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    /** Город и страна (ISO); без сети - страна оператора/системы. Блокирующий вызов. */
    @Suppress("DEPRECATION")
    private fun describe(ctx: Context, l: Location): Pair<String, String?> {
        try {
            if (Geocoder.isPresent()) {
                val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                if (a != null) return (a.locality ?: a.subAdminArea ?: a.adminArea ?: "") to a.countryCode
            }
        } catch (e: Exception) {
            AppLog.i("намаз: Geocoder: ${e.javaClass.simpleName}: ${e.message}")
        }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country.takeIf { it.isNotBlank() }
        return "" to iso?.uppercase()
    }
}
