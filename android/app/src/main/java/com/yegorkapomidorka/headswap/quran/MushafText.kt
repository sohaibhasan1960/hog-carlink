package com.yegorkapomidorka.headswap.quran

import android.content.res.Resources
import com.yegorkapomidorka.headswap.R

/**
 * HOG: текст страницы мусхафа для MushafActivity. Аяты страницы - по QuranPages (Tanzil page data), текст - Tanzil Uthmani
 * (res/raw/quran_uthmani, строки `surah|ayah|text`). Строки страницы не совпадают с печатным мусхафом: текст переносится по ширине.
 */
object MushafText {
    /** Код surah*1000+ayah -> текст аята. */
    @Volatile private var cache: Map<Int, String>? = null

    fun load(res: Resources): Map<Int, String> {
        cache?.let { return it }
        val out = HashMap<Int, String>(6300)
        res.openRawResource(R.raw.quran_uthmani).bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                val parts = line.split('|', limit = 3)
                if (parts.size < 3) continue
                val s = parts[0].toIntOrNull() ?: continue
                val a = parts[1].toIntOrNull() ?: continue
                out[s * 1000 + a] = parts[2]
            }
        }
        return out.also { cache = it }
    }

    const val BASMALA = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ"

    /**
     * Собранная страница: [text] целиком, [ayahs] - диапазон каждого аята (с его номером), [headers] - строки
     * «سورة …», [basmalas] - басмала перед сурой (ключ - номер суры; её звучание в плане - аят 0 перед сурой).
     */
    data class Page(
        val page: Int,
        val text: String,
        val ayahs: Map<Int, IntRange>,
        val headers: List<IntRange>,
        val basmalas: Map<Int, IntRange>,
    )

    fun build(page: Int, texts: Map<Int, String>): Page {
        val sb = StringBuilder()
        val ayahs = LinkedHashMap<Int, IntRange>()
        val headers = ArrayList<IntRange>()
        val basmalas = HashMap<Int, IntRange>()
        for (code in QuranPages.ayahs(page)) {
            val s = code / 1000
            val a = code % 1000
            if (a == 1) {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                val h0 = sb.length
                sb.append("سورة ").append(Quran.name(s))
                headers += h0 until sb.length
                sb.append('\n')
                // ал-Фатиха: басмала - её первый аят; ат-Тауба - без басмалы
                if (s != 1 && s != 9) {
                    val b0 = sb.length
                    sb.append(BASMALA)
                    basmalas[s] = b0 until sb.length
                    sb.append('\n')
                }
            }
            val t0 = sb.length
            sb.append(texts[code] ?: "").append(" ﴿").append(arabicDigits(a)).append("﴾")
            ayahs[code] = t0 until sb.length
            sb.append(' ')
        }
        return Page(page, sb.toString().trimEnd(), ayahs, headers, basmalas)
    }

    /** Диапазон, который подсвечивать для звучащего элемента плана: аят или басмала перед следующей сурой. */
    fun highlight(p: Page, ayah: Int, nextAyah: Int?): IntRange? = when {
        ayah > 0 -> p.ayahs[ayah]
        nextAyah != null -> p.basmalas[nextAyah / 1000]
        else -> null
    }

    fun arabicDigits(n: Int): String = n.toString().map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
}
