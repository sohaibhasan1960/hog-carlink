package com.yegorkapomidorka.headswap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface

/**
 * H.264-энкодер (Surface input) и рисовалка заглушки моста (текст, пока AA не подключился).
 * Кадры отдаются в Annex-B с 4-байтными стартовыми кодами; codec config приходит отдельно (onConfig).
 */
class VideoSource(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(msg: String, t: Throwable?)
    }

    @Volatile var framesRendered = 0L
        private set

    /** Текст заглушки: крупная надпись и живой индикатор вместо видео AA. */
    @Volatile var banner: String? = null

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var renderThread: Thread? = null
    var encoderName: String = "?"
        private set

    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var s: Surface? = null
        try {
            var fmt = format(withExtras = true)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                AppLog.e("configure с profile/CBR не прошёл, пробую минимальный формат", e)
                c.reset()
                fmt = format(withExtras = false)
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            s = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // codec ещё null: stop() вышел бы сразу, аппаратный энкодер освобождаем здесь
            try {
                s?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = s
        codec = c
        running = true
        drainThread = Thread({ drainLoop(c) }, "video-enc-drain").also { it.start() }
        renderThread = Thread({ renderLoop() }, "video-render").also { it.start() }
    }

    private fun format(withExtras: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (withExtras) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        return f
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            AppLog.e("requestKeyFrame", e)
        }
    }

    /** Битрейт на лету (воздух: политика битрейта ведёт и заглушку). false = кодека нет или параметр не принят. */
    fun setBitrate(bps: Int): Boolean {
        val c = codec ?: return false
        return try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            true
        } catch (e: Exception) {
            AppLog.e("битрейт заглушки", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running && codec == null) return
        running = false
        renderThread?.join(1000)
        drainThread?.join(1000)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        codec = null
        surface = null
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val arr = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(arr)
                            val data = AnnexB.normalize(arr)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                listener.onConfig(data)
                            } else {
                                listener.onFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        AppLog.i("энкодер: формат вывода $encoderName: ${c.outputFormat}", ui = false)
                }
            }
        } catch (e: Exception) {
            if (running) listener.onError("drain энкодера", e)
        }
    }

    private fun renderLoop() {
        val s = surface ?: return
        val frameMs = 1000L / fps
        var next = SystemClock.elapsedRealtime()
        try {
            while (running) {
                // программный Canvas: lockHardwareCanvas на Surface энкодера валит media.codec на Xiaomi с vndk v30
                // (Redmi 9T/9S, POCO X3 Pro: SIGSEGV в FenceTime::Snapshot::getFlattenedSize, за ним abort нашего процесса)
                val canvas = s.lockCanvas(null)
                try {
                    draw(canvas)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
                framesRendered++
                next += frameMs
                val d = next - SystemClock.elapsedRealtime()
                if (d > 0) Thread.sleep(d) else next = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (running) listener.onError("рендер на Surface энкодера", e)
        }
    }

    /** Текущий кадр заглушки в Bitmap: с него транскодер начинает зум-растворение в картинку AA. Любая нить. */
    fun snapshot(): Bitmap? {
        val msg = banner ?: return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawBanner(Canvas(bmp), width, height, msg, framesRendered, Paint(text), Paint(fill))
        return bmp
    }

    private fun drawBanner(c: Canvas, msg: String) = drawBanner(c, width, height, msg, framesRendered, text, fill)

    /**
     * HOG CarLink: заглушка как в макете (canvas «تصميم الشاشات»): слева тёмная панель со знаком HOG и CarLink,
     * справа заголовок крупно и строки пояснения/шагов. [msg]: первая строка - заголовок, остальные (через \n) - тело.
     * Арабский текст выравнивается по правому краю, латиница/кириллица - по левому. Плоские цвета: CBR даёт полосы на градиентах.
     */
    private fun drawBanner(c: Canvas, width: Int, height: Int, msg: String, n: Long, text: Paint, fill: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(HOG_GREEN)
        val panelW = w * 0.33f
        fill.style = Paint.Style.FILL
        fill.color = HOG_PANEL
        c.drawRect(0f, 0f, panelW, h, fill)
        drawHog(c, panelW / 2, h * 0.43f, minOf(h * 0.24f, panelW * 0.45f), fill)
        text.typeface = WORDMARK
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = h * 0.06f
        c.drawText("CarLink", panelW / 2, h * 0.43f + h * 0.24f * 0.75f, text)

        val parts = msg.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val rtl = parts.any { line -> line.any { it in '\u0600'..'\u06FF' } }
        val pad = w * 0.05f
        val left = panelW + pad
        val right = w - pad
        val maxW = right - left
        text.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val x = if (rtl) right else left

        // разметка: подпись, заголовок (до 2 строк), тело
        text.typeface = Typeface.DEFAULT_BOLD
        val kicker = h * 0.04f
        val titleSize = h * 0.08f
        val bodySize = h * 0.046f
        text.textSize = titleSize
        val titleLines = wrapBalanced(parts[0], maxW, text).take(2)
        text.textSize = bodySize
        val bodyLines = parts.drop(1).flatMap { wrapBalanced(it, maxW, text) }
        val total = kicker * 1.8f + titleLines.size * titleSize * 1.25f + (if (bodyLines.isEmpty()) 0f else bodySize * 0.6f) +
            bodyLines.size * bodySize * 1.55f
        var y = (h - total) / 2 + kicker

        text.textSize = kicker
        text.color = HOG_GOLD
        c.drawText("HOG CarLink", x, y, text)
        y += kicker * 0.8f

        text.textSize = titleSize
        text.color = Color.WHITE
        for (l in titleLines) {
            y += titleSize * 1.25f
            c.drawText(l, x, y, text)
        }
        if (bodyLines.isNotEmpty()) y += bodySize * 0.6f
        text.typeface = Typeface.DEFAULT
        text.textSize = bodySize
        text.color = BODY
        for (l in bodyLines) {
            y += bodySize * 1.55f
            c.drawText(l, x, y, text)
        }
    }

    /**
     * Перенос по словам на минимум строк, но строки примерно равной ширины:
     * на 4:3 «...перезапустите сервер ГУ» иначе оставляет «ГУ» одно на второй строке.
     */
    private fun wrapBalanced(msg: String, maxW: Float, p: Paint): List<String> {
        val words = msg.split(' ')
        fun greedy(limit: Float): List<String> {
            val lines = ArrayList<String>()
            var cur = ""
            for (word in words) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(t) > limit && cur.isNotEmpty()) {
                    lines.add(cur)
                    cur = word
                } else {
                    cur = t
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            return lines
        }
        val base = greedy(maxW)
        if (base.size < 2) return base
        // сужаем лимит, пока число строк не растёт: последняя строка добирает слова у предыдущих
        var best = base
        var limit = p.measureText(msg) / base.size
        while (limit < maxW) {
            val l = greedy(limit)
            if (l.size == base.size) {
                best = l
                break
            }
            limit += maxW * 0.02f
        }
        return best
    }

    /**
     * HOG CarLink: логотип HOG как в ic_launcher_foreground.xml (единицы холста 108: буквы x 25..83, y 41..61, черта до 67.5),
     * [size] - высота всего знака с чертой, центр в ([cx], [cy]). Плоские цвета: слабый энкодер не любит градиенты.
     */
    private fun drawHog(c: Canvas, cx: Float, cy: Float, size: Float, p: Paint) {
        val k = size / 26.5f
        c.save()
        c.translate(cx - 54f * k, cy - 54.25f * k)
        c.scale(k, k)
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        // H
        c.drawRect(25f, 41f, 29.5f, 61f, p)
        c.drawRect(35.5f, 41f, 40f, 61f, p)
        c.drawRect(29.5f, 48.75f, 35.5f, 53.25f, p)
        // O: кольцо толщиной 4.5
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4.5f
        c.drawOval(RectF(45.25f, 43.25f, 59.75f, 58.75f), p)
        // G: дуга от -45° против часовой до +10° и перекладина
        c.drawArc(RectF(67.25f, 43.25f, 80.75f, 58.75f), -45f, -305f, false, p)
        p.style = Paint.Style.FILL
        c.drawRect(74.5f, 49.5f, 83f, 54f, p)
        // золотая черта
        p.color = HOG_GOLD
        c.drawRoundRect(RectF(40f, 65f, 68f, 67.5f), 1.25f, 1.25f, p)
        c.restore()
    }

    /**
     * Глиф иконки (ic_launcher_foreground.xml, 48×48): дуги-стрелки раз в [SWAP_S] меняются местами
     * поворотом на 180° вокруг экрана, на каждом обмене от логотипа расходится янтарное кольцо.
     * Состояние только от t: snapshot() с любой нити рисует тот же кадр.
     */
    private fun drawLogo(c: Canvas, cx: Float, cy: Float, size: Float, t: Float, p: Paint) {
        val phase = t % SWAP_S
        val k = (phase / SPIN_S).coerceAtMost(1f)
        val ease = k * k * (3 - 2 * k)
        val angle = (t / SWAP_S).toInt() * 180f + ease * 180f

        val s = size / 48f
        c.save()
        c.translate(cx - 24f * s, cy - 24f * s)
        c.scale(s, s)

        // волна обмена: кольцо от радиуса дуг наружу, тает к концу цикла
        val r = phase / SWAP_S
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = AMBER
        p.alpha = ((1 - r) * 110).toInt()
        c.drawCircle(24f, 24f, 15f + r * 9f, p)
        p.alpha = 255

        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        c.save()
        c.rotate(angle, 24f, 24f)
        drawArrow(c, ARC_WHITE, HEAD_WHITE, Color.WHITE, p)
        drawArrow(c, ARC_AMBER, HEAD_AMBER, AMBER, p)
        c.restore()

        // экран в центре неподвижен
        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeWidth = 2f
        c.drawRoundRect(16.5f, 19.25f, 31.5f, 29.25f, 2.2f, 2.2f, p)
        p.color = AMBER
        p.strokeWidth = 1.8f
        c.drawLine(19.6f, 25.4f, 28.4f, 25.4f, p)
        c.restore()
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL
    }

    private fun drawArrow(c: Canvas, arc: Path, head: Path, color: Int, p: Paint) {
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.2f
        c.drawPath(arc, p)
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = 1f
        c.drawPath(head, p)
    }

    private fun draw(c: Canvas) {
        drawBanner(c, banner ?: "")
    }

    companion object {
        const val MIME = "video/avc"

        private val BG = Color.rgb(0x0E, 0x14, 0x1A) // фон иконки
        private val HOG_GREEN = Color.rgb(0x00, 0x3B, 0x1F)
        private val HOG_GOLD = Color.rgb(0xBB, 0x90, 0x32)
        private val HOG_PANEL = Color.rgb(0x00, 0x30, 0x1A)
        private val BODY = Color.rgb(0xCF, 0xE3, 0xD6)
        private val AMBER = Color.rgb(0xF2, 0xB3, 0x3D)
        private val STATUS = Color.rgb(0xB8, 0xC4, 0xCC)
        private val WORDMARK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

        /** Цикл обмена стрелок и длительность самого поворота внутри него, секунды. */
        private const val SWAP_S = 2.2f
        private const val SPIN_S = 0.8f

        // дуги — окружность r=15 вокруг (24,24): 195°..318° и её отражение 15°..138°
        private val ARC_WHITE = Path().apply { addArc(RectF(9f, 9f, 39f, 39f), 195f, 123f) }
        private val ARC_AMBER = Path().apply { addArc(RectF(9f, 9f, 39f, 39f), 15f, 123f) }
        private val HEAD_WHITE = triangle(39.24f, 10.28f, 40.5f, 19.9f, 31.06f, 17.64f)
        private val HEAD_AMBER = triangle(8.76f, 37.72f, 7.5f, 28.1f, 16.94f, 30.36f)

        private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x3, y3)
            close()
        }
    }
}
