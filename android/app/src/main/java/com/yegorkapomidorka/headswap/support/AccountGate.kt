package com.yegorkapomidorka.headswap.support

import android.content.Context
import android.os.Build
import com.yegorkapomidorka.headswap.AppBrand

/**
 * HOG CarLink: активация. Нужны вход Google и, в сборке продавца, действующая подписка (Licensing). Пока чего-то нет,
 * приложение показывает только экран активации (AccountScreen), а мост к машине не запускается.
 * Вход Google работает через системный Credential Manager (Android 14+); ниже входа нет: в HOG требование не действует,
 * в сборке продавца лицензия запрашивается без Google и ждёт одобрения владельца.
 * Проверка только по кэшу: годится и для фоновых запусков без сети.
 */
object AccountGate {
    /** Чистая логика для тестов: нужен ли ещё вход Google. */
    fun signInRequired(sdk: Int, googleEmail: String): Boolean = sdk >= GoogleSignIn.MIN_SDK && googleEmail.isBlank()

    /** Чистая логика для тестов: приложение ещё не активировано. */
    fun required(sdk: Int, googleEmail: String, seller: Boolean, licenseOk: Boolean): Boolean =
        signInRequired(sdk, googleEmail) || (seller && !licenseOk)

    fun signInRequired(ctx: Context): Boolean {
        val c = SupportCloud(ctx)
        return signInRequired(Build.VERSION.SDK_INT, c.googleEmail) && !(AppBrand.isSeller && c.skipGoogle)
    }

    fun required(ctx: Context): Boolean = signInRequired(ctx) || (AppBrand.isSeller && !Licensing.valid(ctx))
}
