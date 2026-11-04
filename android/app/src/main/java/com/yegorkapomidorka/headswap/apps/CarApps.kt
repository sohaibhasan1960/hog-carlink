package com.yegorkapomidorka.headswap.apps

/**
 * HOG CarLink: приложения каталога «Приложения для магнитолы» (ui/HuApps), которые HOG CarLink ставит сам из их
 * официальных релизов GitHub вместо ссылки в браузер. Перед установкой сверяются SHA-256 файла (digest релиза GitHub),
 * имя пакета и сертификат подписи автора ([signerSha256]): подменённый или чужой APK не поставится.
 * Без Android: логика проверяется тестами.
 */
enum class CarApp(
    val pkg: String,
    val repo: String,
    /** Имя APK в релизе: первое совпадение. */
    val asset: Regex,
    /** SHA-256 сертификата подписи автора, нижний регистр без двоеточий. */
    val signerSha256: String,
) {
    /** Браузер для Android Auto (kododake, GPL-3.0). Сертификат сверен с релизом v3.3 (2026-11-04). */
    AABROWSER(
        "com.kododake.aabrowser", "kododake/AABrowser",
        Regex("""^AABrowser-[\w.]+\.apk$"""),
        "a1b5f5c25c27903c9fef3bab4cac77a908fa09bc956795b36310b4536cb7033b",
    ),

    /**
     * Медиаплеер для Android Auto (Andrey Pavlenko, GPL-3.0). Пакет релизной сборки - с суффиксом «.dear.google.why»
     * (прочитан из манифеста 2.0.2, совпадает с каталогом HuApps). Сертификат схем v2/v3 из блока подписи 2.0.2 (2026-11-04).
     */
    FERMATA(
        "me.aap.fermata.auto.dear.google.why", "AndreyPavlenko/Fermata",
        Regex("""^fermata-auto-[\w.]+-arm64\.apk$"""),
        "aba44ddc4ef0e8f162542b0a0065f7c8c97f9b36b51318e02b14842958ca6cc4",
    );

    val apiLatest: String get() = "https://api.github.com/repos/$repo/releases/latest"

    companion object {
        fun forPkg(pkg: String): CarApp? = values().firstOrNull { it.pkg == pkg }
    }
}

object CarAppsLogic {
    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)
    data class Release(val version: String, val asset: Asset)

    /** Тег релиза -> версия: «v3.3» -> «3.3», «2.0.2» -> «2.0.2». */
    fun versionFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** APK приложения в релизе: первое имя по шаблону. */
    fun pickAsset(app: CarApp, assets: List<Asset>): Asset? = assets.firstOrNull { app.asset.matches(it.name) }

    /** «sha256:abcd…» из GitHub -> «abcd…»; другой алгоритм или пусто - null (тогда проверяется только подпись). */
    fun sha256(digest: String?): String? =
        digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase()?.takeIf { it.length == 64 }

    /** Подпись APK наша: среди сертификатов есть сертификат автора. */
    fun signerOk(app: CarApp, certSha256s: List<String>): Boolean =
        certSha256s.any { it.replace(":", "").lowercase() == app.signerSha256 }

    /** Есть ли версия новее установленной (версии не сравнить - нет). */
    fun hasUpdate(installedVersion: String?, latest: String?): Boolean =
        installedVersion != null && latest != null && compare(installedVersion, latest) < 0

    /** Сравнение версий по числам: «2.0.10» > «2.0.2»; хвосты вроде «-beta» отбрасываются. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').substringBefore(' ').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Размер для кнопки: «11 MB», «146 MB». */
    fun sizeMb(bytes: Long): String = if (bytes <= 0) "" else "${(bytes + 512 * 1024) / (1024 * 1024)} MB"
}
