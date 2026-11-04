package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * HOG CarLink: записи азкаров живым голосом. Скачиваются один раз (≈13 МБ на все) в кэш приложения и дальше играют
 * без интернета; система может кэш очистить - тогда скачаются снова. Блокирующие - не с главной нити.
 */
object AdhkarAudio {
    private const val TIMEOUT_MS = 20_000

    private fun dir(ctx: Context) = File(ctx.cacheDir, "adhkar").apply { mkdirs() }

    /** Имя файла по адресу: «hisnmuslim-75.mp3», «mishary-alafasy-112.mp3». */
    fun fileName(url: String): String {
        val parts = url.trimEnd('/').split('/')
        val host = if ("hisnmuslim" in url) "hisnmuslim" else parts.getOrNull(parts.size - 3) ?: "audio"
        return "$host-${parts.last()}".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    fun cached(ctx: Context, url: String): File? = File(dir(ctx), fileName(url)).takeIf { it.isFile && it.length() > 0 }

    /** Файл записи: из кэша или скачать; null - нет сети. */
    fun fetch(ctx: Context, url: String): File? {
        cached(ctx, url)?.let { return it }
        val dst = File(dir(ctx), fileName(url))
        val tmp = File(dst.path + ".part")
        return try {
            var u = URL(url)
            var c: HttpURLConnection
            var hops = 0
            while (true) {
                c = u.openConnection() as HttpURLConnection
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.instanceFollowRedirects = true
                val code = c.responseCode
                if (code in 300..399 && hops++ < 3) {
                    u = URL(u, c.getHeaderField("Location"))
                    c.disconnect()
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                break
            }
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            c.disconnect()
            if (!tmp.renameTo(dst)) throw IllegalStateException("rename")
            dst
        } catch (e: Exception) {
            tmp.delete()
            AppLog.i("азкары: запись не скачана ($url): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
