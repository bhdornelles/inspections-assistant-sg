package com.example.safeguardassistant

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Avançar à pergunta / cartão seguinte: *Next* acessível ou por OCR (ícone/texto) e, em último
 * recurso, *swipe* horizontal, verificando se a árvore acessível mudou.
 */
object NextQuestionNavigation {

    private const val TAG = "NextQuestionNavigation"
    const val POST_MOVE_SETTLE_MS = 320L
    private const val VERIFY_MS = 240L

    /**
     * @param fullScreen captura (opcional) — se não for nula, tenta toque por OCR alinhado a "Next"
     * ou fragmentos semelhantes, tipicamente no *chrome* do cartão.
     * @return true se o ecrã mudou após uma das tentativas.
     */
    fun moveToNextQuestion(
        @Suppress("UNUSED_PARAMETER") appContext: Context,
        service: MyAccessibilityService,
        fullScreen: Bitmap?,
    ): Boolean {
        val start = service.snapshotSignature()

        if (tryClicksWithVerify(service, start)) {
            return true
        }
        if (fullScreen != null && !fullScreen.isRecycled) {
            if (tryOcrNextAndTap(service, fullScreen, start)) {
                return true
            }
        }
        if (trySwipeWithVerify(service, start)) {
            return true
        }
        return false
    }

    private val nextClickLabels: List<String> = listOf(
        "Next",
    )

    private fun tryClicksWithVerify(service: MyAccessibilityService, startSig: Int): Boolean {
        for (label in nextClickLabels) {
            if (service.clickByText(label) && didScreenChangeAfter(service, startSig)) {
                Log.d(TAG, "advanced by click: $label")
                return true
            }
        }
        return false
    }

    private fun tryOcrNextAndTap(
        service: MyAccessibilityService,
        bitmap: Bitmap,
        startSig: Int,
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return false
        val lines: List<OcrTextExtractor.OcrTextLine> = OcrTextExtractor.recognizeSync(bitmap).getOrNull()?.first
            ?: return false
        for (line in lines) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            if (!isNextOcrText(t)) continue
            if (line.y < h * 0.04f) continue
            if (line.y + line.height > h * 0.97f) continue
            val (xPct, yPct) = ocrLineCenterToPercent(line, w, h)
            if (service.tapByPercent(xPct, yPct) && didScreenChangeAfter(service, startSig)) {
                Log.d(TAG, "advanced by OCR tap on \"$t\"")
                return true
            }
        }
        return false
    }

    private fun isNextOcrText(t: String): Boolean {
        if (t.equals("Next", ignoreCase = true)) return true
        val u = t.replace("·", " ").lowercase()
        if (u == "next") return true
        return false
    }

    private fun ocrLineCenterToPercent(
        line: OcrTextExtractor.OcrTextLine,
        bitmapW: Int,
        bitmapH: Int,
    ): Pair<Float, Float> {
        val w = bitmapW.coerceAtLeast(1)
        val h0 = bitmapH.coerceAtLeast(1)
        val cx = if (line.width > 0) {
            line.x + line.width / 2f
        } else {
            (w * 0.5f).coerceIn(0f, w - 1f)
        }
        val cy = if (line.height > 0) {
            line.y + line.height / 2f
        } else {
            (line.y + 6f).coerceIn(0f, h0 - 1f)
        }
        return Pair((cx / w) * 100f, (cy / h0) * 100f)
    }

    private fun trySwipeWithVerify(service: MyAccessibilityService, startSig: Int): Boolean {
        if (service.swipeByPercent(86f, 50f, 12f, 50f, 400) && didScreenChangeAfter(service, startSig)) {
            Log.d(TAG, "advanced by horizontal swipe (R→L)")
            return true
        }
        if (service.swipeByPercent(10f, 50f, 90f, 50f, 400) && didScreenChangeAfter(service, startSig)) {
            Log.d(TAG, "advanced by horizontal swipe (L→R)")
            return true
        }
        return false
    }

    private fun didScreenChangeAfter(service: MyAccessibilityService, startSig: Int): Boolean {
        sleepQuietly(VERIFY_MS)
        if (service.snapshotSignature() != startSig) {
            return true
        }
        sleepQuietly(200L)
        return service.snapshotSignature() != startSig
    }

    private fun sleepQuietly(ms: Long) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms.coerceIn(0L, 3_000L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
