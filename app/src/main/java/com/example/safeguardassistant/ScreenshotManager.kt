package com.example.safeguardassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Uma captura por pedido: cria VirtualDisplay, espera pelo primeiro frame válido, liberta recursos.
 *
 * Notas de robustez:
 * - Em muitos devices, o primeiro frame chega apenas via callback [ImageReader.OnImageAvailableListener].
 * - Largura/altura pares evitam problemas em alguns encoders/drivers.
 * - Largura do [Bitmap] alinha com `rowStride / pixelStride` antes de [Bitmap.copyPixelsFromBuffer].
 */
object ScreenshotManager {

    private const val TAG = "ScreenshotManager"
    private const val JPEG_QUALITY = 72
    private const val MAX_CAPTURE_DIMENSION = 960
    private const val CAPTURE_TIMEOUT_MS = 3500L

    /**
     * Captura o ecrã como [Bitmap] em coordenadas do frame (útil p.ex. com ML Kit OCR).
     * @param maxDisplayLongestSide Se > 0, reduz o ecrã para encaixar o maior lado; se 0, usa
     * a resolução do ecrã (apenas ajusta para dimensões pares) — preferível para leitura de texto.
     */
    fun captureScreenBitmap(
        context: Context,
        projection: MediaProjection,
        maxDisplayLongestSide: Int = 0,
    ): Bitmap? = captureFrameToBitmap(context, projection, maxDisplayLongestSide)

    fun captureJpegBase64(context: Context, projection: MediaProjection): String? {
        val bitmap = captureFrameToBitmap(context, projection, MAX_CAPTURE_DIMENSION) ?: return null
        return try {
            val baos = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) {
                Log.e(TAG, "JPEG compress failed")
                null
            } else {
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureFrameToBitmap(
        context: Context,
        projection: MediaProjection,
        maxDisplayLongestSide: Int,
    ): Bitmap? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val density = metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        if (w <= 0 || h <= 0) {
            Log.e(TAG, "invalid display size $w x $h")
            return null
        }

        if (maxDisplayLongestSide > 0) {
            val maxSide = maxOf(w, h)
            if (maxSide > maxDisplayLongestSide) {
                val scale = maxDisplayLongestSide.toFloat() / maxSide
                w = (w * scale).toInt().coerceAtLeast(1)
                h = (h * scale).toInt().coerceAtLeast(1)
            }
        }
        w = evenPositive(w)
        h = evenPositive(h)

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        var virtualDisplay: VirtualDisplay? = null
        val thread = HandlerThread("safeguard_capture").apply { start() }
        val bgHandler = Handler(thread.looper)
        return try {
            virtualDisplay = projection.createVirtualDisplay(
                "safeguard_screenshot",
                w,
                h,
                density,
                /* flags = */ 0,
                imageReader.surface,
                null,
                bgHandler,
            )

            val latch = CountDownLatch(1)
            val captured = AtomicReference<Image?>(null)
            imageReader.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                // Mantém apenas o primeiro frame que chegar; fecha frames extra.
                if (captured.compareAndSet(null, img)) {
                    latch.countDown()
                } else {
                    img.close()
                }
            }, bgHandler)

            val ok = latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val image = captured.get()
            if (!ok || image == null) {
                Log.e(TAG, "timeout waiting image (${w}x${h}) captureOn=${ScreenCaptureHolder.hasProjection()}")
                return null
            }
            try {
                imageToBitmap(image) ?: run {
                    Log.e(TAG, "imageToBitmap returned null")
                    null
                }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            null
        } finally {
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                Log.w(TAG, "release VirtualDisplay", e)
            }
            try {
                imageReader.close()
            } catch (e: Exception) {
                Log.w(TAG, "close ImageReader", e)
            }
            try {
                thread.quitSafely()
            } catch (_: Exception) {
            }
        }
    }

    private fun evenPositive(n: Int): Int {
        val e = (n / 2) * 2
        return when {
            e >= 2 -> e
            n >= 2 -> 2
            else -> 2
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        val planes = image.planes
        if (planes.isEmpty() || width <= 0 || height <= 0) return null
        val plane = planes[0]
        val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 4
        val rowStride = plane.rowStride
        if (rowStride <= 0) return null

        val buffer: ByteBuffer = plane.buffer.duplicate()
        buffer.rewind()

        if (rowStride % pixelStride != 0) {
            Log.w(TAG, "rowStride % pixelStride != 0, using row copy")
            return imageToBitmapRowCopy(width, height, buffer, rowStride, pixelStride)
        }

        val stridePx = rowStride / pixelStride
        val full = Bitmap.createBitmap(stridePx, height, Bitmap.Config.ARGB_8888)
        return try {
            full.copyPixelsFromBuffer(buffer)
            if (stridePx == width) {
                full
            } else {
                val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
                full.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyPixelsFromBuffer failed, row copy", e)
            full.recycle()
            buffer.rewind()
            imageToBitmapRowCopy(width, height, buffer, rowStride, pixelStride)
        }
    }

    /** Fallback quando o buffer não corresponde exactamente ao que [copyPixelsFromBuffer] espera. */
    private fun imageToBitmapRowCopy(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
    ): Bitmap? {
        if (pixelStride < 4) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rowBytes = ByteArray(rowStride)
        val pixels = IntArray(width)
        try {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                val toRead = min(rowStride, buffer.remaining())
                if (toRead <= 0) return null
                buffer.get(rowBytes, 0, toRead)
                for (x in 0 until width) {
                    val i = x * pixelStride
                    if (i + 3 >= rowBytes.size) return null
                    val r = rowBytes[i].toInt() and 0xFF
                    val g = rowBytes[i + 1].toInt() and 0xFF
                    val b = rowBytes[i + 2].toInt() and 0xFF
                    val a = rowBytes[i + 3].toInt() and 0xFF
                    pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "row copy failed", e)
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
