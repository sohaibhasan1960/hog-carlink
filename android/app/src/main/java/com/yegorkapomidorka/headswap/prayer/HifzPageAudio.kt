package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.quran.QuranPages
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HOG CarLink: страница плана заучивания одним непрерывным звуком.
 *
 * everyayah отдаёт аят отдельным mp3 со своей тишиной в начале и конце (у Хусари 1-2 с перед аятом, у «المعلّم»
 * 3-4 с после), и между файлами плеер слышно запинается. Здесь аяты страницы декодируются, тишина по краям
 * обрезается до [LEAD_KEEP_MS] / [TAIL_KEEP_MS], и всё пишется одним WAV (PCM16 моно) с границами аятов.
 * Плеер играет куски этого WAV (ClippingConfiguration) - PCM без задержки кодека, переходы без щелей,
 * а номер куска по-прежнему = аят для подсветки и «следующей / предыдущей».
 *
 * Готовые страницы лежат в cacheDir/hifz-pages/<чтец>/<страница>.wav (+ .idx); хранится [KEEP_PAGES] последних.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object HifzPageAudio {
    const val LEAD_KEEP_MS = 120
    const val TAIL_KEEP_MS = 350
    private const val KEEP_PAGES = 40
    /** Окно 10 мс тише этого (пик, PCM16) - тишина. */
    private const val SILENCE_PEAK = 700

    /** Страница готова: WAV и начало/конец каждого файла страницы (порядок [QuranPages.files]), мс. */
    class Page(val wav: File, val startMs: LongArray, val endMs: LongArray)

    private fun dir(ctx: Context, voice: QuranPages.Voice) = File(ctx.cacheDir, "hifz-pages/${voice.folder}")

    /** Уже собранная страница или null. */
    fun cached(ctx: Context, voice: QuranPages.Voice, page: Int): Page? {
        val d = dir(ctx, voice)
        val wav = File(d, "$page.wav")
        val idx = File(d, "$page.idx")
        if (!wav.isFile || !idx.isFile) return null
        return try {
            val parts = idx.readText().trim().split('\n').map { l -> l.split(' ').map { it.toLong() } }
            if (parts.size != QuranPages.files(page).size) return null
            wav.setLastModified(System.currentTimeMillis())
            Page(wav, LongArray(parts.size) { parts[it][0] }, LongArray(parts.size) { parts[it][1] })
        } catch (_: Exception) {
            null
        }
    }

    /** Собрать страницу (сеть + декодирование, секунды): не с главной нити. null - не вышло, играть по файлам. */
    fun build(ctx: Context, voice: QuranPages.Voice, page: Int): Page? =
        // одна и та же страница из двух нитей (заранее + запуск) - вторая ждёт и берёт готовую
        synchronized(locks.getOrPut("${voice.name}:$page") { Any() }) { buildLocked(ctx, voice, page) }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun buildLocked(ctx: Context, voice: QuranPages.Voice, page: Int): Page? {
        cached(ctx, voice, page)?.let { return it }
        val t0 = System.currentTimeMillis()
        val files = QuranPages.files(page)
        val out = ByteArrayOutputStream()
        val start = LongArray(files.size)
        val end = LongArray(files.size)
        var rate = 0
        val tmp = File(ctx.cacheDir, "hifz-dl-${Thread.currentThread().id}.mp3")
        try {
            for ((i, f) in files.withIndex()) {
                tmp.writeBytes(download(ctx, voice.url(f)))
                val pcm = decodeMono(tmp) ?: return fail(page, "декодирование $f")
                if (rate == 0) rate = pcm.rate
                if (pcm.rate != rate) return fail(page, "частота $f ${pcm.rate} != $rate")
                val (a, b) = trim(pcm.samples, rate)
                start[i] = msOf(out.size() / 2L, rate)
                val buf = ByteBuffer.allocate((b - a) * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (k in a until b) buf.putShort(pcm.samples[k])
                out.write(buf.array())
                end[i] = msOf(out.size() / 2L, rate)
            }
        } catch (e: Exception) {
            return fail(page, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            tmp.delete()
        }
        val d = dir(ctx, voice).apply { mkdirs() }
        val wav = File(d, "$page.wav")
        writeWav(wav, out.toByteArray(), rate)
        File(d, "$page.idx").writeText(start.indices.joinToString("\n") { "${start[it]} ${end[it]}" })
        evict(ctx)
        AppLog.i("حفظ: страница $page одним звуком: ${files.size} файлов, ${end.last() / 1000} с, " +
            "${System.currentTimeMillis() - t0} мс на сборку", true)
        return Page(wav, start, end)
    }

    private fun fail(page: Int, why: String): Page? {
        AppLog.i("حفظ: страница $page одним звуком не собрана ($why) - играю по файлам", true)
        return null
    }

    private fun msOf(samples: Long, rate: Int) = samples * 1000L / rate

    private fun download(ctx: Context, url: String): ByteArray {
        val ds = HifzCache.dataSource(ctx).createDataSource()
        ds.open(DataSpec(Uri.parse(url)))
        try {
            val bos = ByteArrayOutputStream()
            val b = ByteArray(16 * 1024)
            while (true) {
                val n = ds.read(b, 0, b.size)
                if (n < 0) break
                bos.write(b, 0, n)
            }
            return bos.toByteArray()
        } finally {
            ds.close()
        }
    }

    private class Pcm(val samples: ShortArray, val rate: Int)

    /** mp3 -> PCM16 моно (каналы усредняются). */
    private fun decodeMono(file: File): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val acc = ShortList()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            while (true) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val sb = c.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    sb.position(info.offset / 2)
                    sb.limit((info.offset + info.size) / 2)
                    val chs = ch.coerceAtLeast(1)
                    while (sb.remaining() >= chs) {
                        var sum = 0
                        repeat(chs) { sum += sb.get() }
                        acc.add((sum / chs).toShort())
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return Pcm(acc.toArray(), rate)
        } catch (e: Exception) {
            AppLog.i("حفظ: mp3 не декодирован: ${e.javaClass.simpleName}: ${e.message}", true)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    /** Границы звука: [a, b) с запасом тишины [LEAD_KEEP_MS] до и [TAIL_KEEP_MS] после. */
    fun trim(s: ShortArray, rate: Int): Pair<Int, Int> {
        val win = (rate / 100).coerceAtLeast(1)
        fun loud(w: Int): Boolean {
            val from = w * win
            val to = minOf(from + win, s.size)
            for (k in from until to) {
                val v = s[k].toInt()
                if (v > SILENCE_PEAK || v < -SILENCE_PEAK) return true
            }
            return false
        }
        val wins = (s.size + win - 1) / win
        val first = (0 until wins).firstOrNull { loud(it) } ?: return 0 to s.size
        val last = (wins - 1 downTo 0).first { loud(it) }
        val a = (first * win - rate * LEAD_KEEP_MS / 1000).coerceAtLeast(0)
        val b = ((last + 1) * win + rate * TAIL_KEEP_MS / 1000).coerceAtMost(s.size)
        return a to b
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size)
            r.write(h.array())
            r.write(pcm)
        }
    }

    private fun evict(ctx: Context) {
        val all = File(ctx.cacheDir, "hifz-pages").walkTopDown().filter { it.isFile && it.name.endsWith(".wav") }
            .sortedByDescending { it.lastModified() }.toList()
        for (w in all.drop(KEEP_PAGES)) {
            w.delete()
            File(w.parentFile, w.nameWithoutExtension + ".idx").delete()
        }
    }

    private class ShortList {
        private var a = ShortArray(1 shl 16)
        private var n = 0
        fun add(v: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): ShortArray = a.copyOf(n)
    }
}
