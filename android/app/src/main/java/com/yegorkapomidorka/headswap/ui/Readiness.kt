package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi

/** Всё, что надо включить перед поездкой: порядок, тексты, автоопределение «уже сделано». Общее для онбординга и чеклиста. */
enum class StepId(val key: String) {
    AA_DEV("aa_dev"),
    AA_UNKNOWN("aa_unknown"),
    AUTOSTART("autostart"),
    NOTIF("notif"),
    BACKGROUND("background"),
    MIC("mic"),
    BLUETOOTH("bluetooth"),
    LOCATION("location"),
    FIRST("first"),

    /** Выбор машины: проверенные сообществом настройки (CarPreset). Последний шаг; опытные пропускают. */
    CAR("car"),

    /** HOG CarLink: Wi-Fi машины (SSID и пароль): телефон входит в её точку доступа сам. */
    CAR_WIFI("car_wifi"),

    /** HOG CarLink: машина по Bluetooth (CDM) и «Устройства поблизости»: автозапуск, когда машина включилась. */
    CAR_PICK("car_pick"),
}

/**
 * Тексты шага - id ресурсов (hint/extra могут отсутствовать). Тело шага - либо пронумерованный список (string-array [list]),
 * либо абзац ([body], шаг выбора машины). В строки превращает UI: ctx.getString, список - через [listItems].
 */
class StepInfo(
    val id: StepId,
    val title: Int,
    val list: Int?,
    val body: Int?,
    val hint: Int?,
    val primary: Int,
    val extra: Int?,
    val skip: Int,
    /** Короткое название для чеклиста. */
    val short: Int,
) {
    /** Пункты списка; на шаге фона пункты про «Автозапуск» (3-4) есть только на Xiaomi, на Honor - свои про «Запуск приложений». */
    fun listItems(ctx: Context, xiaomi: Boolean = Readiness.isXiaomi()): List<String> {
        val all = list?.let { ctx.resources.getStringArray(it).toList() } ?: return emptyList()
        if (id == StepId.BACKGROUND && Readiness.isHonor()) return all.take(2) + ctx.resources.getStringArray(R.array.step_background_honor_list)
        return if (id == StepId.BACKGROUND && !xiaomi) all.take(2) else all
    }
}

object Readiness {
    val STEPS: List<StepInfo> = listOf(
        StepInfo(
            StepId.AA_DEV, R.string.step_aa_dev_title, R.array.step_aa_dev_list, null, null,
            R.string.step_aa_dev_primary, null, R.string.onb_already_done, R.string.step_aa_dev_short,
        ),
        StepInfo(
            StepId.AA_UNKNOWN, R.string.step_aa_unknown_title, R.array.step_aa_unknown_list, null, R.string.step_aa_unknown_hint,
            R.string.step_aa_unknown_primary, null, R.string.onb_already_done, R.string.step_aa_unknown_short,
        ),
        StepInfo(
            StepId.AUTOSTART, R.string.step_autostart_title, R.array.step_autostart_list, null, R.string.step_autostart_hint,
            R.string.step_autostart_primary, null, R.string.onb_got_it, R.string.step_autostart_short,
        ),
        StepInfo(
            StepId.NOTIF, R.string.step_notif_title, R.array.step_notif_list, null, R.string.step_notif_hint,
            R.string.onb_allow, null, R.string.onb_later, R.string.step_notif_short,
        ),
        StepInfo(
            StepId.BACKGROUND, R.string.step_background_title, R.array.step_background_list, null, null,
            R.string.step_background_primary, R.string.step_background_extra, R.string.onb_already_done, R.string.step_background_short,
        ),
        StepInfo(
            StepId.MIC, R.string.step_mic_title, R.array.step_mic_list, null, R.string.step_mic_hint,
            R.string.onb_allow, null, R.string.onb_later, R.string.step_mic_short,
        ),
        StepInfo(
            StepId.BLUETOOTH, R.string.step_bluetooth_title, R.array.step_bluetooth_list, null, R.string.step_bluetooth_hint,
            R.string.step_bluetooth_primary, null, R.string.onb_already_done, R.string.step_bluetooth_short,
        ),
        StepInfo(
            StepId.LOCATION, R.string.step_location_title, R.array.step_location_list, null, R.string.step_location_hint,
            R.string.step_location_primary, null, R.string.onb_already_done, R.string.step_location_short,
        ),
        // HOG CarLink: только беспровод на одной машине - шаги «Первое подключение по USB» и «Какая у вас машина» убраны
        // (настройки по умолчанию уже под ГУ 800x480), вместо них - сеть машины и выбор машины по Bluetooth.
        StepInfo(
            StepId.CAR_WIFI, R.string.step_car_wifi_title, null, R.string.step_car_wifi_body, R.string.step_car_wifi_hint,
            R.string.step_car_wifi_primary, null, R.string.onb_later, R.string.step_car_wifi_short,
        ),
        StepInfo(
            StepId.CAR_PICK, R.string.step_car_pick_title, R.array.step_car_pick_list, null, R.string.step_car_pick_hint,
            R.string.step_car_pick_primary, null, R.string.onb_later, R.string.step_car_pick_short,
        ),
    )

