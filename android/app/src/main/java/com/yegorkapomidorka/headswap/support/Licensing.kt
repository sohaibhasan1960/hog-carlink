package com.yegorkapomidorka.headswap.support

import android.content.Context
import android.os.Build
import com.yegorkapomidorka.headswap.AppBrand
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import java.io.IOException

/**
 * HOG CarLink: подписка в сборке продавца (-Pbrand=...). Лицензия - licenses/{uid} в Firestore проекта hog-carlink;
 * владелец HOG видит все и продлевает / останавливает (LicensesScreen). Первая лицензия создаётся телефоном сам:
 * при автоодобрении продавца (sellers/{brand}) - сразу активна на его срок, иначе ждёт владельца.
 * Без сети работает по последней проверке. В HOG самом лицензии нет.
 */
object Licensing {
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** REFUSED - сервер отказал в создании лицензии (у продавца нет записи / автоодобрение выключено). */
    enum class State { OK, PENDING, SUSPENDED, EXPIRED, NONE, REFUSED }

    /** Без сверки с сервером дольше этого срока лицензия по кэшу не считается: остановка доходит до телефона. */
    const val MAX_OFFLINE_MS = 7 * DAY_MS

    /** Чистая логика для тестов: состояние по кэшу с учётом давности проверки. */
    fun cachedState(status: String, expiresAt: Long, checkedAt: Long, now: Long): State =
        if (now - checkedAt > MAX_OFFLINE_MS) State.NONE else state(status, expiresAt, now)

    /** Чистая логика для тестов. */
    fun state(status: String, expiresAt: Long, now: Long): State = when (status) {
        "active" -> if (expiresAt > now) State.OK else State.EXPIRED
        "pending" -> State.PENDING
        "suspended" -> State.SUSPENDED
        else -> State.NONE
    }

    fun expiryFor(now: Long, termDays: Int): Long = now + termDays.coerceAtLeast(0) * DAY_MS

    /** Состояние по кэшу (без сети); лицензия другого uid (после повторного входа) не считается. */
    fun cached(ctx: Context): State {
        if (!AppBrand.isSeller) return State.OK
        val c = SupportCloud(ctx)
        if (c.cachedLicenseUid.isEmpty() || c.cachedLicenseUid != c.uid) return State.NONE
        return cachedState(c.cachedLicenseStatus, c.cachedLicenseExpires, c.cachedLicenseCheckedAt, System.currentTimeMillis())
    }

    fun valid(ctx: Context): Boolean = cached(ctx) == State.OK

    /** Мост запускается: сверить лицензию в фоне (продление или остановка без открытия приложения). */
    fun refreshInBackground(ctx: Context) {
        if (!AppBrand.isSeller) return
        val app = ctx.applicationContext
        Thread({ refreshQuietly(app) }, "license-refresh").start()
    }

    /**
     * Проверить на сервере, а если лицензии ещё нет - создать (автоодобрение продавца со входом Google, иначе ждать).
     * Блокирующий вызов: не с главной нити.
     */
    @Throws(IOException::class)
    fun refresh(ctx: Context): State {
        if (!AppBrand.isSeller) return State.OK
        val c = SupportCloud(ctx)
        c.token()
        val lic = c.myLicense() ?: run {
            val (auto, days) = c.seller(BuildConfig.BRAND)
            val active = auto && days > 0 && c.googleEmail.isNotBlank()
            c.createLicense(BuildConfig.BRAND, "${Build.MANUFACTURER} ${Build.MODEL}", active, days)
        }
        val s = state(lic.status, lic.expiresAt, System.currentTimeMillis())
        AppLog.i("лицензия: $s", true)
        return s
    }

    /** То же без исключений: сеть недоступна - по кэшу, отказ сервера - REFUSED. */
    fun refreshQuietly(ctx: Context): State = try {
        refresh(ctx)
    } catch (e: SupportCloud.HttpError) {
        AppLog.i("лицензия: сервер отказал (${e.code}): ${e.body.take(200)}", true)
        if (e.code == 403) State.REFUSED else cached(ctx)
    } catch (e: Exception) {
        AppLog.i("лицензия: проверка не удалась (${e.javaClass.simpleName}: ${e.message}), по кэшу", true)
        cached(ctx)
    }
}
