package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioBookTest {
    private fun le32(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write(v shr 8 and 0xFF); o.write(v shr 16 and 0xFF); o.write(v shr 24 and 0xFF)
    }

    /** Ogg-страницы с пакетами (без CRC: парсер её не проверяет); пакет длиннее 255*255 байт делится на страницы. */
    private fun ogg(vararg packets: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (pk in packets) {
            // таблица сегментов: по 255 и остаток (<255) - конец пакета
            val segs = ArrayList<Int>()
            var left = pk.size
            while (left >= 255) { segs.add(255); left -= 255 }
            segs.add(left)
            var off = 0
            for (chunk in segs.chunked(255)) {
                out.write("OggS".toByteArray()); out.write(0); out.write(0)
                repeat(8) { out.write(0) }
                le32(out, 0x1234); le32(out, 0); le32(out, 0)
                out.write(chunk.size)
                for (s in chunk) out.write(s)
                val len = chunk.sum()
                out.write(pk, off, len)
                off += len
            }
        }
        return out.toByteArray()
    }

    private fun tags(vararg c: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("OpusTags".toByteArray())
        val vendor = "test".toByteArray()
        le32(o, vendor.size); o.write(vendor)
        le32(o, c.size)
        for (x in c) { val b = x.toByteArray(Charsets.UTF_8); le32(o, b.size); o.write(b) }
        return o.toByteArray()
    }

    @Test
    fun chaptersFromOpusTags() {
        val head = ogg("OpusHead".toByteArray() + ByteArray(11),
            tags("ENCODER=x", "CHAPTER002=00:00:32.720", "CHAPTER002NAME=دعاء الاستيقاظ", "CHAPTER001=00:00:00.000", "CHAPTER001NAME=001 - Jan 1, 08:53 PM",
                "CHAPTER003=01:02:03.5"))
        val ch = AudioBook.oggChapters(head)
        assertEquals(listOf(0L, 32_720L, 3_723_500L), ch.map { it.startMs })
        assertEquals("دعاء الاستيقاظ", ch[1].name)
        // дата вместо имени - «المقطع 1»; настоящее имя - с номером
        assertEquals("المقطع 1", AudioBook.chapterTitle(ch[0], 0, "المقطع"))
        assertEquals("2. دعاء الاستيقاظ", AudioBook.chapterTitle(ch[1], 1, "المقطع"))
        assertEquals("المقطع 3", AudioBook.chapterTitle(ch[2], 2, "المقطع"))
    }

    /** OpusHead как в настоящем файле: версия 1, mono, pre-skip 312, 48 кГц. */
    private fun opusHead(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("OpusHead".toByteArray()); o.write(1); o.write(1)
        o.write(312 and 0xFF); o.write(312 shr 8)
        le32(o, 48_000)
        o.write(0); o.write(0); o.write(0)
        return o.toByteArray()
    }

    /**
     * Маленький Ogg Opus в памяти, устроенный как файлы азкаров: теги TITLE/ARTIST/ALBUM/SOURCE, затем три главы
     * с арабскими именами (ﷺ U+FDFA, «ليلاً»), первая - короткая (6.7 с), и аудиостраницы после тегов.
     */
    @Test
    fun arabicOpusWithThreeChapters() {
        val head = ogg(opusHead(),
            tags("TITLE=أذكار الصلاة", "ARTIST=القارئ", "ALBUM=أذكار", "SOURCE=test",
                "CHAPTER001=00:00:00.000", "CHAPTER001NAME=الذكر قبل الوضوء",
                "CHAPTER002=00:00:06.734", "CHAPTER002NAME=الصلاة على النبي ﷺ بعد التشهد",
                "CHAPTER003=00:05:24.249", "CHAPTER003NAME=الدعاء إذا تقلب ليلاً"),
            ByteArray(300) { 0x55 }, ByteArray(200) { 0x66 })
        val ch = AudioBook.oggChapters(head)
        assertEquals(3, ch.size)
        assertEquals(listOf(0L, 6_734L, 324_249L), ch.map { it.startMs })
        assertEquals(listOf("الذكر قبل الوضوء", "الصلاة على النبي ﷺ بعد التشهد", "الدعاء إذا تقلب ليلاً"), ch.map { it.name })
        assertTrue(ch[1].name.contains('ﷺ'))
        assertTrue(ch[2].name.endsWith("ليلاً"))
        val b = AudioBook.Book("p", "u", "أذكار_الصلاة_القارئ.opus", 400_000, ch, 0)
        assertEquals("2. الصلاة على النبي ﷺ بعد التشهد", b.title(1, "المقطع"))
        // короткая первая глава: «назад» на 5-й секунде - в её начало, «вперёд» - во вторую
        assertEquals(6_734L, b.length(0))
        assertEquals(0L, AudioBook.backTarget(ch, 5_000))
        assertEquals(6_734L, AudioBook.nextStart(ch, 5_000))
        assertEquals(0, b.chapterAt(6_733))
        assertEquals(1, b.chapterAt(6_734))
    }

    @Test
    fun singleChapterFile() {
        val head = ogg(opusHead(), tags("TITLE=أذكار بعد الصلاة", "CHAPTER001=00:00:00.000", "CHAPTER001NAME=الأذكار بعد السلام من الصلاة"))
        val ch = AudioBook.oggChapters(head)
        assertEquals(1, ch.size)
        val b = AudioBook.Book("s", "u", "أذكار_بعد_الصلاة_القارئ.opus", 213_733, ch, 0)
        // одна глава - имя без номера, «вперёд» некуда, «назад» - в начало
        assertEquals("الأذكار بعد السلام من الصلاة", b.title(0, "المقطع"))
        assertEquals(213_733L, b.length(0))
        assertNull(AudioBook.nextStart(ch, 100_000))
        assertEquals(0L, AudioBook.backTarget(ch, 100_000))
        // длительность не узнали - длина главы 0, а не отрицательная
        assertEquals(0L, AudioBook.Book("s", "u", "x.opus", 0, ch, 0).length(0))
    }

    @Test
    fun bigTagsSpanPages() {
        val many = (1..300).flatMap { listOf("CHAPTER%03d=%d.0".format(it, it * 10), "CHAPTER%03dNAME=%s".format(it, "ذكر ".repeat(60))) }
        val head = ogg("OpusHead".toByteArray() + ByteArray(11), tags(*many.toTypedArray()))
        assertTrue(head.size > 65_025)
        assertEquals(300, AudioBook.oggChapters(head).size)
        // обрезанное начало файла - не падать
        assertTrue(AudioBook.oggChapters(head.copyOf(5_000)).isEmpty())
        assertNull(AudioBook.oggComments("ID3not ogg at all".toByteArray()))
    }

    @Test
    fun navigation() {
        val ch = listOf(AudioBook.Chapter(0, ""), AudioBook.Chapter(10_000, ""), AudioBook.Chapter(25_000, ""))
        assertEquals(1, AudioBook.chapterAt(ch, 10_000))
        assertEquals(2, AudioBook.chapterAt(ch, 99_000))
        assertEquals(25_000L, AudioBook.nextStart(ch, 12_000))
        assertNull(AudioBook.nextStart(ch, 30_000))
        // >3 с от начала главы - в её начало, иначе в предыдущую
        assertEquals(10_000L, AudioBook.backTarget(ch, 14_000))
        assertEquals(0L, AudioBook.backTarget(ch, 11_000))
        assertEquals(0L, AudioBook.backTarget(ch, 1_000))
        val b = AudioBook.Book("x", "u", "أذكار_اليوم_والليلة.opus", 40_000, ch, 0)
        assertEquals(15_000L, b.end(1) - b.start(1))
        assertEquals(15_000L, b.end(2) - b.start(2))
        assertEquals("أذكار اليوم والليلة", AudioBook.displayName(b.name))
        assertEquals("1:21:16", AudioBook.clock(4_876_206))
    }

    @Test
    fun voiceMatch() {
        // имя чтеца есть во всех файлах - само по себе ничего не выбирает
        val names = listOf("أذكار_الصلاة_القارئ.opus", "أذكار_النوم_القارئ.opus", "أذكار_الصباح_والمساء_القارئ.opus",
            "أذكار_بعد_الصلاة_القارئ.opus", "أذكار_اليوم_والليلة_القارئ.opus")
        assertEquals(1, AudioBook.match(names, "شغل أذكار النوم"))
        assertEquals(0, AudioBook.match(names, "اذكار الصلاه"))
        assertEquals(3, AudioBook.match(names, "أذكار بعد الصلاة"))
        assertEquals(2, AudioBook.match(names, "أذكار المساء"))
        assertEquals(4, AudioBook.match(names, "أذكار اليوم والليلة"))
        assertNull(AudioBook.match(names, "سورة الكهف"))
        assertNull(AudioBook.match(names, "أذكار القارئ"))
    }
}
