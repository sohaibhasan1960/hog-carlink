package com.yegorkapomidorka.headswap.quran

/** HOG: мелочи для страницы мусхафа. */
object MushafText {
    fun arabicDigits(n: Int): String = n.toString().map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
}
