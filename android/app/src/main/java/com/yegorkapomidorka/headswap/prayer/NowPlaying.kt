package com.yegorkapomidorka.headswap.prayer

/**
 * HOG CarLink: что звучит сейчас (сура, план заучивания, книга) - одним неизменяемым снимком.
 *
 * Пишет только сервис [PrayerPlayer] (главная нить), читают мусхаф в машине (нить кадра), MushafActivity, страница
 * Tesla и список Android Auto. Раньше это были восемь отдельных @Volatile полей: читатель мог взять страницу от
 * одного состояния, а аят от другого, а остановка сбрасывала не все поля. Снимок публикуется целиком, читатель
 * берёт [PrayerPlayer.now] один раз и работает с ним.
 */
data class NowPlaying(
    /** Сура/план/книга загружены (играют или на паузе). */
    val active: Boolean = false,
    /** Сура целиком (не план); 0 - нет. */
    val surah: Int = 0,
    /** План заучивания: страница, аят (surah*1000+ayah, 0 - басмала) и следующий; 0 / -1 - тишина. */
    val page: Int = 0,
    val ayah: Int = -1,
    val next: Int = -1,
    val paused: Boolean = false,
    /** Позиция и длительность звука, мс (тик главной нити раз в 250 мс). */
    val posMs: Long = 0L,
    val durMs: Long = 0L,
) {
    /** Звучит ли сейчас (загружено и не на паузе). */
    val playing: Boolean get() = active && !paused

    companion object {
        val NONE = NowPlaying()
    }
}
