package com.yegorkapomidorka.headswap.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R

/**
 * HOG CarLink: «Подключаюсь к машине…» - на несколько секунд, пока телефон входит в Wi-Fi машины. Android принимает
 * запрос сети (WifiNetworkSpecifier) только от приложения на экране; сервис открывает этот экран, когда запрос из фона
 * отклонён. Закрывается сам: сеть есть, сессия пошла или прошло [MAX_MS]. Виден и поверх экрана блокировки.
 */
class CarConnectActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var started = 0L

    private val poll = object : Runnable {
        override fun run() {
            val done = CarLifeState.carNet == "available" || CarLifeState.running
            if (done || System.currentTimeMillis() - started > MAX_MS) {
                finish()
                return
            }
            main.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val c = this
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.hero), 24)
            pad(c, 28, 22, 28, 22)
            addView(c.text(c.getString(R.string.cc_title), 18f, 700, c.clr(R.color.onhero)).apply { gravity = Gravity.CENTER })
            addView(c.text(c.getString(R.string.cc_sub), 14f, 400, c.clr(R.color.onhero2)).apply { gravity = Gravity.CENTER })
        }
        setContentView(FrameLayout(c).apply {
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { finish() }
        })
    }

    override fun onResume() {
        super.onResume()
        started = System.currentTimeMillis()
        // на виду: теперь запрос сети машины пройдёт
        try {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_CAR_NET_FG))
        } catch (_: Exception) {
            finish()
            return
        }
        main.postDelayed(poll, 400)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
        if (!isFinishing) finish()
    }

    private companion object {
        const val MAX_MS = 25_000L
    }
}
