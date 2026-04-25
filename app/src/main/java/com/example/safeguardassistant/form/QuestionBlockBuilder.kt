package com.example.safeguardassistant.form

import android.graphics.Rect
import com.example.safeguardassistant.OcrQuestionGrouper
import com.example.safeguardassistant.OcrTextExtractor
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ScreenParser] + construção de [QuestionBlockF] a partir de grupos OCR + nós a11y.
 * Não assume “uma pergunta por ecrã”: devolve todos os blocos em ordem Y.
 */
object ScreenParser {
    fun parseOcrToLegacyBlocks(ocr: List<OcrTextExtractor.OcrTextLine>): List<OcrQuestionGrouper.QuestionBlock> {
        if (ocr.isEmpty()) return emptyList()
        return OcrQuestionGrouper.groupQuestions(ocr).sortedBy { it.yStart }
    }
}

object QuestionBlockBuilder {

    private val idGen = AtomicInteger(0)

    private val navIgnoreSubstrings = listOf(
        "survey", "queue", "stations", "station", "camera", "gallery",
        "label", "badge", "version", "v1.", "v2.",
    )

    fun build(
        snapshot: ScreenSnapshot,
    ): List<QuestionBlockF> {
        val ocr = snapshot.ocrLines.map { it.line }
        if (ocr.isEmpty()) {
            return emptyList()
        }
        val legacyBlocks = ScreenParser.parseOcrToLegacyBlocks(ocr)
        if (legacyBlocks.isEmpty()) return emptyList()
        val sw = snapshot.screenW.toFloat()
        val sh = snapshot.screenH.toFloat()
        return legacyBlocks.mapIndexed { index, g ->
            mergeLegacyBlock(
                g,
                index,
                snapshot.accessibilityNodes,
                sw = sw,
                sh = sh,
            )
        }.filter { !isNoiseBlock(it) }
    }

    private fun isNoiseBlock(b: QuestionBlockF): Boolean {
        val n = b.normalizedQuestion
        if (n.isBlank() || n == "[no question line detected]") return false
        for (p in navIgnoreSubstrings) {
            if (n.contains(p)) return true
        }
        return false
    }

    private fun mergeLegacyBlock(
        g: OcrQuestionGrouper.QuestionBlock,
        index: Int,
        a11y: List<AccessibilityTextNode>,
        sw: Float,
        sh: Float,
    ): QuestionBlockF {
        val inBand = g.ocrLinesInBand
        val r = if (inBand.isNotEmpty()) {
            var l = inBand.minOf { it.x }
            var t = inBand.minOf { it.y }
            var rgt = inBand.maxOf { it.x + (if (it.width > 0) it.width else 0) }
            var bot = inBand.maxOf { it.y + (if (it.height > 0) it.height else 0) }
            l = l.coerceIn(0, 100_000)
            t = t.coerceIn(0, 100_000)
            rgt = rgt.coerceAtLeast(l + 4)
            bot = bot.coerceAtLeast(t + 4)
            Rect(l, t, rgt, bot)
        } else {
            Rect(0, 0, sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1))
        }
        val q = g.question.trim()
        val nq = q.lowercase(Locale.ROOT)
        val required = inBand.joinToString(" ") { it.text }
            .contains("required", ignoreCase = true) ||
            a11yInRect(a11y, r).any { it.text.contains("required", true) } ||
            a11yInRect(a11y, r).any { it.text.contains(" *", true) }
        val optF = inBand
            .filter { line -> line.text.trim() != g.question.trim() }
            .map { line -> lineToOption(line, a11y) }
        val itype = inferInputType(nq, optF.map { it.label })
        var conf = 0.72f
        if (nq.contains("?") || nq.length > 24) conf += 0.1f
        if (optF.isNotEmpty()) conf += 0.08f
        return QuestionBlockF(
            id = "qb_${index}_${idGen.incrementAndGet()}",
            questionText = q,
            normalizedQuestion = nq,
            options = optF,
            inputType = itype,
            required = required,
            bounds = r,
            blockConfidence = conf.coerceIn(0.35f, 0.99f),
            source = if (a11y.isNotEmpty()) SourceBlend.BLENDED else SourceBlend.OCR,
            ocrLinesInBand = inBand,
        )
    }

    private fun a11yInRect(nodes: List<AccessibilityTextNode>, r: Rect): List<AccessibilityTextNode> {
        if (r.isEmpty) return emptyList()
        return nodes.filter { n ->
            val b = n.bounds
            val cx = (b.left + b.right) / 2
            val cy = (b.top + b.bottom) / 2
            if (rectContainsPoint(r, cx, cy)) return@filter true
            Rect.intersects(b, r)
        }
    }

    private fun rectContainsPoint(rect: Rect, x: Int, y: Int): Boolean =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

    private fun lineToOption(
        line: OcrTextExtractor.OcrTextLine,
        a11y: List<AccessibilityTextNode>,
    ): QuestionOptionF {
        val lb = line.text.trim()
        val nr = Rect(
            line.x,
            line.y,
            line.x + line.width.coerceAtLeast(1),
            line.y + line.height.coerceAtLeast(1),
        )
        val hit = a11y.firstOrNull { a ->
            val c = a.bounds
            val acx = (c.left + c.right) / 2
            val acy = (c.top + c.bottom) / 2
            acx >= line.x - 4 && acx <= line.x + line.width + 4 &&
                acy >= line.y - 4 && acy <= line.y + line.height + 4
        }
        return QuestionOptionF(
            label = lb,
            normalizedLabel = lb.lowercase(Locale.ROOT),
            bounds = nr,
            selected = hit?.isChecked == true || hit?.isSelected == true,
            clickable = hit?.isClickable == true,
            source = if (hit != null) SourceBlend.BLENDED else SourceBlend.OCR,
        )
    }

    private fun inferInputType(
        q: String,
        options: List<String>,
    ): InputType {
        val olow = options.map { it.lowercase(Locale.ROOT) }
        if (olow.size == 2 && olow.toSet() == setOf("yes", "no")) {
            return InputType.YES_NO
        }
        if (q.contains("date", ignoreCase = true) || options.any { it.contains("/20") || it.contains("/19") }) {
            return InputType.DATE
        }
        if (q.contains("unit", ignoreCase = true) || (q.contains("number", true) && q.contains("unit", true))) {
            return InputType.NUMBER
        }
        if (q.contains("comment", true) && options.isEmpty()) return InputType.TEXT
        if (options.any { it.equals("select", true) || it.startsWith("enter", true) }) {
            return InputType.DROPDOWN
        }
        if (options.isEmpty() && (q.length > 40)) return InputType.TEXT
        return if (options.size > 1) InputType.SINGLE_CHOICE else InputType.UNKNOWN
    }
}

fun QuestionBlockF.textLinesForRuleEngine(): List<String> {
    val ocrPart = ocrLinesInBand.map { it.text }
    val withQ = (listOf(questionText) + ocrPart).map { it.trim() }.filter { it.isNotEmpty() }
    return withQ
}

/**
 * Texto visível a usar em [FormFieldMapRules] para **este** bloco.
 * Não incluir a11y global para evitar match cruzado de perguntas irmãs
 * (ex.: vários Yes/No na mesma tela).
 */
fun QuestionBlockF.textSliceForRuleMatching(@Suppress("UNUSED_PARAMETER") fullA11y: List<String>): List<String> {
    return textLinesForRuleEngine()
        .map { it.substringBefore("@@").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
