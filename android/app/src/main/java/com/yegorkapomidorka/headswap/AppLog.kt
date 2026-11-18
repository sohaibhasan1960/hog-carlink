package com.yegorkapomidorka.headswap

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Лог в три места: logcat (тег Headswap), файл headswap-<timestamp>.log в getExternalFilesDir(null)
 * и кольцо последних строк для экрана. Подробности (hexdump) идут только в logcat и файл.
 */
object AppLog {
    const val TAG = "HOGCarLink"

    /** HOG CarLink: имя файлов лога и архивов (раньше headswap-; старые файлы LogShare тоже подбирает). */
    const val LOG_PREFIX = "hogcarlink-"
    private const val RING = 200

    private val ring = ArrayDeque<String>()
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var out: FileOutputStream? = null
    var file: File? = null
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (out != null) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "$LOG_PREFIX$stamp.log")
        try {
            out = FileOutputStream(f, true)
            file = f
        } catch (e: Exception) {
            Log.e(TAG, "не открыть файл лога $f", e)
        }
        i("лог: ${f.absolutePath}")
        CrashLog.install(ctx)
        prune(dir, f)
    }

    /**
     * HOG: новый файл на каждый запуск процесса, а удалялись старые только кнопкой в экране разработчика -
     * память телефона росла. В фоне: оставить [KEEP_FILES] новейших и всё моложе [KEEP_DAYS] дней.
     */
    private fun prune(dir: File, current: File) = Thread({
        try {
            val old = dir.listFiles { x -> x.name.startsWith(LOG_PREFIX) && x.name.endsWith(".log") && x != current }
                ?.sortedByDescending { it.name }.orEmpty().drop(KEEP_FILES - 1)
            val edge = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            val gone = old.filter { it.lastModified() < edge && it.delete() }
            if (gone.isNotEmpty()) i("лог: удалено старых файлов ${gone.size}", ui = false)
        } catch (e: Exception) {
            Log.w(TAG, "очистка логов", e)
        }
    }, "log-prune").apply { isDaemon = true }.start()

    private const val KEEP_FILES = 15
    private const val KEEP_DAYS = 3

    /** Строка для всех приёмников (экран тоже, если ui=true). */
    @Synchronized
    fun i(line: String, ui: Boolean = true) {
        Log.i(TAG, line)
        write(line)
        if (ui) {
            ring.addLast(tsFmt.format(Date()) + " " + line)
            while (ring.size > RING) ring.removeFirst()
        }
    }

    /** Подробности: только logcat и файл. */
    @Synchronized
    fun detail(text: String) {
        for (l in text.split('\n')) Log.d(TAG, l)
        write(text)
    }

    @Synchronized
    fun e(line: String, t: Throwable? = null) {
        Log.e(TAG, line, t)
        val full = if (t != null) "$line: ${t.javaClass.simpleName}: ${t.message}" else line
        write("ERROR $full")
        ring.addLast(tsFmt.format(Date()) + " ERROR " + full)
        while (ring.size > RING) ring.removeFirst()
    }

    @Synchronized
    fun lastLines(): List<String> = ring.toList()

    /** Очистить строки на экране (файл лога не трогаем). */
    @Synchronized
    fun clearRing() = ring.clear()

    private fun write(text: String) {
        val o = out ?: return
        try {
            o.write((tsFmt.format(Date()) + " " + text + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        } catch (_: Exception) {
        }
    }

    /** Лог одного сообщения: заголовок + hexdump первых 256 байт inner и декодированный protobuf. */
    fun msg(dir: String, chan: Int, outerLen: Int, type: Int, len: Int, decoded: String?, inner: ByteArray, ui: Boolean = true, note: String = "") {
        val head = "$dir ${Ch.name(chan)} ${Msg.hex(type)} ${Msg.name(type)} len=$len" +
            (if (decoded != null) " $decoded" else "") + note
        i(head, ui)
        detail("    outer: chan=$chan len=$outerLen\n" + Hex.dump(inner, 256).prependIndent("    "))
    }
}
