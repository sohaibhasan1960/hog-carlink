package com.yegorkapomidorka.headswap.link.car

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * HOG CarLink: system-driven wake-up for the selected car. CompanionDeviceManager binds this service when the associated
 * Bluetooth device connects (observing started in [CarPairing]), even with the app process dead, and the app gets the
 * CDM exemption for starting a foreground service from the background. Works next to [CarAclReceiver], not instead of it.
 *
 * API 36+: [onDevicePresenceEvent] (ObservingDevicePresenceRequest by association id); 33-35: onDeviceAppeared(AssociationInfo);
 * 31-32: onDeviceAppeared(String). Events for another association are ignored.
 */
class CarPresenceService : CompanionDeviceService() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)      // процесс могла поднять система ради этого сервиса
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val id = event.associationId
        val what = CarPresencePolicy.of(event.event)
        WLog.ev(WLog.TRIGGER, "cdm-event", "event" to event.event, "cdm" to id, "mine" to isMine(id))
        if (!isMine(id)) return
        when (what) {
            CarPresence.NEAR -> CarTrigger.near(this, "cdm")
            CarPresence.GONE -> CarTrigger.gone(this, "cdm")
            CarPresence.IGNORE -> {}
        }
    }

    @Deprecated("API 33-35")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        WLog.ev(WLog.TRIGGER, "cdm-appeared", "cdm" to associationInfo.id, "mine" to isMine(associationInfo.id))
        if (isMine(associationInfo.id)) CarTrigger.near(this, "cdm")
    }

    @Deprecated("API 33-35")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        WLog.ev(WLog.TRIGGER, "cdm-disappeared", "cdm" to associationInfo.id, "mine" to isMine(associationInfo.id))
        if (isMine(associationInfo.id)) CarTrigger.gone(this, "cdm")
    }

    @Deprecated("API 31-32")
    override fun onDeviceAppeared(address: String) {
        val mine = CarMac.same(address, CarProfile(this).btMac)
        WLog.ev(WLog.TRIGGER, "cdm-appeared", "dev" to WLog.maskMac(address), "mine" to mine)
        if (mine) CarTrigger.near(this, "cdm")
    }

    @Deprecated("API 31-32")
    override fun onDeviceDisappeared(address: String) {
        val mine = CarMac.same(address, CarProfile(this).btMac)
        WLog.ev(WLog.TRIGGER, "cdm-disappeared", "dev" to WLog.maskMac(address), "mine" to mine)
        if (mine) CarTrigger.gone(this, "cdm")
    }

    /** Привязка выбранной машины; без id (API 29-32) судим по MAC в колбэках с адресом. */
    private fun isMine(id: Int): Boolean = CarProfile(this).let { it.isSet && it.cdmAssociationId == id }
}
