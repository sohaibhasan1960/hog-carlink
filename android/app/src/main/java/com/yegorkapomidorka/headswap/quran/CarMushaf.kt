package com.yegorkapomidorka.headswap.quran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.prayer.HifzPrefs
import com.yegorkapomidorka.headswap.prayer.PrayerPlayer
import kotlin.math.abs

/**
 * HOG: мусхаф на экране машины (только на стоянке; решает AaBridgeCore по скорости от ГУ). Разворот как в бумажном
 * мусхафе: справа нечётная страница, слева чётная. Снизу кнопки: السابقة / استمع / التالية / خروج.
 * Жест вбок листает разворот, касание аята - слушать с его страницы. Во время плана заучивания разворот следует за
 * чтецом и подсвечивает аят. Кадр рисуется в Bitmap только при изменении, энкодер каждый раз берёт готовый.
 */
object CarMushaf {
    private val BG = Color.rgb(0x14, 0x17, 0x1C)
    private val BTN = Color.rgb(0x2A, 0x2F, 0x37)
    private val GOLD_DIM = Color.rgb(0x6B, 0x55, 0x2A)
    private val INK = Color.rgb(0xF1, 0xE9, 0xD8)
    private val DIM = Color.rgb(0x8A, 0x85, 0x7B)

    @Volatile private var app: Context? = null
    @Volatile private var data: MadaniData.Data? = null
    @Volatile private var painter: MushafPainter? = null
    /** Показать на экране машины (BridgeService -> AaBridgeCore.enterMushaf): null - показан, иначе причина отказа. */
    @Volatile var onEnter: (() -> String?)? = null
    /** Выход из мусхафа на экране (BridgeService -> AaBridgeCore.exitMushaf). */
    @Volatile var onExit: (() -> Unit)? = null

    /** Первая (правая, нечётная) страница разворота. */
    @Volatile var spread = 1
        private set
    @Volatile private var userTurnAt = 0L
    @Volatile private var seenAyah = -2

