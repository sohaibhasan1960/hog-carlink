package com.yegorkapomidorka.headswap.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * HOG CarLink: скачивание и установка [CarApp] из официального релиза GitHub. Порядок: последний релиз (API GitHub),
 * APK по шаблону, загрузка в cacheDir/carapps со сверкой размера и SHA-256, проверка пакета и сертификата подписи автора,
 * установка через PackageInstaller (подтверждает пользователь). Состояние по приложению - для экрана (опрос в tick).
 */
object CarAppInstaller {
    enum class Step { IDLE, CHECKING, DOWNLOADING, INSTALLING }

    class Status(
        @Volatile var step: Step = Step.IDLE,
        @Volatile var progress: Int = 0,
        @Volatile var error: String? = null,
        @Volatile var latest: CarAppsLogic.Release? = null,
    )

    private val status = CarApp.values().associateWith { Status() }

    fun status(app: CarApp): Status = status.getValue(app)

    /** Установленная версия или null. */
    fun installedVersion(ctx: Context, app: CarApp): String? = try {
        ctx.packageManager.getPackageInfo(app.pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun launch(ctx: Context, app: CarApp): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Разрешение «Установка неизвестных приложений» для HOG CarLink; без него открываем настройку и возвращаем false. */
    fun ensureCanInstall(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.e("приложения: не открыть настройку неизвестных источников", e)
        }
        return false
    }

    /** Узнать последнюю версию (для кнопки «Обновить»); тихо, ошибки только в лог. */
    fun refreshLatest(ctx: Context, app: CarApp) {
        val st = status(app)
        if (st.step != Step.IDLE) return
        Thread({
            try {
                st.latest = fetchLatest(ctx, app)
            } catch (e: Exception) {
                AppLog.i("приложения: ${app.name} последний релиз не узнать: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "carapps-check").start()
    }

    fun install(ctx: Context, app: CarApp) {
        val st = status(app)
        synchronized(st) {
            if (st.step != Step.IDLE) return
            st.step = Step.CHECKING
            st.progress = 0
            st.error = null
        }
        val appCtx = ctx.applicationContext
        Thread({
            try {
                val rel = fetchLatest(appCtx, app).also { st.latest = it }
                st.step = Step.DOWNLOADING
                val f = download(appCtx, app, rel, st)
                verify(appCtx, app, f)
                st.step = Step.INSTALLING
                session(appCtx, app, f)
            } catch (e: Exception) {
                AppLog.e("приложения: ${app.name} не установлено", e)
                st.error = e.message ?: e.javaClass.simpleName
                st.step = Step.IDLE
            }
        }, "carapps-install").start()
    }

    // --- сеть ---

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "HOG-CarLink (Android ${Build.VERSION.RELEASE})")
    }

    private fun fetchLatest(ctx: Context, app: CarApp): CarAppsLogic.Release {
        val c = open(app.apiLatest)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("GitHub HTTP $code")
            val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val arr = o.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                CarAppsLogic.Asset(
                    a.optString("name"), a.optString("browser_download_url"), a.optLong("size", -1),
                    CarAppsLogic.sha256(if (a.isNull("digest")) null else a.optString("digest")),
                )
            }
            val asset = CarAppsLogic.pickAsset(app, assets) ?: throw IOException("no APK in ${o.optString("tag_name")}")
            return CarAppsLogic.Release(CarAppsLogic.versionFromTag(o.getString("tag_name")), asset)
        } finally {
            c.disconnect()
        }
    }

    private fun download(ctx: Context, app: CarApp, rel: CarAppsLogic.Release, st: Status): File {
        val dir = File(ctx.cacheDir, "carapps").apply { mkdirs() }
        val f = File(dir, "${app.name.lowercase()}.apk")
        f.delete()
        val c = open(rel.asset.url)
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("APK HTTP $code")
            val total = if (rel.asset.size > 0) rel.asset.size else c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) st.progress = (done * 100 / total).toInt().coerceIn(0, 100)
                    }
                }
            }
            if (rel.asset.size > 0 && done != rel.asset.size) throw IOException("incomplete: $done / ${rel.asset.size}")
            val sum = md.digest().joinToString("") { "%02x".format(it) }
            if (rel.asset.sha256 != null && sum != rel.asset.sha256) throw IOException("SHA-256 mismatch")
            AppLog.i("приложения: ${app.name} ${rel.version} скачано $done Б, SHA-256 $sum")
            return f
        } catch (e: Exception) {
            f.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Пакет тот и подписан автором: иначе файл удаляется и установка не начинается. */
    private fun verify(ctx: Context, app: CarApp, f: File) {
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = info?.signingInfo?.let { si ->
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.map { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        }.orEmpty()
        if (info?.packageName != app.pkg || !CarAppsLogic.signerOk(app, certs)) {
            f.delete()
            AppLog.i("приложения: ${app.name} отклонён: пакет ${info?.packageName}, подписи $certs", true)
            throw IOException("signature check failed")
        }
        AppLog.i("приложения: ${app.name} подпись автора совпала")
    }

    private fun session(ctx: Context, app: CarApp, f: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.pkg)
            setSize(f.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("app.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, REQ_BASE + app.ordinal,
                    Intent(ctx, CarAppInstallReceiver::class.java).putExtra(EXTRA_APP, app.name),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(id)
            } catch (_: Exception) {
            }
            throw e
        }
        AppLog.i("приложения: ${app.name} сессия установки $id отправлена")
    }

    fun onInstallStatus(ctx: Context, app: CarApp, status: Int, message: String?, confirm: Intent?) {
        val st = status(app)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm != null) {
                    try {
                        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return      // ждём ответа пользователя; шаг остаётся INSTALLING
                    } catch (e: Exception) {
                        AppLog.e("приложения: не открыть подтверждение установки", e)
                    }
                }
                st.error = message ?: "no confirm"
                st.step = Step.IDLE
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i("приложения: ${app.name} установлено")
                File(ctx.cacheDir, "carapps/${app.name.lowercase()}.apk").delete()
                st.step = Step.IDLE
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.i("приложения: ${app.name} установку отменили")
                st.step = Step.IDLE
            }
            else -> {
                AppLog.i("приложения: ${app.name} установка не удалась, статус $status: $message", true)
                st.error = message ?: "status $status"
                st.step = Step.IDLE
            }
        }
    }

    const val EXTRA_APP = "carApp"
    private const val REQ_BASE = 4800
}

/** Статус сессии PackageInstaller -> [CarAppInstaller]. */
class CarAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = CarApp.values().firstOrNull { it.name == intent.getStringExtra(CarAppInstaller.EXTRA_APP) } ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        CarAppInstaller.onInstallStatus(context, app, status, message, confirm)
    }
}
