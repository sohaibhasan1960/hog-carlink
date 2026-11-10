package com.yegorkapomidorka.headswap.aa

/**
 * HOG CarLink: защита от отказа сертификата head unit. Если Android Auto на телефоне [REJECTS] раза подряд
 * отвергает наш сертификат в TLS-рукопожатии, переходим на другой встроенный набор ([AapCerts.SETS]).
 * Каждый набор пробуется не больше одного раза за серию; установленная сессия серию обнуляет.
 * Чистая логика без Android: решение принимает [AaConnector], сохранение выбора - BridgeService.
 */
class CertFailover(private val sets: List<String>, start: String, private val rejects: Int = REJECTS) {
    var current: String = start
        private set
    private var streak = 0
    private val failed = HashSet<String>()

    /**
     * Итог одной попытки подключения к AA. Возвращает набор, на который пора перейти, иначе null
     * (сессия установилась, причина не в сертификате, отказов ещё мало или пробовать больше нечего).
     */
    fun onResult(established: Boolean, reason: String): String? {
        if (established) {
            streak = 0
            failed.clear()
            return null
        }
        if (!isCertRejection(reason)) return null      // сервер выключен, сеть, таймаут - сертификат ни при чём
        if (++streak < rejects) return null
        failed += current
        val next = sets.firstOrNull { it !in failed } ?: return null
        current = next
        streak = 0
        return next
    }

    companion object {
        const val REJECTS = 2

        /**
         * Отказ именно в TLS-рукопожатии (SSLException): так телефон отвергает сертификат ГУ. Таймаут рукопожатия и
         * TLS-ошибки после установления сессии не считаем - это сеть или обрыв, а не сертификат.
         */
        fun isCertRejection(reason: String): Boolean =
            reason.startsWith("TLS handshake не прошёл") || reason.startsWith("TLS: ")
    }
}
