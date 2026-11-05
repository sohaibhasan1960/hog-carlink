package com.yegorkapomidorka.headswap.quran

/**
 * HOG CarLink: Коран. Звук - потоком с mp3quran.net (их API для приложений), ничего не хранится в APK и не
 * качается заранее: грузится только играющая сура. Текст - res/raw/quran_uthmani.txt (Tanzil, CC BY 3.0, без изменений),
 * читается по одной суре. Без Android - для теста.
 */
object Quran {
    const val SURAHS = 114

    /** Названия сур (mp3quran.net /api/v3/suwar). */
    val NAMES = listOf(
        "الفاتحة",
        "البقرة",
        "آل عمران",
        "النساء",
        "المائدة",
        "الأنعام",
        "الأعراف",
        "الأنفال",
        "التوبة",
        "يونس",
        "هود",
        "يوسف",
        "الرعد",
        "إبراهيم",
        "الحجر",
        "النحل",
        "الإسراء",
        "الكهف",
        "مريم",
        "طه",
        "الأنبياء",
        "الحج",
        "المؤمنون",
        "النور",
        "الفرقان",
        "الشعراء",
        "النمل",
        "القصص",
        "العنكبوت",
        "الروم",
        "لقمان",
        "السجدة",
        "الأحزاب",
        "سبأ",
        "فاطر",
        "يس",
        "الصافات",
        "ص",
        "الزمر",
        "غافر",
        "فصلت",
        "الشورى",
        "الزخرف",
        "الدّخان",
        "الجاثية",
        "الأحقاف",
        "محمد",
        "الفتح",
        "الحجرات",
        "ق",
        "الذاريات",
        "الطور",
        "النجم",
        "القمر",
        "الرحمن",
        "الواقعة",
        "الحديد",
        "المجادلة",
        "الحشر",
        "الممتحنة",
        "الصف",
        "الجمعة",
        "المنافقون",
        "التغابن",
        "الطلاق",
        "التحريم",
        "الملك",
        "القلم",
        "الحاقة",
        "المعارج",
        "نوح",
        "الجن",
        "المزمل",
        "المدثر",
        "القيامة",
        "الإنسان",
        "المرسلات",
        "النبأ",
        "النازعات",
        "عبس",
        "التكوير",
        "الإنفطار",
        "المطففين",
        "الإنشقاق",
        "البروج",
        "الطارق",
        "الأعلى",
        "الغاشية",
        "الفجر",
        "البلد",
        "الشمس",
        "الليل",
        "الضحى",
        "الشرح",
        "التين",
        "العلق",
        "القدر",
        "البينة",
        "الزلزلة",
        "العاديات",
        "القارعة",
        "التكاثر",
        "العصر",
        "الهمزة",
        "الفيل",
        "قريش",
        "الماعون",
        "الكوثر",
        "الكافرون",
        "النصر",
        "المسد",
        "الإخلاص",
        "الفلق",
        "الناس",
    )

    /** 1 - мекканская, 0 - мединская (по номеру суры - 1). */
    private const val MAKKI = "100001100111011111111010111111110111111111111100011111010000000000111111111011111111111111111111100111111111101111"

    fun name(surah: Int): String = NAMES[surah - 1]
    fun makki(surah: Int): Boolean = MAKKI[surah - 1] == '1'

