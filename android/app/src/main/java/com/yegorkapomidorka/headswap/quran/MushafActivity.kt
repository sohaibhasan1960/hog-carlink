package com.yegorkapomidorka.headswap.quran

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.Layout
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.prayer.HifzPrefs
import com.yegorkapomidorka.headswap.prayer.PrayerPlayer
import kotlin.math.abs

/**
 * HOG: страница мусхафа (604, Tanzil) шрифтом Amiri Quran. Во время плана заучивания следует за чтецом: открывает звучащую
 * страницу и подсвечивает аят. Листание - жест вбок (как в бумажном мусхафе: следующая страница слева) и кнопки.
 * Работает и на телефоне, и на экране машины в режиме рабочего стола (лаунчер показывает «المصحف»).
 */
class MushafActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var head: TextView
    private lateinit var body: TextView
    private lateinit var prev: Button
    private lateinit var next: Button
    private var texts: Map<Int, String>? = null
    private var page = 1
    private var built: MushafText.Page? = null
    private var lit: IntRange? = null
    private var userPage = false            // листал руками во время чтения - не тянуть назад к чтецу до смены аята
    private var seenAyah = -2

    private val follow = object : Runnable {
        override fun run() {
            val p = PrayerPlayer.hifzNowPage
            val a = PrayerPlayer.hifzNowAyah
            if (p > 0) {
                if (a != seenAyah) {
                    seenAyah = a
                    userPage = false
                }
                if (!userPage && p != page) show(p)
                highlight(a, PrayerPlayer.hifzNowNext)
            } else if (lit != null) {
                highlight(-1, null)
            }
            main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        page = intent.getIntExtra(EXTRA_PAGE, 0).takeIf { it in 1..QuranPages.PAGES }
            ?: PrayerPlayer.hifzNowPage.takeIf { it > 0 } ?: HifzPrefs(this).page
        val font = runCatching { resources.getFont(R.font.amiri_quran) }.getOrNull()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            fitsSystemWindows = true
        }
        head = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        body = TextView(this).apply {
            typeface = font ?: Typeface.SERIF
            setTextColor(INK)
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.TOP or Gravity.START
            justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
            setLineSpacing(0f, 1.25f)
            setPadding(dp(18), dp(4), dp(18), dp(4))
            setAutoSizeTextTypeUniformWithConfiguration(12, 44, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        // RTL: «التالية» справа налево - первой, как в мусхафе следующая страница слева
        prev = button(getString(R.string.hf_prev)) { turn(-1) }
        val play = button(getString(R.string.mf_listen)) {
            HifzPrefs(this).page = page
            PrayerPlayer.hifz(this, page)
            userPage = false
        }
        next = button(getString(R.string.hf_next)) { turn(+1) }
        bar.addView(prev, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(play, LinearLayout.LayoutParams(0, dp(52), 1.3f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(dx) < dp(60) || abs(vx) < abs(vy)) return false
                turn(if (dx > 0) +1 else -1)       // палец вправо - открывается следующая (левая) страница
                return true
            }
        })
        body.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev) }

        head.text = getString(R.string.qr_loading)
        Thread({
            val t = try {
                MushafText.load(resources)
            } catch (e: Exception) {
                AppLog.e("مصحف: текст не прочитан", e)
                emptyMap()
            }
            main.post {
                texts = t
                show(page)
            }
        }, "mushaf-text").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(EXTRA_PAGE, 0).takeIf { it in 1..QuranPages.PAGES }?.let { if (texts != null) show(it) else page = it }
    }

    override fun onResume() {
        super.onResume()
        main.post(follow)
    }

    override fun onPause() {
        main.removeCallbacks(follow)
        super.onPause()
    }

    private fun turn(d: Int) {
        val p = (page + d).coerceIn(1, QuranPages.PAGES)
        if (p == page) return
        if (PrayerPlayer.hifzNowPage > 0) userPage = true
        show(p)
    }

    private fun show(p: Int) {
        val t = texts ?: return
        page = p
        val b = MushafText.build(p, t)
        built = b
        lit = null
        val names = QuranPages.surahs(p).joinToString("، ") { Quran.name(it) }
        head.text = getString(R.string.mf_head, MushafText.arabicDigits(p), names, MushafText.arabicDigits(QuranPages.juzOf(p)))
        val sp = SpannableString(b.text)
        for (h in b.headers + b.basmalas.values) {
            sp.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), h.first, h.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (h in b.headers) {
            sp.setSpan(ForegroundColorSpan(GOLD), h.first, h.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.setText(sp, TextView.BufferType.SPANNABLE)
        prev.isEnabled = p > 1
        next.isEnabled = p < QuranPages.PAGES
        if (PrayerPlayer.hifzNowPage == p) highlight(PrayerPlayer.hifzNowAyah, PrayerPlayer.hifzNowNext)
    }

    private val mark = BackgroundColorSpan(MARK)

    private fun highlight(ayah: Int, nextAyah: Int?) {
        val b = built ?: return
        val r = if (PrayerPlayer.hifzNowPage == b.page) MushafText.highlight(b, ayah, nextAyah?.takeIf { it > 0 }) else null
        if (r == lit) return
        lit = r
        val sp = body.text as? Spannable ?: return
        sp.removeSpan(mark)
        if (r != null) sp.setSpan(mark, r.first, r.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAGE = "page"
        private val BG = Color.rgb(0x14, 0x17, 0x1C)
        private val INK = Color.rgb(0xF1, 0xE9, 0xD8)
        private val DIM = Color.rgb(0xA8, 0xA2, 0x96)
        private val GOLD = Color.rgb(0xD9, 0xB1, 0x5C)
        private val MARK = Color.argb(0x66, 0xD9, 0xB1, 0x5C)

        fun open(ctx: Context, page: Int = 0) {
            ctx.startActivity(Intent(ctx, MushafActivity::class.java).putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
}
