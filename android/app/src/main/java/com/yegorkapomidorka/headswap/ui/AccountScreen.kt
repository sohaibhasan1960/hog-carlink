package com.yegorkapomidorka.headswap.ui

import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.support.AccountGate
import com.yegorkapomidorka.headswap.support.GoogleSignIn
import com.yegorkapomidorka.headswap.support.SupportJob

/**
 * HOG CarLink: активация входом Google (AccountGate). Обойти нельзя: «назад» закрывает приложение, а MainActivity
 * показывает этот экран поверх любого другого, пока входа нет. После входа экран уходит сам.
 */
class AccountScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val result = ctx.text("", 15f, 500, ctx.clr(R.color.on2)).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        lineHeight = ctx.dp(21)
    }
    private var busy = false

    init {
        val c = ctx
        val body = c.vbox(16).apply {
            pad(c, 24, 48, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        body.addView(c.text(c.getString(R.string.acc_title), 26f, 600).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        body.addView(c.text(c.getString(R.string.acc_why), 16f, 400, c.clr(R.color.on2)).apply {
            gravity = Gravity.CENTER
            lineHeight = c.dp(24)
        }, lp(MATCH, WRAP))
        body.addView(c.primaryButton(c.getString(R.string.sup_google_btn)).apply { setOnClickListener { signIn() } },
            lp(MATCH, WRAP).apply { topMargin = c.dp(16) })
        body.addView(result, lp(MATCH, WRAP))
        body.addView(c.text(c.getString(R.string.acc_note), 13f, 400, c.clr(R.color.on2)).apply {
            gravity = Gravity.CENTER
            lineHeight = c.dp(19)
        }, lp(MATCH, WRAP).apply { topMargin = c.dp(24) })
        view = ScrollView(c).apply {
            isFillViewport = true
            addView(body)
        }
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
                    host.accountDone()
                }
                is GoogleSignIn.Result.Cancelled -> say("", R.color.on2)
                is GoogleSignIn.Result.Failed -> say(when {
                    "NoCredential" in r.why -> ctx.getString(R.string.sup_google_no_account)
                    "ProviderConfiguration" in r.why || "Unsupported" in r.why -> ctx.getString(R.string.sup_google_no_provider)
                    else -> ctx.getString(R.string.sup_google_failed, r.why)
                }, R.color.warn)
            }
        }
    }

    override fun onShow() {
        if (!AccountGate.required(ctx)) host.accountDone()
    }

    /** Без входа приложением пользоваться нельзя: «назад» закрывает его. */
    override fun onBack(): Boolean {
        host.finish()
        return true
    }
}
