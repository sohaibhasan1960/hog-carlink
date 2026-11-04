package com.yegorkapomidorka.headswap.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * HOG CarLink: адаптер ELM327 (KONNWEI KW906) по классическому Bluetooth (SPP). Адаптер сопрягают в настройках
 * Bluetooth телефона (имя «KONNWEI», PIN обычно 1234 или 0000), здесь только подключение и опрос.
 * Блокирующий - только с рабочей нити.
 */
class ObdLink(private val ctx: Context) {
    data class Reading(
        val speedKmh: Int? = null,
        val volts: Double? = null,
        val ambientC: Int? = null,
        val batteryPct: Double? = null,
        val mebSocPct: Double? = null,
        val protocol: String = "",
    )

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile var log: (String) -> Unit = {}

    val connected: Boolean get() = socket?.isConnected == true

    fun granted(): Boolean = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Сопряжённые устройства, похожие на OBD-адаптеры, - первыми. */
    @SuppressLint("MissingPermission")
    fun bonded(): List<BluetoothDevice> {
        if (!granted()) return emptyList()
        val all = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: return emptyList()
        return all.sortedByDescending { looksLikeObd(it.name ?: "") }
    }

    @SuppressLint("MissingPermission")
    fun connect(dev: BluetoothDevice) {
        close()
        val s = try {
            dev.createRfcommSocketToServiceRecord(SPP)
        } catch (e: Exception) {
            throw IllegalStateException("RFCOMM: ${e.message}")
        }
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
        for (c in Elm327.INIT) {
            val r = cmd(c, if (c == "ATZ") 3000 else 1500)
            log("$c -> ${r.trim()}")
        }
    }

    /** Одна команда: ответ до «>» или до таймаута. */
    fun cmd(c: String, timeoutMs: Long = 3000): String {
        val out = output ?: throw IllegalStateException("не подключено")
        val inp = input ?: throw IllegalStateException("не подключено")
        while (inp.available() > 0) inp.read()
        out.write((c + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val ch = inp.read()
                if (ch < 0) break
                if (ch == '>'.code) break
                sb.append(ch.toChar())
            } else {
                Thread.sleep(10)
            }
        }
        return sb.toString()
    }

    /** Круг опроса: скорость, напряжение, наружная температура, батарея (PID 5B). */
    fun poll(): Reading {
        val speed = Elm327.speedKmh(cmd("010D"))
        val volts = Elm327.moduleVolts(cmd("0142"))
        val amb = Elm327.ambientC(cmd("0146"))
        val bat = Elm327.hybridBatteryPct(cmd("015B"))
        return Reading(speed, volts, amb, bat, null, cmd("ATDP").trim())
    }

    /** Коды неисправностей (режим 03). */
    fun troubleCodes(): List<String> = Elm327.dtcs(cmd("03", 5000))

    /** Опытное: заряд батареи VW MEB; потом адаптер возвращается в авто-протокол. */
    fun mebSoc(): Double? {
        for (c in Elm327.MEB_SOC_SETUP) log("$c -> ${cmd(c).trim()}")
        val raw = cmd(Elm327.MEB_SOC_CMD, 4000)
        log("${Elm327.MEB_SOC_CMD} -> ${raw.trim()}")
        for (c in listOf("ATCRA", "ATSP0", "ATH0")) cmd(c)
        return Elm327.mebSocPct(raw)
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun looksLikeObd(name: String): Boolean {
            val n = name.uppercase()
            return listOf("KONNWEI", "OBD", "ELM", "VLINK", "V-LINK", "VGATE", "KW90").any { it in n }
        }
    }
}
