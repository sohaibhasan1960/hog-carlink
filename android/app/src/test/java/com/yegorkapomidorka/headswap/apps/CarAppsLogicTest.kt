package com.yegorkapomidorka.headswap.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAppsLogicTest {
    private fun a(name: String) = CarAppsLogic.Asset(name, "https://x/$name", 1, null)

    @Test
    fun picksTheRightApk() {
        assertEquals("AABrowser-3.3.apk", CarAppsLogic.pickAsset(CarApp.AABROWSER, listOf(a("source.zip"), a("AABrowser-3.3.apk")))?.name)
        // у Fermata в релизе бывают сборки под другие ABI и мобильная версия - берём только auto arm64
        val fermata = listOf(a("fermata-mobile-2.0.2-arm64.apk"), a("fermata-auto-2.0.2-arm.apk"), a("fermata-auto-2.0.2-arm64.apk"))
        assertEquals("fermata-auto-2.0.2-arm64.apk", CarAppsLogic.pickAsset(CarApp.FERMATA, fermata)?.name)
        assertNull(CarAppsLogic.pickAsset(CarApp.FERMATA, listOf(a("fermata-auto-2.0.2-arm64.apk.sha256"))))
    }

    @Test
    fun signer() {
        assertTrue(CarAppsLogic.signerOk(CarApp.AABROWSER, listOf("A1:B5:F5:C2:5C:27:90:3C:9F:EF:3B:AB:4C:AC:77:A9:08:FA:09:BC:95:67:95:B3:63:10:B4:53:6C:B7:03:3B")))
        assertTrue(CarAppsLogic.signerOk(CarApp.FERMATA, listOf("aba44ddc4ef0e8f162542b0a0065f7c8c97f9b36b51318e02b14842958ca6cc4")))
        assertFalse(CarAppsLogic.signerOk(CarApp.FERMATA, listOf(CarApp.AABROWSER.signerSha256)))
        assertFalse(CarAppsLogic.signerOk(CarApp.AABROWSER, emptyList()))
        // пакет релизной сборки Fermata и каталог HuApps должны совпадать, иначе «Установлено» не найдёт приложение
        assertEquals("me.aap.fermata.auto.dear.google.why", CarApp.FERMATA.pkg)
        for (app in CarApp.values()) {
            assertTrue(app.name, com.yegorkapomidorka.headswap.ui.HuApps.CATALOG.any { it.pkg == app.pkg })
            assertEquals(app, CarApp.forPkg(app.pkg))
        }
    }

    @Test
    fun digestAndVersions() {
        val h = "d5f189977c217910fc0380ce899cdbd41152a561e39128d5bc9d05afb45ce1d2"
        assertEquals(h, CarAppsLogic.sha256("sha256:${h.uppercase()}"))
        assertNull(CarAppsLogic.sha256("sha1:abc"))
        assertNull(CarAppsLogic.sha256(null))
        assertEquals("3.3", CarAppsLogic.versionFromTag("v3.3"))
        assertEquals("2.0.2", CarAppsLogic.versionFromTag("2.0.2"))
        assertTrue(CarAppsLogic.compare("2.0.10", "2.0.2") > 0)
        assertEquals(0, CarAppsLogic.compare("3.3", "3.3.0"))
        assertFalse(CarAppsLogic.hasUpdate(null, "3.3"))
        assertTrue(CarAppsLogic.hasUpdate("3.0", "3.3"))
        assertFalse(CarAppsLogic.hasUpdate("3.3", "3.3"))
        assertFalse(CarAppsLogic.hasUpdate("3.3", null))
        assertEquals("146 MB", CarAppsLogic.sizeMb(152_635_725))
        assertEquals("", CarAppsLogic.sizeMb(-1))
    }
}
