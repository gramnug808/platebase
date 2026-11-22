package art.phoenix.platebase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.internal.TextRegistrar
import com.google.mlkit.vision.common.internal.VisionCommonRegistrar
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * PlateBase OCR — ML Kit Text Recognition v2 (16.0.1, bundled model),
 * on-device, no download, no network. ML Kit only — Tesseract is NOT
 * included and NOT claimed as a fallback (native Init() returned false
 * for every OEM mode/datapath variant on this device in Wardrive
 * v1.8-v1.11 and ALPR v1.0/v1.1).
 *
 * ML Kit init (the fix from Wardrive v1.9 / ALPR v1.1): this is a
 * manual aapt2 build with no Gradle manifest merging, so the ML Kit
 * manifest discovery service does not exist. MlKitContext MUST be
 * initialised with EXPLICIT registrars (Common + Text + VisionCommon)
 * before TextRecognition.getClient(), or getClient fails with
 * "MlKitContext has not been initialized".
 *
 * Pipeline (crop-first, per the build spec): the Y-plane crop is
 * extracted BEFORE any per-pixel work — a centred crop of the
 * rotated frame (80% wide x 50% tall) is cut straight out of the
 * Y plane, rotated upright if needed, Otsu-binarized, upscaled 2x,
 * then fed to ML Kit. The full 1280x720 frame is never run through
 * a per-pixel loop un-cropped.
 */
class MlKitOcr(private val ctx: Context) {
    @Volatile var ready: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var diagnostics: String = "not initialised yet"
        private set

    private var recognizer: TextRecognizer? = null

    fun init(): Boolean {
        return try {
            initInner()
        } catch (t: Throwable) {
            lastError = "OCR init crashed: ${t.javaClass.simpleName}: ${t.message}"
            diagnostics = lastError ?: "OCR init crashed"
            ready = false
            false
        }
    }

