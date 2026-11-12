package com.yegorkapomidorka.headswap.quran

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/**
 * HOG: рисует страницу мусхафа Медины в прямоугольник любого Canvas: строки как в печатном (MadaniLayout), слова справа
 * налево, растянутые строки - по ширине, остальные - по центру. Заставка суры - рамка с названием, басмала - по центру.
 * [lit] - код звучащего аята (surah*1000+ayah) или басмалы суры (-surah). Один painter - на телефоне ([MushafPageView])
 * и на экране машины (CarMushaf, кадры энкодера CarLife). Не потокобезопасен: одна нить на экземпляр.
 */
class MushafPainter(font: Typeface) {
    var ink = 0xFFF1E9D8.toInt()
    var gold = 0xFFD9B15C.toInt()
    var mark = 0x66D9B15C

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    /** Цифры номера аята шрифт KFGQPC собирает в одну розетку только в одном порядке; проверяем, в каком. */
    val markDigitsReversed: Boolean = run {
        val p = Paint().apply { typeface = font; textSize = 100f }
        val probe = MushafText.arabicDigits(253)
        p.measureText(probe.reversed()) < p.measureText(probe)
    }

    private class Geometry(val r: RectF, val size: Float, val pitch: Float, val top: Float, val pad: Float, val width: Float)

    private fun geometry(p: MadaniLayout.Page, r: RectF): Geometry {
        val pad = r.width() * 0.035f
        val w = r.width() - 2 * pad
        // размер шрифта: самая длинная растянутая строка влезает с минимальными пробелами
        paint.textSize = 100f
        val space = paint.measureText(" ")
        var need = 0f
        for (l in p.lines) if (l is MadaniLayout.Words) {
            val natural = l.tokens.sumOf { paint.measureText(it.text).toDouble() }.toFloat() + space * 0.6f * (l.tokens.size - 1)
            need = maxOf(need, natural)
        }
        val byWidth = if (need > 0) 100f * w / need else 40f
        val pitchAll = r.height() / p.lineCount
        val size = min(byWidth, pitchAll * 0.62f)
        val pitch = if (p.lineCount == 15) pitchAll else min(pitchAll, size * 2.1f)
        val top = r.top + (r.height() - pitch * p.lineCount) / 2f
        return Geometry(r, size, pitch, top, r.left + pad, w)
    }

    /** Слова строки с левым и правым краем (x), справа налево. */
    private fun placed(l: MadaniLayout.Words, g: Geometry): List<Triple<MadaniLayout.Token, Float, Float>> {
        val widths = l.tokens.map { paint.measureText(it.text) }
        val total = widths.sum()
        val n = l.tokens.size
        val gap = if (!l.centred && n > 1) (g.width - total) / (n - 1) else paint.measureText(" ") * 0.6f
        var x = if (l.centred || n == 1) g.pad + (g.width + total + gap * (n - 1)) / 2f else g.pad + g.width
        val out = ArrayList<Triple<MadaniLayout.Token, Float, Float>>(n)
        for (i in 0 until n) {
            val right = x
            val left = right - widths[i]
            out += Triple(l.tokens[i], left, right)
            x = left - gap
        }
        return out
    }

    /** Код аята под точкой (x, y) страницы, нарисованной в [r], или 0. */
    fun ayahAt(p: MadaniLayout.Page, r: RectF, x: Float, y: Float): Int {
        if (!r.contains(x, y)) return 0
        val g = geometry(p, r)
        val line = ((y - g.top) / g.pitch).toInt() + 1
        val words = p.lines.firstOrNull { it.number == line } as? MadaniLayout.Words ?: return 0
        paint.textSize = g.size
        for (t in placed(words, g)) if (x >= t.second && x <= t.third) return t.first.code
        return 0
    }

    fun draw(canvas: Canvas, p: MadaniLayout.Page, r: RectF, lit: Int) {
        if (r.width() <= 0 || r.height() <= 0) return
        val g = geometry(p, r)
        paint.textSize = g.size
        val fm = paint.fontMetrics
        for (l in p.lines) {
            val bandTop = g.top + g.pitch * (l.number - 1)
            val base = bandTop + (g.pitch - (fm.descent - fm.ascent)) / 2f - fm.ascent
            when (l) {
                is MadaniLayout.Banner -> {
                    box.set(g.pad, bandTop + g.pitch * 0.12f, g.pad + g.width, bandTop + g.pitch * 0.88f)
                    frame.color = gold
                    frame.strokeWidth = g.size * 0.05f
                    canvas.drawRoundRect(box, g.pitch * 0.2f, g.pitch * 0.2f, frame)
                    paint.color = gold
                    paint.textSize = g.size * 0.9f
                    val t = "سُورَةُ " + Quran.name(l.surah)
                    canvas.drawText(t, g.pad + (g.width - paint.measureText(t)) / 2f, base, paint)
                    paint.textSize = g.size
                }
                is MadaniLayout.Basmala -> {
                    val w = paint.measureText(BASMALA)
                    val x = g.pad + (g.width - w) / 2f
                    if (lit == -l.surah) highlight(canvas, x, x + w, bandTop, g)
                    paint.color = ink
                    canvas.drawText(BASMALA, x, base, paint)
                }
                is MadaniLayout.Words -> for ((t, left, right) in placed(l, g)) {
                    if (lit != 0 && t.code == lit) highlight(canvas, left, right, bandTop, g)
                    paint.color = if (t.isMark) gold else ink
                    canvas.drawText(t.text, left, base, paint)
                }
            }
        }
    }

    private fun highlight(canvas: Canvas, left: Float, right: Float, bandTop: Float, g: Geometry) {
        fill.color = mark
        val ext = g.size * 0.15f
        box.set(left - ext, bandTop + g.pitch * 0.08f, right + ext, bandTop + g.pitch * 0.92f)
        canvas.drawRect(box, fill)
    }

    companion object {
        /** Басмала в написании текста Хафс (кодировка Комплекса короля Фахда). */
        const val BASMALA = "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ"
    }
}

/** Страница мусхафа на экране телефона. */
class MushafPageView(context: Context, font: Typeface) : View(context) {
    private val painter = MushafPainter(font)
    private val rect = RectF()

    val markDigitsReversed get() = painter.markDigitsReversed

    var page: MadaniLayout.Page? = null
        set(v) {
            field = v
            invalidate()
        }

    var lit: Int = 0
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    fun ayahAt(x: Float, y: Float): Int {
        val p = page ?: return 0
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        return painter.ayahAt(p, rect, x, y)
    }

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        painter.draw(canvas, p, rect, lit)
    }
}
