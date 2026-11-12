// Line layout of the Madinah mushaf, ported from sherifshabans/mushaf-text (MIT, see assets/licenses/MIT-mushaf-text.txt)
// to plain Kotlin for HOG CarLink.
package com.yegorkapomidorka.headswap.quran

import java.io.Reader

/**
 * HOG: страница мусхафа Медины строка в строку (15 строк, на 1-2 странице - 8). Данные:
 *  - assets/mushaf/layout.txt - сколько слов на каждой строке каждой страницы (из номеров строк quran.com),
 *    строки заставки суры (b) и басмалы (m), и какие строки по центру (c), а какие растянуты (j);
 *  - assets/mushaf/hafs.tsv - `id sura ayah juz page line_start line_end text`, текст Хафс в кодировке Комплекса
 *    короля Фахда (рисуется шрифтом KFGQPC Uthmanic Hafs, номер аята шрифт сам рисует в розетке).
 */
object MadaniLayout {
    data class Ayah(val surah: Int, val number: Int, val juz: Int, val page: Int, val text: String) {
        val code get() = surah * 1000 + number
    }

    /** Слово строки или номер аята (розетка). */
    data class Token(val text: String, val code: Int, val isMark: Boolean)

    sealed interface Line {
        val number: Int
    }

    data class Words(override val number: Int, val tokens: List<Token>, val centred: Boolean) : Line
    data class Banner(override val number: Int, val surah: Int) : Line
    data class Basmala(override val number: Int, val surah: Int) : Line

    data class Page(val page: Int, val lineCount: Int, val lines: List<Line>, val ayahs: List<Ayah>)

    class PageLines(val lineCount: Int, val tokensPerLine: IntArray, val roles: Map<Int, Line>, val centred: Set<Int>)

    fun parseLayout(reader: Reader): Map<Int, PageLines> {
        val out = HashMap<Int, PageLines>(QuranPages.PAGES)
        reader.buffered().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(':')
                if (parts.size != 5) continue
                val page = parts[0].toIntOrNull() ?: continue
                val count = parts[1].toIntOrNull() ?: continue
                val counts = parts[2].split(',').map { it.trim().toIntOrNull() ?: 0 }
                if (counts.size != count) continue
                val roles = HashMap<Int, Line>()
                for (entry in parts[3].split(';')) {
                    val f = entry.split(',')
                    if (f.size != 3) continue
                    val at = f[0].toIntOrNull() ?: continue
                    val surah = f[2].toIntOrNull() ?: continue
                    roles[at] = when (f[1]) {
                        "b" -> Banner(at, surah)
                        "m" -> Basmala(at, surah)
                        else -> continue
                    }
                }
                val centred = parts[4].withIndex().filter { it.value == 'c' }.map { it.index + 1 }.toSet()
                out[page] = PageLines(count, counts.toIntArray(), roles, centred)
            }
        }
        return out
    }

    fun parseText(reader: Reader): List<Ayah> = reader.buffered().useLines { seq ->
        seq.mapNotNull { line ->
            val f = line.split('\t', limit = 8)
            if (f.size < 8) return@mapNotNull null
            Ayah(f[1].toIntOrNull() ?: return@mapNotNull null, f[2].toIntOrNull() ?: return@mapNotNull null,
                f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: return@mapNotNull null, f[7])
        }.toList()
    }

    /** Слова аятов страницы по порядку, после каждого аята - его номер (цифры в порядке [markDigitsReversed]). */
    fun tokens(ayahs: List<Ayah>, markDigitsReversed: Boolean): List<Token> {
        val out = ArrayList<Token>()
        for (a in ayahs) {
            for (w in a.text.split(' ')) if (w.isNotEmpty()) out += Token(w, a.code, false)
            val digits = MushafText.arabicDigits(a.number)
            out += Token(if (markDigitsReversed) digits.reversed() else digits, a.code, true)
        }
        return out
    }

    /** Страница по строкам; null - слов не столько, сколько в раскладке (данные не сошлись). */
    fun page(page: Int, layout: PageLines, ayahs: List<Ayah>, markDigitsReversed: Boolean): Page? {
        val tokens = tokens(ayahs, markDigitsReversed)
        if (tokens.size != layout.tokensPerLine.sum()) return null
        val lines = ArrayList<Line>()
        var at = 0
        for (i in 0 until layout.lineCount) {
            val n = i + 1
            val count = layout.tokensPerLine[i]
            if (count > 0) {
                lines += Words(n, tokens.subList(at, at + count), n in layout.centred)
                at += count
            } else {
                layout.roles[n]?.let { lines += it }
            }
        }
        return Page(page, layout.lineCount, lines, ayahs)
    }
}
