package com.example.safeguardassistant.form

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Preenche [QuestionBlockF.answered], [selectedAnswer] e [answerState] com base em a11y
 * (preferencial) e opções conhecidas do bloco.
 */
object AnswerStateDetector {

    private const val TAG = "AnswerStateDetector"
    private val radioOptionLabels = setOf(
        "yes", "no", "broker", "owner", "unknown", "direct contact",
    )
    private val sectionHeaders = setOf(
        "main structure", "loan information", "general", "check out", "checkout",
    )

    fun applyAll(
        @Suppress("UNUSED_PARAMETER") service: com.example.safeguardassistant.MyAccessibilityService,
        blocks: List<QuestionBlockF>,
        a11y: List<AccessibilityTextNode>,
        ocrLinesRaw: List<com.example.safeguardassistant.OcrTextExtractor.OcrTextLine>,
        screenshot: Bitmap?,
    ) {
        for (b in blocks) {
            applyBlock(b, a11y, ocrLinesRaw, screenshot)
        }
    }

    private fun applyBlock(
        b: QuestionBlockF,
        a11y: List<AccessibilityTextNode>,
        ocrLinesRaw: List<com.example.safeguardassistant.OcrTextExtractor.OcrTextLine>,
        screenshot: Bitmap?,
    ) {
        val inRect = nodesInRect(a11y, b.bounds)
        b.answerReason = null
        b.selectedAnswer = null
        b.answered = false
        b.answerState = AnswerStatus.UNANSWERED

        // 1) Required badge verde dentro do bloco => já respondida.
        if (hasGreenRequiredBadge(b, ocrLinesRaw, screenshot)) {
            markAnswered(b, reason = "required-green-badge", selected = null)
            return
        }

        // Segurança adicional: qualquer nó marcado/selecionado dentro do bloco
        // indica que já existe resposta nesse bloco.
        val anyMarked = inRect.firstOrNull { it.isChecked || it.isSelected }
        if (anyMarked != null) {
            val selected = anyMarked.text.trim().takeIf { it.isNotEmpty() }
            markAnswered(b, reason = "selected-node-in-block", selected = selected)
            return
        }

        // 2) Rádio selecionado no bloco (a11y).
        for (o in b.options) {
            val n = inRect.find { it.text.equals(o.label, true) }
            if (n != null && (n.isChecked || n.isSelected)) {
                markAnswered(b, reason = "radio-selected-a11y", selected = o.label)
                return
            }
        }

        // 2) Rádio selecionado visualmente perto de opções do bloco.
        if (hasSelectedRadioVisual(b, screenshot)) {
            markAnswered(b, reason = "radio-selected-visual", selected = null)
            return
        }

        // 3) Campo/drowdown com valor dentro do bloco.
        val value = detectFieldValueInsideBlock(b, inRect)
        if (value != null) {
            markAnswered(b, reason = "field-value", selected = value)
            return
        }

        // 4) Se estiver incerto, trata como já respondida por segurança.
        if (isUncertainBlock(b)) {
            b.answerState = AnswerStatus.UNCERTAIN
            b.answered = true
            b.answerReason = "uncertain"
            b.selectedAnswer = null
            Log.i(TAG, "already answered -> skip | block=${b.id} reason=uncertain")
            return
        }
    }

    private fun markAnswered(b: QuestionBlockF, reason: String, selected: String?) {
        b.answered = true
        b.answerReason = reason
        b.answerState = AnswerStatus.ANSWERED
        b.selectedAnswer = selected
        Log.i(TAG, "already answered -> skip | block=${b.id} reason=$reason selected=$selected")
    }

    private fun isPlaceholderish(t: String): Boolean {
        val s = t.lowercase()
        if (s == "n/a" || s == "—" || s == "-") return true
        if (s == "ok") return true
        if (s.startsWith("enter ") && s.length < 22) return true
        if (s.startsWith("select ") && s.length < 28) return true
        return false
    }

    private fun detectFieldValueInsideBlock(
        b: QuestionBlockF,
        inRect: List<AccessibilityTextNode>,
    ): String? {
        val fromA11y = inRect
            .asSequence()
            .filter { it.isEditable }
            .map { it.text.trim() }
            .firstOrNull { t -> t.isNotEmpty() && !isPlaceholderish(t) && !t.equals("select", true) }
        if (fromA11y != null) return fromA11y

        val qNorm = normalizeText(b.questionText)
        val optSet = b.options.map { normalizeText(it.label) }.toSet() + radioOptionLabels
        for (line in b.ocrLinesInBand) {
            val raw = line.text.trim()
            val n = normalizeText(raw)
            if (n.isEmpty()) continue
            if (n == qNorm) continue
            if (n == "required") continue
            if (n in optSet) continue
            if (sectionHeaders.any { n.contains(it) }) continue
            if (n.contains("?")) continue
            if (isPlaceholderish(n) || n == "select") continue
            if (n.length <= 1) continue
            return raw
        }
        return null
    }

    private fun normalizeText(s: String): String =
        s.substringBefore("@@")
            .replace("\n", " ")
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")

