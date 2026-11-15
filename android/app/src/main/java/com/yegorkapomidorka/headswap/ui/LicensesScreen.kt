package com.yegorkapomidorka.headswap.ui

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.support.Licensing
import com.yegorkapomidorka.headswap.support.SupportCloud
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HOG CarLink: владелец - подписчики сборок продавцов (licenses) и условия продавцов (sellers: автоодобрение, срок).
 * Продлить (+30 дней / +год от текущего окончания или от сегодня), остановить, включить, одобрить ожидающего.
 */
class LicensesScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val cloud = SupportCloud(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val sellersBox: LinearLayout
    private val listBox: LinearLayout
    private val status = ctx.text("", 14f, 500, ctx.clr(R.color.on2))

    init {
        val c = ctx
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 24) }
        body.addView(status, lp(MATCH, WRAP))
        body.addView(c.sectionTitle(c.getString(R.string.lic_sellers)))
        sellersBox = c.vbox(10)
        body.addView(sellersBox, lp(MATCH, WRAP))
        body.addView(c.sectionTitle(c.getString(R.string.lic_customers)))
        listBox = c.vbox(10)
        body.addView(listBox, lp(MATCH, WRAP))
        view = scaffold(c, c.getString(R.string.lic_title), { host.back() }, body)
    }

    override fun onShow() = load()

    private fun bg(work: () -> Unit) = Thread(work, "licenses").start()

    private fun load() {
        status.text = ctx.getString(R.string.lic_loading)
        bg {
            val r = try {
                Triple(cloud.licenses(), cloud.sellers(), null)
            } catch (e: Exception) {
                Triple(null, null, e.message ?: e.javaClass.simpleName)
            }
            main.post { render(r.first, r.second, r.third) }
        }
    }

    private fun render(list: List<SupportCloud.License>?, sellers: List<SupportCloud.Seller>?, err: String?) {
        val c = ctx
        status.text = err?.let { c.getString(R.string.lic_failed, it) } ?: ""
        sellersBox.removeAllViews()
        listBox.removeAllViews()
        if (list == null || sellers == null) return
        val brands = (sellers.map { it.brand } + list.map { it.brand }).filter { it.isNotBlank() }.distinct()
        for (b in brands) sellersBox.addView(sellerCard(sellers.firstOrNull { it.brand == b } ?: SupportCloud.Seller(b, false, 0)), lp(MATCH, WRAP))
        sellersBox.addView(addSeller(), lp(MATCH, WRAP))
        if (list.isEmpty()) listBox.addView(c.text(c.getString(R.string.lic_none), 14f, 400, c.clr(R.color.on2)))
        for (l in list) listBox.addView(licenseCard(l), lp(MATCH, WRAP))
    }

    private fun sellerCard(s: SupportCloud.Seller): View {
        val c = ctx
        val card = c.card().apply { pad(c, 16, 12, 16, 12) }
        val box = c.vbox(8)
        box.addView(c.text(s.brand, 17f, 600))
        val days = numberField(s.termDays.takeIf { it > 0 }?.toString() ?: "365")
        val auto = ToggleRow(c, c.getString(R.string.lic_auto), c.getString(R.string.lic_auto_sub), s.autoApprove, standalone = false) {}
        box.addView(auto.view, lp(MATCH, WRAP))
        box.addView(c.text(c.getString(R.string.lic_term_days), 14f, 400, c.clr(R.color.on2)))
        box.addView(days, lp(MATCH, c.dp(48)))
        box.addView(c.tonalButton(c.getString(R.string.lic_save)).apply {
            setOnClickListener { act { cloud.setSeller(s.brand, auto.sw.isChecked, days.text.toString().toIntOrNull() ?: 0) } }
        }, lp(MATCH, WRAP))
        card.addView(box, lp(MATCH, WRAP))
        return card
    }

    private fun addSeller(): View {
        val c = ctx
        val row = c.hbox(8)
        val id = EditText(c).apply {
            hint = c.getString(R.string.lic_new_seller)
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(c.clr(R.color.on))
            setHintTextColor(c.clr(R.color.on2))
            background = c.shape(c.clr(R.color.surf2), 14)
            pad(c, 12, 0, 12, 0)
        }
        row.addView(id, lp(0, c.dp(48), 1f))
        row.addView(c.tonalButton(c.getString(R.string.lic_add)).apply {
            setOnClickListener {
                val b = id.text.toString().trim().lowercase()
                if (b.matches(Regex("[a-z0-9_-]{2,30}"))) act { cloud.setSeller(b, false, 365) }
            }
        }, lp(WRAP, WRAP))
        return row
    }

    private fun licenseCard(l: SupportCloud.License): View {
        val c = ctx
        val now = System.currentTimeMillis()
        val st = Licensing.state(l.status, l.expiresAt, now)
        val card = c.card().apply { pad(c, 16, 12, 16, 12) }
        val box = c.vbox(6)
        box.addView(c.text(l.email.ifBlank { c.getString(R.string.lic_no_google) }, 16f, 600))
        val who = listOf(l.name, l.brand, l.model).filter { it.isNotBlank() }.joinToString(" · ")
        if (who.isNotBlank()) box.addView(c.text(who, 13f, 400, c.clr(R.color.on2)))
        val stateText = when (st) {
            Licensing.State.OK -> c.getString(R.string.lic_state_ok, date(l.expiresAt))
            Licensing.State.EXPIRED -> c.getString(R.string.lic_state_expired, date(l.expiresAt))
            Licensing.State.PENDING -> c.getString(R.string.lic_state_pending)
            Licensing.State.SUSPENDED -> c.getString(R.string.lic_state_suspended)
            Licensing.State.NONE -> l.status
        }
        box.addView(c.text(stateText, 14f, 500, c.clr(if (st == Licensing.State.OK) R.color.ok else R.color.warn)))
        val row = c.hbox(8)
        fun extend(days: Int) = act { cloud.setLicense(l.uid, "active", Licensing.expiryFor(maxOf(now, l.expiresAt), days)) }
        row.addView(c.tonalButton(c.getString(R.string.lic_plus_month), heightDp = 44, sp = 14f).apply { setOnClickListener { extend(30) } }, lp(0, WRAP, 1f))
        row.addView(c.tonalButton(c.getString(R.string.lic_plus_year), heightDp = 44, sp = 14f).apply { setOnClickListener { extend(365) } }, lp(0, WRAP, 1f))
        val stop = st == Licensing.State.OK
        row.addView(c.tonalButton(c.getString(if (stop) R.string.lic_suspend else R.string.lic_resume), heightDp = 44, sp = 14f).apply {
            setOnClickListener {
                if (stop) act { cloud.setLicense(l.uid, "suspended", l.expiresAt) }
                else if (l.expiresAt > now) act { cloud.setLicense(l.uid, "active", l.expiresAt) }
                else extend(365)
            }
        }, lp(0, WRAP, 1f))
        box.addView(row, lp(MATCH, WRAP))
        card.addView(box, lp(MATCH, WRAP))
        return card
    }

    private fun act(work: () -> Unit) {
        status.text = ctx.getString(R.string.lic_saving)
        bg {
            val err = try {
                work()
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (err != null) Toast.makeText(ctx, ctx.getString(R.string.lic_failed, err), Toast.LENGTH_LONG).show()
                load()
            }
        }
    }

    private fun numberField(v: String) = EditText(ctx).apply {
        setText(v)
        inputType = InputType.TYPE_CLASS_NUMBER
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ctx.clr(R.color.on))
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 12, 0, 12, 0)
    }

    private fun date(ms: Long) = if (ms <= 0) "-" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
}
