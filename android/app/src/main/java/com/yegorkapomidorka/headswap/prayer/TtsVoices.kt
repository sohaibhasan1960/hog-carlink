package com.yegorkapomidorka.headswap.prayer

/**
 * HOG CarLink: арабские голоса Google TTS. Пол определён по высоте тона образцов (эмулятор Android 16, 2026-11-04):
 * ard 126 Гц и are 136 Гц - мужские; arc 184 Гц, arz и голос по умолчанию 209 Гц - женские.
 */
object TtsVoices {
    private val MALE = listOf("ar-xa-x-ard-local", "ar-xa-x-are-local", "ar-xa-x-ard-network", "ar-xa-x-are-network")
    private val FEMALE = setOf("arc", "arz", "language")

    /** Первый доступный мужской голос; нет - null (голос по умолчанию). */
    fun preferredMale(names: List<String>): String? = MALE.firstOrNull { it in names }

    /** Пол по имени: true - мужской, false - женский, null - неизвестно. */
    fun male(name: String): Boolean? {
        val code = name.substringAfter("-x-", name.substringAfterLast('-')).substringBefore('-')
        return when {
            code == "ard" || code == "are" -> true
            code in FEMALE || name == "ar-language" -> false
            else -> null
        }
    }
}
