package com.yegorkapomidorka.headswap.ui

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.support.AutoReport
import com.yegorkapomidorka.headswap.support.SupportCloud
import com.yegorkapomidorka.headswap.support.SupportJob

/**
 * HOG CarLink: поддержка внутри приложения. Клиент отправляет запрос (имя, телефон, описание + сводка устройства)
 * и видит ответы; владелец (код владельца, долгое нажатие на строку версии внизу) видит все запросы, отвечает и удаляет.
 * Сеть - на своей нити, результат на главную через Handler.
 */
class SupportScreen(host: MainActivity) : Screen(host) {
    override val view: View
    private val cloud = SupportCloud(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val ownerBox: LinearLayout
    private val mineBox: LinearLayout
    private val status = ctx.text("", 14f, 500, ctx.clr(R.color.on2)).apply { visibility = View.GONE }
    private lateinit var name: EditText
    private lateinit var phone: EditText
    private lateinit var problem: EditText
    private val autoRow: ToggleRow
    private val googleBox: LinearLayout

    init {
        val c = ctx
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 24) }
        ownerBox = c.vbox(10)
        body.addView(ownerBox, lp(MATCH, WRAP))

        // HOG CarLink: вход Google - запросы и ответы сохраняются при переустановке и на другом телефоне
        googleBox = c.vbox(8).apply { pad(c, 16, 14, 16, 14); background = c.shape(c.clr(R.color.surf), 20) }
        body.addView(googleBox, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.sup_new)))
        val form = c.card().apply { pad(c, 16, 16, 16, 16) }
        val formBox = c.vbox(10)
        name = field(c.getString(R.string.sup_name), multi = false)
        phone = field(c.getString(R.string.sup_phone), multi = false).apply { inputType = InputType.TYPE_CLASS_PHONE }
        problem = field(c.getString(R.string.sup_problem), multi = true)
        formBox.addView(name, lp(MATCH, c.dp(52)))
        formBox.addView(phone, lp(MATCH, c.dp(52)))
        formBox.addView(problem, lp(MATCH, c.dp(120)))
        formBox.addView(c.text(c.getString(R.string.sup_consent), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(19) })
        formBox.addView(status, lp(MATCH, WRAP))
        formBox.addView(c.primaryButton(c.getString(R.string.sup_send), heightDp = 56, sp = 17f).apply { setOnClickListener { send() } })
        form.addView(formBox, lp(MATCH, WRAP))
        body.addView(form, lp(MATCH, WRAP))

        // HOG CarLink: автоотчёты о падениях (support/AutoReport); до первого падения - «спросить»
        autoRow = ToggleRow(c, c.getString(R.string.ar_switch), c.getString(R.string.ar_switch_sub),
            AutoReport.consent(c) == AutoReport.Consent.YES) { AutoReport.setConsent(c, it) }
        body.addView(autoRow.view, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.sup_mine)))
        mineBox = c.vbox(10)
        body.addView(mineBox, lp(MATCH, WRAP))

        // вход владельца: долгое нажатие на строку версии
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        } catch (_: Exception) {
            ""
        }
        body.addView(c.text("HOG CarLink $ver", 12f, 400, c.clr(R.color.on2)).apply {
            gravity = Gravity.CENTER
            pad(c, 0, 16, 0, 8)
            setOnLongClickListener { askOwnerCode(); true }
        }, lp(MATCH, WRAP))

        view = scaffold(c, c.getString(R.string.sup_title), { host.back() }, body)
    }

    override fun onShow() {
        refreshGoogle()
        autoRow.setChecked(AutoReport.consent(ctx) == AutoReport.Consent.YES)
        refresh()
    }

    private fun refreshGoogle() {
        val c = ctx
        googleBox.removeAllViews()
        val email = cloud.googleEmail
        if (email.isNotBlank()) {
            googleBox.addView(c.text(c.getString(R.string.sup_google_signed, email), 15f, 600, c.clr(R.color.ok)))
            if (name.text.isBlank() && cloud.googleName.isNotBlank()) name.setText(cloud.googleName)
            return
        }
        googleBox.addView(c.text(c.getString(R.string.sup_google_why), 14f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(20) })
        if (!com.yegorkapomidorka.headswap.support.GoogleSignIn.supported) {
            googleBox.addView(c.text(c.getString(R.string.sup_google_old), 13f, 400, c.clr(R.color.warn)))
            return
        }
        // результат входа - прямо под кнопкой (раньше уходил в строку статуса формы ниже и был не виден)
        val result = c.text("", 14f, 500, c.clr(R.color.on2)).apply { visibility = View.GONE }
        fun say(text: String, color: Int) {
            result.text = text
            result.setTextColor(c.clr(color))
            result.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        googleBox.addView(c.containerButton(c.getString(R.string.sup_google_btn)).apply {
            setOnClickListener {
                say(c.getString(R.string.sup_google_wait), R.color.on2)
                com.yegorkapomidorka.headswap.support.GoogleSignIn.start(host) { r ->
                    when (r) {
                        is com.yegorkapomidorka.headswap.support.GoogleSignIn.Result.Ok -> {
                            android.widget.Toast.makeText(c, c.getString(R.string.sup_google_done), android.widget.Toast.LENGTH_LONG).show()
                            SupportJob.schedule(c)
                            refreshGoogle()
                            refresh()
                        }
                        is com.yegorkapomidorka.headswap.support.GoogleSignIn.Result.Cancelled -> say("", R.color.on2)
                        is com.yegorkapomidorka.headswap.support.GoogleSignIn.Result.Failed -> say(when {
                            "NoCredential" in r.why -> c.getString(R.string.sup_google_no_account)
                            "ProviderConfiguration" in r.why || "Unsupported" in r.why -> c.getString(R.string.sup_google_no_provider)
                            else -> c.getString(R.string.sup_google_failed, r.why)
                        }, R.color.warn)
                    }
                }
            }
        })
        googleBox.addView(result, lp(MATCH, WRAP))
    }

    private fun field(hint: String, multi: Boolean): EditText = EditText(ctx).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ctx.clr(R.color.on))
        setHintTextColor(ctx.clr(R.color.on2))
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 14, if (multi) 12 else 0, 14, if (multi) 12 else 0)
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
        } else {
            isSingleLine = true
        }
        typeface = ctx.sans(400)
    }

    private fun show(text: String, colorRes: Int) {
        status.text = text
        status.setTextColor(ctx.clr(colorRes))
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bg(work: () -> Unit) = Thread(work, "support").start()

    // ---------------- клиент ----------------

    private fun send() {
        val c = ctx
        val text = problem.text.toString().trim()
        if (text.isEmpty()) {
            show(c.getString(R.string.sup_need_text), R.color.warn)
            return
        }
        show(c.getString(R.string.sup_sending), R.color.on2)
        val n = name.text.toString().trim()
        val p = phone.text.toString().trim()
        val car = com.yegorkapomidorka.headswap.aa.AaPrefs(c).lastHuLabel
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        bg {
            val ok = try {
                cloud.send(n, p, car, ver, text, LogShare.deviceInfo(c))
                true
            } catch (e: Exception) {
                AppLog.i("поддержка: запрос не отправлен: ${e.javaClass.simpleName}: ${e.message}", true)
                false
            }
            main.post {
                if (ok) {
                    problem.setText("")
                    show(c.getString(R.string.sup_sent), R.color.ok)
                    SupportJob.schedule(c)
                    refresh()
                } else {
                    show(c.getString(R.string.sup_failed), R.color.warn)
                }
            }
        }
    }

    private fun refresh() {
        bg {
            val mine = try {
                if (cloud.hasSent) cloud.mine() else emptyList()
            } catch (e: Exception) {
                null
            }
            val owner = try {
                if (cloud.isOwner) cloud.all() else null
            } catch (e: Exception) {
                null
            }
            main.post {
                renderMine(mine)
                renderOwner(owner)
            }
        }
    }

    private fun renderMine(list: List<SupportCloud.Request>?) {
        val c = ctx
        mineBox.removeAllViews()
        when {
            list == null -> mineBox.addView(c.text(c.getString(R.string.sup_offline), 14f, 400, c.clr(R.color.warn)))
            list.isEmpty() -> mineBox.addView(c.text(c.getString(R.string.sup_none), 14f, 400, c.clr(R.color.on2)))
            else -> for (r in list) {
                mineBox.addView(requestCard(r, owner = false), lp(MATCH, WRAP))
                if (r.reply.isNotBlank()) cloud.markReplySeen(r.id, r.repliedAt)
            }
        }
    }

    // ---------------- владелец ----------------

    private fun renderOwner(list: List<SupportCloud.Request>?) {
        val c = ctx
        ownerBox.removeAllViews()
        if (!cloud.isOwner) return
        ownerBox.addView(c.sectionTitle(c.getString(R.string.sup_inbox)))
        when {
            list == null -> ownerBox.addView(c.text(c.getString(R.string.sup_offline), 14f, 400, c.clr(R.color.warn)))
            list.isEmpty() -> ownerBox.addView(c.text(c.getString(R.string.sup_none), 14f, 400, c.clr(R.color.on2)))
            else -> {
                for (r in list) ownerBox.addView(requestCard(r, owner = true), lp(MATCH, WRAP))
                list.maxOfOrNull { it.createdAt }?.let { cloud.ownerSeen = it }
            }
        }
    }

    private fun requestCard(r: SupportCloud.Request, owner: Boolean): View {
        val c = ctx
        val card = c.card().apply { pad(c, 16, 14, 16, 14) }
        val box = c.vbox(6)
        val statusText = c.getString(
            when (r.status) {
                "answered" -> R.string.sup_st_answered
                "closed" -> R.string.sup_st_closed
                else -> R.string.sup_st_new
            },
        )
        val head = if (owner) listOf(r.name.ifBlank { "?" }, r.phone, r.car).filter { it.isNotBlank() }.joinToString(" · ")
        else r.createdAt.take(16).replace('T', ' ')
        box.addView(c.text(head, 15f, 600))
        box.addView(c.text("$statusText${if (owner) " · " + r.createdAt.take(16).replace('T', ' ') else ""}", 13f, 500,
            c.clr(if (r.status == "new") R.color.acc else R.color.on2)))
        box.addView(c.text(r.text, 15f, 400).apply { lineHeight = c.dp(21) })
        if (r.reply.isNotBlank()) {
            box.addView(c.text(c.getString(R.string.sup_reply_label) + " " + r.reply, 15f, 500, c.clr(R.color.onpric)).apply {
                lineHeight = c.dp(21)
                background = c.shape(c.clr(R.color.pric), 12)
                pad(c, 12, 10, 12, 10)
            }, lp(MATCH, WRAP))
        }
        if (owner) {
            val row = c.hbox(8)
            row.addView(c.tonalButton(c.getString(R.string.sup_reply)).apply { setOnClickListener { askReply(r) } }, lp(0, WRAP, 1f))
            row.addView(c.tonalButton(c.getString(R.string.sup_report)).apply { setOnClickListener { showReport(r) } }, lp(0, WRAP, 1f))
            row.addView(c.tonalButton(c.getString(R.string.sup_delete)).apply { setOnClickListener { confirmDelete(r) } }, lp(0, WRAP, 1f))
            box.addView(row, lp(MATCH, WRAP))
        }
        card.addView(box, lp(MATCH, WRAP))
        return card
    }

    private fun askReply(r: SupportCloud.Request) {
        val c = ctx
        val input = field(c.getString(R.string.sup_reply_hint), multi = true).apply { setText(r.reply) }
        AlertDialog.Builder(c, R.style.Theme_Headswap_Dialog)
            .setTitle(c.getString(R.string.sup_reply_to, r.name.ifBlank { "?" }))
            .setView(c.vbox().apply { pad(c, 20, 8, 20, 0); addView(input, lp(MATCH, c.dp(140))) })
            .setPositiveButton(R.string.sup_reply_send) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) bg {
                    val ok = try {
                        cloud.reply(r.id, t); true
                    } catch (e: Exception) {
                        AppLog.i("поддержка: ответ не отправлен: ${e.message}", true); false
                    }
                    main.post { if (ok) refresh() else show(c.getString(R.string.sup_failed), R.color.warn) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showReport(r: SupportCloud.Request) {
        val c = ctx
        AlertDialog.Builder(c, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.sup_report)
            .setMessage(r.report.ifBlank { "—" }.take(20_000))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(r: SupportCloud.Request) {
        val c = ctx
        AlertDialog.Builder(c, R.style.Theme_Headswap_Dialog)
            .setMessage(R.string.sup_delete_confirm)
            .setPositiveButton(R.string.sup_delete) { _, _ ->
                bg {
                    try {
                        cloud.delete(r.id)
                    } catch (e: Exception) {
                        AppLog.i("поддержка: не удалён: ${e.message}", true)
                    }
                    main.post { refresh() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askOwnerCode() {
        val c = ctx
        val input = field(c.getString(R.string.sup_owner_code), multi = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = c.sans(400)
        }
        AlertDialog.Builder(c, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.sup_owner_title)
            .setView(c.vbox().apply { pad(c, 20, 8, 20, 0); addView(input, lp(MATCH, c.dp(52))) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) bg {
                    val ok = try {
                        cloud.claimOwner(code) || cloud.checkOwner()
                    } catch (e: Exception) {
                        AppLog.i("поддержка: проверка владельца не прошла: ${e.message}", true); false
                    }
                    main.post {
                        show(c.getString(if (ok) R.string.sup_owner_ok else R.string.sup_owner_bad), if (ok) R.color.ok else R.color.warn)
                        if (ok) {
                            SupportJob.schedule(c)
                            refresh()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
