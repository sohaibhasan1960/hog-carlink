package com.yegorkapomidorka.headswap.prayer

import org.json.JSONArray

/**
 * HOG CarLink: азкары утра и вечера («Крепость мусульманина»). Текст - res/raw/adhkar_ar.json из
 * Seen-Arabic/Morning-And-Evening-Adhkar-DB (MIT): t 0 - утро и вечер, 1 - утро, 2 - вечер; n - число повторов.
 * Разбор и план чтения без Android - для теста.
 */
object Adhkar {
    /** [audio] - запись живым голосом (сайт «Крепости мусульманина», чтец Хамад ад-Дурейхим; три «Куль» - Мишари аль-Афаси). */
    data class Item(val type: Int, val count: Int, val text: String, val source: String, val audio: String = "")

    fun parse(json: String): List<Item> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Item(o.getInt("t"), o.getInt("n"), o.getString("c"), o.optString("s"), o.optString("a"))
        }
    }

    fun morning(all: List<Item>) = all.filter { it.type == 0 || it.type == 1 }
    fun evening(all: List<Item>) = all.filter { it.type == 0 || it.type == 2 }

    /** Повторять полностью до этого числа; больше - один раз и «повторяется N раз» (если не включён полный повтор). */
    const val FULL_REPEAT_MAX = 7

    /** Текст для синтеза: без скобок аятов и служебных знаков. */
    fun speakable(text: String): String = text
        .replace("﴿", "").replace("﴾", "")
        .replace("((", "").replace("))", "").replace("*", "،")
        .replace("ﷺ", "صلى الله عليه وسلم")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Фразы для чтения одного зикра: сам текст нужное число раз, иначе один раз и указание числа. */
    fun utterances(item: Item, fullRepeat: Boolean): List<String> {
        val t = speakable(item.text)
        return if (fullRepeat || item.count <= FULL_REPEAT_MAX) List(item.count.coerceAtLeast(1)) { t }
        else listOf(t, countPhrase(item.count))
    }

    /** Сколько раз проиграть запись: полностью до [FULL_REPEAT_MAX] (или всегда при полном повторе), иначе один раз. */
    fun plays(item: Item, fullRepeat: Boolean): Int = if (fullRepeat || item.count <= FULL_REPEAT_MAX) item.count.coerceAtLeast(1) else 1

    /** «تُكرَّر عشر مرات» / «مئة مرة». */
    fun countPhrase(n: Int): String = when (n) {
        10 -> "يُكرَّر عشر مرات"
        100 -> "يُكرَّر مئة مرة"
        else -> "يُكرَّر $n مرة"
    }

    /**
     * Короткие зикры для напоминаний (по кругу), HOG CarLink: каждый с записью живым голосом (hisnmuslim.com, 5-18 с),
     * тексты совпадают с азкарами утра/вечера в adhkar_ar.json. Без интернета и кэша - синтез речи.
     */
    val SHORT_AUDIO: Map<String, String> = linkedMapOf(
        "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ" to "https://www.hisnmuslim.com/audio/ar/91.mp3",
        "لاَ إِلَهَ إِلاَّ اللَّهُ، وَحْدَهُ لاَ شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ" to "https://www.hisnmuslim.com/audio/ar/92.mp3",
        "أَسْتَغْفِرُ اللَّهَ وَأَتُوبُ إِلَيْهِ" to "https://www.hisnmuslim.com/audio/ar/96.mp3",
        "اللَّهُمَّ صَلِّ وَسَلِّمْ عَلَى نَبَيِّنَا مُحَمَّدٍ" to "https://www.hisnmuslim.com/audio/ar/98.mp3",
        "حَسْبِيَ اللَّهُ لاَ إِلَهَ إِلاَّ هُوَ عَلَيهِ تَوَكَّلتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ" to "https://www.hisnmuslim.com/audio/ar/83.mp3",
        "رَضِيتُ بِاللَّهِ رَبَّاً، وَبِالْإِسْلاَمِ دِيناً، وَبِمُحَمَّدٍ ﷺ نَبِيًّا" to "https://www.hisnmuslim.com/audio/ar/87.mp3",
        "يَا حَيُّ يَا قَيُّومُ بِرَحْمَتِكَ أَسْتَغيثُ أَصْلِحْ لِي شَأْنِيَ كُلَّهُ وَلاَ تَكِلْنِي إِلَى نَفْسِي طَرْفَةَ عَيْنٍ" to "https://www.hisnmuslim.com/audio/ar/88.mp3",
        "أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ" to "https://www.hisnmuslim.com/audio/ar/97.mp3",
    )

    val SHORT: List<String> = SHORT_AUDIO.keys.toList()

    /** Слова икамы (по шафиитскому мазхабу): читаются синтезом речи. */
    const val IQAMA = "اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ. أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ. أَشْهَدُ أَنَّ مُحَمَّدًا رَسُولُ اللَّهِ. " +
        "حَيَّ عَلَى الصَّلَاةِ. حَيَّ عَلَى الْفَلَاحِ. قَدْ قَامَتِ الصَّلَاةُ، قَدْ قَامَتِ الصَّلَاةُ. " +
        "اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ. لَا إِلَهَ إِلَّا اللَّهُ"
}
