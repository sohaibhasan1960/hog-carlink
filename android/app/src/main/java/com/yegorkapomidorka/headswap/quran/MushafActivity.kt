package com.yegorkapomidorka.headswap.quran

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * HOG: страница мусхафа Медины строка в строку (MadaniLayout, шрифт KFGQPC Uthmanic Hafs). Во время плана заучивания
 * следует за чтецом: открывает звучащую страницу и подсвечивает аят (или басмалу). Листание - жест вбок (следующая
 * страница слева, как в бумажном мусхафе) и кнопки; касание аята - слушать с этой страницы.
 * Работает и на телефоне, и на экране машины в режиме рабочего стола (лаунчер показывает «المصحف»).
 */
class MushafActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var head: TextView
    private lateinit var juzHead: TextView
    private lateinit var pageNo: TextView
    private lateinit var play: Button
    private lateinit var repChip: Button
    private lateinit var pagesChip: Button
    private lateinit var ayahChip: Button
    private lateinit var view: MushafPageView
    private lateinit var prev: Button
    private lateinit var next: Button
    private var data: MadaniData.Data? = null
    private var page = 1
    private var userPage = false            // листал руками во время чтения - не тянуть назад к чтецу до смены аята
    private var seenAyah = -2

    private val follow = object : Runnable {
        override fun run() {
            val p = PrayerPlayer.hifzNowPage
            val a = PrayerPlayer.hifzNowAyah
            if (p > 0) {
                // листнул сам - страница остаётся, пока не нажмёт «استمع» (следующий аят больше не уводит назад)
                if (a != seenAyah) seenAyah = a
                if (!userPage && p != page) show(p)
            }
            view.lit = if (p == page) litCode(a, PrayerPlayer.hifzNowNext) else 0
            updatePlay()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        page = intent.getIntExtra(EXTRA_PAGE, 0).takeIf { it in 1..QuranPages.PAGES }
            ?: PrayerPlayer.hifzNowPage.takeIf { it > 0 } ?: HifzPrefs(this).page
        val font = runCatching { resources.getFont(R.font.uthmanic_hafs) }.getOrNull() ?: Typeface.SERIF
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            fitsSystemWindows = true
        }
        // HOG: ترويسة مصحف المدينة: اسم السورة في جهة البداية (يمين)، والجزء في الجهة الأخرى؛ رقم الصفحة أسفلها
        head = TextView(this).apply {
            setTextColor(GOLD)
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        juzHead = TextView(this).apply {
            setTextColor(GOLD)
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(2))
            addView(head, LinearLayout.LayoutParams(0, -2, 1f))
            addView(juzHead, LinearLayout.LayoutParams(-2, -2))
        }
        pageNo = TextView(this).apply {
            setTextColor(GOLD)
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        }
        // HOG: الحفظ من المصحف نفسه: التكرار وعدد الصفحات وتكرار الآية، ثم «استمع» من هذه الصفحة
        repChip = chip { pickRepeat() }
        pagesChip = chip { pickPages() }
        ayahChip = chip { pickAyahRepeat() }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), 0)
            addView(repChip, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(pagesChip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
            addView(ayahChip, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        refreshChips()
        view = MushafPageView(this, font)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        // RTL: первая кнопка справа - «السابقة», последняя слева - «التالية», как страницы мусхафа
        prev = button(getString(R.string.mf_prev)) { turn(-1) }
        play = button(getString(R.string.mf_listen_short)) { onPlay() }
        next = button(getString(R.string.mf_next)) { turn(+1) }
        val speed = button(Quran.speedLabel(com.yegorkapomidorka.headswap.prayer.QuranPrefs(this).speed)) {}
        speed.setOnClickListener {
            val q = com.yegorkapomidorka.headswap.prayer.QuranPrefs(this)
            q.speed = Quran.nextSpeed(q.speed)
            if (PrayerPlayer.quranActive) PrayerPlayer.control(this, PrayerPlayer.ACTION_Q_SPEED_SET)
            speed.text = Quran.speedLabel(q.speed)
        }
        bar.addView(prev, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(play, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(speed, LinearLayout.LayoutParams(0, dp(52), 0.8f).apply { marginEnd = dp(8) })
        bar.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(headRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pageNo, LinearLayout.LayoutParams(-1, -2))
        root.addView(chips, LinearLayout.LayoutParams(-1, -2))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(dx) < dp(60) || abs(vx) < abs(vy)) return false
                turn(if (dx > 0) +1 else -1)       // палец вправо - открывается следующая (левая) страница
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (view.ayahAt(e.x, e.y) == 0) return false
                listen()
                return true
            }
        })
        view.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        head.text = getString(R.string.qr_loading)
        Thread({
            val d = try {
                MadaniData.load(this)
            } catch (e: Exception) {
                AppLog.e("مصحف: данные мусхафа не прочитаны", e)
                null
            }
            main.post {
                data = d
                show(page)
            }
        }, "mushaf-data").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(EXTRA_PAGE, 0).takeIf { it in 1..QuranPages.PAGES }?.let { if (data != null) show(it) else page = it }
    }

    override fun onResume() {
        super.onResume()
        main.post(follow)
    }

    override fun onPause() {
        main.removeCallbacks(follow)
        super.onPause()
    }

    private fun listen() {
        HifzPrefs(this).page = page
        PrayerPlayer.hifz(this, page)
        userPage = false
    }

    /** Звучит план заучивания (а не сура целиком). */
    private fun hifzOn(): Boolean = PrayerPlayer.now.active && PrayerPlayer.hifzNowPage > 0

    /** Играет план - пауза/продолжить; иначе слушать с открытой страницы. */
    private fun onPlay() {
        val n = PrayerPlayer.now
        when {
            hifzOn() && !userPage && !n.paused -> PrayerPlayer.control(this, PrayerPlayer.ACTION_Q_PAUSE)
            hifzOn() && !userPage && n.paused -> PrayerPlayer.control(this, PrayerPlayer.ACTION_Q_RESUME)
            else -> listen()
        }
        main.postDelayed({ updatePlay() }, 300)
    }

    private fun updatePlay() {
        if (!::play.isInitialized) return
        val label = when {
            !hifzOn() || userPage -> getString(R.string.mf_listen_short)
            PrayerPlayer.now.paused -> getString(R.string.mf_resume)
            else -> getString(R.string.mf_pause)
        }
        if (play.text != label) play.text = label
    }

    private fun repeatLabel(r: Int) = if (r == 0) getString(R.string.hf_repeat_inf) else getString(R.string.hf_times, r)
    private fun ayahLabel(r: Int) = if (r <= 1) getString(R.string.hf_ayah_once) else getString(R.string.hf_times, r)

    private fun refreshChips() {
        val h = HifzPrefs(this)
        repChip.text = getString(R.string.mf_chip_repeat, repeatLabel(h.repeat))
        pagesChip.text = getString(R.string.mf_chip_pages, MushafText.arabicDigits(h.pagesPerSession))
        ayahChip.text = getString(R.string.mf_chip_ayah, ayahLabel(h.ayahRepeat))
    }

    /** Выбор из списка; изменение плана действует со следующего «استمع». */
    private fun choose(title: Int, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i ->
                onPick(i)
                d.dismiss()
                refreshChips()
            }.show()
    }

    private fun pickRepeat() {
        val all = QuranPages.REPEATS
        choose(R.string.hf_repeat, all.map { repeatLabel(it) }, all.indexOf(HifzPrefs(this).repeat)) { HifzPrefs(this).repeat = all[it] }
    }

    private fun pickPages() {
        val all = (1..HifzPrefs.MAX_PAGES).toList()
        choose(R.string.hf_pages, all.map { MushafText.arabicDigits(it) }, all.indexOf(HifzPrefs(this).pagesPerSession)) {
            HifzPrefs(this).pagesPerSession = all[it]
        }
    }

    private fun pickAyahRepeat() {
        val all = QuranPages.AYAH_REPEATS
        choose(R.string.hf_ayah_repeat, all.map { ayahLabel(it) }, all.indexOf(HifzPrefs(this).ayahRepeat)) { HifzPrefs(this).ayahRepeat = all[it] }
    }

    private fun chip(onClick: () -> Unit) = Button(this).apply {
        isAllCaps = false
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setOnClickListener { onClick() }
    }

    private fun turn(d: Int) {
        val p = (page + d).coerceIn(1, QuranPages.PAGES)
        if (p == page) return
        if (PrayerPlayer.hifzNowPage > 0) userPage = true
        show(p)
    }

    private fun show(p: Int) {
        page = p
        val d = data ?: return
        val built = d.page(p, view.markDigitsReversed)
        if (built == null) AppLog.i("مصحف: страница $p не сложилась по раскладке")
        view.page = built
        view.lit = if (PrayerPlayer.hifzNowPage == p) litCode(PrayerPlayer.hifzNowAyah, PrayerPlayer.hifzNowNext) else 0
        // как в мусхафе Медины: сура, с которой начинается страница, и джуз; номер страницы - под текстом
        val top = QuranPages.surahs(p).firstOrNull()
        head.text = top?.let { getString(R.string.mf_head_surah, Quran.name(it)) } ?: ""
        juzHead.text = getString(R.string.mf_head_juz, MushafText.arabicDigits(QuranPages.juzOf(p)))
        pageNo.text = MushafText.arabicDigits(p)
        updatePlay()
        prev.isEnabled = p > 1
        next.isEnabled = p < QuranPages.PAGES
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAGE = "page"
        private val BG = Color.rgb(0x14, 0x17, 0x1C)
        private val GOLD = Color.rgb(0xC9, 0xA8, 0x5C)

        /** Звучащий элемент плана -> что подсветить: аят (код) или басмала суры следующего аята (-сура). */
        fun litCode(ayah: Int, nextAyah: Int): Int = when {
            ayah > 0 -> ayah
            ayah == 0 && nextAyah > 0 -> -(nextAyah / 1000)
            else -> 0
        }

        fun open(ctx: Context, page: Int = 0) {
            ctx.startActivity(Intent(ctx, MushafActivity::class.java).putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
}

/** Раскладка и текст мусхафа Медины из assets/mushaf, один раз на процесс. */
object MadaniData {
    class Data(private val layout: Map<Int, MadaniLayout.PageLines>, private val byPage: Map<Int, List<MadaniLayout.Ayah>>) {
        fun page(p: Int, markDigitsReversed: Boolean): MadaniLayout.Page? {
            val l = layout[p] ?: return null
            return MadaniLayout.page(p, l, byPage[p].orEmpty(), markDigitsReversed)
        }
    }

    @Volatile private var cache: Data? = null

    fun load(ctx: Context): Data {
        cache?.let { return it }
        val layout = ctx.assets.open("mushaf/layout.txt").reader(Charsets.UTF_8).use { MadaniLayout.parseLayout(it) }
        val ayahs = ctx.assets.open("mushaf/hafs.tsv").reader(Charsets.UTF_8).use { MadaniLayout.parseText(it) }
        return Data(layout, ayahs.groupBy { it.page }).also { cache = it }
    }
}