    private fun isUncertainBlock(b: QuestionBlockF): Boolean {
        if (b.blockConfidence < 0.58f) return true
        if (b.questionText.isBlank()) return true
        if ((b.inputType == InputType.YES_NO || b.inputType == InputType.SINGLE_CHOICE) && b.options.isEmpty()) {
            return true
        }
        return false
    }

    private fun hasGreenRequiredBadge(
        b: QuestionBlockF,
        ocrLinesRaw: List<com.example.safeguardassistant.OcrTextExtractor.OcrTextLine>,
        screenshot: Bitmap?,
    ): Boolean {
        val bm = screenshot ?: return false
        if (bm.isRecycled) return false
        // Usar OCR bruto (não filtrado pelo grouper) para não perder âncoras "required".
        val requiredLines = ocrLinesRaw.filter { line ->
            val n = normalizeText(line.text)
            val inVerticalBand = line.y >= b.bounds.top - 16 && line.y <= b.bounds.bottom + 16
            val inHorizontalBand = line.x >= b.bounds.left - 24 && line.x <= b.bounds.right + 24
            inVerticalBand && inHorizontalBand && n.startsWith("required")
        }
        if (requiredLines.isEmpty()) return false
        for (line in requiredLines) {
            val left = max(0, line.x - max(8, line.width / 4))
            val top = max(0, line.y - max(6, line.height / 3))
            val right = min(bm.width - 1, line.x + line.width + max(12, line.width / 3))
            val bottom = min(bm.height - 1, line.y + line.height + max(8, line.height / 3))
            if (right <= left || bottom <= top) continue
            var total = 0
            var greenish = 0
            var y = top
            while (y <= bottom) {
                var x = left
                while (x <= right) {
                    val c = bm.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val bCh = c and 0xFF
                    if (g > r + 18 && g > bCh + 18 && g > 92) greenish++
                    total++
                    x += 2
                }
                y += 2
            }
            if (total > 0 && greenish.toFloat() / total.toFloat() > 0.18f) {
                return true
            }
        }
        return false
    }

    private fun hasSelectedRadioVisual(
        b: QuestionBlockF,
        screenshot: Bitmap?,
    ): Boolean {
        val bm = screenshot ?: return false
        if (bm.isRecycled) return false
        val qNorm = normalizeText(b.questionText)
        for (line in b.ocrLinesInBand) {
            val n = normalizeText(line.text)
            if (n.isEmpty()) continue
            if (n == qNorm || n == "required") continue
            if (sectionHeaders.any { n.contains(it) }) continue
            // Só tenta onde parece label de opção
            if (line.height <= 0) continue
            if (looksLikeFilledRadioNearLine(bm, line)) return true
        }
        return false
    }

    private fun looksLikeFilledRadioNearLine(
        bm: Bitmap,
        line: com.example.safeguardassistant.OcrTextExtractor.OcrTextLine,
    ): Boolean {
        val r = line.height.coerceIn(8, 16)
        val cx = (line.x - (line.height * 1.1f)).toInt().coerceIn(0, bm.width - 1)
        val cy = (line.y + line.height / 2f).toInt().coerceIn(0, bm.height - 1)
        if (cx - r < 0 || cy - r < 0 || cx + r >= bm.width || cy + r >= bm.height) return false

        var innerSum = 0f
        var innerCount = 0
        var ringSum = 0f
        var ringCount = 0
        var innerDark = 0
        val r2 = r * r
        val innerR = (r * 0.55f)
        val innerR2 = innerR * innerR
        val ringIn = (r * 0.72f)
        val ringOut = (r * 1.0f)
        val ringIn2 = ringIn * ringIn
        val ringOut2 = ringOut * ringOut
        for (dy in -r..r) {
            for (dx in -r..r) {
                val d2 = (dx * dx + dy * dy).toFloat()
                if (d2 > r2) continue
                val c = bm.getPixel(cx + dx, cy + dy)
                val rr = ((c shr 16) and 0xFF).toFloat()
                val gg = ((c shr 8) and 0xFF).toFloat()
                val bb = (c and 0xFF).toFloat()
                val lum = 0.299f * rr + 0.587f * gg + 0.114f * bb
                if (d2 <= innerR2) {
                    innerSum += lum
                    innerCount++
                    if (lum < 95f) innerDark++
                } else if (d2 in ringIn2..ringOut2) {
                    ringSum += lum
                    ringCount++
                }
            }
        }
        if (innerCount == 0 || ringCount == 0) return false
        val innerAvg = innerSum / innerCount
        val ringAvg = ringSum / ringCount
        val darkRatio = innerDark.toFloat() / innerCount.toFloat()
        return (innerAvg < 122f && ringAvg - innerAvg > 18f) || darkRatio > 0.62f
    }

    private fun nodesInRect(
        a11y: List<AccessibilityTextNode>,
        r: Rect,
    ): List<AccessibilityTextNode> {
        if (r.isEmpty) return emptyList()
        return a11y.filter { n ->
            val b = n.bounds
            val cx = (b.left + b.right) / 2
            val cy = (b.top + b.bottom) / 2
            rectContainsPoint(r, cx, cy) || Rect.intersects(b, r)
        }
    }

    /** Bordas esquerda/topo inclusivas, direita/fundo exclusivas (como [Rect.contains]). */
    private fun rectContainsPoint(rect: Rect, x: Int, y: Int): Boolean =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
}
