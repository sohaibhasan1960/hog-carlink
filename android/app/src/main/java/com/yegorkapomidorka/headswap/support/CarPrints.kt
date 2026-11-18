package com.yegorkapomidorka.headswap.support

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.aa.CarPrint
import com.yegorkapomidorka.headswap.aa.CarRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * HOG CarLink: общая база отпечатков ГУ. Машину, которую [CarRecognizer] не знает, телефон запоминает у себя и, только
 * с согласия человека (карточка на главном, переключатель в настройках), отправляет в Firestore carprints/{uid}_{key}:
 * канал прошивки, модель, product, board, Bluetooth-имя без цифр и машину, которую человек выбрал сам. Владелец
 * смотрит базу и добавляет подтверждённые машины в таблицу [CarRecognizer] в следующем выпуске.
 * Ни имени, ни телефона, ни места не отправляем. Сеть - только из фоновой задачи с проверенным интернетом
 * (Wi-Fi машины интернета не даёт).
 */
object CarPrints {
    private const val PREFS = "carprints"
    private const val K_CONSENT = "consent"
    private const val K_QUEUE = "queue"
    private const val K_SENT = "sent"
    private const val UNSET = 0
    private const val YES = 1
    private const val NO = 2
    private const val JOB_ID = 4721
    private const val MAX_QUEUE = 10

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Человек разрешил отправку. */
    fun consentOn(ctx: Context): Boolean = sp(ctx).getInt(K_CONSENT, UNSET) == YES

    /** Спросить на главном: есть незнакомая машина, а человек ещё не отвечал. */
    fun askNow(ctx: Context): Boolean = sp(ctx).getInt(K_CONSENT, UNSET) == UNSET && queue(ctx).length() > 0

    fun setConsent(ctx: Context, yes: Boolean) {
        val e = sp(ctx).edit().putInt(K_CONSENT, if (yes) YES else NO)
        if (!yes) e.remove(K_QUEUE)
        e.apply()
        AppLog.i("CARPRINT согласие: ${if (yes) "да" else "нет"}", true)
        if (yes) schedule(ctx)
    }

    /**
     * Сессия прислала отпечаток. Запоминаем только полный и незнакомый (и не нашу ID.6); [chosen] - машина,
     * выбранная человеком вручную (подсказка владельцу, что это за ГУ). Одно и то же второй раз не шлём.
     */
    fun record(ctx: Context, p: CarPrint, chosen: String?) {
        if (!p.complete() || CarRecognizer.isId6(p) || CarRecognizer.match(p) != null) return
        if (sp(ctx).getInt(K_CONSENT, UNSET) == NO) return
        val s = p.shareable()
        val key = s.key()
        val tag = key + "|" + (chosen ?: "")
        if (tag in sent(ctx)) return
        val q = queue(ctx)
        val out = JSONArray()
        for (i in 0 until q.length()) {
            val o = q.optJSONObject(i) ?: continue
            if (o.optString("key") != key) out.put(o)
        }
        out.put(
            JSONObject().put("key", key).put("manufacturer", s.manufacturer ?: "").put("model", s.model ?: "")
                .put("product", s.product ?: "").put("board", s.board ?: "").put("channel", s.channel ?: "")
                .put("chosen", chosen ?: ""),
        )
        while (out.length() > MAX_QUEUE) out.remove(0)
        sp(ctx).edit().putString(K_QUEUE, out.toString()).apply()
        AppLog.i("CARPRINT незнакомая ГУ запомнена: ${s.label()}${chosen?.let { " (выбрана $it)" } ?: ""}", true)
        if (consentOn(ctx)) schedule(ctx)
    }

    private fun queue(ctx: Context): JSONArray = try {
        JSONArray(sp(ctx).getString(K_QUEUE, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun sent(ctx: Context): Set<String> = sp(ctx).getStringSet(K_SENT, emptySet()) ?: emptySet()

    /** Отправить очередь (блокирующе, не с главной нити). true - готово или нечего; false - повторить позже. */
    fun upload(ctx: Context): Boolean {
        if (!consentOn(ctx)) return true
        val q = queue(ctx)
        if (q.length() == 0) return true
        val cloud = SupportCloud(ctx)
        val done = HashSet(sent(ctx))
        val left = JSONArray()
        var retry = false
        var stop = false
        for (i in 0 until q.length()) {
            val o = q.optJSONObject(i) ?: continue
            if (stop) { left.put(o); continue }
            try {
                cloud.sendCarPrint(o, BuildConfig.VERSION_NAME)
                done += o.optString("key") + "|" + o.optString("chosen")
                AppLog.i("CARPRINT отправлен: ${o.optString("key")}", true)
            } catch (e: SupportCloud.HttpError) {
                // 400/403: правила запись не принимают - не повторяем по кругу, попробуем при следующей машине
                AppLog.i("CARPRINT не принят (${e.code})", true)
                left.put(o)
                retry = e.code != 403 && e.code != 400
                stop = true
            } catch (e: IOException) {
                AppLog.i("CARPRINT сеть: ${e.javaClass.simpleName}: ${e.message}")
                left.put(o)
                retry = true
                stop = true
            }
        }
        sp(ctx).edit().putString(K_QUEUE, left.toString()).putStringSet(K_SENT, done).apply()
        return !retry
    }

    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val net = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, CarPrintJob::class.java))
                    .setRequiredNetwork(net)
                    .setPersisted(false)
                    .build(),
            )
        } catch (e: Exception) {
            AppLog.i("CARPRINT задача не запланирована: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }
}

/** HOG CarLink: отправка отпечатков ГУ, когда появился настоящий интернет. */
class CarPrintJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            val ok = try {
                CarPrints.upload(applicationContext)
            } catch (e: Exception) {
                AppLog.i("CARPRINT задача упала: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            jobFinished(params, !ok)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
