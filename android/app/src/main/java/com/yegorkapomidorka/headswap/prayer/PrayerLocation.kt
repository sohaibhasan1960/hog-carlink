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
import java.util.concurrent.Executors

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
                Executors.newSingleThreadExecutor().execute {
                    val (place, country) = describe(app, l)
                    PrayerPrefs(app).setLocation(l.latitude, l.longitude, place, country)
                    AppLog.i("намаз: место %.3f, %.3f %s %s".format(Locale.US, l.latitude, l.longitude, place, country ?: "-"))
                    PrayerAlarms.reschedule(app)
                    main.post { done(true) }
                }
            }
        }
        try {
            val provider = when {
                Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null || Build.VERSION.SDK_INT < 30) {
                save(null)
                return
            }
            val cancel = CancellationSignal()
            main.postDelayed({ cancel.cancel(); save(null) }, 15_000)
            lm.getCurrentLocation(provider, cancel, app.mainExecutor) { l ->
                if (cancel.isCanceled) return@getCurrentLocation
                main.removeCallbacksAndMessages(null)
                cancel.cancel()
                save(l)
            }
        } catch (e: SecurityException) {
            done(false)
        } catch (e: Exception) {
            AppLog.i("намаз: местоположение не получено: ${e.javaClass.simpleName}: ${e.message}")
            save(null)
        }
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