    private fun initInner(): Boolean {
        var mlkitInit = "not attempted"
        val registrars = mutableListOf<com.google.firebase.components.ComponentRegistrar>()
        for ((name, build) in listOf(
            "common" to { CommonComponentRegistrar() as com.google.firebase.components.ComponentRegistrar },
            "text" to { TextRegistrar() as com.google.firebase.components.ComponentRegistrar },
            "vision" to { VisionCommonRegistrar() as com.google.firebase.components.ComponentRegistrar }
        )) {
            try { registrars.add(build()) } catch (t: Throwable) {
                mlkitInit = "registrar $name failed: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        try {
            if (registrars.isNotEmpty()) {
                MlKitContext.initializeIfNeeded(ctx.applicationContext, registrars)
                mlkitInit = "MlKitContext initialised (explicit registrars: ${registrars.size}/3 built)"
            } else {
                MlKitContext.initializeIfNeeded(ctx.applicationContext)
                mlkitInit = "MlKitContext initialised (no-arg, no registrars built)"
            }
        } catch (t: Throwable) {
            mlkitInit = "explicit-registrar init failed: ${t.javaClass.simpleName}: ${t.message}"
            try {
                MlKitContext.initializeIfNeeded(ctx.applicationContext)
                mlkitInit += " | no-arg init OK"
            } catch (t2: Throwable) {
                mlkitInit += " | no-arg init failed: ${t2.javaClass.simpleName}: ${t2.message}"
            }
        }
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (t: Throwable) {
            lastError = "ML Kit client creation failed: ${t.javaClass.simpleName}: ${t.message} | init: $mlkitInit"
            diagnostics = lastError ?: "client creation failed"
            ready = false
            return false
        }
        ready = recognizer != null
        diagnostics = if (ready) "ML Kit ready — bundled model, on-device, offline | $mlkitInit"
            else "ML Kit client null after init: $mlkitInit"
        if (!ready) lastError = diagnostics
        return ready
    }

    fun close() {
        try { recognizer?.close() } catch (_: Throwable) { }
        recognizer = null; ready = false
    }

    private fun runOcr(bmp: Bitmap): String {
        val client = recognizer ?: return ""
        return try {
            Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)), 15, TimeUnit.SECONDS).text ?: ""
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            ""
        }
    }

    /** Build the ML Kit bitmap from a cropped grayscale array: Otsu-binarize, 2x upscale. */
    private fun bitmapFromCrop(gray: IntArray, w: Int, h: Int): Bitmap {
        OcrPreprocess.binarize(gray, w, h)
        val (up, uw, uh) = OcrPreprocess.upscale2x(gray, w, h)
        val bmp = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
        val px = IntArray(uw * uh)
        for (i in up.indices) { val v = up[i]; px[i] = -0x1000000 or (v shl 16) or (v shl 8) or v }
        bmp.setPixels(px, 0, uw, 0, 0, uw, uh)
        return bmp
    }

    /**
     * OCR straight from a YUV luminance plane. Crop-first: the centred
     * crop of the ROTATED frame is cut from the Y plane (for a 90/270
     * rotation the crop dims swap, because the centre of the rotated
     * frame maps to a centred rectangle in sensor coords), rotated
     * upright, binarized, upscaled, then recognised. Returns plates
     * found, strongest first.
     */
    @Synchronized
    fun recognizeFromLuminance(
        yPlane: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int = 0
    ): List<String> {
        if (!ready) return emptyList()
        val rot = ((rotationDegrees % 360) + 360) % 360
        // Crop size in rotated-frame coords: 80% wide, 50% tall.
        val rotW = if (rot == 90 || rot == 270) height else width
        val rotH = if (rot == 90 || rot == 270) width else height
        val cropRotW = (rotW * 0.8).toInt().coerceAtLeast(1)
        val cropRotH = (rotH * 0.5).toInt().coerceAtLeast(1)
        // Same centred rectangle in sensor coords (dims swap for 90/270).
        val cropW = if (rot == 90 || rot == 270) cropRotH else cropRotW
        val cropH = if (rot == 90 || rot == 270) cropRotW else cropRotH
        val x0 = (width - cropW) / 2
        val y0 = (height - cropH) / 2
        val gray = IntArray(cropW * cropH)
        for (y in 0 until cropH) {
            val srcRow = (y0 + y) * rowStride + x0
            for (x in 0 until cropW) gray[y * cropW + x] = yPlane[srcRow + x].toInt() and 0xFF
        }
        var g = gray; var gw = cropW; var gh = cropH
        if (rot != 0) {
            val (r, rw, rh) = OcrPreprocess.rotate(g, gw, gh, rot)
            g = r; gw = rw; gh = rh
        }
        val bmp = bitmapFromCrop(g, gw, gh)
        return try { PlateLogic.find(runOcr(bmp)) } finally { bmp.recycle() }
    }

    /** OCR a bitmap (self-test path): grayscale, Otsu-binarize, 2x upscale, recognise. */
    @Synchronized
    fun recognizeBitmap(src: Bitmap): List<String> {
        if (!ready) return emptyList()
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt().coerceIn(0, 255)
        }
        val bmp = bitmapFromCrop(gray, w, h)
        return try { PlateLogic.find(runOcr(bmp)) } finally { bmp.recycle() }
    }

    /** On-device self-check: OCR the bundled test plates. */
    @Synchronized
    fun selfTest(): String {
        if (!ready) return "FAILED — engine did not initialize (${lastError ?: "unknown"})"
        val results = ArrayList<String>()
        for (name in listOf("ABC123", "123ABC")) {
            val bmp = try {
                ctx.assets.open("test-plates/$name.png").use { BitmapFactory.decodeStream(it) }
            } catch (t: Throwable) { null }
            if (bmp == null) results.add("$name: test plate unreadable")
            else {
                val found = recognizeBitmap(bmp)
                bmp.recycle()
                results.add(if (found.any { it.equals(name, ignoreCase = true) }) "$name: OK"
                    else "$name: read ${if (found.isEmpty()) "nothing" else found.joinToString()}")
            }
        }
        return "self-test — " + results.joinToString("; ")
    }
}
