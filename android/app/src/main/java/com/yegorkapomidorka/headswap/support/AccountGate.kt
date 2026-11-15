package com.yegorkapomidorka.headswap.support

import android.content.Context
import android.os.Build

/**
 * HOG CarLink: вход Google - часть активации. Пока на телефоне нет входа, приложение показывает только экран входа
 * (AccountScreen), а мост к машине не запускается. Вход Google работает через системный Credential Manager (Android 14+);
 * ниже его нет, и требование не действует, иначе такой телефон нельзя было бы активировать вообще.
 */
object AccountGate {
    /** Чистая логика для тестов: нужен ли ещё вход. */
    fun required(sdk: Int, googleEmail: String): Boolean = sdk >= GoogleSignIn.MIN_SDK && googleEmail.isBlank()

    fun required(ctx: Context): Boolean = required(Build.VERSION.SDK_INT, SupportCloud(ctx).googleEmail)
}
