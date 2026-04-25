package com.example.safeguardassistant.form

import android.content.Context
import android.graphics.Bitmap
import com.example.safeguardassistant.MyAccessibilityService
import com.example.safeguardassistant.OcrTextExtractor

object ScreenSnapshotProvider {

    fun capture(
        @Suppress("UNUSED_PARAMETER") appContext: Context,
        service: MyAccessibilityService,
        ocrResultLines: List<OcrTextExtractor.OcrTextLine>,
        bitmap: Bitmap?,
    ): ScreenSnapshot {
        val a11yNodes: List<AccessibilityTextNode> = try {
            service.collectAccessibilityTextNodes()
        } catch (e: Exception) {
            emptyList()
        }
        val plain: List<String> = try {
            service.getVisibleTexts()
        } catch (e: Exception) {
            emptyList()
        }
        val w = bitmap?.width?.takeIf { it > 0 } ?: 0
        val h = bitmap?.height?.takeIf { it > 0 } ?: 0
        val ocrF = ocrResultLines.map { OcrTextLineF(it) }
        return ScreenSnapshot(
            accessibilityNodes = a11yNodes,
            a11yPlainTexts = plain,
            ocrLines = ocrF,
            screenshot = bitmap,
            width = w,
            height = h,
        )
    }
}
