package com.yegorkapomidorka.headswap.aa

/**
 * HOG CarLink: «отпечаток» ГУ, который она сама присылает при подключении CarLife: HU_INFO (производитель - часто
 * Bluetooth-имя машины, модель, product, board) и STATISTIC_INFO.channel (прошивка ГУ). Bluetooth-имя у каждой машины
 * своё (у VW «VW BT 5075», «VW BT 9491»...), поэтому опознаём прежде всего по каналу прошивки.
 */
data class CarPrint(
    val manufacturer: String? = null,
    val model: String? = null,
    val product: String? = null,
    val board: String? = null,
    val channel: String? = null,
) {
    /** Для журнала и запроса поддержки. */
    fun label(): String = listOfNotNull(manufacturer, model, product, board, channel?.let { "ch $it" })
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")

    /** Пришли обе части: канал прошивки (STATISTIC_INFO) и HU_INFO. */
    fun complete(): Boolean = channel != null && (manufacturer != null || model != null || product != null || board != null)

    /**
     * Для общей базы: Bluetooth-имя машины («VW BT 5075») у каждой своё - цифры заменяем на #; модель, совпадающая
     * с этим именем, - тоже оно, её не отправляем.
     */
    fun shareable(): CarPrint = copy(
        manufacturer = manufacturer?.let { mask(it) },
        model = model?.takeIf { it != manufacturer },
    )

    /** Ключ записи в общей базе: канал, модель, product, board - без Bluetooth-имени (SHA-256, 24 hex). */
    fun key(): String {
        val s = listOf(channel, model, product, board).joinToString("|") { it.orEmpty() }
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    private companion object {
        fun mask(s: String) = s.replace(Regex("[0-9]"), "#")
    }
}

/**
 * HOG CarLink: какая это машина из списка «Мой автомобиль» - по отпечатку ГУ. Таблица собрана из того, что мост
 * уже знает о ГУ (константы AaBridgeCore и комментарии CarPreset). Только уверенные совпадения: канал, общий для
 * разных машин (VW CNS3 20082122 - Tharu и другие), не опознаём. VW ID.6 (канал 20082128) здесь нет: её
 * настройки ставит [AaPrefs.learnCar], а пресет VW ID.UNYX ей не подходит.
 */
object CarRecognizer {
    private val BY_CHANNEL = mapOf(
        AaBridgeCore.VW_20082138_CHANNEL to CarPreset.VW_ID_UNYX,
        AaBridgeCore.HONDA_20832125_CHANNEL to CarPreset.HONDA_VEZEL_2022,
        AaBridgeCore.DESAY_G6S_CHANNEL to CarPreset.MAZDA_CX5_2025,
        AaBridgeCore.MAZDA_20622111_CHANNEL to CarPreset.MAZDA_3_CN_2025,
        AaBridgeCore.MOBIS_NX4C_CHANNEL to CarPreset.HYUNDAI_TUCSON_NX4C,
        AaBridgeCore.SKODA_CNS3_21290002_CHANNEL to CarPreset.SKODA_OCTAVIA_PRO,
        AaBridgeCore.VW_CNS3_20082134_CHANNEL to CarPreset.VW_TIGUAN_L_PRO,
        "21070007" to CarPreset.TOYOTA_COROLLA_CROSS_2024,   // HSAE, 1920×720 (h420)
    )

    /** Пресет машины по отпечатку или null (не знаем - человек выбирает сам). */
    fun match(p: CarPrint): CarPreset? {
        if (p.board == AaBridgeCore.MOBIS_BOARD || p.model == AaBridgeCore.MOBIS_BOARD) return CarPreset.HYUNDAI_TUCSON_NX4C
        if (p.product == AaBridgeCore.HYUNDAI_CN7_PRODUCT) return CarPreset.HYUNDAI_ELANTRA_7
        if (p.manufacturer == AaBridgeCore.PIONEER_MANUFACTURER && p.model == AaBridgeCore.PIONEER_RM56_MODEL) return CarPreset.DONGFENG_DF6_2023
        if (p.manufacturer == AaBridgeCore.DESAY_MANUFACTURER && p.model == AaBridgeCore.DESAY_G6S_MODEL) return CarPreset.MAZDA_CX5_2025
        return p.channel?.let { BY_CHANNEL[it] }
    }

    /** Это наша VW ID.6 (её опознаёт [AaPrefs.learnCar], пресет не ставим). */
    fun isId6(p: CarPrint): Boolean = p.channel == AaPrefs.ID6_CHANNEL
}
