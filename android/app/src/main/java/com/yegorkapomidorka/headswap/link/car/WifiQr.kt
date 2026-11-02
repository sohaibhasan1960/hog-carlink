package com.yegorkapomidorka.headswap.link.car

/**
 * HOG CarLink: QR-код Wi-Fi с экрана машины -> имя сети и пароль. Формат тот же, что у «Поделиться сетью» в Android
 * и у ZXing: `WIFI:T:WPA;S:<имя>;P:<пароль>;H:true;;`. Поля в любом порядке, значения могут быть в кавычках,
 * спецсимволы `\ ; , : "` экранируются обратной косой. Без Android-классов: проверяется unit-тестами.
 */
object WifiQr {
    data class Net(val ssid: String, val pass: String, val hidden: Boolean = false)

    private const val PREFIX = "WIFI:"

    /** null - это не Wi-Fi код (нет префикса или имени сети). */
    fun parse(text: String): Net? {
        val t = text.trim()
        if (t.length < PREFIX.length || !t.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        var i = PREFIX.length
        val n = t.length
        while (i < n) {
            if (t[i] == ';') {
                i++
                continue
            }
            val colon = t.indexOf(':', i)
            if (colon < 0) break
            val key = t.substring(i, colon).trim().uppercase()
            i = colon + 1
            val sb = StringBuilder()
            var quoted = i < n && t[i] == '"'
            if (quoted) i++
            while (i < n) {
                val ch = t[i]
                when {
                    ch == '\\' && i + 1 < n -> {
                        sb.append(t[i + 1])
                        i += 2
                    }
                    quoted && ch == '"' -> {
                        quoted = false
                        i++
                    }
                    !quoted && ch == ';' -> break
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            if (key.isNotEmpty()) fields[key] = sb.toString()
        }
        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        val open = fields["T"].orEmpty().equals("nopass", ignoreCase = true)
        val pass = if (open) "" else fields["P"].orEmpty()
        return Net(ssid, pass, fields["H"].orEmpty().equals("true", ignoreCase = true))
    }
}
