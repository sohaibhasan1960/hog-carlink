package com.yegorkapomidorka.headswap.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.prayer.HifzPrefs
import com.yegorkapomidorka.headswap.prayer.PrayerPlayer
import com.yegorkapomidorka.headswap.prayer.QuranPrefs
import com.yegorkapomidorka.headswap.quran.Quran
import com.yegorkapomidorka.headswap.quran.QuranPages

/**
 * HOG CarLink: القرآن الكريم. 114 сур: нажатие - слушать (поток выбранного чтеца, ничего не хранится), «قراءة» - текст
 * суры (Tanzil). Тот же звук и в машине: «الصلاة والأذكار» -> «القرآن الكريم» в Android Auto.
 */
class QuranScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val prefs = QuranPrefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val topBox: LinearLayout
    private val hifz = HifzPrefs(ctx)
    private val hifzBox: LinearLayout

    init {
        val c = ctx
        val body = c.vbox(10).apply { pad(c, 16, 4, 16, 24) }
        topBox = c.vbox(8)
        body.addView(topBox, lp(MATCH, WRAP))
        buildTop()

        // HOG: план заучивания по страницам мусхафа (604), с повтором страницы
        body.addView(c.sectionTitle(c.getString(R.string.hf_title)))
        hifzBox = c.vbox(8)
        body.addView(hifzBox, lp(MATCH, WRAP))
        buildHifz()

        body.addView(c.sectionTitle(c.getString(R.string.qr_surahs)))
        val list = c.card()
        for (n in 1..Quran.SURAHS) {
            if (n > 1) list.addView(c.divider())
            list.addView(surahRow(n))
        }
        body.addView(list, lp(MATCH, WRAP))
        body.addView(c.text(c.getString(R.string.qr_credits), 12f, 400, c.clr(R.color.on2)).apply {
            gravity = Gravity.CENTER
            pad(c, 0, 12, 0, 0)
            setOnClickListener { try { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tanzil.net"))) } catch (_: Exception) {} }
        }, lp(MATCH, WRAP))
        view = scaffold(c, c.getString(R.string.qr_title), { host.back() }, body)
    }

    override fun onShow() {
        buildTop()
        buildHifz()
    }

    private fun valueRow(title: String, value: String, onClick: () -> Unit): View {
        val c = ctx
        return c.hbox(12).apply {
            minimumHeight = c.dp(56)
            pad(c, 16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 16), 16)
            isClickable = true
            setOnClickListener { onClick() }
            addView(c.text(title, 16f, 500), lp(0, WRAP, 1f))
            addView(c.text(value, 15f, 600, c.clr(R.color.acc)))
        }
    }

    private fun voiceName(v: QuranPages.Voice) = if (ctx.resources.configuration.locales[0].language == "ar") v.nameAr else v.nameEn

    private fun repeatLabel(r: Int) = if (r == 0) ctx.getString(R.string.hf_repeat_inf) else ctx.getString(R.string.hf_times, r)

    private fun pageNames(page: Int) = QuranPages.surahs(page).joinToString("، ") { Quran.name(it) }

    private fun buildHifz() {
        val c = ctx
        hifzBox.removeAllViews()
        val page = hifz.page
        hifzBox.addView(valueRow(c.getString(R.string.hf_page), "$page") { pickPage() }, lp(MATCH, WRAP))
        hifzBox.addView(c.text(c.getString(R.string.hf_page_sum, page, pageNames(page), QuranPages.juzOf(page)), 14f, 500, c.clr(R.color.on2)).apply {
            pad(c, 16, 0, 16, 0)
        }, lp(MATCH, WRAP))
        hifzBox.addView(valueRow(c.getString(R.string.hf_repeat), repeatLabel(hifz.repeat)) { pickRepeat() }, lp(MATCH, WRAP))
        hifzBox.addView(valueRow(c.getString(R.string.hf_pages), "${hifz.pagesPerSession}") { pickPages() }, lp(MATCH, WRAP))
        hifzBox.addView(valueRow(c.getString(R.string.hf_voice), voiceName(hifz.voice)) { pickVoice() }, lp(MATCH, WRAP))
        hifzBox.addView(c.primaryButton(c.getString(R.string.hf_play, page), heightDp = 56, sp = 17f).apply {
            setOnClickListener { PrayerPlayer.hifz(c, hifz.page) }
        }, lp(MATCH, WRAP))
        hifzBox.addView(c.hbox(8).apply {
            addView(c.tonalButton(c.getString(R.string.hf_prev)).apply {
                isEnabled = page > 1
                setOnClickListener { hifz.page = page - 1; buildHifz() }
            }, lp(0, c.dp(48), 1f))
            addView(c.tonalButton(c.getString(R.string.qr_read)).apply {
                setOnClickListener { readPage(hifz.page) }
            }, lp(0, c.dp(48), 1f))
            addView(c.tonalButton(c.getString(R.string.hf_next)).apply {
                isEnabled = page < QuranPages.PAGES
                setOnClickListener { hifz.page = page + 1; buildHifz() }
            }, lp(0, c.dp(48), 1f))
        }, lp(MATCH, WRAP))
        if (hifz.done > 0) hifzBox.addView(c.text(c.getString(R.string.hf_progress, hifz.done), 14f, 500, c.clr(R.color.on2)).apply {
            pad(c, 16, 0, 16, 0)
        }, lp(MATCH, WRAP))
        hifzBox.addView(c.text(c.getString(R.string.hf_note), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(19) })
    }

    private fun pickPage() {
        // контекст диалога: цифры колеса в цветах тёмного диалога
        val builder = AlertDialog.Builder(host)
        val np = android.widget.NumberPicker(builder.context).apply {
            minValue = 1
            maxValue = QuranPages.PAGES
            value = hifz.page
            wrapSelectorWheel = false
        }
        builder.setTitle(R.string.hf_page).setView(np)
            .setPositiveButton(android.R.string.ok) { _, _ -> hifz.page = np.value; buildHifz() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun pickRepeat() {
        val all = QuranPages.REPEATS
        AlertDialog.Builder(host).setTitle(R.string.hf_repeat)
            .setSingleChoiceItems(all.map { repeatLabel(it) }.toTypedArray(), all.indexOf(hifz.repeat)) { d, i ->
                hifz.repeat = all[i]
                d.dismiss()
                buildHifz()
            }.show()
    }

    private fun pickPages() {
        val all = (1..HifzPrefs.MAX_PAGES).toList()
        AlertDialog.Builder(host).setTitle(R.string.hf_pages)
            .setSingleChoiceItems(all.map { "$it" }.toTypedArray(), all.indexOf(hifz.pagesPerSession)) { d, i ->
                hifz.pagesPerSession = all[i]
                d.dismiss()
                buildHifz()
            }.show()
    }

    private fun pickVoice() {
        val all = QuranPages.Voice.values()
        AlertDialog.Builder(host).setTitle(R.string.hf_voice)
            .setSingleChoiceItems(all.map { voiceName(it) }.toTypedArray(), all.indexOf(hifz.voice)) { d, i ->
                hifz.voice = all[i]
                d.dismiss()
                buildHifz()
            }.show()
    }

    /** Текст страницы мусхафа (аяты Tanzil этой страницы) в окне с прокруткой. */
    private fun readPage(page: Int) {
        val c = ctx
        val codes = QuranPages.ayahs(page).toHashSet()
        Thread({
            val lines = try {
                c.resources.openRawResource(R.raw.quran_uthmani).bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.mapNotNull { line ->
                        val parts = line.split('|', limit = 3)
                        val s = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                        val a = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                        if (s * 1000 + a in codes) parts[2] + " ﴿" + arabicDigits(a) + "﴾" else null
                    }.toList()
                }
            } catch (e: Exception) {
                AppLog.e("حفظ: текст страницы не прочитан", e)
                emptyList()
            }
            main.post {
                val builder = AlertDialog.Builder(host)
                val tv = TextView(c).apply {
                    text = lines.joinToString(" ")
                    textSize = 22f
                    setLineSpacing(0f, 1.6f)
                    setTextColor(c.dialogTextColor(builder.context))
                    textDirection = View.TEXT_DIRECTION_RTL
                    setTextIsSelectable(true)
                    pad(c, 20, 12, 20, 12)
                }
                builder.setTitle(c.getString(R.string.hf_page_title, page, pageNames(page)))
                    .setView(ScrollView(c).apply { addView(tv) })
                    .setPositiveButton(c.getString(R.string.hf_play, page)) { _, _ -> hifz.page = page; PrayerPlayer.hifz(c, page) }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }, "hifz-text").start()
    }

    private fun buildTop() {
        val c = ctx
        topBox.removeAllViews()
        val r = prefs.reciter
        topBox.addView(c.hbox(12).apply {
            minimumHeight = c.dp(56)
            pad(c, 16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 16), 16)
            isClickable = true
            setOnClickListener { pickReciter() }
            addView(c.text(c.getString(R.string.qr_reciter), 16f, 500), lp(0, WRAP, 1f))
            addView(c.text(reciterName(r), 15f, 600, c.clr(R.color.acc)))
        }, lp(MATCH, WRAP))
        topBox.addView(ToggleRow(c, c.getString(R.string.qr_continuous), c.getString(R.string.qr_continuous_sub), prefs.continuous) {
            prefs.continuous = it
        }.view, lp(MATCH, WRAP))
        // скорость ×1 / ×1.5 / ×2: та же настройка, что и кнопка на экране машины
        topBox.addView(c.hbox(12).apply {
            minimumHeight = c.dp(56)
            pad(c, 16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 16), 16)
            isClickable = true
            setOnClickListener { pickSpeed() }
            addView(c.text(c.getString(R.string.qr_speed), 16f, 500), lp(0, WRAP, 1f))
            addView(c.text(Quran.speedLabel(prefs.speed), 15f, 600, c.clr(R.color.acc)))
        }, lp(MATCH, WRAP))
        val last = prefs.lastSurah
        if (last in 1..Quran.SURAHS) {
            val pos = prefs.lastPosMs / 1000
            topBox.addView(c.primaryButton(c.getString(R.string.qr_continue, Quran.name(last)) + if (pos > 0) "  %d:%02d".format(pos / 60, pos % 60) else "",
                heightDp = 56, sp = 17f).apply { setOnClickListener { PrayerPlayer.quran(c, last, prefs.lastPosMs) } }, lp(MATCH, WRAP))
        }
        topBox.addView(c.tonalButton(c.getString(R.string.pr_stop)).apply {
            setOnClickListener { c.startService(Intent(c, PrayerPlayer::class.java).setAction(PrayerPlayer.ACTION_STOP)) }
        }, lp(MATCH, c.dp(48)))
        // «Тадарус» (com.tadarose_quran): мусхаф с тафсирами - открыть; нет - страница в магазине
        val tadarus = c.packageManager.getLaunchIntentForPackage(TADARUS)
        topBox.addView(c.containerButton(c.getString(if (tadarus != null) R.string.qr_tadarus_open else R.string.qr_tadarus_get)).apply {
            setOnClickListener {
                try {
                    host.startActivity(tadarus ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TADARUS")))
                } catch (_: Exception) {
                    try {
                        host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TADARUS")))
                    } catch (_: Exception) {
                    }
                }
            }
        }, lp(MATCH, WRAP))
        topBox.addView(c.text(c.getString(R.string.qr_data_note), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(19) })
    }

    private fun reciterName(r: Quran.Reciter) = if (ctx.resources.configuration.locales[0].language == "ar") r.nameAr else r.nameEn

    private fun pickReciter() {
        val all = Quran.Reciter.values()
        AlertDialog.Builder(host).setTitle(R.string.qr_reciter)
            .setSingleChoiceItems(all.map { reciterName(it) }.toTypedArray(), all.indexOf(prefs.reciter)) { d, i ->
                prefs.reciter = all[i]
                d.dismiss()
                buildTop()
            }.show()
    }

    private fun pickSpeed() {
        val all = Quran.SPEEDS
        AlertDialog.Builder(host).setTitle(R.string.qr_speed)
            .setSingleChoiceItems(all.map { Quran.speedLabel(it) }.toTypedArray(), all.indexOfFirst { it == prefs.speed }) { d, i ->
                prefs.speed = all[i]
                // играет сура - применить сразу
                if (PrayerPlayer.quranActive) PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_SPEED_SET)
                d.dismiss()
                buildTop()
            }.show()
    }

    private fun surahRow(n: Int): View {
        val c = ctx
        return c.hbox(12).apply {
            minimumHeight = c.dp(56)
            pad(c, 16, 6, 8, 6)
            gravity = Gravity.CENTER_VERTICAL
            background = c.rippleBg(null, 0)
            isClickable = true
            contentDescription = Quran.name(n)
            setOnClickListener { PrayerPlayer.quran(c, n) }
            addView(c.text("$n", 14f, 600, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, lp(c.dp(32), WRAP))
            addView(c.vbox(0).apply {
                addView(c.text(Quran.name(n), 17f, 600))
                addView(c.text(c.getString(if (Quran.makki(n)) R.string.qr_makki else R.string.qr_madani), 12f, 400, c.clr(R.color.on2)))
            }, lp(0, WRAP, 1f))
            addView(c.linkButton(c.getString(R.string.qr_read), heightDp = 44, sp = 15f).apply {
                setOnClickListener { read(n) }
            }, LinearLayout.LayoutParams(WRAP, c.dp(44)))
        }
    }

    /** Текст суры: чтение файла на своей нити, показ в окне с прокруткой. */
    private fun read(n: Int) {
        val c = ctx
        Thread({
            var fatiha1 = ""
            val ayat = try {
                c.resources.openRawResource(R.raw.quran_uthmani).bufferedReader(Charsets.UTF_8).useLines { seq ->
                    // одним проходом: 1:1 (образец басмалы) и аяты суры
                    val wanted = "$n|"
                    val lines = seq.filter { it.startsWith("1|1|") || it.startsWith(wanted) }.toList()
                    fatiha1 = lines.firstOrNull { it.startsWith("1|1|") }?.substringAfter("1|1|") ?: ""
                    Quran.ayat(lines.asSequence(), n)
                }
            } catch (e: Exception) {
                AppLog.e("Коран: текст не прочитан", e)
                emptyList()
            }
            val (basmala, rest) = Quran.splitBasmala(n, ayat, fatiha1)
            val body = rest.withIndex().joinToString(" ") { (i, a) -> "$a ﴿${arabicDigits(i + 1)}﴾" }
            val text = if (basmala != null) "$basmala\n\n$body" else body
            main.post {
                val builder = AlertDialog.Builder(host)
                val tv = TextView(c).apply {
                    setText(text)
                    textSize = 22f
                    setLineSpacing(0f, 1.6f)
                    setTextColor(c.dialogTextColor(builder.context))
                    textDirection = View.TEXT_DIRECTION_RTL
                    setTextIsSelectable(true)
                    pad(c, 20, 12, 20, 12)
                }
                builder.setTitle(c.getString(R.string.qr_surah_title, Quran.name(n)))
                    .setView(ScrollView(c).apply { addView(tv) })
                    .setPositiveButton(R.string.qr_listen) { _, _ -> PrayerPlayer.quran(c, n) }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }, "quran-text").start()
    }

    private companion object {
        const val TADARUS = "com.tadarose_quran"
    }

    private fun arabicDigits(n: Int): String = n.toString().map { '٠' + (it - '0') }.joinToString("")
}
