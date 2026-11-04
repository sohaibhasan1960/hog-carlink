package com.yegorkapomidorka.headswap.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.prayer.Adhkar
import com.yegorkapomidorka.headswap.prayer.CalcMethod
import com.yegorkapomidorka.headswap.prayer.Madhab
import com.yegorkapomidorka.headswap.prayer.Prayer
import com.yegorkapomidorka.headswap.prayer.PrayerAlarms
import com.yegorkapomidorka.headswap.prayer.PrayerLocation
import com.yegorkapomidorka.headswap.prayer.PrayerPlan
import com.yegorkapomidorka.headswap.prayer.PrayerPlayer
import com.yegorkapomidorka.headswap.prayer.PrayerPrefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HOG CarLink: «Намаз и азкары». Время на сегодня по месту телефона, азан и икама по местному времени,
 * азкары утра и вечера (синтез речи) и напоминания зикра. Звук - в машине, вне машины - уведомления.
 */
class PrayerScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val prefs = PrayerPrefs(ctx)
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val timesBox: LinearLayout
    private val nextText: TextView
    private val placeText: TextView
    private val voiceHint: View
    private val settingsBox: LinearLayout
    private var awaitingLocation = false
    private var lastMinute = -1L

    init {
        val c = ctx
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 24) }

        // --- сегодня ---
        val today = c.card().apply { pad(c, 16, 16, 16, 16) }
        val tbox = c.vbox(6)
        placeText = c.text("", 14f, 400, c.clr(R.color.on2))
        nextText = c.text("", 18f, 600, c.clr(R.color.acc))
        timesBox = c.vbox(2)
        tbox.addView(placeText)
        tbox.addView(nextText)
        tbox.addView(timesBox, lp(MATCH, WRAP))
        tbox.addView(c.tonalButton(c.getString(R.string.pr_locate)).apply { setOnClickListener { locate() } }, lp(MATCH, c.dp(48)))
        tbox.addView(c.linkButton(c.getString(R.string.pr_city)).apply { setOnClickListener { askCity() } }, lp(MATCH, c.dp(44)))
        today.addView(tbox, lp(MATCH, WRAP))
        body.addView(today, lp(MATCH, WRAP))

        body.addView(ToggleRow(c, c.getString(R.string.pr_enable), c.getString(R.string.pr_enable_sub), prefs.enabled) {
            prefs.enabled = it
            if (it && !prefs.hasLocation) locate()
            PrayerAlarms.reschedule(c)
            refresh()
        }.view, lp(MATCH, WRAP))

        // --- слушать сейчас ---
        body.addView(c.sectionTitle(c.getString(R.string.pr_listen_now)))
        val listen = c.vbox(8)
        listen.addView(c.containerButton(c.getString(R.string.pr_morning)).apply { setOnClickListener { PrayerPlayer.start(c, PrayerPlayer.What.MORNING) } })
        listen.addView(c.containerButton(c.getString(R.string.pr_evening)).apply { setOnClickListener { PrayerPlayer.start(c, PrayerPlayer.What.EVENING) } })
        val row = c.hbox(8)
        row.addView(c.tonalButton(c.getString(R.string.pr_test_adhan)).apply { setOnClickListener { PrayerPlayer.start(c, PrayerPlayer.What.TEST_ADHAN, Prayer.DHUHR) } }, lp(0, c.dp(52), 1f))
        row.addView(c.tonalButton(c.getString(R.string.pr_stop)).apply {
            setOnClickListener { c.startService(Intent(c, PrayerPlayer::class.java).setAction(PrayerPlayer.ACTION_STOP)) }
        }, lp(0, c.dp(52), 1f))
        listen.addView(row, lp(MATCH, WRAP))
        listen.addView(c.linkButton(c.getString(R.string.pr_read_text)).apply { setOnClickListener { showText() } })
        voiceHint = c.vbox(6).apply {
            addView(c.text(c.getString(R.string.pr_no_voice), 14f, 400, c.clr(R.color.warn)))
            addView(c.tonalButton(c.getString(R.string.pr_install_voice)).apply { setOnClickListener { installVoice() } })
            visibility = View.GONE
        }
        listen.addView(voiceHint, lp(MATCH, WRAP))
        body.addView(listen, lp(MATCH, WRAP))

        settingsBox = c.vbox(8)
        body.addView(settingsBox, lp(MATCH, WRAP))
        buildSettings()

        body.addView(c.text(c.getString(R.string.pr_credits), 12f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER; pad(c, 0, 16, 0, 0) }, lp(MATCH, WRAP))
        view = scaffold(c, c.getString(R.string.pr_title), { host.back() }, body)
    }

    override fun onShow() {
        refresh()
        // место сменилось (поездка) - обновить тихо, если разрешение уже есть
        if (prefs.enabled && PrayerLocation.granted(ctx)) PrayerLocation.refresh(ctx) { if (it) refresh() }
    }

    override fun tick() {
        val m = System.currentTimeMillis() / 60_000
        if (m != lastMinute) refresh()
        voiceHint.visibility = if (PrayerPlayer.voiceMissing) View.VISIBLE else View.GONE
    }

    override fun onPermissionResult() {
        if (!awaitingLocation) return
        awaitingLocation = false
        if (PrayerLocation.granted(ctx)) locate()
    }

    private fun refresh() {
        lastMinute = System.currentTimeMillis() / 60_000
        val c = ctx
        timesBox.removeAllViews()
        if (!prefs.hasLocation) {
            placeText.text = c.getString(R.string.pr_no_place)
            nextText.text = ""
            return
        }
        val zone = ZoneId.systemDefault()
        val day = prefs.day(LocalDate.now(zone), zone) ?: return
        val tomorrow = prefs.day(LocalDate.now(zone).plusDays(1), zone) ?: return
        val method = methodName(prefs.method) + (if (prefs.methodOverride == null) " · " + c.getString(R.string.pr_auto) else "")
        placeText.text = listOf(prefs.place.ifBlank { "%.2f, %.2f".format(prefs.lat, prefs.lon) }, method).joinToString(" · ")
        val now = System.currentTimeMillis()
        val next = PrayerPlan.FARD.map { it to day[it] }.firstOrNull { it.second > now } ?: (Prayer.FAJR to tomorrow[Prayer.FAJR])
        val left = (next.second - now) / 60_000
        nextText.text = c.getString(R.string.pr_next, PrayerPlayer.prayerName(c, next.first), "%d:%02d".format(left / 60, left % 60))
        for (p in Prayer.values()) {
            val r = c.hbox(8).apply { pad(c, 4, 6, 4, 6) }
            val sel = p == next.first && next.second == day[p]
            val color = if (sel) c.clr(R.color.acc) else c.clr(R.color.on)
            r.addView(c.text(PrayerPlayer.prayerName(c, p), 17f, if (sel) 600 else 500, color), lp(0, WRAP, 1f))
            if (p != Prayer.SUNRISE && prefs.iqamaOn) {
                val iq = day[p] + prefs.iqamaMinutes(p) * 60_000L
                r.addView(c.text(c.getString(R.string.pr_iqama_short, fmt(iq, zone)), 14f, 400, c.clr(R.color.on2)))
            }
            r.addView(c.text(fmt(day[p], zone), 17f, 600, color).apply { gravity = Gravity.END }, lp(c.dp(64), WRAP))
            timesBox.addView(r, lp(MATCH, WRAP))
        }
    }

    private fun fmt(ms: Long, zone: ZoneId) = hm.format(Instant.ofEpochMilli(ms).atZone(zone))

    // ---------------- место ----------------

    private fun locate() {
        if (!PrayerLocation.granted(ctx)) {
            awaitingLocation = true
            // точное - по желанию (спутники, если нет сети); приблизительного хватает
            host.requestPermissions(arrayOf(PrayerLocation.PERMISSION, android.Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }
        placeText.text = ctx.getString(R.string.pr_locating)
        PrayerLocation.refresh(ctx) { ok ->
            refresh()
            // после refresh: иначе «место не задано» затирает причину
            if (!ok) placeText.text = ctx.getString(R.string.pr_locate_failed)
        }
    }

    /** Город вручную: если геолокации нет (выключена, нет сети и спутников). */
    private fun askCity() {
        val c = ctx
        val input = android.widget.EditText(c).apply {
            hint = c.getString(R.string.pr_city_hint)
            isSingleLine = true
            pad(c, 20, 12, 20, 12)
        }
        AlertDialog.Builder(host).setTitle(R.string.pr_city).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                placeText.text = c.getString(R.string.pr_locating)
                PrayerLocation.byName(c, name) { ok ->
                    refresh()
                    if (!ok) placeText.text = c.getString(R.string.pr_city_failed)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---------------- настройки ----------------

    private fun buildSettings() {
        val c = ctx
        val box = settingsBox
        box.removeAllViews()

        box.addView(c.sectionTitle(c.getString(R.string.pr_sec_adhan)))
        for (p in PrayerPlan.FARD) {
            box.addView(ToggleRow(c, PrayerPlayer.prayerName(c, p), null, prefs.adhanOn(p), 56) {
                prefs.setAdhanOn(p, it); PrayerAlarms.reschedule(c)
            }.view, lp(MATCH, WRAP))
        }
        box.addView(ToggleRow(c, c.getString(R.string.pr_sound_always), c.getString(R.string.pr_sound_always_sub), prefs.soundAlways) {
            prefs.soundAlways = it
        }.view, lp(MATCH, WRAP))
        box.addView(valueRow(c.getString(R.string.pr_adhan_sound), prefs.adhanName.ifBlank { adhanBuiltinLabel() }) { pickAdhan() })
        box.addView(valueRow(c.getString(R.string.pr_adhan_speed), speedLabel(prefs.adhanSpeed)) { pickSpeed() })
        box.addView(c.text(c.getString(R.string.pr_adhan_file_note), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(19) })

        box.addView(c.sectionTitle(c.getString(R.string.pr_sec_iqama)))
        box.addView(ToggleRow(c, c.getString(R.string.pr_iqama_on), c.getString(R.string.pr_iqama_on_sub), prefs.iqamaOn) {
            prefs.iqamaOn = it; PrayerAlarms.reschedule(c); refresh()
        }.view, lp(MATCH, WRAP))
        for (p in PrayerPlan.FARD) {
            box.addView(valueRow(PrayerPlayer.prayerName(c, p), c.getString(R.string.pr_minutes_after, prefs.iqamaMinutes(p))) {
                pickNumber(PrayerPlayer.prayerName(c, p), 1, 60, prefs.iqamaMinutes(p)) { prefs.setIqamaMinutes(p, it) }
            })
        }

        box.addView(c.sectionTitle(c.getString(R.string.pr_sec_adhkar)))
        box.addView(ToggleRow(c, c.getString(R.string.pr_morning), c.getString(R.string.pr_minutes_after_fajr, prefs.morningAfterFajr), prefs.morningOn) {
            prefs.morningOn = it; PrayerAlarms.reschedule(c)
        }.view, lp(MATCH, WRAP))
        box.addView(valueRow(c.getString(R.string.pr_morning_time), c.getString(R.string.pr_minutes_after_fajr, prefs.morningAfterFajr)) {
            pickNumber(c.getString(R.string.pr_morning_time), 0, 180, prefs.morningAfterFajr) { prefs.morningAfterFajr = it }
        })
        box.addView(ToggleRow(c, c.getString(R.string.pr_evening), c.getString(R.string.pr_minutes_after_asr, prefs.eveningAfterAsr), prefs.eveningOn) {
            prefs.eveningOn = it; PrayerAlarms.reschedule(c)
        }.view, lp(MATCH, WRAP))
        box.addView(valueRow(c.getString(R.string.pr_evening_time), c.getString(R.string.pr_minutes_after_asr, prefs.eveningAfterAsr)) {
            pickNumber(c.getString(R.string.pr_evening_time), 0, 180, prefs.eveningAfterAsr) { prefs.eveningAfterAsr = it }
        })
        box.addView(ToggleRow(c, c.getString(R.string.pr_autoplay), c.getString(R.string.pr_autoplay_sub), prefs.adhkarAutoPlay) {
            prefs.adhkarAutoPlay = it
        }.view, lp(MATCH, WRAP))
        box.addView(ToggleRow(c, c.getString(R.string.pr_full_repeat), c.getString(R.string.pr_full_repeat_sub), prefs.fullRepeat) {
            prefs.fullRepeat = it
        }.view, lp(MATCH, WRAP))
        box.addView(valueRow(c.getString(R.string.pr_dhikr_every), dhikrLabel(prefs.dhikrEvery)) { pickDhikr() })
        box.addView(ToggleRow(c, c.getString(R.string.pr_human), c.getString(R.string.pr_human_sub), prefs.adhkarHuman) {
            prefs.adhkarHuman = it
        }.view, lp(MATCH, WRAP))
        box.addView(valueRow(c.getString(R.string.pr_voice), prefs.ttsVoice.ifBlank { c.getString(R.string.pr_voice_default) }.let { voiceLabel(it) }) { pickVoice() })
        box.addView(valueRow(c.getString(R.string.pr_morning_audio), prefs.adhkarName(true).ifBlank { c.getString(R.string.pr_audio_tts) }) { pickAdhkarAudio(true) })
        box.addView(valueRow(c.getString(R.string.pr_evening_audio), prefs.adhkarName(false).ifBlank { c.getString(R.string.pr_audio_tts) }) { pickAdhkarAudio(false) })
        box.addView(c.text(c.getString(R.string.pr_audio_note), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(19) })

        box.addView(c.sectionTitle(c.getString(R.string.pr_sec_calc)))
        box.addView(valueRow(c.getString(R.string.pr_method), prefs.methodOverride?.let { methodName(it) } ?: c.getString(R.string.pr_method_auto, methodName(prefs.method))) { pickMethod() })
        box.addView(valueRow(c.getString(R.string.pr_madhab), madhabLabel(prefs.madhabOverride)) { pickMadhab() })
        for (p in Prayer.values()) {
            val v = prefs.adjust(p)
            box.addView(valueRow(c.getString(R.string.pr_adjust_of, PrayerPlayer.prayerName(c, p)), c.getString(R.string.pr_adjust_value, if (v > 0) "+$v" else "$v")) {
                pickNumber(c.getString(R.string.pr_adjust_of, PrayerPlayer.prayerName(c, p)), -30, 30, v) { prefs.setAdjust(p, it) }
            })
        }
        box.addView(c.text(c.getString(R.string.pr_adjust_note), 13f, 400, c.clr(R.color.on2)))
    }

    private fun valueRow(title: String, value: String, onClick: () -> Unit): View {
        val c = ctx
        return c.hbox(12).apply {
            minimumHeight = c.dp(56)
            pad(c, 16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
            addView(c.text(title, 16f, 500), lp(0, WRAP, 1f))
            addView(c.text(value, 15f, 500, c.clr(R.color.acc)))
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 16), 16)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = lp(MATCH, WRAP)
        }
    }

    private fun changed() {
        PrayerAlarms.reschedule(ctx)
        buildSettings()
        refresh()
    }

    private fun pickNumber(title: String, min: Int, max: Int, value: Int, set: (Int) -> Unit) {
        // NumberPicker не умеет отрицательные: сдвиг на min
        val np = NumberPicker(ctx).apply {
            minValue = 0
            maxValue = max - min
            this.value = value - min
            displayedValues = Array(max - min + 1) { (it + min).toString() }
            wrapSelectorWheel = false
        }
        AlertDialog.Builder(host).setTitle(title).setView(np)
            .setPositiveButton(android.R.string.ok) { _, _ -> set(np.value + min); changed() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun pickMethod() {
        val all = CalcMethod.values()
        val labels = arrayOf(ctx.getString(R.string.pr_method_auto, methodName(CalcMethod.forCountry(prefs.country)))) + all.map { methodName(it) }
        val sel = prefs.methodOverride?.let { all.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(host).setTitle(R.string.pr_method).setSingleChoiceItems(labels, sel) { d, i ->
            prefs.methodOverride = if (i == 0) null else all[i - 1]
            d.dismiss()
            changed()
        }.show()
    }

    private fun pickMadhab() {
        val opts = listOf(null, Madhab.SHAFI, Madhab.HANAFI)
        AlertDialog.Builder(host).setTitle(R.string.pr_madhab)
            .setSingleChoiceItems(opts.map { madhabLabel(it) }.toTypedArray(), opts.indexOf(prefs.madhabOverride)) { d, i ->
                prefs.madhabOverride = opts[i]
                d.dismiss()
                changed()
            }.show()
    }

    private fun pickDhikr() {
        val steps = PrayerPrefs.DHIKR_STEPS
        AlertDialog.Builder(host).setTitle(R.string.pr_dhikr_every)
            .setSingleChoiceItems(steps.map { dhikrLabel(it) }.toTypedArray(), steps.indexOf(prefs.dhikrEvery).coerceAtLeast(0)) { d, i ->
                prefs.dhikrEvery = steps[i]
                d.dismiss()
                changed()
            }.show()
    }

    private fun dhikrLabel(m: Int) = if (m == 0) ctx.getString(R.string.pr_off) else ctx.getString(R.string.pr_every_min, m)

    private fun madhabLabel(m: Madhab?): String = when (m) {
        null -> ctx.getString(R.string.pr_madhab_auto, madhabLabel(Madhab.forCountry(prefs.country)))
        Madhab.SHAFI -> ctx.getString(R.string.pr_madhab_shafi)
        Madhab.HANAFI -> ctx.getString(R.string.pr_madhab_hanafi)
    }

    private fun methodName(m: CalcMethod): String = when (m) {
        CalcMethod.JORDAN -> ctx.getString(R.string.pr_m_jordan)
        CalcMethod.MAKKAH -> ctx.getString(R.string.pr_m_makkah)
        CalcMethod.EGYPT -> ctx.getString(R.string.pr_m_egypt)
        CalcMethod.MWL -> ctx.getString(R.string.pr_m_mwl)
        CalcMethod.ISNA -> "ISNA"
        CalcMethod.KARACHI -> ctx.getString(R.string.pr_m_karachi)
        CalcMethod.GULF -> ctx.getString(R.string.pr_m_gulf)
        CalcMethod.KUWAIT -> ctx.getString(R.string.pr_m_kuwait)
        CalcMethod.QATAR -> ctx.getString(R.string.pr_m_qatar)
        CalcMethod.DUBAI -> ctx.getString(R.string.pr_m_dubai)
        CalcMethod.TURKEY -> "Diyanet"
        CalcMethod.SINGAPORE -> "MUIS"
        CalcMethod.JAKIM -> "JAKIM"
        CalcMethod.KEMENAG -> "Kemenag"
        CalcMethod.TUNISIA -> ctx.getString(R.string.pr_m_tunisia)
        CalcMethod.ALGERIA -> ctx.getString(R.string.pr_m_algeria)
        CalcMethod.MOROCCO -> ctx.getString(R.string.pr_m_morocco)
        CalcMethod.FRANCE -> "UOIF"
        CalcMethod.RUSSIA -> "ДУМ РФ"
        CalcMethod.TEHRAN -> ctx.getString(R.string.pr_m_tehran)
    }

    // ---------------- текст азкаров ----------------

    private fun showText() {
        val all = try {
            Adhkar.parse(ctx.resources.openRawResource(R.raw.adhkar_ar).use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) {
            AppLog.e("намаз: азкары не прочитаны", e)
            return
        }
        val morningNow = java.time.LocalTime.now().hour < 12
        fun block(title: String, list: List<Adhkar.Item>) = title + "\n\n" + list.joinToString("\n\n") { item ->
            item.text + (if (item.count > 1) "  (" + ctx.getString(R.string.pr_times, item.count) + ")" else "")
        }
        val text = if (morningNow) block(ctx.getString(R.string.pr_morning), Adhkar.morning(all)) else block(ctx.getString(R.string.pr_evening), Adhkar.evening(all))
        val builder = AlertDialog.Builder(host)
        val tv = ctx.text(text, 19f, 400, ctx.dialogTextColor(builder.context)).apply {
            pad(ctx, 20, 12, 20, 12)
            setLineSpacing(0f, 1.35f)
            textDirection = View.TEXT_DIRECTION_RTL
            setTextIsSelectable(true)
        }
        builder
            .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun installVoice() {
        try {
            host.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: Exception) {
            try {
                host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")))
            } catch (_: Exception) {
            }
        }
        PrayerPlayer.voiceMissing = false
    }

    // ---------------- свой файл азана ----------------

    private fun adhanBuiltinLabel() = ctx.getString(
        if (prefs.adhanBuiltin == PrayerPrefs.ADHAN_FAST) R.string.pr_adhan_fast else R.string.pr_adhan_calm)

    private fun speedLabel(v: Float) = ctx.getString(when {
        v >= 1.25f -> R.string.pr_speed_fast
        v > 1.01f -> R.string.pr_speed_bit
        else -> R.string.pr_speed_normal
    })

    private fun pickSpeed() {
        val speeds = PrayerPrefs.ADHAN_SPEEDS
        AlertDialog.Builder(host).setTitle(R.string.pr_adhan_speed)
            .setSingleChoiceItems(speeds.map { speedLabel(it) }.toTypedArray(), speeds.indexOfFirst { it == prefs.adhanSpeed }.coerceAtLeast(0)) { d, i ->
                prefs.adhanSpeed = speeds[i]
                d.dismiss()
                buildSettings()
            }.show()
    }

    private fun pickAdhan() {
        AlertDialog.Builder(host).setTitle(R.string.pr_adhan_sound)
            .setItems(arrayOf(ctx.getString(R.string.pr_adhan_calm), ctx.getString(R.string.pr_adhan_fast), ctx.getString(R.string.pr_adhan_file))) { _, i ->
                if (i < 2) {
                    prefs.adhanUri = ""
                    prefs.adhanName = ""
                    prefs.adhanBuiltin = if (i == 0) PrayerPrefs.ADHAN_CALM else PrayerPrefs.ADHAN_FAST
                    buildSettings()
                    // сразу послушать выбранный
                    PrayerPlayer.start(ctx, PrayerPlayer.What.TEST_ADHAN, com.yegorkapomidorka.headswap.prayer.Prayer.DHUHR)
                } else {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    try {
                        host.startActivityForResult(pick, REQ_ADHAN_FILE)
                    } catch (e: Exception) {
                        AppLog.i("намаз: выбор файла не открылся: ${e.message}")
                    }
                }
            }.show()
    }

    override fun onActivityResultDone() = buildSettings()

    // ---------------- голос и свои записи азкаров ----------------

    private var voiceTts: TextToSpeech? = null

    /** Имя голоса Google -> «رجالي (ard)» / «نسائي (arc)»; пусто - мужской по умолчанию. */
    private fun voiceLabel(name: String): String {
        if (name == ctx.getString(R.string.pr_voice_default)) return name
        val code = name.substringAfter("-x-").substringBefore('-').ifBlank { name }
        val kind = when (com.yegorkapomidorka.headswap.prayer.TtsVoices.male(name)) {
            true -> ctx.getString(R.string.pr_voice_male)
            false -> ctx.getString(R.string.pr_voice_female)
            null -> ctx.getString(R.string.pr_voice_n, "")
        }
        return "$kind ($code)" + if ("network" in name) " · " + ctx.getString(R.string.pr_voice_online) else ""
    }

    /** Список арабских голосов движка; выбор сразу озвучивает образец. */
    private fun pickVoice() {
        val c = ctx
        voiceTts?.shutdown()
        val engine = try { c.packageManager.getPackageInfo("com.google.android.tts", 0); "com.google.android.tts" } catch (_: Exception) { null }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c, { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val voices = try {
                t.voices?.filter { it.locale.language == "ar" && !it.isNetworkConnectionRequired }?.sortedBy { it.name }.orEmpty() +
                    (t.voices?.filter { it.locale.language == "ar" && it.isNetworkConnectionRequired }?.sortedBy { it.name }.orEmpty())
            } catch (_: Exception) {
                emptyList()
            }
            host.runOnUiThread {
                if (voices.isEmpty()) {
                    PrayerPlayer.voiceMissing = true
                    return@runOnUiThread
                }
                val labels = arrayOf(c.getString(R.string.pr_voice_default)) + voices.map { voiceLabel(it.name) }
                val sel = voices.indexOfFirst { it.name == prefs.ttsVoice }.let { if (it < 0) 0 else it + 1 }
                AlertDialog.Builder(host).setTitle(R.string.pr_voice)
                    .setSingleChoiceItems(labels, sel) { _, i ->
                        prefs.ttsVoice = if (i == 0) "" else voices[i - 1].name
                        t.setLanguage(java.util.Locale("ar"))
                        val v = if (i > 0) voices[i - 1]
                            else com.yegorkapomidorka.headswap.prayer.TtsVoices.preferredMale(voices.map { it.name })?.let { n -> voices.first { it.name == n } }
                        if (v != null) t.voice = v
                        t.speak(Adhkar.SHORT[0], TextToSpeech.QUEUE_FLUSH, null, "sample")
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ -> buildSettings() }
                    .setOnDismissListener { t.shutdown(); voiceTts = null }
                    .show()
            }
        }, engine)
        voiceTts = tts
    }

    private fun pickAdhkarAudio(morning: Boolean) {
        AlertDialog.Builder(host).setTitle(if (morning) R.string.pr_morning_audio else R.string.pr_evening_audio)
            .setItems(arrayOf(ctx.getString(R.string.pr_audio_tts), ctx.getString(R.string.pr_adhan_file))) { _, i ->
                if (i == 0) {
                    prefs.setAdhkarFile(morning, "", "")
                    buildSettings()
                } else {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    try {
                        host.startActivityForResult(pick, if (morning) REQ_MORNING_FILE else REQ_EVENING_FILE)
                    } catch (e: Exception) {
                        AppLog.i("намаз: выбор файла не открылся: ${e.message}")
                    }
                }
            }.show()
    }

    companion object {
        const val REQ_LOCATION = 45
        const val REQ_ADHAN_FILE = 46
        const val REQ_MORNING_FILE = 48
        const val REQ_EVENING_FILE = 49

        /** Своя запись азкаров утра/вечера: постоянное разрешение и имя. */
        fun onAdhkarPicked(act: Activity, morning: Boolean, resultCode: Int, data: Intent?) {
            val uri = data?.data ?: return
            if (resultCode != Activity.RESULT_OK) return
            try {
                act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                AppLog.i("намаз: постоянное разрешение на файл не дано: ${e.message}")
            }
            val name = try {
                act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } ?: uri.lastPathSegment ?: ""
            PrayerPrefs(act).setAdhkarFile(morning, uri.toString(), name)
            AppLog.i("намаз: своя запись азкаров (${if (morning) "утро" else "вечер"}): $name")
        }

        /** Результат выбора файла (MainActivity.onActivityResult): постоянное разрешение на чтение и имя. */
        fun onAdhanPicked(act: Activity, resultCode: Int, data: Intent?) {
            val uri = data?.data ?: return
            if (resultCode != Activity.RESULT_OK) return
            try {
                act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                AppLog.i("намаз: постоянное разрешение на файл не дано: ${e.message}")
            }
            val name = try {
                act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } ?: uri.lastPathSegment ?: ""
            val p = PrayerPrefs(act)
            p.adhanUri = uri.toString()
            p.adhanName = name
            AppLog.i("намаз: свой азан: $name")
        }
    }
}
