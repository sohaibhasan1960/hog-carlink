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
 * HOG: мусхаф на экране машины (только на стоянке; решает AaBridgeCore по скорости от ГУ).
 *
 * Одна страница на весь экран крупно: экран машины широкий и низкий, вся страница из 15 строк в нём - мелкий
 * шрифт, поэтому видно [VISIBLE_LINES] строк, и окно само едет за звучащим аятом (строка аята - второй сверху).
 * Жест вверх/вниз двигает окно, вбок - листает страницу. Снизу кнопки: السابقة / تشغيل-إيقاف / السرعة / التالية / خروج.
 * Касание аята - слушать с его страницы. Кадр рисуется в Bitmap только при изменении, энкодер каждый раз берёт готовый.
 */
object CarMushaf {
    private val BG = Color.rgb(0x14, 0x17, 0x1C)
    private val BTN = Color.rgb(0x2A, 0x2F, 0x37)
    private val GOLD_DIM = Color.rgb(0x6B, 0x55, 0x2A)
    private val INK = Color.rgb(0xF1, 0xE9, 0xD8)
    private val DIM = Color.rgb(0x8A, 0x85, 0x7B)

    /** Строк страницы в окне: на экране 1280x688 шрифт около 50 px вместо 24 px у всей страницы. */
    const val VISIBLE_LINES = 7
    /** Строк в полной странице мусхафа аль-Мадина. */
    private const val FULL_LINES = 15

    @Volatile private var app: Context? = null
    @Volatile private var data: MadaniData.Data? = null
    @Volatile private var painter: MushafPainter? = null
    /** Показать на экране машины (BridgeService -> AaBridgeCore.enterMushaf): null - показан, иначе причина отказа. */
    @Volatile var onEnter: (() -> String?)? = null
    /** Выход из мусхафа на экране (BridgeService -> AaBridgeCore.exitMushaf). */
    @Volatile var onExit: (() -> Unit)? = null

    /** Страница на экране. */
    @Volatile var page = 1
        private set
    /** Первая видимая строка окна (0 - верх страницы). */
    @Volatile private var top = 0
    @Volatile private var userTurnAt = 0L
    @Volatile private var userScrollAt = 0L
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

    /** Открыть страницу [p] (0 - звучащая или текущая страница плана). */
    fun prepare(p: Int) {
        page = p.takeIf { it in 1..QuranPages.PAGES } ?: PrayerPlayer.hifzNowPage.takeIf { it > 0 }
            ?: app?.let { HifzPrefs(it).page } ?: 1
        top = 0
        userTurnAt = 0L
        userScrollAt = 0L
    }

    /** Открыть мусхаф на экране машины со страницы [page] (0 - звучащая / текущая плана). null - открыт, иначе причина. */
    fun open(page: Int = 0): String? {
        val enter = onEnter ?: return "carlife"
        prepare(page)
        return enter()
    }

    /** План заучивания запущен: показать его страницу на экране машины, если мост жив. Отказ (едет, нет связи) - молча. */
    fun showFor(p: Int) {
        if (onEnter == null) return
        val why = open(p)
        AppLog.i("مصحف в машине: сам при запуске плана - ${why ?: "показан"}", true)
    }

    // ---------------- кадр ----------------

