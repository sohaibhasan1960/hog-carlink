package com.yegorkapomidorka.headswap.aa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog

/**
 * HOG: скорость по GPS телефона - запасной источник «стоим ли», когда ГУ свою скорость не шлёт (VW ID.6: CAR_DATA_START
 * игнорирует). Слушает, пока жив мост (BridgeService): GPS раз в секунду и пассивно - чужие запросы (навигатор в AA).
 * В фоне нужны ACCESS_FINE_LOCATION и ACCESS_BACKGROUND_LOCATION («Разрешить всегда»); без них источника просто нет.
 */
object PhoneSpeed {
    /** Скорость, км/ч, и момент (elapsedRealtime) последней точки GPS со скоростью; -1 - не было. */
    @Volatile var kmh = -1f
        private set
    @Volatile var atMs = 0L
        private set
    @Volatile var onUpdate: ((Float) -> Unit)? = null

    private var lm: LocationManager? = null
    private var logged = false

    private val listener = LocationListener { loc: Location ->
        if (!loc.hasSpeed()) return@LocationListener
        kmh = loc.speed * 3.6f
        atMs = android.os.SystemClock.elapsedRealtime()
        if (!logged) {
            logged = true
            AppLog.i("скорость GPS телефона: первая точка ${"%.1f".format(kmh)} км/ч (${loc.provider})")
        }
        onUpdate?.invoke(kmh)
    }

    fun permitted(ctx: Context): Boolean {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context) {
        if (lm != null) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i("скорость GPS телефона: нет разрешения на геолокацию")
            return
        }
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            m.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm = m
            logged = false
            AppLog.i("скорость GPS телефона: слушаю (в фоне ${if (permitted(ctx)) "разрешено" else "НЕ разрешено - только при открытом приложении"})")
        } catch (e: Exception) {
            AppLog.e("скорость GPS телефона: подписка", e)
        }
    }

    @Synchronized
    fun stop() {
        val m = lm ?: return
        lm = null
        try {
            m.removeUpdates(listener)
        } catch (_: Exception) {
        }
        kmh = -1f
        atMs = 0L
    }
}