    private var cache: Bitmap? = null
    private var cacheKey = ""
    private val bg = Paint().apply { color = BG }
    private val btn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (data != null) return
        Thread({
            try {
                val c = ctx.applicationContext
                painter = MushafPainter(c.resources.getFont(R.font.uthmanic_hafs))
                data = MadaniData.load(c)
            } catch (e: Exception) {
                AppLog.e("مصحف в машине: данные не загружены", e)
            }
        }, "car-mushaf-data").start()
    }

    /** Открыть разворот со страницей [page] (0 - звучащая или текущая страница плана). */
    fun prepare(page: Int) {
        val p = page.takeIf { it in 1..QuranPages.PAGES } ?: PrayerPlayer.hifzNowPage.takeIf { it > 0 }
            ?: app?.let { HifzPrefs(it).page } ?: 1
        spread = spreadOf(p)
        userTurnAt = 0L
    }

    /** Открыть мусхаф на экране машины со страницы [page] (0 - звучащая / текущая плана). null - открыт, иначе причина. */
    fun open(page: Int = 0): String? {
        val enter = onEnter ?: return "carlife"
        prepare(page)
        return enter()
    }

    fun spreadOf(page: Int) = if (page % 2 == 1) page else page - 1

    // ---------------- кадр ----------------

    /** Нить рендера энкодера: кадр мусхафа в (w, h). */
    @Synchronized
    fun draw(c: Canvas, w: Int, h: Int) {
        follow()
        val now = PrayerPlayer.hifzNowPage
        val lit = if (now == spread || now == spread + 1) MushafActivity.litCode(PrayerPlayer.hifzNowAyah, PrayerPlayer.hifzNowNext) else 0
        val key = "$w×$h:$spread:$now:$lit:${data != null}"
        var b = cache
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cache = b
            cacheKey = ""
        }
        if (key != cacheKey) {
            render(Canvas(b), w, h, if (now == spread || now == spread + 1) now else 0, lit)
            cacheKey = key
        }
        c.drawBitmap(b, 0f, 0f, null)
    }

    /** План заучивания играет: разворот за чтецом, пока водитель сам не листал (до смены аята). */
    private fun follow() {
        val p = PrayerPlayer.hifzNowPage
        if (p <= 0) return
        val a = PrayerPlayer.hifzNowAyah
        if (a != seenAyah) {
            seenAyah = a
            userTurnAt = 0L
        }
        if (userTurnAt == 0L) spread = spreadOf(p)
    }

    private class Layout(val right: RectF, val left: RectF, val bar: RectF, val buttons: List<RectF>)

    private fun layout(w: Int, h: Int): Layout {
        val barH = h * 0.13f
        val gap = w * 0.02f
        val areaH = h - barH - gap
        val pageW = minOf((w - 3 * gap) / 2f, areaH * 0.78f)
        val cx = w / 2f
        val right = RectF(cx + gap / 2f, gap / 2f, cx + gap / 2f + pageW, areaH)
        val left = RectF(cx - gap / 2f - pageW, gap / 2f, cx - gap / 2f, areaH)
        val bar = RectF(0f, h - barH, w.toFloat(), h.toFloat())
        val bw = (w - 5 * gap) / 4f
        // справа налево: السابقة, استمع, التالية, خروج
        val buttons = (0 until 4).map { i ->
            val r = w - gap - i * (bw + gap)
            RectF(r - bw, bar.top + barH * 0.12f, r, bar.bottom - barH * 0.12f)
        }
        return Layout(right, left, bar, buttons)
    }

    private fun render(c: Canvas, w: Int, h: Int, playing: Int, lit: Int) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val l = layout(w, h)
        val d = data
        val pt = painter
        val ctx = app
        if (d == null || pt == null || ctx == null) {
            small.color = DIM
            small.textSize = h * 0.05f
            c.drawText("…", w / 2f, h / 2f, small)
            return
        }
        for ((p, r) in listOf(spread to l.right, spread + 1 to l.left)) {
            if (p > QuranPages.PAGES) continue
            val page = d.page(p, pt.markDigitsReversed) ?: continue
            pt.draw(c, page, r, if (p == playing) lit else 0)
            small.color = DIM
            small.textSize = h * 0.032f
            c.drawText(MushafText.arabicDigits(p), r.centerX(), r.bottom + h * 0.012f + small.textSize * 0.6f, small)
        }
        val names = listOf(R.string.mf_prev, R.string.mf_listen_short, R.string.mf_next, R.string.mf_exit)
        label.textSize = l.bar.height() * 0.32f
        for ((i, r) in l.buttons.withIndex()) {
            val enabled = when (i) {
                0 -> spread > 1
                2 -> spread + 2 <= QuranPages.PAGES
                else -> true
            }
            btn.color = if (i == 1) GOLD_DIM else BTN
            c.drawRoundRect(r, r.height() * 0.25f, r.height() * 0.25f, btn)
            label.color = if (enabled) INK else DIM
            val fm = label.fontMetrics
            c.drawText(ctx.getString(names[i]), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, label)
        }
    }

    // ---------------- касания ----------------

    private var downX = 0
    private var downY = 0
    private var downAt = 0L

    /** Касание ГУ (0 down, 1 up, 2 move) в координатах кадра (w, h). Нить моста. */
    @Synchronized
    fun onTouch(action: Int, x: Int, y: Int, w: Int, h: Int) {
        when (action) {
            0 -> {
                downX = x
                downY = y
                downAt = System.currentTimeMillis()
            }
            1 -> {
                val dx = x - downX
                if (abs(dx) > w * 0.12f && abs(dx) > abs(y - downY)) {
                    turn(if (dx > 0) +2 else -2)        // палец вправо - следующий разворот (слева), как в мусхафе
                    return
                }
                tap(x.toFloat(), y.toFloat(), w, h)
            }
        }
    }

    private fun tap(x: Float, y: Float, w: Int, h: Int) {
        val l = layout(w, h)
        for ((i, r) in l.buttons.withIndex()) if (r.contains(x, y)) {
            when (i) {
                0 -> turn(-2)
                1 -> listen(spread)
                2 -> turn(+2)
                3 -> onExit?.invoke()
            }
            return
        }
        val d = data ?: return
        val pt = painter ?: return
        for ((p, r) in listOf(spread to l.right, spread + 1 to l.left)) {
            if (p > QuranPages.PAGES || !r.contains(x, y)) continue
            val page = d.page(p, pt.markDigitsReversed) ?: continue
            if (pt.ayahAt(page, r, x, y) != 0) listen(p)
            return
        }
    }

    private fun turn(d: Int) {
        val s = (spread + d).coerceIn(1, spreadOf(QuranPages.PAGES))
        if (s == spread) return
        spread = s
        userTurnAt = System.currentTimeMillis()
    }

    private fun listen(page: Int) {
        val ctx = app ?: return
        HifzPrefs(ctx).page = page
        PrayerPlayer.hifz(ctx, page)
        userTurnAt = 0L
    }

}