    /** Нить рендера энкодера: кадр мусхафа в (w, h). */
    @Synchronized
    fun draw(c: Canvas, w: Int, h: Int) {
        val lit = follow()
        val playing = PrayerPlayer.quranActive && PrayerPlayer.hifzNowPage > 0 && !PrayerPlayer.quranPausedNow
        val key = "$w×$h:$page:$top:$lit:$playing:${data != null}:${app?.let { com.yegorkapomidorka.headswap.prayer.QuranPrefs(it).speed }}"
        var b = cache
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cache = b
            cacheKey = ""
        }
        if (key != cacheKey) {
            render(Canvas(b), w, h, lit, playing)
            cacheKey = key
        }
        c.drawBitmap(b, 0f, 0f, null)
    }

    /** Звучащий аят на этой странице (код для подсветки) или 0; страница и окно - за чтецом, пока водитель не трогал. */
    private fun follow(): Int {
        val now = PrayerPlayer.hifzNowPage
        if (now <= 0) return 0
        val a = PrayerPlayer.hifzNowAyah
        if (a != seenAyah) {
            seenAyah = a
            userTurnAt = 0L
            userScrollAt = 0L
        }
        if (userTurnAt == 0L && page != now) {
            page = now
            top = 0
        }
        if (page != now) return 0
        val lit = MushafActivity.litCode(PrayerPlayer.hifzNowAyah, PrayerPlayer.hifzNowNext)
        if (userScrollAt == 0L) lineOf(lit)?.let { top = clampTop(it - 2) }
        return lit
    }

    /** Строка (0..) страницы, где аят [lit] (или басмала -сура), или null. */
    private fun lineOf(lit: Int): Int? {
        if (lit == 0) return null
        val d = data ?: return null
        val pt = painter ?: return null
        val p = d.page(page, pt.markDigitsReversed) ?: return null
        for (l in p.lines) {
            val hit = when (l) {
                is MadaniLayout.Words -> l.tokens.any { it.code == lit }
                is MadaniLayout.Basmala -> lit == -l.surah
                else -> false
            }
            if (hit) return l.number - 1
        }
        return null
    }

    private fun lineCount(): Int {
        val d = data ?: return FULL_LINES
        val pt = painter ?: return FULL_LINES
        return d.page(page, pt.markDigitsReversed)?.lineCount ?: FULL_LINES
    }

    /** Окно листается только у полных страниц (15 строк); короткие (аль-Фатиха, начало аль-Бакары) видны целиком. */
    private fun clampTop(t: Int): Int {
        val n = lineCount()
        if (n < FULL_LINES) return 0
        return t.coerceIn(0, maxOf(0, n - VISIBLE_LINES))
    }

    private class Layout(val view: RectF, val bar: RectF, val buttons: List<RectF>)

    private fun layout(w: Int, h: Int): Layout {
        val barH = h * 0.13f
        val gap = w * 0.015f
        val view = RectF(gap, gap, w - gap, h - barH - gap)
        val bar = RectF(0f, h - barH, w.toFloat(), h.toFloat())
        val bw = (w - 6 * gap) / 5f
        // справа налево: السابقة, تشغيل/إيقاف, السرعة, التالية, خروج
        val buttons = (0 until 5).map { i ->
            val r = w - gap - i * (bw + gap)
            RectF(r - bw, bar.top + barH * 0.12f, r, bar.bottom - barH * 0.12f)
        }
        return Layout(view, bar, buttons)
    }

    /** Прямоугольник всей страницы так, чтобы в [view] попали строки top..top+VISIBLE_LINES. */
    private fun pageRect(view: RectF, lines: Int): RectF {
        if (lines < FULL_LINES) return RectF(view)
        val pitch = view.height() / VISIBLE_LINES
        val y = view.top - top * pitch
        return RectF(view.left, y, view.right, y + pitch * lines)
    }

    private fun render(c: Canvas, w: Int, h: Int, lit: Int, playing: Boolean) {
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
        val p = d.page(page, pt.markDigitsReversed)
        if (p != null) {
            c.save()
            c.clipRect(l.view)
            pt.draw(c, p, pageRect(l.view, p.lineCount), lit)
            c.restore()
        }
        val speed = Quran.speedLabel(com.yegorkapomidorka.headswap.prayer.QuranPrefs(ctx).speed)
        val play = ctx.getString(if (playing) R.string.mf_pause else R.string.mf_play)
        val names = listOf(ctx.getString(R.string.mf_prev), play, speed, ctx.getString(R.string.mf_next), ctx.getString(R.string.mf_exit))
        label.textSize = l.bar.height() * 0.32f
        for ((i, r) in l.buttons.withIndex()) {
            val enabled = when (i) {
                0 -> page > 1
                3 -> page < QuranPages.PAGES
                else -> true
            }
            btn.color = if (i == 1) GOLD_DIM else BTN
            c.drawRoundRect(r, r.height() * 0.25f, r.height() * 0.25f, btn)
            label.color = if (enabled) INK else DIM
            val fm = label.fontMetrics
            c.drawText(names[i], r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, label)
        }
        // номер страницы - в промежутке над кнопками, слева
        small.color = DIM
        small.textSize = h * 0.032f
        c.drawText(MushafText.arabicDigits(page), l.view.left + h * 0.04f, l.bar.top - h * 0.005f, small)
    }

    // ---------------- касания ----------------

    private var downX = 0
    private var downY = 0

    /** Касание ГУ (0 down, 1 up, 2 move) в координатах кадра (w, h). Нить моста. */
    @Synchronized
    fun onTouch(action: Int, x: Int, y: Int, w: Int, h: Int) {
        when (action) {
            0 -> {
                downX = x
                downY = y
            }
            1 -> {
                val dx = x - downX
                val dy = y - downY
                if (abs(dx) > w * 0.12f && abs(dx) > abs(dy)) {
                    turn(if (dx > 0) +1 else -1)        // палец вправо - следующая страница, как в мусхафе
                    return
                }
                if (abs(dy) > h * 0.12f && abs(dy) > abs(dx)) {
                    scroll(if (dy < 0) +VISIBLE_LINES - 2 else -(VISIBLE_LINES - 2))   // палец вверх - ниже по странице
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
                0 -> turn(-1)
                1 -> playPause()
                2 -> {
                    val ctx = app ?: return
                    val q = com.yegorkapomidorka.headswap.prayer.QuranPrefs(ctx)
                    q.speed = Quran.nextSpeed(q.speed)
                    if (PrayerPlayer.quranActive) PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_SPEED_SET)
                }
                3 -> turn(+1)
                4 -> onExit?.invoke()
            }
            return
        }
        val d = data ?: return
        val pt = painter ?: return
        if (!l.view.contains(x, y)) return
        val p = d.page(page, pt.markDigitsReversed) ?: return
        if (pt.ayahAt(p, pageRect(l.view, p.lineCount), x, y) != 0) listen(page)
    }

    /** Играет план - пауза; на паузе - дальше с того же места; не играет - слушать страницу на экране. */
    private fun playPause() {
        val ctx = app ?: return
        when {
            PrayerPlayer.quranActive && PrayerPlayer.hifzNowPage > 0 && !PrayerPlayer.quranPausedNow ->
                PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_PAUSE)
            PrayerPlayer.quranActive && PrayerPlayer.hifzNowPage > 0 ->
                PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_RESUME)
            else -> listen(page)
        }
    }

    private fun turn(d: Int) {
        val p = (page + d).coerceIn(1, QuranPages.PAGES)
        if (p == page) return
        page = p
        top = 0
        userTurnAt = System.currentTimeMillis()
        userScrollAt = 0L
    }

    private fun scroll(d: Int) {
        val t = clampTop(top + d)
        if (t == top) return
        top = t
        userScrollAt = System.currentTimeMillis()
    }

    private fun listen(p: Int) {
        val ctx = app ?: return
        HifzPrefs(ctx).page = p
        PrayerPlayer.hifz(ctx, p)
        userTurnAt = 0L
        userScrollAt = 0L
    }
}
