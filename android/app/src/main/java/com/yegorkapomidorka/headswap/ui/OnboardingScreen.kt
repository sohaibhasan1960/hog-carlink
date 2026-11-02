package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.link.car.CarNetwork
import com.yegorkapomidorka.headswap.link.car.CarPairing
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.car.WifiScan

/**
 * Онбординг (Onboarding.dc.html) и чеклист готовности. Шаги из Readiness.STEPS; при первом запуске уже выполненные
 * (определяются автоматически или отмечены вручную) пропускаются, при повторном прохождении показываются все.
 * В конце - чеклист со статусом каждого пункта; тот же чеклист открывается из Настроек.
 */
class OnboardingScreen(host: MainActivity, private val showAll: Boolean, startAtChecklist: Boolean, startAt: StepId? = null) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root
    private val ui = UiPrefs(ctx)
    private val queue: List<StepInfo>
    private var pos = 0
    private var checklist = false

    /** Шаг открыт из чеклиста: после него возвращаемся в чеклист. */
    private var single: StepInfo? = null
    private var shownDone = false

    /** Машина, отмеченная на шаге выбора, но ещё не применённая. */
    private var carChoice: CarPreset? = null

    /** HOG CarLink: ждём «Устройства поблизости», чтобы потом открыть выбор машины. */
    private var awaitingCarBt = false

    /** HOG CarLink: проверка Wi-Fi машины с шага онбординга (как «Сохранить и проверить» в настройках). */
    private var cwCheck: CarNetwork? = null
    private val cwHandler = Handler(Looper.getMainLooper())

    init {
        queue = if (showAll) Readiness.STEPS else Readiness.STEPS.filter { !Readiness.isDone(ctx, it.id) }
        checklist = startAtChecklist || queue.isEmpty()
        if (startAt != null) pos = queue.indexOfFirst { it.id == startAt }.coerceAtLeast(0)
        render()
    }

    override fun onShow() {
        // вернулись из системных настроек: статус мог измениться
        if (checklist) render() else if (current() != null && Readiness.isDone(ctx, current()!!.id) != shownDone) render()
    }

    override fun onPermissionResult() {
        awaitingWifiScan?.let {
            awaitingWifiScan = null
            if (WifiScan.granted(ctx)) it()
        }
        if (awaitingCarBt) {
            awaitingCarBt = false
            CarPairing.start(host)      // отказ выбор не блокирует: шаг останется невыполненным
        }
        val s = current() ?: return
        if (!checklist && Readiness.isDone(ctx, s.id) && (s.id == StepId.NOTIF || s.id == StepId.MIC)) next() else render()
    }

    override fun onActivityResultDone() = render()

    override fun onHide() {
        cwCheck?.release("onboarding")
        cwCheck = null
    }

    override fun onBack(): Boolean {
        when {
            checklist -> { ui.onboardingDone = true; return false }
            single != null -> { single = null; checklist = true; render() }
            pos > 0 -> { pos--; render() }
            else -> { ui.onboardingDone = true; return false }      // вышли из онбординга сами: больше не навязываем
        }
        return true
    }

    private fun current(): StepInfo? = if (checklist) null else (single ?: queue.getOrNull(pos))

    private fun next() {
        if (single != null) {
            single = null
            checklist = true
        } else if (pos + 1 < queue.size) {
            pos++
        } else {
            checklist = true
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        val s = if (checklist) null else current()
        when {
            checklist -> renderChecklist()
            s == null -> return
            s.id == StepId.CAR -> renderCar(s)
            s.id == StepId.CAR_WIFI -> renderCarWifi(s)
            else -> renderStep(s)
        }
    }

    /** Полоса «Шаг n из N» с точками. */
    private fun renderProgress(s: StepInfo) {
        val c = ctx
        val all = Readiness.STEPS
        val idx = all.indexOf(s)
        val total = if (single != null || showAll) all.size else queue.size
        val n = if (single != null || showAll) idx + 1 else pos + 1
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_step_progress, n, total), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
            val dots = c.hbox(6)
            for (i in 1..total) {
                dots.addView(View(c).apply { background = c.shape(c.clr(if (i <= n) R.color.pri else R.color.line), 3) }, lp(c.dp(if (total > 8) 14 else if (total > 6) 18 else 28), c.dp(6)))
            }
            addView(dots)
        }
        root.addView(top, c.lpDp(MATCH, 48))
    }

    private fun renderStep(s: StepInfo) {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        shownDone = done
        renderProgress(s)
        renderTitle(s)

        // прокручивается только картинка и текст, заголовок остаётся на месте
        val body = c.vbox().apply {
            addView(
                FrameLayout(c).apply {
                    background = c.shape(c.clr(R.color.surf2), 28)
                    // с подсказкой и на шагах с окном Android Auto макет компактнее: текст целиком виден без прокрутки
                    val compact = s.hint != null || s.id == StepId.AA_DEV || s.id == StepId.AA_UNKNOWN
                    minimumHeight = c.dp(if (compact) 0 else 190)
                    pad(c, 20, 20, 20, 20)
                    addView(Mocks.build(c, s.id), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
                },
                lp(MATCH, WRAP).margins(c, 0, 4, 0, 0),
            )
            val text = c.vbox(10).apply { pad(c, 0, 18, 0, 36) }     // нижний отступ больше затемнения края: конец текста не тонет в fade
            if (done) text.addView(doneChip(), lp(WRAP, WRAP))
            text.addView(numberedList(s.listItems(c)), lp(MATCH, WRAP))
            s.hint?.let {
                text.addView(
                    c.text(c.getString(it), 14f, 400, c.clr(R.color.onpric)).apply {
                        lineHeight = c.dp(20)
                        background = c.shape(c.clr(R.color.pric), 16)
                        pad(c, 14, 12, 14, 12)
                    },
                    lp(MATCH, WRAP),
                )
            }
            addView(text, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        val buttons = c.vbox(4)
        val primaryLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_next) else c.getString(s.primary)
        buttons.addView(c.primaryButton(primaryLabel).apply { setOnClickListener { onPrimary(s, done) } })
        val showExtra = s.extra != null && !done && (s.id != StepId.BACKGROUND || Readiness.isXiaomi() || Readiness.isHonor())
        if (showExtra) {
            val extraLabel = if (s.id == StepId.BACKGROUND && Readiness.isHonor()) R.string.step_background_extra_honor else s.extra!!
            buttons.addView(c.containerButton(c.getString(extraLabel)).apply { setOnClickListener { onExtra(s) } })
        }
        val skipLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_back) else c.getString(s.skip)
        buttons.addView(c.linkButton(skipLabel, 48).apply { setOnClickListener { onSkip(s, done) } })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /**
     * Последний шаг: какая у вас машина. Выпадашка только показывает настройки машины, «Применить» пишет их (AaPrefs.applyCar),
     * «Пропустить» (для опытных) ничего не трогает и ставит ручную отметку шага.
     */
    private fun renderCar(s: StepInfo) {
        val c = ctx
        shownDone = Readiness.isDone(c, s.id)
        renderProgress(s)
        renderTitle(s)
        val aa = AaPrefs(c)
        if (carChoice == null) carChoice = aa.carPreset

        val applyBtn = c.primaryButton(c.getString(s.primary))
        fun refresh() {
            applyBtn.isEnabled = carChoice != null
            applyBtn.alpha = if (carChoice != null) 1f else 0.45f
        }
        val chooser = CarChooser(c, withOther = false) { p ->
            carChoice = p
            refresh()
        }
        carChoice?.let { chooser.show(it) }

        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        body.addView(chooser.view, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        applyBtn.setOnClickListener {
            val p = carChoice ?: return@setOnClickListener
            aa.applyCar(p)
            AppLog.i("онбординг, мой автомобиль: ${p.name}, применены ${p.settings}")
            next()
        }
        refresh()
        val buttons = c.vbox(4)
        buttons.addView(applyBtn)
        buttons.addView(c.linkButton(c.getString(s.skip), 48).apply {
            setOnClickListener {
                AppLog.i("онбординг, мой автомобиль: пропущен")
                ui.setManualDone(s.id.key, true)
                next()
            }
        })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /**
     * HOG CarLink: Wi-Fi машины прямо в онбординге. «Сохранить и проверить» входит в сеть с переднего плана: здесь человек
     * один раз одобряет системный диалог, дальше сервис входит сам. Проверку держим 5 с и снимаем.
     */
    private fun renderCarWifi(s: StepInfo) {
        val c = ctx
        val cw = CarWifi(c)
        shownDone = cw.isSet
        renderProgress(s)
        renderTitle(s)

        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 16f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(23) })
        val ssid = wifiField(c.getString(R.string.wl_cw_ssid_hint), password = false).apply { setText(cw.ssid.orEmpty()) }
        val pass = wifiField(c.getString(R.string.wl_cw_pass_hint), password = true).apply { setText(cw.pass) }
        body.addView(ssid, lp(MATCH, c.dp(56)).margins(c, 0, 8, 0, 0))
        body.addView(pass, lp(MATCH, c.dp(56)))
        val result = c.text("", 14f, 500, c.clr(R.color.on2)).apply { visibility = View.GONE }
        body.addView(
            c.outlineButton(c.getString(R.string.wl_cw_scan), heightDp = 52, sp = 15f).apply {
                setOnClickListener {
                    scanCarWifi(ssid) { msg ->
                        result.text = msg
                        result.setTextColor(c.clr(R.color.warn))
                        result.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            },
            lp(MATCH, c.dp(52)),
        )
        body.addView(result, lp(MATCH, WRAP))
        s.hint?.let {
            body.addView(
                c.text(c.getString(it), 14f, 400, c.clr(R.color.onpric)).apply {
                    lineHeight = c.dp(20)
                    background = c.shape(c.clr(R.color.pric), 16)
                    pad(c, 14, 12, 14, 12)
                },
                lp(MATCH, WRAP).margins(c, 0, 8, 0, 0),
            )
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        fun show(text: String, color: Int) {
            result.text = text
            result.setTextColor(c.clr(color))
            result.visibility = View.VISIBLE
        }
        val nextBtn = c.primaryButton(c.getString(R.string.onb_next)).apply { setOnClickListener { next() } }
        fun enableNext(on: Boolean) {
            nextBtn.isEnabled = on
            nextBtn.alpha = if (on) 1f else 0.45f
        }
        val saveBtn = c.containerButton(c.getString(s.primary)).apply {
            setOnClickListener {
                val name = ssid.text.toString().trim()
                if (name.isEmpty()) {
                    show(c.getString(R.string.wl_cw_need_ssid), R.color.warn)
                    return@setOnClickListener
                }
                val pw = pass.text.toString()
                CarWifi(c).save(name, pw)
                AppLog.i("онбординг: Wi-Fi машины сохранён")
                enableNext(true)
                cwCheck?.release("recheck")
                val net = CarNetwork(c)
                cwCheck = net
                show(c.getString(R.string.wl_cw_checking), R.color.on2)
                val fail = {
                    if (cwCheck === net) {
                        show(c.getString(R.string.step_car_wifi_fail), R.color.warn)
                        cwCheck = null
                    }
                }
                net.request(
                    name, pw, CW_CHECK_TIMEOUT_MS,
                    onAvailable = {
                        if (cwCheck === net) {
                            show(c.getString(R.string.wl_cw_ok), R.color.ok)
                            cwHandler.postDelayed({
                                if (cwCheck === net) {
                                    net.release("check")
                                    cwCheck = null
                                }
                            }, CW_CHECK_HOLD_MS)
                        }
                    },
                    onUnavailable = { fail() },
                    onLost = { fail() },
                )
            }
        }
        enableNext(cw.isSet)
        val buttons = c.vbox(4)
        buttons.addView(nextBtn)
        buttons.addView(saveBtn)
        buttons.addView(c.linkButton(c.getString(s.skip), 48).apply { setOnClickListener { next() } })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    private fun wifiField(hint: String, password: Boolean): EditText = EditText(ctx).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ctx.clr(R.color.on))
        setHintTextColor(ctx.clr(R.color.on2))
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 16, 0, 16, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or
            if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = ctx.sans(400)      // setInputType для пароля сам ставит моноширинный
    }

    /** HOG CarLink: выбор машины. На Android 12+ сначала «Устройства поблизости» (без него ACL машины не доставляются). */
    private var awaitingWifiScan: (() -> Unit)? = null

    /** HOG CarLink: список сетей рядом -> имя сети машины в поле (см. WifiScan). */
    private fun scanCarWifi(field: EditText, warn: (String) -> Unit) {
        val c = ctx
        if (!WifiScan.granted(c)) {
            awaitingWifiScan = { scanCarWifi(field, warn) }
            host.requestPermissions(WifiScan.PERMISSIONS, REQ_WIFI_SCAN)
            return
        }
        if (!WifiScan.locationOn(c)) {
            warn(c.getString(R.string.wl_cw_scan_location))
            return
        }
        WifiScan.start(c)
        warn(c.getString(R.string.wl_cw_scanning))
        cwHandler.postDelayed({
            val nets = WifiScan.nearby(c)
            if (nets.isEmpty()) {
                warn(c.getString(R.string.wl_cw_scan_none))
                return@postDelayed
            }
            warn("")
            android.app.AlertDialog.Builder(c, R.style.Theme_Headswap_Dialog)
                .setTitle(R.string.wl_cw_scan_title)
                .setItems(nets.map { "${WifiScan.bars(it.level)}  ${it.ssid}" }.toTypedArray()) { _, i -> field.setText(nets[i].ssid) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }, WifiScan.SETTLE_MS)
    }

    private fun pickCar() {
        if (!Readiness.carBtGranted(ctx)) {
            awaitingCarBt = true
            host.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_CAR_BT)
            return
        }
        CarPairing.start(host)
    }

    /** Прокрутка с затемнением края: видно, что текст продолжается. */
    private fun fadingScroll(content: View) = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true     // постоянная полоса ложится на правый край карточки-иллюстрации; хвост текста и так виден по затемнению
        addView(content)
    }

    /** Заголовок шага под строкой «Шаг n из N»: вне прокрутки. */
    private fun renderTitle(s: StepInfo) {
        val c = ctx
        root.addView(
            c.text(c.getString(s.title), 24f, 600).apply { letterSpacing = -0.01f; lineHeight = c.dp(30) },
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 16),
        )
    }

    /** Пронумерованный список: кружок pri с цифрой и текст шага. */
    private fun numberedList(items: List<String>): View {
        val c = ctx
        return c.vbox(12).apply {
            items.forEachIndexed { i, t ->
                addView(
                    c.hbox(12).apply {
                        gravity = Gravity.TOP
                        addView(
                            FrameLayout(c).apply {
                                background = c.oval(c.clr(R.color.pri))
                                addView(c.text("${i + 1}", 14f, 700, c.clr(R.color.onpri)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                            },
                            lp(c.dp(26), c.dp(26)),
                        )
                        addView(c.text(t, 17f, 400).apply { lineHeight = c.dp(24) }, lp(0, WRAP, 1f))
                    },
                    lp(MATCH, WRAP),
                )
            }
        }
    }

    private fun doneChip(): View {
        val c = ctx
        return c.hbox(6).apply {
            background = c.shape(c.clr(R.color.okc), 10)
            pad(c, 10, 4, 12, 4)
            addView(c.icon(R.drawable.ic_check, 14, c.clr(R.color.ok)))
            addView(c.text(c.getString(R.string.onb_done_chip), 13f, 600, c.clr(R.color.ok)))
        }
    }

    private fun onPrimary(s: StepInfo, done: Boolean) {
        val c = ctx
        if (done && s.id != StepId.FIRST) { next(); return }
        when (s.id) {
            StepId.AA_DEV, StepId.AA_UNKNOWN -> Actions.openAaSettings(c)
            StepId.AUTOSTART -> Actions.startServerFromUi(c)
            StepId.NOTIF -> requestNotifications()
            StepId.BACKGROUND -> when {
                !Readiness.batteryOk(c) -> Readiness.requestIgnoreBattery(c)
                Readiness.isHonor() -> Readiness.openHonorLaunch(c)
                else -> Readiness.openXiaomiAutostart(c)
            }
            StepId.MIC -> requestMic()
            StepId.BLUETOOTH -> Readiness.openBluetoothSettings(c)
            StepId.LOCATION -> Readiness.openLocationSettings(c)
            StepId.FIRST -> { ui.setManualDone(s.id.key, true); next() }
            StepId.CAR -> next()      // свои кнопки в renderCar
            StepId.CAR_WIFI -> next()      // свои кнопки в renderCarWifi
            StepId.CAR_PICK -> pickCar()
        }
    }

    private fun onExtra(s: StepInfo) {
        try {
            when (s.id) {
                StepId.BACKGROUND -> if (Readiness.isHonor()) Readiness.openHonorLaunch(ctx) else Readiness.openXiaomiAutostart(ctx)
                else -> {}
            }
        } catch (e: Exception) {
            AppLog.e("шаг ${s.id}: не открыть системный экран", e)
        }
    }

    private fun onSkip(s: StepInfo, done: Boolean) {
        if (done && s.id != StepId.FIRST) { stepBack(); return }
        when (s.id) {
            StepId.FIRST -> stepBack()
            StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART, StepId.BACKGROUND, StepId.BLUETOOTH, StepId.LOCATION -> { ui.setManualDone(s.id.key, true); next() }
            else -> next()
        }
    }

    private fun stepBack() {
        if (!onBack()) host.back()
    }

    private fun requestNotifications() {
        val c = ctx
        val p = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && firstAsk(p)) {
            markAsked(p)
            host.requestPermissions(arrayOf(p), 1)
            return
        }
        // системный диалог уже был (или SDK < 33): включаем в настройках уведомлений приложения
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun requestMic() {
        val c = ctx
        if (!firstAsk(Manifest.permission.RECORD_AUDIO) && !host.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Readiness.appDetails(c)     // отказано насовсем: разрешить можно только в карточке приложения
            return
        }
        markAsked(Manifest.permission.RECORD_AUDIO)
        host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun firstAsk(p: String) = !ui.isManualDone("asked_$p")

    private fun markAsked(p: String) = ui.setManualDone("asked_$p", true)

    // --- чеклист ---

    private fun renderChecklist() {
        val c = ctx
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_checklist_title), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        }
        root.addView(top, c.lpDp(MATCH, 48))

        val list = c.vbox(12).apply { pad(c, 0, 8, 0, 8) }
        val doneCount = Readiness.STEPS.count { Readiness.isDone(c, it.id) }
        val heading = if (doneCount == Readiness.STEPS.size) c.getString(R.string.onb_all_ready) else c.getString(R.string.onb_ready_count, doneCount, Readiness.STEPS.size)
        list.addView(c.text(heading, 28f, 600).apply {
            letterSpacing = -0.01f
            lineHeight = c.dp(34)
        })
        list.addView(c.text(c.getString(R.string.onb_checklist_hint), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        val card = c.card(24).apply { pad(c, 0, 6, 0, 6) }
        for (s in Readiness.STEPS) card.addView(checkRow(s), c.lpDp(MATCH, 60))
        list.addView(card, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(list), lp(MATCH, 0, 1f))

        root.addView(c.primaryButton(c.getString(R.string.onb_done)).apply {
            setOnClickListener {
                ui.onboardingDone = true
                host.back()
            }
        })
    }

    private fun checkRow(s: StepInfo): View {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        val auto = Readiness.auto(c, s.id)
        val statusText: String
        val statusColor: Int
        when {
            done -> { statusText = c.getString(if (auto == null) R.string.onb_status_marked else R.string.onb_status_ready); statusColor = R.color.ok }
            auto == null -> { statusText = c.getString(R.string.onb_status_unmarked); statusColor = R.color.on2 }
            else -> { statusText = c.getString(R.string.onb_status_off); statusColor = R.color.warn }
        }
        return c.hbox(14).apply {
            pad(c, 20, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener {
                single = s
                checklist = false
                render()
            }
            val mark = FrameLayout(c)
            when {
                done -> {
                    mark.background = c.oval(c.clr(R.color.ok))
                    mark.addView(c.icon(R.drawable.ic_check, 16, c.clr(R.color.onok)), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                }
                auto == null -> mark.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0)
                    setStroke(c.dp(2), c.clr(R.color.line))
                }
                else -> {
                    mark.background = c.oval(c.clr(R.color.warn))
                    mark.addView(c.text("!", 16f, 700, c.clr(R.color.warnc)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
            }
            addView(mark, lp(c.dp(28), c.dp(28)))
            addView(c.vbox(1).apply {
                addView(c.text(c.getString(s.short), 16f, 500))
                addView(c.text(statusText, 13f, 500, c.clr(statusColor)))
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }

    private companion object {
        /** Тот же код, что в настройках: BLUETOOTH_CONNECT перед выбором машины (38 занят диалогом CDM). */
        const val REQ_CAR_BT = 39
        const val REQ_WIFI_SCAN = 40
        const val CW_CHECK_TIMEOUT_MS = 60_000L
        const val CW_CHECK_HOLD_MS = 5_000L
    }
}
