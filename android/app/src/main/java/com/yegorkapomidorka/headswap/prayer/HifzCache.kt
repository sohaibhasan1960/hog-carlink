package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * HOG CarLink: кэш аятов для заучивания. Страница повторяется много раз - качаем её один раз, повторы и следующее
 * прослушивание идут из кэша (и без сети). Старые аяты вытесняются, когда кэш больше [MAX_BYTES].
 * Один SimpleCache на процесс: второй экземпляр на ту же папку Media3 не допускает.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object HifzCache {
    private const val MAX_BYTES = 300L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    @Synchronized
    private fun cache(ctx: Context): SimpleCache = cache ?: SimpleCache(
        File(ctx.applicationContext.cacheDir, "hifz-audio"),
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(ctx.applicationContext),
    ).also { cache = it }

    fun dataSource(ctx: Context): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(ctx))
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("HOG CarLink").setAllowCrossProtocolRedirects(true))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
