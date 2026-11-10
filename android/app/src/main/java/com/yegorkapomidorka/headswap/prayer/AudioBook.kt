package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * HOG CarLink: аудиокнига - один файл с телефона, разбитый на главы (например, сборник азкаров одним файлом). Главы читаются из тегов Ogg (OpusTags / Vorbis comment): CHAPTER001=00:00:32.720 и CHAPTER001NAME=...
 * Файл не копируется и никуда не отправляется: приложение хранит только ссылку (content://, постоянное разрешение).
 * «Вперёд»/«назад» в машине - по главам внутри файла, позиция запоминается.
 */
object AudioBook {
    class Chapter(val startMs: Long, val name: String)

    class Book(val id: String, val uri: String, val name: String, val durationMs: Long, val chapters: List<Chapter>, val lastPosMs: Long) {
        /** Начало и конец главы [i] (конец последней - длительность файла). */
        fun start(i: Int): Long = chapters[i].startMs
        fun end(i: Int): Long = chapters.getOrNull(i + 1)?.startMs ?: durationMs.coerceAtLeast(chapters[i].startMs)
        fun chapterAt(posMs: Long): Int = AudioBook.chapterAt(chapters, posMs)
        fun title(i: Int, fallback: String): String = chapterTitle(chapters[i], i, fallback, chapters.size)
        /** Длина главы; 0 - неизвестна (длительность файла ещё не узнали). */
        fun length(i: Int): Long = (end(i) - start(i)).coerceAtLeast(0)
    }

    // ------------------------------------------------------------ главы из тегов Ogg

    /**
     * Главы из начала файла Ogg (Opus или Vorbis): второй пакет первого потока - теги. Пусто, если тегов глав нет
     * или это не Ogg. [head] - первые байты файла (теги с сотней глав занимают несколько КБ).
     */
    fun oggChapters(head: ByteArray): List<Chapter> = commentsToChapters(oggComments(head) ?: return emptyList())

    /** Теги «KEY=value» второго пакета Ogg; null - не Ogg или пакет не поместился в [head]. */
    fun oggComments(head: ByteArray): List<String>? {
        val packets = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        var serial: Int? = null
        var p = 0
        while (p + 27 <= head.size && packets.size < 2) {
            if (head[p] != 'O'.code.toByte() || head[p + 1] != 'g'.code.toByte() || head[p + 2] != 'g'.code.toByte() || head[p + 3] != 'S'.code.toByte()) return null
            val pageSerial = le32(head, p + 14)
            val nSeg = head[p + 26].toInt() and 0xFF
            val segStart = p + 27
            if (segStart + nSeg > head.size) return null
            var data = segStart + nSeg
            val mine = serial == null || serial == pageSerial
            if (serial == null) serial = pageSerial
            for (k in 0 until nSeg) {
                val len = head[segStart + k].toInt() and 0xFF
                if (data + len > head.size) return null
                if (mine && packets.size < 2) {
                    cur.write(head, data, len)
                    if (len < 255) {
                        packets.add(cur.toByteArray())
                        cur.reset()
                    }
                }
                data += len
            }
            p = data
        }
        if (packets.size < 2) return null
        val pk = packets[1]
        val off = when {
            startsWith(pk, "OpusTags".toByteArray()) -> 8
            pk.size > 7 && pk[0].toInt() == 3 && startsWith(pk.copyOfRange(1, 7), "vorbis".toByteArray()) -> 7
            else -> return null
        }
        return try {
            var q = off
            val vendor = le32(pk, q); q += 4 + vendor
            val n = le32(pk, q); q += 4
            List(n) {
                val len = le32(pk, q); q += 4
                String(pk, q, len, Charsets.UTF_8).also { q += len }
            }
        } catch (_: Exception) {
            null
        }
    }

    private val CH_TIME = Regex("""^CHAPTER(\d+)=(.+)$""", RegexOption.IGNORE_CASE)
    private val CH_NAME = Regex("""^CHAPTER(\d+)NAME=(.*)$""", RegexOption.IGNORE_CASE)

    fun commentsToChapters(comments: List<String>): List<Chapter> {
        val times = HashMap<Int, Long>()
        val names = HashMap<Int, String>()
        for (c in comments) {
            val n = CH_NAME.find(c)
            if (n != null) {
                names[n.groupValues[1].toInt()] = n.groupValues[2].trim()
                continue
            }
            val t = CH_TIME.find(c) ?: continue
            parseTime(t.groupValues[2])?.let { times[t.groupValues[1].toInt()] = it }
        }
        return times.keys.sorted().map { k -> Chapter(times[k]!!, names[k].orEmpty()) }.sortedBy { it.startMs }
    }

    /** «01:02:03.456» / «02:03.5» / «123.4» -> мс. */
    fun parseTime(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var sec = 0.0
        for (x in parts) sec = sec * 60 + (x.toDoubleOrNull() ?: return null)
        return Math.round(sec * 1000)
    }

    /** Глава, в которой позиция [posMs]. */
    fun chapterAt(chapters: List<Chapter>, posMs: Long): Int {
        var i = 0
        while (i + 1 < chapters.size && chapters[i + 1].startMs <= posMs) i++
        return i
    }

    /** «Назад»: дальше 3 с от начала главы - в её начало, иначе в начало предыдущей. */
    fun backTarget(chapters: List<Chapter>, posMs: Long): Long {
        val i = chapterAt(chapters, posMs)
        return if (posMs - chapters[i].startMs > 3_000 || i == 0) chapters[i].startMs else chapters[i - 1].startMs
    }

    /** Следующая глава или null, если это последняя. */
    fun nextStart(chapters: List<Chapter>, posMs: Long): Long? = chapters.getOrNull(chapterAt(chapters, posMs) + 1)?.startMs

    private val STAMP = Regex("""^\d*\s*[-–.]?\s*[A-Z][a-z]{2} \d{1,2}, \d{1,2}:\d{2}\s*(AM|PM)?$""")

    /**
     * Имя главы для экрана: «12. имя»; номер с датой вместо имени («012 - Jan 3, 09:27 PM», так называет главы
     * склейка голосовых сообщений) или пустое - «[fallback] 12». Единственная глава ([total] = 1) - имя без номера.
     */
    fun chapterTitle(ch: Chapter, index: Int, fallback: String, total: Int = 0): String {
        val n = index + 1
        val raw = ch.name.trim()
        val named = raw.isNotEmpty() && !STAMP.matches(raw)
        if (total == 1) return if (named) raw else fallback
        if (!named) return "$fallback $n"
        val bare = raw.replace(Regex("""^0*$n\s*[-–.:]\s*"""), "")
        return "$n. $bare"
    }

    private fun norm(x: String) = x.lowercase().replace(Regex("[\u064B-\u0652\u0670\u0640]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replace('_', ' ')

    private val STOP = setOf("اذكار", "ذكر", "شغل", "تشغيل", "hog", "carlink", "في", "من", "و")

    /**
     * Голосовой запрос -> индекс файла аудиотеки: больше всего общих значимых слов с именем файла
     * («شغل أذكار النوم» -> «أذكار_النوم_….opus»); null - совпадений нет. «ال» в начале слова не важно.
     * Слова, общие для всех файлов (имя чтеца, «اذكار»), не различают их и не считаются.
     */
    fun match(names: List<String>, query: String?): Int? {
        fun words(x: String) = norm(x).split(Regex("""[^\p{L}\p{N}]+""")).map { w -> if (w.startsWith("وال") && w.length > 4) w.substring(3) else w.removePrefix("ال") }.filter { it.length > 1 && it !in STOP && "ال$it" !in STOP }.toSet()
        val q = words(query ?: return null)
        if (q.isEmpty()) return null
        // (индекс, общих слов, лишних слов в имени): «أذكار الصلاة» - «أذكار الصلاة», а не «أذكار بعد الصلاة»
        val nameWords = names.map { words(displayName(it)) }
        val common = if (nameWords.size > 1) nameWords.reduce { a, b -> a intersect b } else emptySet()
        val scored = nameWords.mapIndexed { i, w0 -> (w0 - common).let { w -> Triple(i, (w intersect q).size, (w - q).size) } }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        val top = scored.filter { it.second == best }
        val fewest = top.minOf { it.third }
        // ничья - лучше ничего, чем не тот файл
        return top.filter { it.third == fewest }.singleOrNull()?.first
    }

    /** Имя файла для экрана: без расширения, «_» - пробелы («حصن_المسلم.opus» -> «حصن المسلم»). */
    fun displayName(file: String): String = file.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { file }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun startsWith(b: ByteArray, p: ByteArray) = b.size >= p.size && p.indices.all { b[it] == p[it] }

    // ------------------------------------------------------------ библиотека (настройки)

    class Library(ctx: Context) {
        private val sp = ctx.applicationContext.getSharedPreferences("audiobooks", Context.MODE_PRIVATE)

        fun books(): List<Book> = try {
            val a = JSONArray(sp.getString("books", "[]"))
            List(a.length()) { i -> fromJson(a.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }

        fun book(id: String): Book? = books().firstOrNull { it.id == id }

        fun add(uri: String, name: String, durationMs: Long, chapters: List<Chapter>): Book {
            val list = books().filter { it.uri != uri }
            val b = Book(System.currentTimeMillis().toString(36), uri, name, durationMs,
                chapters.ifEmpty { listOf(Chapter(0, "")) }, 0)
            save(list + b)
            return b
        }

        fun remove(id: String) = save(books().filter { it.id != id })

        /** Длительность, если при добавлении её не узнали (MediaMetadataRetriever вернул 0) - из плеера. */
        fun setDuration(id: String, durMs: Long) {
            if (durMs <= 0) return
            save(books().map { if (it.id == id && it.durationMs <= 0) Book(it.id, it.uri, it.name, durMs, it.chapters, it.lastPosMs) else it })
        }

        fun setPos(id: String, posMs: Long) {
            val list = books()
            if (list.none { it.id == id }) return
            save(list.map { if (it.id == id) Book(it.id, it.uri, it.name, it.durationMs, it.chapters, posMs) else it })
            sp.edit().putString("last", id).apply()
        }

        /** Книга, которую слушали последней (для «Играть» без выбора). */
        val lastId: String get() = sp.getString("last", "") ?: ""

        private fun save(list: List<Book>) {
            val a = JSONArray()
            for (b in list) a.put(toJson(b))
            sp.edit().putString("books", a.toString()).apply()
        }

        private fun toJson(b: Book) = JSONObject().put("id", b.id).put("uri", b.uri).put("name", b.name).put("dur", b.durationMs)
            .put("pos", b.lastPosMs).put("ch", JSONArray().apply { for (c in b.chapters) put(JSONObject().put("t", c.startMs).put("n", c.name)) })

        private fun fromJson(o: JSONObject): Book {
            val ch = o.getJSONArray("ch")
            return Book(o.getString("id"), o.getString("uri"), o.optString("name"), o.optLong("dur"),
                List(ch.length()) { i -> ch.getJSONObject(i).let { Chapter(it.getLong("t"), it.optString("n")) } }, o.optLong("pos"))
        }
    }
}
