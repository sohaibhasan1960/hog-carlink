package com.yegorkapomidorka.headswap.tesla

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.prayer.HifzPrefs
import com.yegorkapomidorka.headswap.prayer.PrayerIndicatorText
import com.yegorkapomidorka.headswap.prayer.PrayerPlayer
import com.yegorkapomidorka.headswap.prayer.QuranPrefs
import com.yegorkapomidorka.headswap.quran.MadaniData
import com.yegorkapomidorka.headswap.quran.MadaniLayout
import com.yegorkapomidorka.headswap.quran.MushafActivity
import com.yegorkapomidorka.headswap.quran.Quran
import com.yegorkapomidorka.headswap.quran.QuranPages
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * HOG CarLink: «شاشة تسلا» - у Tesla нет CarLife / Android Auto, зато есть браузер. Телефон раздаёт точку доступа,
 * Tesla к ней подключается и открывает http://<адрес телефона>:[PORT]/ - страницу с мусхафом за чтецом, кнопками
 * плана заучивания и индикатором намаза (assets/tesla/index.html). Звук - с телефона по Bluetooth, как обычно.
 *
 * Свой маленький HTTP-сервер без зависимостей: GET /, /font.ttf, /state, /page?n=, /cmd?a=&k=. Команды - только
 * с ключом из адреса (в сети точки доступа могут быть и чужие устройства), чтение страниц и состояния - без ключа.
 */
object TeslaWeb {
    const val PORT = 8686

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "tesla-web").also { it.isDaemon = true } }

    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            AppLog.e("Tesla: порт $PORT не открылся", e)
            return
        }
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { serve(app, c) }
            }
        }, "tesla-accept").apply { isDaemon = true }.start()
        AppLog.i("Tesla: страница работает: ${addresses().joinToString { url(app, it) }}", true)
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    /** IPv4 телефона: точка доступа (192.168.x / 172.x / 10.x), без loopback. */
    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { i.name to it.hostAddress.orEmpty() } }
            .sortedBy { (name, _) -> if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1")) 0 else 1 }
            .map { it.second }.filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    fun url(ctx: Context, ip: String) = "http://$ip:$PORT/?k=${key(ctx)}"

    /** Ключ команд: случайный, один на телефон. */
    fun key(ctx: Context): String {
        val sp = ctx.getSharedPreferences("tesla", Context.MODE_PRIVATE)
        sp.getString("key", null)?.let { return it }
        val k = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        sp.edit().putString("key", k).apply()
        return k
    }

    var enabled: (Context) -> Boolean = { it.getSharedPreferences("tesla", Context.MODE_PRIVATE).getBoolean("on", false) }

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("tesla", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    // ---------------- HTTP ----------------

    private fun serve(ctx: Context, c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = 10_000
                val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val line = r.readLine() ?: return
                while (true) {
                    val h = r.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val target = line.split(' ').getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val q = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val out = sock.getOutputStream()
                when (path) {
                    "/", "/index.html" -> send(out, "text/html; charset=utf-8", ctx.assets.open("tesla/index.html").use { it.readBytes() })
                    "/font.ttf" -> send(out, "font/ttf", fontBytes(ctx), cache = true)
                    "/state" -> send(out, "application/json", state(ctx).toString().toByteArray())
                    "/page" -> {
                        val n = q["n"]?.toIntOrNull()?.coerceIn(1, QuranPages.PAGES) ?: 1
                        send(out, "application/json", page(ctx, n, q["r"] == "1").toString().toByteArray(), cache = true)
                    }
                    "/cmd" -> {
                        val ok = q["k"] == key(ctx) && command(ctx, q["a"].orEmpty(), q["p"]?.toIntOrNull())
                        send(out, "application/json", JSONObject().put("ok", ok).toString().toByteArray(), status = if (ok) "200 OK" else "403 Forbidden")
                    }
                    else -> send(out, "text/plain", "not found".toByteArray(), status = "404 Not Found")
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Шрифт KFGQPC из res/font как есть (лицензия запрещает менять файл); font - тот же raw-файл. */
    @android.annotation.SuppressLint("ResourceType")
    private fun fontBytes(ctx: Context): ByteArray = ctx.resources.openRawResource(R.font.uthmanic_hafs).use { it.readBytes() }

    private fun send(out: OutputStream, type: String, body: ByteArray, status: String = "200 OK", cache: Boolean = false) {
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            (if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    // ---------------- данные ----------------

    private fun state(ctx: Context): JSONObject {
        val now = PrayerPlayer.hifzNowPage
        val o = JSONObject()
            .put("playing", PrayerPlayer.quranActive && now > 0 && !PrayerPlayer.quranPausedNow)
            .put("active", PrayerPlayer.quranActive && now > 0)
            .put("page", if (now > 0) now else HifzPrefs(ctx).page)
            .put("lit", if (now > 0) MushafActivity.litCode(PrayerPlayer.hifzNowAyah, PrayerPlayer.hifzNowNext) else 0)
            .put("speed", Quran.speedLabel(QuranPrefs(ctx).speed))
            .put("labels", JSONObject()
                .put("prev", ctx.getString(R.string.mf_prev)).put("next", ctx.getString(R.string.mf_next))
                .put("play", ctx.getString(R.string.mf_play)).put("pause", ctx.getString(R.string.mf_pause)))
        PrayerIndicatorText.now(ctx)?.let { p ->
            o.put("prayer", JSONObject().put("phase", p.state.phase.name).put("title", p.title).put("tip", p.tip)
                .put("name", p.name).put("remainMs", p.state.remainMs).put("spanMs", p.state.spanMs)
                .put("left", ctx.getString(R.string.pr_ind_left)))
        }
        return o
    }

    private fun page(ctx: Context, n: Int, reversed: Boolean): JSONObject {
        val p = MadaniData.load(ctx).page(n, reversed) ?: return JSONObject().put("page", n).put("lines", JSONArray())
        val lines = JSONArray()
        for (l in p.lines) lines.put(when (l) {
            is MadaniLayout.Words -> JSONObject().put("n", l.number).put("t", "w").put("c", l.centred)
                .put("w", JSONArray().apply { l.tokens.forEach { put(JSONArray().put(it.text).put(it.code).put(it.isMark)) } })
            is MadaniLayout.Banner -> JSONObject().put("n", l.number).put("t", "b").put("s", l.surah).put("name", "سُورَةُ " + Quran.name(l.surah))
            is MadaniLayout.Basmala -> JSONObject().put("n", l.number).put("t", "m").put("s", l.surah)
        })
        return JSONObject().put("page", n).put("count", p.lineCount).put("lines", lines)
    }

    /** Кнопки страницы: play (с [page]), pause, resume, speed. */
    private fun command(ctx: Context, a: String, page: Int?): Boolean {
        when (a) {
            "play" -> {
                val p = (page ?: HifzPrefs(ctx).page).coerceIn(1, QuranPages.PAGES)
                HifzPrefs(ctx).page = p
                PrayerPlayer.hifz(ctx, p)
            }
            "pause" -> PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_PAUSE)
            "resume" -> PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_RESUME)
            "speed" -> {
                val q = QuranPrefs(ctx)
                q.speed = Quran.nextSpeed(q.speed)
                if (PrayerPlayer.quranActive) PrayerPlayer.control(ctx, PrayerPlayer.ACTION_Q_SPEED_SET)
            }
            else -> return false
        }
        return true
    }
}
