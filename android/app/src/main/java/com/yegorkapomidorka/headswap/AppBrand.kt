package com.yegorkapomidorka.headswap

import android.content.Context

/**
 * HOG CarLink: имя приложения для текстов вне ресурсов (кадр экрана машины, сводка лога). В сборке продавца
 * (-Pbrand=...) - его имя из brands/<brand>/brand.properties, в HOG - «HOG CarLink».
 */
object AppBrand {
    @Volatile var name: String = "HOG CarLink"
        private set

    /** Сборка продавца: лицензия (подписка) проверяется только в ней, обновления с GitHub HOG не предлагаются. */
    val isSeller: Boolean get() = BuildConfig.BRAND.isNotEmpty()

    fun init(ctx: Context) {
        name = ctx.getString(R.string.app_name)
    }
}
