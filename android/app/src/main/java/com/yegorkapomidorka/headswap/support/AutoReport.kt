package com.yegorkapomidorka.headswap.support

import android.app.AlertDialog
import android.app.Activity
import android.content.Context
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.ui.LogShare
import java.io.File
import java.io.RandomAccessFile

/**
 * HOG CarLink: автоматический отчёт о падении во «входящие» поддержки владельца (SupportCloud.send).
 * CrashLog помечает падение ([markPending]); на следующем запуске MainActivity один раз спрашивает согласие
 * (в отчёте журнал: имя сети машины, адрес Bluetooth, модель телефона), дальше отчёт уходит сам или не уходит.
 * Один отчёт на падение, не больше [DAY_LIMIT] в сутки.
 */
object AutoReport {
    private const val PREFS = "auto_report"
    private const val K_CONSENT = "consent"        // ask / yes / no
    private const val K_PENDING = "pending"        // причина падения, ещё не отправленного
    private const val K_LOG = "pending_log"        // журнал, в котором случилось падение
    private const val K_SENT_DAY = "sent_day"
    private const val K_SENT_COUNT = "sent_count"
    const val DAY_LIMIT = 3
    private const val LOG_TAIL = 60_000
    private const val MS_DAY = 86_400_000L

    enum class Consent { ASK, YES, NO }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun consent(ctx: Context): Consent = when (sp(ctx).getString(K_CONSENT, null)) {
        "yes" -> Consent.YES
        "no" -> Consent.NO
        else -> Consent.ASK
    }

    fun setConsent(ctx: Context, yes: Boolean) {
        sp(ctx).edit().putString(K_CONSENT, if (yes) "yes" else "no").apply()
        if (!yes) sp(ctx).edit().remove(K_PENDING).remove(K_LOG).apply()
    }

    /** Падение для отчёта. commit(): после необработанного исключения процесс сейчас умрёт. */
    fun markPending(ctx: Context, reason: String, log: File?) {
        try {
            val p = sp(ctx)
            if (p.getString(K_CONSENT, null) == "no") return
            val prev = p.getString(K_PENDING, null)
            val all = if (prev.isNullOrBlank()) reason else "$prev\n$reason"
            p.edit().putString(K_PENDING, all.takeLast(4000)).putString(K_LOG, log?.absolutePath).commit()
        } catch (_: Throwable) {
        }
    }

    fun pending(ctx: Context): String? = sp(ctx).getString(K_PENDING, null)?.takeIf { it.isNotBlank() }

    /** Лимит в сутки: day - номер суток, count - сколько уже ушло в эти сутки. */
    fun allowed(day: Long, sentDay: Long, sentCount: Int): Boolean = day != sentDay || sentCount < DAY_LIMIT

    /** Из MainActivity.onResume: спросить один раз или отправить в фоне. */
    fun onAppVisible(act: Activity) {
        val reason = pending(act) ?: return
        when (consent(act)) {
            Consent.NO -> sp(act).edit().remove(K_PENDING).apply()
            Consent.YES -> sendInBackground(act.applicationContext)
            Consent.ASK -> {
                if (asking) return
                asking = true
                AlertDialog.Builder(act)
                    .setTitle(R.string.ar_title)
                    .setMessage(act.getString(R.string.ar_text))
                    .setPositiveButton(R.string.ar_yes) { _, _ -> setConsent(act, true); sendInBackground(act.applicationContext) }
                    .setNegativeButton(R.string.ar_no) { _, _ -> setConsent(act, false) }
                    .setOnDismissListener { asking = false }
                    .show()
                AppLog.i("автоотчёт: спрашиваю согласие, причина: ${reason.lineSequence().first()}")
            }
        }
    }

    @Volatile private var asking = false
    @Volatile private var sending = false

    private fun sendInBackground(ctx: Context) {
        if (sending) return
        sending = true
        Thread({
            try {
                send(ctx)
            } catch (e: Exception) {
                AppLog.i("автоотчёт не отправлен: ${e.javaClass.simpleName}: ${e.message}", true)
            } finally {
                sending = false
            }
        }, "auto-report").start()
    }

    private fun send(ctx: Context) {
        val p = sp(ctx)
        val reason = pending(ctx) ?: return
        val day = System.currentTimeMillis() / MS_DAY
        val sentDay = p.getLong(K_SENT_DAY, -1)
        val count = if (sentDay == day) p.getInt(K_SENT_COUNT, 0) else 0
        if (!allowed(day, sentDay, count)) {
            AppLog.i("автоотчёт: лимит $DAY_LIMIT в сутки, этот не отправляю")
            p.edit().remove(K_PENDING).remove(K_LOG).apply()
            return
        }
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val log = p.getString(K_LOG, null)?.let { File(it) }?.takeIf { it.isFile }
        val report = buildString {
            append(LogShare.deviceInfo(ctx)).append("\n\n=== ").append(reason).append('\n')
            if (log != null) append("\n=== ").append(log.name).append(" (конец)\n").append(tail(log, LOG_TAIL))
        }
        SupportCloud(ctx).send(ctx.getString(R.string.ar_name), "", AaPrefs(ctx).lastHuLabel, ver, reason.take(2000), report)
        p.edit().remove(K_PENDING).remove(K_LOG).putLong(K_SENT_DAY, day).putInt(K_SENT_COUNT, count + 1).apply()
        AppLog.i("автоотчёт отправлен (${report.length} символов)", true)
    }

    private fun tail(f: File, max: Int): String = RandomAccessFile(f, "r").use { r ->
        val start = maxOf(0L, r.length() - max)
        r.seek(start)
        val b = ByteArray((r.length() - start).toInt())
        r.readFully(b)
        String(b, Charsets.UTF_8)
    }
}