    fun info(id: StepId): StepInfo = STEPS.first { it.id == id }

    /** Honor/Huawei (MagicOS, EMUI): «Запуск приложений» вручную, иначе система гасит фон и автозапуск. */
    fun isHonor(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("honor") || m.contains("huawei")
    }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** true/false - определено автоматически; null - определить нельзя (только ручная отметка «Уже сделано»). */
    fun auto(ctx: Context, id: StepId): Boolean? = when (id) {
        StepId.NOTIF -> ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        StepId.MIC -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        StepId.BACKGROUND -> batteryOk(ctx)
        StepId.CAR -> if (AaPrefs(ctx).carPreset != null) true else null      // пропуск - ручная отметка
        StepId.CAR_WIFI -> CarWifi(ctx).isSet
        StepId.CAR_PICK -> CarProfile(ctx).isSet && carBtGranted(ctx)
        StepId.LOCATION -> try {
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    fun carBtGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Выполнено: автоопределение, а где его нет - ручная отметка. Фон на Xiaomi требует ещё и ручной отметки. */
    fun isDone(ctx: Context, id: StepId): Boolean {
        val ui = UiPrefs(ctx)
        if (id == StepId.BACKGROUND) return batteryOk(ctx) && (!(isXiaomi() || isHonor()) || ui.isManualDone(id.key))
        return auto(ctx, id) ?: ui.isManualDone(id.key)
    }

    // --- действия кнопок ---

    fun appDetails(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun requestIgnoreBattery(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.e("не открыть запрос исключения из оптимизации батареи", e)
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                appDetails(ctx)
            }
        }
    }

    /** Автозапуск Xiaomi (Безопасность); не вышло - карточка приложения. */
    fun openXiaomiAutostart(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.i("автозапуск Xiaomi не открылся (${e.javaClass.simpleName}), открываю карточку приложения")
            try {
                appDetails(ctx)
            } catch (e2: Exception) {
                AppLog.e("не открыть карточку приложения", e2)
            }
        }
    }

    /**
     * HOG CarLink: «Запуск приложений» Honor/Huawei (ручное управление: автозапуск, вторичный запуск, работа в фоне).
     * Экран у прошивок разный: пробуем известные, иначе карточка приложения.
     */
    fun openHonorLaunch(ctx: Context) {
        val known = listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        )
        for ((pkg, cls) in known) {
            try {
                ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        AppLog.i("«Запуск приложений» Honor не открылся, открываю карточку приложения")
        appDetails(ctx)
    }

    fun openLocationSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun openBluetoothSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)

    private fun openSettings(ctx: Context, action: String) {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть $action", e)
        }
    }
}
