package com.yegorkapomidorka.headswap.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.AppBrand
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.support.AccountGate
import com.yegorkapomidorka.headswap.support.GoogleSignIn
import com.yegorkapomidorka.headswap.support.Licensing
import com.yegorkapomidorka.headswap.support.SupportCloud
import com.yegorkapomidorka.headswap.support.SupportJob

/**
 * HOG CarLink: экран активации (AccountGate): вход Google, а в сборке продавца ещё и подписка. Обойти нельзя: «назад»
 * закрывает приложение, MainActivity показывает этот экран поверх любого другого, пока не активировано.
 */
class AccountScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val main = Handler(Looper.getMainLooper())
    private val box: LinearLayout
    private val result = ctx.text("", 15f, 500, ctx.clr(R.color.on2)).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        lineHeight = ctx.dp(21)
    }
    private var busy = false
    private var signInFailed = false
    private var licState: Licensing.State? = null

    init {
        val c = ctx
        box = c.vbox(16).apply {
            pad(c, 24, 40, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        view = ScrollView(c).apply {
            isFillViewport = true
            addView(box)
        }
        render()
    }

    private fun render() {
        val c = ctx
        box.removeAllViews()
        (result.parent as? LinearLayout)?.removeView(result)
        if (AppBrand.isSeller) box.addView(SellerCard.logo(c), lp(MATCH, c.dp(110)))
        box.addView(c.text(c.getString(R.string.acc_title), 26f, 600).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        if (AccountGate.signInRequired(c)) {
            box.addView(centered(c.getString(R.string.acc_why), 16f), lp(MATCH, WRAP))
            box.addView(c.primaryButton(c.getString(R.string.sup_google_btn)).apply { setOnClickListener { signIn() } },
                lp(MATCH, WRAP).apply { topMargin = c.dp(16) })
            box.addView(result, lp(MATCH, WRAP))
            // сборка продавца: телефон без службы входа Google (Huawei и т.п.) - запрос владельцу без Google
            if (AppBrand.isSeller && signInFailed) {
                box.addView(c.tonalButton(c.getString(R.string.acc_no_google)).apply {
                    setOnClickListener {
                        SupportCloud(c).skipGoogle = true
                        checkLicense()
                    }
                }, lp(MATCH, WRAP))
            }
            box.addView(centered(c.getString(R.string.acc_note), 13f), lp(MATCH, WRAP).apply { topMargin = c.dp(24) })
        } else {
            val text = when (licState) {
                null -> R.string.acc_lic_checking
                Licensing.State.PENDING -> R.string.acc_lic_pending
                Licensing.State.SUSPENDED -> R.string.acc_lic_suspended
                Licensing.State.EXPIRED -> R.string.acc_lic_expired
                Licensing.State.NONE -> R.string.acc_lic_offline
                Licensing.State.OK -> R.string.acc_lic_checking
            }
            box.addView(centered(c.getString(text), 17f), lp(MATCH, WRAP))
            if (licState != null && !busy) {
                box.addView(c.primaryButton(c.getString(R.string.acc_retry)).apply { setOnClickListener { checkLicense() } },
                    lp(MATCH, WRAP).apply { topMargin = c.dp(8) })
            }
        }
        if (AppBrand.isSeller) box.addView(SellerCard.contacts(c), lp(MATCH, WRAP).apply { topMargin = c.dp(16) })
    }

    private fun centered(s: String, sp: Float) = ctx.text(s, sp, 400, ctx.clr(R.color.on2)).apply {
        gravity = Gravity.CENTER
        lineHeight = ctx.dp(sp * 1.5f)
    }

    private fun say(text: String, color: Int) {
        result.text = text
        result.setTextColor(ctx.clr(color))
        result.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun signIn() {
        if (busy) return
        busy = true
        say(ctx.getString(R.string.sup_google_wait), R.color.on2)
        GoogleSignIn.start(host) { r ->
            busy = false
            when (r) {
                is GoogleSignIn.Result.Ok -> {
                    AppLog.i("активация: вход Google выполнен", true)
                    SupportJob.schedule(ctx)
                    if (AccountGate.required(ctx)) {
                        render()
                        checkLicense()
                    } else {
                        host.accountDone()
                    }
                }
                is GoogleSignIn.Result.Cancelled -> say("", R.color.on2)
                is GoogleSignIn.Result.Failed -> {
                    signInFailed = true
                    render()
                    say(when {
                        "NoCredential" in r.why -> ctx.getString(R.string.sup_google_no_account)
                        "ProviderConfiguration" in r.why || "Unsupported" in r.why -> ctx.getString(R.string.sup_google_no_provider)
                        else -> ctx.getString(R.string.sup_google_failed, r.why)
                    }, R.color.warn)
                }
            }
        }
    }

    /** Сборка продавца: проверить (или создать) лицензию на сервере. */
    private fun checkLicense() {
        if (busy) return
        busy = true
        licState = null
        render()
        val app = ctx.applicationContext
        Thread({
            val s = Licensing.refreshQuietly(app)
            main.post {
                busy = false
                licState = s
                if (!AccountGate.required(ctx)) host.accountDone() else render()
            }
        }, "license-check").start()
    }

    override fun onShow() {
        when {
            !AccountGate.required(ctx) -> host.accountDone()
            AppBrand.isSeller && !AccountGate.signInRequired(ctx) -> checkLicense()
        }
    }

    /** Без активации приложением пользоваться нельзя: «назад» закрывает его. */
    override fun onBack(): Boolean {
        host.finish()
        return true
    }
}
