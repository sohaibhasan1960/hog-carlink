package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import java.util.concurrent.Executor

/**
 * HOG CarLink: полноэкранный диалог с камерой, читает один QR-код и отдаёт его текст. Camera2 из фреймворка плюс
 * ZXing core (чистая Java, единственная внешняя зависимость приложения): без CameraX и ML Kit, чтобы не тянуть AndroidX.
 * Кадры идут в ImageReader (YUV), декодируется только яркостная плоскость; превью в TextureView по центру с обрезкой.
 * Разрешение CAMERA спрашивает вызывающий экран ([granted], [PERMISSION]); диалог закрыть при уходе с экрана ([dismiss]).
 */
class QrScan(
    private val ctx: Context,
    private val title: String,
    private val hint: String,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var texture: TextureView? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var size: Size? = null
    private var sensorOrientation = 90
    private val qr = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))

    @Volatile private var done = false
    @Volatile private var busy = false

    fun show() {
        val c = ctx
        val root = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        val tv = TextureView(c)
        texture = tv
        root.addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(
            View(c).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(c.dp(3), Color.WHITE)
                    cornerRadius = c.dp(16).toFloat()
                }
            },
            FrameLayout.LayoutParams(c.dp(240), c.dp(240), Gravity.CENTER),
        )
        val top = c.vbox(4).apply {
            setBackgroundColor(0x99000000.toInt())
            pad(c, 20, 24, 20, 16)
        }
        top.addView(TextView(c).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = c.sans(500)
        })
        top.addView(TextView(c).apply {
            text = hint
            textSize = 14f
            setTextColor(0xFFDDDDDD.toInt())
            typeface = c.sans(400)
        })
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(
            TextView(c).apply {
                text = c.getString(android.R.string.cancel)
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = c.sans(500)
                gravity = Gravity.CENTER
                background = c.shape(0x66000000, 14, Color.WHITE, 1)
                pad(c, 28, 0, 28, 0)
                minHeight = c.dp(52)
                setOnClickListener { dismiss() }
            },
            FrameLayout.LayoutParams(WRAP_CONTENT, c.dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = c.dp(40) },
        )
        val d = Dialog(c, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        d.setContentView(root)
        d.setCancelable(true)
        d.setOnDismissListener { close() }
        dialog = d
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = open(st, w, h)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = transform(w, h)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        d.show()
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        dialog = null
    }

    private fun fail(why: String) {
        AppLog.i("QR: камера недоступна: $why")
        main.post {
            if (!done) {
                done = true
                dismiss()
                onError(why)
            }
        }
    }

    private fun open(st: SurfaceTexture, vw: Int, vh: Int) {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return fail("no CameraManager")
        val t = HandlerThread("qr-camera").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        try {
            val ids = cm.cameraIdList
            val id = ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: ids.firstOrNull() ?: return fail("no camera")
            val ch = cm.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("no stream map")
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val tex = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
            val both = yuv.filter { it in tex }.ifEmpty { yuv }
            val s = both.filter { it.width * it.height >= MIN_PIXELS }.minByOrNull { it.width * it.height }
                ?: both.maxByOrNull { it.width * it.height } ?: return fail("no YUV size")
            size = s
            st.setDefaultBufferSize(s.width, s.height)
            main.post { transform(vw, vh) }
            val r = ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ frame(it) }, h)
            reader = r
            AppLog.i("QR: камера $id, ${s.width}x${s.height}, сенсор $sensorOrientation°")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    camera = dev
                    start(dev, Surface(st), r.surface, h)
                }

                override fun onDisconnected(dev: CameraDevice) {
                    dev.close()
                    if (camera === dev) camera = null
                }

                override fun onError(dev: CameraDevice, error: Int) {
                    dev.close()
                    if (camera === dev) camera = null
                    fail("open error $error")
                }
            }, h)
        } catch (e: SecurityException) {
            fail("no permission")
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun start(dev: CameraDevice, preview: Surface, frames: Surface, h: Handler) {
        try {
            val exec = Executor { h.post(it) }
            val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(frames))
            dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(frames)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        s.setRepeatingRequest(req.build(), null, h)
                    } catch (e: Exception) {
                        fail("request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = fail("session configure failed")
            }))
        } catch (e: Exception) {
            fail("session: ${e.message}")
        }
    }

    /** Один кадр: Y-плоскость -> ZXing. Пока идёт декодирование, следующие кадры пропускаем (acquireLatestImage их выбрасывает). */
    private fun frame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (done || busy) return
            busy = true
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val buf = plane.buffer
            val rs = plane.rowStride
            val data = ByteArray(w * h)
            if (rs == w) {
                buf.get(data, 0, minOf(data.size, buf.remaining()))
            } else {
                for (y in 0 until h) {
                    buf.position(y * rs)
                    buf.get(data, y * w, minOf(w, buf.remaining()))
                }
            }
            val src = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val result = try {
                qr.decode(BinaryBitmap(HybridBinarizer(src)), hints)
            } catch (_: ReaderException) {
                null
            } finally {
                qr.reset()
            }
            if (result != null && !done) {
                done = true
                val text = result.text
                AppLog.i("QR: прочитан, ${text.length} симв.")
                main.post {
                    dismiss()
                    onText(text)
                }
            }
        } catch (e: Exception) {
            AppLog.i("QR: кадр: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            img.close()
            busy = false
        }
    }

    /** Превью по центру с обрезкой: буфер камеры повёрнут относительно экрана на угол сенсора. */
    private fun transform(vw: Int, vh: Int) {
        val tv = texture ?: return
        val s = size ?: return
        if (vw == 0 || vh == 0) return
        val rotation = tv.display?.rotation ?: Surface.ROTATION_0
        val landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val swap = (sensorOrientation % 180 != 0) != landscape
        val bw = if (swap) s.height else s.width
        val bh = if (swap) s.width else s.height
        val scale = maxOf(vw.toFloat() / bw, vh.toFloat() / bh)
        val m = Matrix()
        val cx = vw / 2f
        val cy = vh / 2f
        if (landscape) {
            val view = RectF(0f, 0f, vw.toFloat(), vh.toFloat())
            val buffer = RectF(0f, 0f, vh.toFloat(), vw.toFloat())
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY())
            m.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
            m.postRotate(if (rotation == Surface.ROTATION_90) 270f else 90f, cx, cy)
        }
        m.postScale(bw * scale / vw, bh * scale / vh, cx, cy)
        tv.setTransform(m)
    }

    private fun close() {
        done = true
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            camera?.close()
        } catch (_: Exception) {
        }
        camera = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
        texture = null
    }

    companion object {
        const val PERMISSION = Manifest.permission.CAMERA

        /** Меньше - QR с экрана машины через полсалона не читается; больше - декодирование тормозит. */
        private const val MIN_PIXELS = 640 * 480

        fun granted(ctx: Context): Boolean = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        fun hasCamera(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}
