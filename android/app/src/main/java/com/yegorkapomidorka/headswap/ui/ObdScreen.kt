package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.obd.ObdLink

/**
 * HOG CarLink: OBD-адаптер ELM327 (KONNWEI KW906). Выбор сопряжённого адаптера, живые значения (скорость, 12 В,
 * наружная температура, батарея), коды неисправностей и опытный заряд батареи VW MEB. Опрос - на своей нити, пока экран виден.
 */
class ObdScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val link = ObdLink(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val devicesBox: LinearLayout
    private val status: TextView
    private val values: TextView
    private val codes: TextView
    private val logView: TextView
    private val logLines = ArrayDeque<String>()
    @Volatile private var polling = false
    private var worker: Thread? = null
    private var awaitingPermission = false

    init {
        val c = ctx
        link.log = { line -> main.post { addLog(line) } }
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 24) }
        body.addView(c.text(c.getString(R.string.obd_lead), 14f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(20) })
        status = c.text(c.getString(R.string.obd_not_connected), 16f, 600, c.clr(R.color.acc))
        body.addView(status)

        body.addView(c.sectionTitle(c.getString(R.string.obd_devices)))
        devicesBox = c.vbox(8)
        body.addView(devicesBox, lp(MATCH, WRAP))
        body.addView(c.linkButton(c.getString(R.string.obd_pair)).apply {
            setOnClickListener { try { host.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }
        })

        body.addView(c.sectionTitle(c.getString(R.string.obd_live)))
        values = c.text("—", 17f, 500).apply { lineHeight = c.dp(28) }
        body.addView(c.card().apply { pad(c, 16, 14, 16, 14); addView(values, lp(MATCH, WRAP)) }, lp(MATCH, WRAP))

        val row = c.hbox(8)
        row.addView(c.tonalButton(c.getString(R.string.obd_read_codes)).apply { setOnClickListener { readCodes() } }, lp(0, c.dp(52), 1f))
        row.addView(c.tonalButton(c.getString(R.string.obd_meb_soc)).apply { setOnClickListener { readSoc() } }, lp(0, c.dp(52), 1f))
        body.addView(row, lp(MATCH, WRAP))
        codes = c.text("", 16f, 500)
        body.addView(codes)

        body.addView(c.sectionTitle(c.getString(R.string.obd_log)))
        logView = c.text("", 12f, 400, c.clr(R.color.on2)).apply { typeface = c.mono(); setTextIsSelectable(true) }
        body.addView(logView, lp(MATCH, WRAP))
        body.addView(c.text(c.getString(R.string.obd_note), 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.START; lineHeight = c.dp(19) })

        view = scaffold(c, c.getString(R.string.obd_title), { host.back() }, body)
    }

    override fun onShow() {
        if (!link.granted() && android.os.Build.VERSION.SDK_INT >= 31) {
            awaitingPermission = true
            host.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
        listDevices()
        if (link.connected) startPolling()
    }

    override fun onHide() {
        polling = false
    }

    override fun onBack(): Boolean {
        polling = false
        Thread { link.close() }.start()
        return false
    }

    override fun onPermissionResult() {
        if (awaitingPermission) {
            awaitingPermission = false
            listDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun listDevices() {
        val c = ctx
        devicesBox.removeAllViews()
        val list = link.bonded()
        if (list.isEmpty()) {
            devicesBox.addView(c.text(c.getString(R.string.obd_no_devices), 14f, 400, c.clr(R.color.on2)))
            return
        }
        for (d in list.take(8)) {
            val name = d.name ?: d.address
            val btn = if (ObdLink.looksLikeObd(name)) c.containerButton(name) else c.tonalButton(name)
            btn.setOnClickListener { connect(d) }
            devicesBox.addView(btn, lp(MATCH, c.dp(52)))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        polling = false
        status.text = ctx.getString(R.string.obd_connecting, d.name ?: d.address)
        Thread({
            val ok = try {
                link.connect(d)
                true
            } catch (e: Exception) {
                AppLog.i("OBD: не подключено: ${e.javaClass.simpleName}: ${e.message}")
                main.post { addLog("! ${e.message}") }
                false
            }
            main.post {
                status.text = if (ok) ctx.getString(R.string.obd_connected, d.name ?: d.address) else ctx.getString(R.string.obd_failed)
                if (ok) startPolling()
            }
        }, "obd-connect").start()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        worker = Thread({
            while (polling && link.connected) {
                val r = try {
                    synchronized(link) { link.poll() }
                } catch (e: Exception) {
                    main.post { addLog("! ${e.message}"); status.text = ctx.getString(R.string.obd_failed) }
                    break
                }
                main.post { show(r) }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            polling = false
        }, "obd-poll").apply { start() }
    }

    private fun show(r: ObdLink.Reading) {
        val c = ctx
        val na = c.getString(R.string.obd_na)
        values.text = listOf(
            c.getString(R.string.obd_speed, r.speedKmh?.toString() ?: na),
            c.getString(R.string.obd_volts, r.volts?.let { "%.1f".format(it) } ?: na),
            c.getString(R.string.obd_ambient, r.ambientC?.toString() ?: na),
            c.getString(R.string.obd_battery, r.batteryPct?.let { "%.0f".format(it) } ?: na),
            c.getString(R.string.obd_protocol, r.protocol.ifBlank { na }),
        ).joinToString("\n")
    }

    private fun readCodes() {
        if (!link.connected) return
        codes.text = ctx.getString(R.string.obd_reading)
        Thread({
            val list = try {
                synchronized(link) { link.troubleCodes() }
            } catch (e: Exception) {
                null
            }
            main.post {
                codes.text = when {
                    list == null -> ctx.getString(R.string.obd_failed)
                    list.isEmpty() -> ctx.getString(R.string.obd_no_codes)
                    else -> ctx.getString(R.string.obd_codes, list.joinToString("  "))
                }
            }
        }, "obd-dtc").start()
    }

    private fun readSoc() {
        if (!link.connected) return
        codes.text = ctx.getString(R.string.obd_reading)
        Thread({
            val soc = try {
                synchronized(link) { link.mebSoc() }
            } catch (e: Exception) {
                null
            }
            main.post { codes.text = soc?.let { ctx.getString(R.string.obd_soc, "%.0f".format(it)) } ?: ctx.getString(R.string.obd_soc_none) }
        }, "obd-soc").start()
    }

    private fun addLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 30) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    companion object {
        const val REQ_BT = 47
    }
}
