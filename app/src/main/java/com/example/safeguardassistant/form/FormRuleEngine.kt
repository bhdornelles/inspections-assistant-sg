package com.example.safeguardassistant.form

import android.content.Context
import com.example.safeguardassistant.FormFieldMapRules
import com.example.safeguardassistant.InspectionProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RuleResolution {
    data class Matched(
        val ruleId: String,
        val answer: RuleAnswer,
        val formMatch: FormFieldMapRules.MatchResult? = null,
    ) : RuleResolution()

    data class Manual(
        val reason: String,
    ) : RuleResolution()

    data object None : RuleResolution()
}

/**
 * Regras conservadoras (condicionais, evidência) + [FormFieldMapRules] por fatia de texto.
 */
object FormRuleEngine {

    private val noAutoYesToChangeOfAddress: Regex = Regex("new address|address changed|moved|forwarding|different address|changed address", RegexOption.IGNORE_CASE)
    private val noAutoNoToComplete: Regex = Regex("bad address|blocked|vacant land|unsafe|unable to access|no property|gated\\s*community|not found|invalid address", RegexOption.IGNORE_CASE)
    private val onlyYesToControlled: Regex = Regex("gated community|access code|guard gate|locked gate|controlled entrance|no public access|call box", RegexOption.IGNORE_CASE)
    private val multiUnitEvidence: Regex = Regex("duplex|multi-?unit|apartment|townhouse|several\\s*units|multiple\\s*units|complex", RegexOption.IGNORE_CASE)
    private val datePattern: Regex = Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b")

    fun resolve(
        context: Context,
        profile: InspectionProfile,
        block: QuestionBlockF,
        lineSlice: List<String>,
        fullScreenBlob: String,
    ): RuleResolution {
        prebuiltConditionalHalt(fullScreenBlob)?.let { return it }

        val b = block.normalizedQuestion
        val full = fullScreenBlob.lowercase(Locale.ROOT)

        if (b.contains("how was new address", ignoreCase = true) || full.contains("how was new address verified", ignoreCase = true)) {
            return RuleResolution.Manual("Pergunta condicional de verificação de endereço — requer revisão explícita.")
        }
        if (b.contains("why were you unable to complete", ignoreCase = true) || full.contains("why were you unable to complete this inspection", ignoreCase = true)) {
            return RuleResolution.Manual("Pergunta condicional após 'unable to complete' — requer revisão explícita.")
        }
        if (b.contains("comments", ignoreCase = true) && b.length < 40) {
            return RuleResolution.Manual("Secção Comments — parar automação ou revisão manual.")
        }
        if (b.contains("completed date", ignoreCase = true) && b.length < 32) {
            val joined = lineSlice.joinToString(" | ")
            if (datePattern.containsMatchIn(joined)) {
                return RuleResolution.Manual("Completed Date já preenchido — não alterar.")
            }
            val s = SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date())
            return RuleResolution.Matched("builtin_completed_date", RuleAnswer.TypeText(s))
        }
        if (b.contains("change of address", ignoreCase = true) && b.length < 64) {
            if (noAutoYesToChangeOfAddress.containsMatchIn(fullScreenBlob)) {
                return RuleResolution.Manual("Evidência de alteração de endereço; não marcar Yes/No sem revisão (Change of Address).")
            }
            return RuleResolution.Matched("builtin_coa", RuleAnswer.ClickOption("No"))
        }
        if (b.contains("are you able to complete this inspection", ignoreCase = true)) {
            if (noAutoNoToComplete.containsMatchIn(fullScreenBlob)) {
                return RuleResolution.Manual("Evidência de excepção; não preencher 'não' automaticamente (complete inspection).")
            }
            return RuleResolution.Matched("builtin_able", RuleAnswer.ClickOption("Yes"))
        }
        if (b.contains("controlled access", ignoreCase = true)) {
            if (onlyYesToControlled.containsMatchIn(fullScreenBlob)) {
                return RuleResolution.Matched("builtin_gated_yes", RuleAnswer.ClickOption("Yes"))
            }
            return RuleResolution.Matched("builtin_gated_no", RuleAnswer.ClickOption("No"))
        }
        if (b.contains("number of units", ignoreCase = true)) {
            if (multiUnitEvidence.containsMatchIn(fullScreenBlob)) {
                return RuleResolution.Manual("Evidência multi-unit — requer revisão (Number of Units).")
            }
            return RuleResolution.Matched("builtin_units", RuleAnswer.TypeText("1"))
        }

        val m = FormFieldMapRules.findMatch(context, lineSlice, profile) ?: return RuleResolution.None
        if (m.clickText.equals("yes", ignoreCase = true) && b.contains("change of address", true)) {
            if (!noAutoYesToChangeOfAddress.containsMatchIn(fullScreenBlob)) {
                return RuleResolution.Matched("json_${m.id}", RuleAnswer.ClickOption("No"), m)
            }
        }
        if (m.clickText.equals("no", ignoreCase = true) && b.contains("are you able to complete this inspection", true)) {
            return RuleResolution.Manual("Ficheiro JSON pedia 'No' para conclusão — requer confirmação (conservador).")
        }
        return RuleResolution.Matched(m.id, RuleAnswer.ClickOption(m.clickText), m)
    }

    private fun prebuiltConditionalHalt(blob: String): RuleResolution.Manual? {
        val l = blob.lowercase(Locale.ROOT)
        if (l.contains("we need evidence of this interaction") && l.contains("mortgage")) {
            return null
        }
        if (l.contains("check out") && l.contains("comments") && l.contains("enter comment")) {
            return RuleResolution.Manual("Check-out / comentários — fim de automação.")
        }
        return null
    }
}
