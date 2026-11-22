package art.phoenix.platebase

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 controller, activity-owned (no service in v1 — screen-off
 * is out of scope). The ACTIVITY's TextureView owns the preview
 * surface (PocketFlocker v2.0 lesson: a service-owned
 * SurfaceTexture(0) never renders — the preview was black).
 *
 * Session: preview + YUV (1280x720) only. If the device refuses the
 * 2-surface session, fall back to YUV alone and report "preview
 * unavailable" on the status line — OCR keeps running.
 */
class CameraController(
    private val ctx: Context,
    private val textureView: TextureView,
    private val ocr: MlKitOcr,
    private val listener: Listener
) {
    interface Listener {
        fun onLivePlates(plates: List<String>)
        fun onCameraError(message: String)
        fun onInfo(message: String)
        fun onCameraReady()
    }

    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sensorOrientation = 0
    private var sensorRect: Rect? = null
    private var zoomFactor = 1.0f
    private var torchOn = false
    private var previewSize = Size(1280, 720)
    private var yuvReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var sessionSurfaces: List<Surface> = emptyList()
    private var previewBuilder: CaptureRequest.Builder? = null
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val ocrBusy = AtomicBoolean(false)
    @Volatile private var lastOcrMs = 0L
    @Volatile var liveOcrEnabled = true

    @SuppressLint("MissingPermission")
    fun open() {
        bgThread = HandlerThread("platebase-cam").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
        try {
            val id = cameraManager.cameraIdList.firstOrNull { cid ->
                cameraManager.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
                ?: run { listener.onCameraError("No camera found"); return }
            val chars = cameraManager.getCameraCharacteristics(id)
            sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val yuvSize = yuvSizes?.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: yuvSizes?.filter { it.width <= 1920 }?.maxByOrNull { it.width * it.height }
                ?: Size(640, 480)
            previewSize = yuvSize
            yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try { maybeLiveOcr(image) } finally { image.close() }
                }, bgHandler)
            }
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; createSession(attempt = 0) }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    listener.onCameraError("Camera error $error")
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            listener.onCameraError("Camera permission missing")
        } catch (e: Exception) {
            listener.onCameraError("Camera open failed: ${e.message}")
        }
    }

    private fun createSession(attempt: Int) {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: run {
            // TextureView not ready yet — retry with YUV alone so OCR can start.
            createSessionYuvOnly(device)
            return
        }
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)
        val yuvSurface = yuvReader?.surface
        val combos: List<List<Surface>> = if (yuvSurface != null) {
            listOf(listOf(previewSurface, yuvSurface), listOf(yuvSurface))
        } else listOf(listOf(previewSurface))
        if (attempt >= combos.size) {
            listener.onCameraError("Camera session could not be configured")
            return
        }
        val surfaces = combos[attempt]
        if (attempt > 0) listener.onInfo("Preview unavailable on this device — OCR still running (YUV only)")
        try {
            session?.close()
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    sessionSurfaces = surfaces
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        for (surface in surfaces) builder.addTarget(surface)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        previewBuilder = builder
                        applyZoomAndTorch(builder)
                        s.setRepeatingRequest(builder.build(), null, bgHandler)
                        listener.onCameraReady()
                    } catch (e: Exception) {
                        listener.onCameraError("Preview failed: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { createSession(attempt + 1) }
            }, bgHandler)
        } catch (e: Exception) { createSession(attempt + 1) }
    }

    private fun createSessionYuvOnly(device: CameraDevice) {
        val yuvSurface = yuvReader?.surface ?: return
        try {
            session?.close()
            device.createCaptureSession(listOf(yuvSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(yuvSurface)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        s.setRepeatingRequest(builder.build(), null, bgHandler)
                        listener.onCameraReady()
                    } catch (e: Exception) { listener.onCameraError("Preview failed: ${e.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { listener.onCameraError("Camera session could not be configured") }
            }, bgHandler)
        } catch (e: Exception) { listener.onCameraError("Camera session failed: ${e.message}") }
    }

    private fun applyZoomAndTorch(builder: CaptureRequest.Builder) {
        sensorRect?.let { rect ->
            if (zoomFactor > 1.0f) {
                val cropW = (rect.width() / zoomFactor).toInt()
                val cropH = (rect.height() / zoomFactor).toInt()
                val left = rect.left + (rect.width() - cropW) / 2
                val top = rect.top + (rect.height() - cropH) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
            }
        }
        builder.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    fun setZoomFactor(f: Float) {
        zoomFactor = f.coerceIn(1.0f, 8.0f)
        rebuildRequest()
    }

    fun pinchZoom(scaleFactor: Float) = setZoomFactor(zoomFactor * scaleFactor)

    fun toggleTorch(): Boolean {
        torchOn = !torchOn
        rebuildRequest()
        return torchOn
    }

    private fun rebuildRequest() {
        val b = previewBuilder ?: return
        val s = session ?: return
        try {
            applyZoomAndTorch(b)
            s.setRepeatingRequest(b.build(), null, bgHandler)
        } catch (_: Exception) { }
    }

    private fun maybeLiveOcr(image: android.media.Image) {
        if (!liveOcrEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastOcrMs < 1000L) return
        if (!ocrBusy.compareAndSet(false, true)) return
        lastOcrMs = now
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val rotation = relativeRotationDegrees()
        ocrExecutor.execute {
            try {
                val plates = ocr.recognizeFromLuminance(bytes, width, height, rowStride, rotation)
                if (plates.isNotEmpty()) listener.onLivePlates(plates)
            } catch (t: Throwable) {
                listener.onInfo("live OCR: ${t.javaClass.simpleName}: ${t.message}")
            } finally { ocrBusy.set(false) }
        }
    }

    private fun relativeRotationDegrees(): Int {
        val displayDegrees = try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            when (dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    fun close() {
        try { session?.close() } catch (_: Exception) { }
        try { cameraDevice?.close() } catch (_: Exception) { }
        try { yuvReader?.close() } catch (_: Exception) { }
        ocrExecutor.shutdownNow()
        try { bgThread?.quitSafely(); bgThread?.join() } catch (_: Exception) { }
        bgThread = null; bgHandler = null
        cameraDevice = null; session = null
    }

    companion object {
        const val OCR_INTERVAL_MS = 1000L
    }
}
