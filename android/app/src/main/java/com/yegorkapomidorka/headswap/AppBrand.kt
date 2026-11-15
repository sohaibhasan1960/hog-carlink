package com.yegorkapomidorka.headswap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * HOG CarLink: имя приложения для текстов вне ресурсов (кадр экрана машины, сводка лога). В сборке продавца
 * (-Pbrand=...) - его имя из brands/<brand>/brand.properties, в HOG - «HOG CarLink».
 */
object AppBrand {
    @Volatile var name: String = "HOG CarLink"
        private set

    /** Сборка продавца: лицензия (подписка) проверяется только в ней, обновления с GitHub HOG не предлагаются. */
    val isSeller: Boolean get() = BuildConfig.BRAND.isNotEmpty()

    /** Логотип продавца для кадра экрана машины (VideoSource); в HOG - null, рисуется свой знак. */
    @Volatile var logo: Bitmap? = null
        private set

    fun init(ctx: Context) {
        name = ctx.getString(R.string.app_name)
        if (isSeller && logo == null) logo = try {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.brand_logo)
        } catch (_: Exception) {
            null
        }
    }
}
