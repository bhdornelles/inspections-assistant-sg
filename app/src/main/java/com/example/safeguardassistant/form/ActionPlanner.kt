package com.example.safeguardassistant.form

import com.example.safeguardassistant.OcrTextExtractor

object ActionPlanner {

    private const val MIN_OCR_CONF = 0.55f

    fun fromRule(
        res: RuleResolution,
        targetBlock: QuestionBlockF,
        sw: Int,
        sh: Int,
    ): PlannedAction? {
        return when (res) {
            is RuleResolution.Manual -> PlannedAction.ManualReview(res.reason)
            is RuleResolution.None -> null
            is RuleResolution.Matched -> when (val a = res.answer) {
                is RuleAnswer.ClickOption -> {
                    val line = findOcrLineForLabel(targetBlock, a.label, res.formMatch)
                    val isShortAmbiguous = a.label.trim().length <= 3 ||
                        a.label.equals("yes", true) ||
                        a.label.equals("no", true)
                    // Segurança: se alvo curto/ambíguo não tiver linha OCR no bloco,
                    // não arriscar clique por texto quando há múltiplos iguais na tela.
                    if (line == null && isShortAmbiguous) {
                        return PlannedAction.ManualReview(
                            "Alvo ambíguo (${a.label}) sem coordenada OCR no bloco",
                        )
                    }
                    if (line != null) {
                        val c = ocrLineConfidence(a.label, line)
                        if (c < MIN_OCR_CONF) {
                            return PlannedAction.ManualReview(
                                "Confiança de alvo no bloco muito baixa: ${a.label}",
                            )
                        }
                    }
                    val (px, py) = if (line != null) ocrToPercent(line, sw, sh) else Pair(-1f, -1f)
                    PlannedAction.TapOptionInBlock(
                        targetBlock,
                        a.label,
                        line,
                        px,
                        py,
                        res.ruleId,
                        res.formMatch,
                    )
                }
                is RuleAnswer.TypeText -> PlannedAction.TypeInBlock(
                    targetBlock,
                    a.value,
                )
                is RuleAnswer.SelectDropdown -> PlannedAction.TypeInBlock(targetBlock, a.value)
                is RuleAnswer.ManualReview -> PlannedAction.ManualReview(a.reason)
                is RuleAnswer.Skip -> PlannedAction.NextScreen(a.reason)
            }
        }
    }

    private fun findOcrLineForLabel(
        block: QuestionBlockF,
        label: String,
        formMatch: com.example.safeguardassistant.FormFieldMapRules.MatchResult?,
    ): OcrTextExtractor.OcrTextLine? {
        if (label.isEmpty()) return null
        val click = label.trim()
        val wantExact = click.length <= 2 || (formMatch?.shortOptionExactLine == true)
        val band = block.ocrLinesInBand
        if (band.isEmpty()) return null
        if (wantExact) {
            val ex = band.filter { it.text.trim().equals(click, true) }
                .minByOrNull { it.y } ?: if (click.length > 2) {
                band.filter { it.text.trim().contains(click, true) }.minByOrNull { it.y }
            } else {
                null
            }
            return ex
        }
        return band
            .filter { it.text.trim().equals(click, true) }
            .minByOrNull { it.y }
            ?: band
                .filter { it.text.contains(click, true) }
                .minByOrNull { it.y }
    }

    private fun ocrLineConfidence(click: String, line: OcrTextExtractor.OcrTextLine): Float {
        val t = line.text.trim()
        val c = click.trim()
        if (c.isEmpty() || t.isEmpty()) return 0f
        if (t.equals(c, true)) return 1f
        if (c.length > 1 && t.contains(c, true)) {
            return if (c.length < 4) 0.7f else 0.86f
        }
        if (c.length in 1..2 && t.equals(c, true)) return 1f
        return 0.2f
    }

    private fun ocrToPercent(
        line: OcrTextExtractor.OcrTextLine,
        w: Int,
        h: Int,
    ): Pair<Float, Float> {
        val ww = w.coerceAtLeast(1)
        val hh = h.coerceAtLeast(1)
        val cx = if (line.width > 0) line.x + line.width / 2f else (ww * 0.5f)
        val cy = if (line.height > 0) line.y + line.height / 2f else (line.y + 6f)
        return Pair((cx / ww) * 100f, (cy / hh) * 100f)
    }
}
