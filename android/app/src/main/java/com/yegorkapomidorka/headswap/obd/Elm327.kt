package com.yegorkapomidorka.headswap.obd

/**
 * HOG CarLink: разбор ответов адаптера ELM327 (KONNWEI KW906 и подобные). Без Android: только текст ответов -> значения,
 * проверяется тестом. Транспорт (Bluetooth) - ObdLink.
 */
object Elm327 {
    /** Команды начальной настройки: сброс, без эха, без переводов строк и пробелов, без заголовков, протокол - авто. */
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")

    /** Ответ адаптера без мусора: строки без «>», «SEARCHING...», эха и пустых. */
    fun lines(raw: String): List<String> = raw.replace(">", "").split('\r', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !it.startsWith("BUS INIT") }

    /** Ошибка адаптера/машины вместо данных. */
    fun isError(raw: String): Boolean = lines(raw).any { l ->
        l == "?" || l.startsWith("NO DATA") || l.startsWith("UNABLE TO CONNECT") || l.startsWith("CAN ERROR") ||
            l.startsWith("BUS ERROR") || l.startsWith("STOPPED") || l.startsWith("ERROR")
    }

    /** Байты из hex-строк ответа (с пробелами или без). */
    fun bytes(raw: String): List<Int> = lines(raw).flatMap { l ->
        val hex = l.replace(" ", "")
        if (hex.length % 2 != 0 || !hex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) emptyList()
        else hex.chunked(2).map { it.toInt(16) }
    }

    /** Данные PID режима 01: байты после «41 <pid>»; null - нет ответа. */
    fun mode01(raw: String, pid: Int): List<Int>? {
        if (isError(raw)) return null
        val b = bytes(raw)
        for (i in 0 until b.size - 1) if (b[i] == 0x41 && b[i + 1] == pid) return b.drop(i + 2)
        return null
    }

    fun speedKmh(raw: String): Int? = mode01(raw, 0x0D)?.firstOrNull()

    /** Напряжение блока управления (≈ 12-вольтовая батарея), В. */
    fun moduleVolts(raw: String): Double? = mode01(raw, 0x42)?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 1000.0 }

    fun ambientC(raw: String): Int? = mode01(raw, 0x46)?.firstOrNull()?.let { it - 40 }

    /** Остаток тяговой батареи (PID 5B), %. У многих электромобилей не поддерживается. */
    fun hybridBatteryPct(raw: String): Double? = mode01(raw, 0x5B)?.firstOrNull()?.let { it * 100.0 / 255.0 }

    /** Коды неисправностей режима 03: «43 01 33 00 00 00 00» -> [P0133]. */
    fun dtcs(raw: String): List<String> {
        if (isError(raw)) return emptyList()
        val out = ArrayList<String>()
        for (l in lines(raw)) {
            val b = bytes(l)
            val start = b.indexOf(0x43)
            if (start < 0) continue
            // CAN: после 43 бывает байт количества кодов; пары по 2 байта, нули - пусто
            var data = b.drop(start + 1)
            if (data.size % 2 == 1) data = data.drop(1)
            for (i in 0 until data.size - 1 step 2) {
                val a = data[i]
                val c = data[i + 1]
                if (a == 0 && c == 0) continue
                out.add(dtc(a, c))
            }
        }
        return out.distinct()
    }

    fun dtc(a: Int, b: Int): String {
        val sys = "PCBU"[(a shr 6) and 3]
        return "%c%d%X%02X".format(sys, (a shr 4) and 3, a and 0xF, b)
    }

    /**
     * Опытное: заряд тяговой батареи VW MEB (ID.3/ID.4/ID.6) - UDS 22 028C у блока батареи (CAN 29 бит, 17FC007B),
     * ответ «62 02 8C XX», процент = XX / 2.5. На ID.6 CROZZ не проверено.
     */
    val MEB_SOC_SETUP = listOf("ATSP7", "ATSH17FC007B", "ATCRA17FE007B")
    const val MEB_SOC_CMD = "22028C"

    fun mebSocPct(raw: String): Double? {
        if (isError(raw)) return null
        val b = bytes(raw)
        for (i in 0 until b.size - 3) if (b[i] == 0x62 && b[i + 1] == 0x02 && b[i + 2] == 0x8C) return b[i + 3] / 2.5
        return null
    }
}
