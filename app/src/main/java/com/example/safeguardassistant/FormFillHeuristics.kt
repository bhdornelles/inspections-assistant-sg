package com.example.safeguardassistant

/**
 * Critérios de paragem e heurísticas para o preenchimento em ciclo.
 */
object FormFillHeuristics {

    /** Secção Comments: automação para aqui; inspetor escreve manualmente. */
    fun shouldStopAtCommentsSection(visibleTexts: List<String>): Boolean {
        if (visibleTexts.isEmpty()) return false
        val blob = visibleTexts.joinToString("\n").lowercase()
        if (blob.contains("enter comments")) return true
        val hasCheckout = blob.contains("check out")
        val hasCommentsLabel = visibleTexts.any { it.trim().equals("Comments", ignoreCase = true) }
        return hasCheckout && hasCommentsLabel
    }

    /**
     * Evita repetir clique quando a opção já está seleccionada (rádio/checkbox).
     * Não usamos isto para respostas muito curtas ("Yes","No","1") para não saltar a pergunta errada.
     */
    fun shouldTrySkipAlreadySelected(clickText: String): Boolean = clickText.trim().length >= 5

    /**
     * O asterisco/vermelho "Required" não vem da a11y, mas a palavra costuma aparecer no rótulo.
     */
    fun hasRequiredMarker(visibleTexts: List<String>): Boolean =
        visibleTexts.any { it.contains("required", ignoreCase = true) }

    /**
     * Com [onlyRequired] (FI `only_fill_required_red`): não actuar se a pergunta não tiver
     * "Required" (ou ' * ') visível numa janela de ±2 linhas em relação a [matchAll]-[0].
     * Modais *Apply* passam sempre ([screenBlob] com "apply").
     */
    fun canApplyOnlyRequiredRule(
        onlyRequired: Boolean,
        visibleLines: List<String>,
        matchAll: List<String>,
        screenBlob: String,
    ): Boolean {
        if (!onlyRequired) return true
        val b = screenBlob.lowercase()
        if (b.contains("apply")) return true
        val p0 = matchAll.firstOrNull()?.trim().orEmpty()
        if (p0.isEmpty()) return true
        val pl = p0.lowercase()
        var found = false
        for (i in visibleLines.indices) {
            if (!visibleLines[i].lowercase().contains(pl)) continue
            found = true
            for (d in -2..2) {
                val line = visibleLines.getOrNull(i + d).orEmpty()
                if (line.contains("required", ignoreCase = true) || line.contains(" * ")) {
                    return true
                }
            }
        }
        if (!found) return true
        return false
    }
}