    /** Чтец: полный Коран (114 сур) на сервере mp3quran.net. */
    enum class Reciter(val nameAr: String, val nameEn: String, val server: String) {
        ALAFASY("مشاري العفاسي", "Mishary Alafasy", "https://cdn.mp3quran.net/audio/mishary-alafasy/r1/"),
        ABDULBASIT("عبدالباسط عبدالصمد", "Abdulbasit Abdulsamad", "https://cdn.mp3quran.net/audio/abdulbasit-abdulsamad/r3/"),
        HUSARY("محمود خليل الحصري", "Mahmoud Khalil Al-Husary", "https://cdn.mp3quran.net/audio/mahmoud-husary/r1/"),
        MINSHAWI("محمد صديق المنشاوي", "Mohamed Siddiq Al-Minshawi", "https://cdn.mp3quran.net/audio/muhammad-minshawi/r1/"),
        SUDAIS("عبدالرحمن السديس", "Abdulrahman Al-Sudais", "https://cdn.mp3quran.net/audio/abdulrahman-sudais/r1/"),
        SHURAIM("سعود الشريم", "Saud Al-Shuraim", "https://cdn.mp3quran.net/audio/saud-shuraim/r1/"),
        MUAIQLY("ماهر المعيقلي", "Maher Al-Muaiqly", "https://cdn.mp3quran.net/audio/maher-muaiqly/r1/"),
        // hog.26: чтецы из «تدارس القرآن» и самые известные; все - Хафс, муратталь, 114 сур (проверено по API mp3quran 2026-11-05)
        DOSARI("ياسر الدوسري", "Yasser Al-Dosari", "https://cdn.mp3quran.net/audio/yasser-dosari/r1/"),
        QATAMI("ناصر القطامي", "Nasser Al-Qatami", "https://cdn.mp3quran.net/audio/nasser-qatami/r1/"),
        AJMI("أحمد بن علي العجمي", "Ahmad Al-Ajmi", "https://cdn.mp3quran.net/audio/ahmad-ajmi/r1/"),
        GHAMDI("سعد الغامدي", "Saad Al-Ghamdi", "https://cdn.mp3quran.net/audio/saad-ghamdi/r1/"),
        SHATRI("أبو بكر الشاطري", "Abu Bakr Al-Shatri", "https://cdn.mp3quran.net/audio/abubakr-shatri/r1/"),
        FARES("فارس عباد", "Fares Abbad", "https://cdn.mp3quran.net/audio/fares-abbad/r1/"),
        JUHANI("عبدالله عواد الجهني", "Abdullah Al-Juhani", "https://cdn.mp3quran.net/audio/abdullah-juhani/r1/"),
        JALIL("خالد الجليل", "Khalid Al-Jalil", "https://cdn.mp3quran.net/audio/khalid-jalil/r1/"),
        ABKAR("إدريس أبكر", "Idris Abkar", "https://cdn.mp3quran.net/audio/idrees-abkar/r1/"),
        AYYUB("محمد أيوب", "Muhammad Ayyub", "https://cdn.mp3quran.net/audio/muhammad-ayyub/r1/"),
        ALI_JABER("علي جابر", "Ali Jaber", "https://cdn.mp3quran.net/audio/ali-jaber/r1/"),
        JIBREEL("محمد جبريل", "Muhammad Jibreel", "https://cdn.mp3quran.net/audio/muhammad-jibreel/r1/"),
        RIFAI("هاني الرفاعي", "Hani Ar-Rifai", "https://cdn.mp3quran.net/audio/hani-rifai/r1/"),
        BASFAR("عبدالله بصفر", "Abdullah Basfar", "https://cdn.mp3quran.net/audio/abdullah-basfar/r1/"),
        BUKHATIR("صلاح بو خاطر", "Salah Bukhatir", "https://cdn.mp3quran.net/audio/salah-bukhatir/r1/"),
        TABLAWI("محمد الطبلاوي", "Muhammad Al-Tablawi", "https://cdn.mp3quran.net/audio/muhammad-tablawi/r1/"),
        NAUINA("أحمد نعينع", "Ahmad Nauina", "https://cdn.mp3quran.net/audio/ahmad-naina/r1/"),
        AKHDAR("إبراهيم الأخضر", "Ibrahim Al-Akhdar", "https://cdn.mp3quran.net/audio/ibrahim-akhdar/r1/"),
        QASIM("عبدالمحسن القاسم", "Abdulmohsen Al-Qasim", "https://cdn.mp3quran.net/audio/abdulmohsen-qasim/r1/"),
        MOHANNA("خالد المهنا", "Khalid Al-Mohanna", "https://cdn.mp3quran.net/audio/khalid-mohanna/r1/"),
        SHAHEEN("أحمد خليل شاهين", "Ahmad Shaheen", "https://cdn.mp3quran.net/audio/ahmad-shaheen/r1/"),
        ALAQMI("أكرم العلاقمي", "Akram Al-Alaqmi", "https://cdn.mp3quran.net/audio/akram-alaqmi/r1/"),
        SAHL("سهل ياسين", "Sahl Yassin", "https://cdn.mp3quran.net/audio/sahl-yassin/r1/"),
        SOUISSI("علي حجاج السويسي", "Ali Hajjaj Al-Souissi", "https://cdn.mp3quran.net/audio/ali-souissi/r1/"),
        IMAD_HAFEZ("عماد زهير حافظ", "Emad Hafez", "https://cdn.mp3quran.net/audio/imad-hafez/r1/"),
        OOSI("عبدالرحمن العوسي", "Abdulrahman Al-Oosi", "https://cdn.mp3quran.net/audio/abdulrahman-oosi/r1/");

        fun url(surah: Int): String = server + "%03d.mp3".format(surah)
    }

    /** Аяты суры из текста Tanzil (строки «сура|аят|текст»). */
    fun ayat(lines: Sequence<String>, surah: Int): List<String> {
        val prefix = "$surah|"
        return lines.dropWhile { !it.startsWith(prefix) }.takeWhile { it.startsWith(prefix) }
            .map { it.substringAfter('|').substringAfter('|') }.toList()
    }

    /**
     * В тексте Tanzil басмала стоит в начале первого аята каждой суры (кроме ат-Тауба). Аятом она является только в
     * аль-Фатихе: для показа отделяем её отдельной строкой (сам файл не меняется - условие лицензии).
     * Варианты написания: «بِسْمِ» и «بِّسْمِ» (95, 97).
     */
    fun splitBasmala(surah: Int, ayat: List<String>, fatiha1: String): Pair<String?, List<String>> {
        if (surah == 1 || ayat.isEmpty() || fatiha1.isBlank()) return null to ayat
        // в 95 и 97 после «ب» ещё шадда
        val forms = listOf(fatiha1, fatiha1.take(1) + "\u0651" + fatiha1.drop(1))
        val b = forms.firstOrNull { ayat[0].startsWith("$it ") } ?: return null to ayat
        return b to (listOf(ayat[0].substring(b.length).trim()) + ayat.drop(1))
    }

    /** Следующая сура для непрерывного чтения; после ан-Наса - конец. */
    fun next(surah: Int): Int? = if (surah < SURAHS) surah + 1 else null
}
