package art.phoenix.platebase

/**
 * OCR image preprocessing — pure logic on grayscale pixel arrays
 * (JVM-tested). Ben's pipeline used Otsu thresholding before
 * Tesseract; this is that step, kept Android-free so the math is
 * testable off-device. The Camera glue converts YUV frames / bitmaps
 * to the IntArray luminance form this object works on.
 */
object OcrPreprocess {

    /** Otsu's method: the threshold that maximizes between-class
     *  variance of the 256-bin luminance histogram. */
    fun otsuThreshold(gray: IntArray, width: Int, height: Int): Int {
        val hist = IntArray(256)
        val n = width * height
        if (n == 0) return 128
        for (i in 0 until n) hist[gray[i] and 0xFF]++
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var threshold = 128
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += i * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                threshold = i
            }
        }
        return threshold
    }

    /** Binarize in place: pixels at/above the threshold become white
     *  (255), below become black (0). Returns the threshold used. */
    fun binarize(gray: IntArray, width: Int, height: Int): Int {
        val t = otsuThreshold(gray, width, height)
        for (i in gray.indices) {
            gray[i] = if ((gray[i] and 0xFF) > t) 255 else 0
        }
        return t
    }

    /** Rotate a grayscale image by [degreesClockwise] (0/90/180/270)
     *  — v4.1: camera frames arrive in sensor orientation, and
     *  Tesseract cannot read sideways text, so live frames are
     *  rotated upright before recognition. Returns
     *  (pixels, width, height). */
    fun rotate(
        gray: IntArray, width: Int, height: Int, degreesClockwise: Int
    ): Triple<IntArray, Int, Int> {
        val deg = ((degreesClockwise % 360) + 360) % 360
        return when (deg) {
            0 -> Triple(gray, width, height)
            180 -> {
                val out = IntArray(gray.size)
                for (i in gray.indices) out[gray.size - 1 - i] = gray[i]
                Triple(out, width, height)
            }
            90 -> {
                // (x, y) -> (height - 1 - y, x); new dims swap.
                val out = IntArray(gray.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[x * height + (height - 1 - y)] = gray[y * width + x]
                    }
                }
                Triple(out, height, width)
            }
            270 -> {
                // (x, y) -> (y, width - 1 - x); new dims swap.
                val out = IntArray(gray.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = gray[y * width + x]
                    }
                }
                Triple(out, height, width)
            }
            else -> Triple(gray, width, height)
        }
    }

    /** Nearest-neighbour 2x upscale — v4.1: plate glyphs in a
     *  center-cropped live frame are ~10–20 px tall, below the size
     *  Tesseract reads reliably; doubling the crop before
     *  recognition measurably helps and costs little at crop size.
     *  Returns (pixels, width, height). */
    fun upscale2x(
        gray: IntArray, width: Int, height: Int
    ): Triple<IntArray, Int, Int> {
        val nw = width * 2
        val nh = height * 2
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val srcRow = (y / 2) * width
            val dstRow = y * nw
            for (x in 0 until nw) {
                out[dstRow + x] = gray[srcRow + x / 2]
            }
        }
        return Triple(out, nw, nh)
    }

    /** Crop to a centered sub-rectangle (fraction of width/height),
     *  used to aim live OCR at the middle of the preview where a
     *  plate is being framed. Returns (pixels, width, height). */
    fun centerCrop(
        gray: IntArray, width: Int, height: Int,
        widthFraction: Double, heightFraction: Double
    ): Triple<IntArray, Int, Int> {
        val cw = maxOf(1, (width * widthFraction).toInt())
        val ch = maxOf(1, (height * heightFraction).toInt())
        val x0 = (width - cw) / 2
        val y0 = (height - ch) / 2
        val out = IntArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(gray, (y0 + y) * width + x0, out, y * cw, cw)
        }
        return Triple(out, cw, ch)
    }
}
