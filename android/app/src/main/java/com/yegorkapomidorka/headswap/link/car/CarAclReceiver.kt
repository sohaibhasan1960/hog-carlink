package com.yegorkapomidorka.headswap.link.car

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * Триггер автостарта: ACL_CONNECTED/DISCONNECTED выбранной машины ([CarProfile]) запускают и гасят ожидание по Wi-Fi.
 * В манифесте выключен (enabled=false), включается [CarProfile.enableTrigger], когда машина выбрана. Чужие устройства игнорируются.
 * На Android 12+ без BLUETOOTH_CONNECT система эти рассылки не доставляет.
 */
class CarAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) return afterBoot(context, action)
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (!BuildConfig.WIRELESS_BETA) return
        val mac = CarProfile(context).btMac ?: return
        AppLog.init(context)      // рассылка может поднять процесс раньше activity и сервиса
        val addr = device(intent)?.address
        if (!CarMac.same(addr, mac)) {
            if (ignoreLog.tryAcquire()) WLog.ev(WLog.TRIGGER, "ignore", "action" to action.substringAfterLast('.'))
            return
        }
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            WLog.ev(WLog.TRIGGER, "acl-connected", "dev" to WLog.maskMac(addr))
            CarTrigger.near(context, "acl")
        } else {
            WLog.ev(WLog.TRIGGER, "acl-disconnected", "dev" to WLog.maskMac(addr))
            CarTrigger.gone(context, "acl")
        }
    }

    /**
     * HOG: после перезагрузки (или обновления) телефон часто подключается к машине по Bluetooth ещё до нас, и
     * ACL_CONNECTED уже не придёт (лог 15.11: перезагрузка 05:25, машина ждала до переподключения в 05:39).
     * Машина уже подключена (A2DP/HEADSET) - запускаем то же, что и по ACL.
     */
    private fun afterBoot(context: Context, action: String) {
        if (!BuildConfig.WIRELESS_BETA) return
        val mac = CarProfile(context).btMac ?: return
        AppLog.init(context)
        val pending = goAsync()
        com.yegorkapomidorka.headswap.link.bt.CarBtState.check(context, mac) { connected ->
            try {
                WLog.ev(WLog.TRIGGER, "boot-check", "action" to action.substringAfterLast('.'), "connected" to connected)
                if (connected) CarTrigger.near(context, "boot")
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        /** Чужих устройств у телефона много (наушники, часы): в лог не чаще раза в минуту. */
        val ignoreLog = MinInterval(60_000L) { SystemClock.elapsedRealtime() }
    }
}
