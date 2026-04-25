package com.example.safeguardassistant

/**
 * Evita cliques em chrome da app Safeguard e exige que a resposta exista no que foi lido
 * do ecrã antes de tocar (IA ou heurística "no chute").
 */
object UiClickSafety {

    /** Respostas que nunca devemos enviar como clique automático. */
    private val forbiddenAnswerLower = setOf(
        "camera", "gallery", "label", "badge", "queue",
        "stations", "station", "survey", "back", "info",
        "door knock", "direct contact", "front of house",
        "address sign", "street scene", "navigate up",
    )

    fun isForbiddenAnswer(answer: String): Boolean {
        val a = answer.trim().lowercase()
        if (a.isEmpty()) return true
        if (a in forbiddenAnswerLower) return true
        return forbiddenAnswerLower.any { a.contains(it) }
    }

    /**
     * A resposta deve aparecer de forma plausível nas linhas lidas (mesma ideia que
     * [FormFieldMapRules] para não clicar em algo inventado pela IA).
     */
    fun answerAppearsInVisibleTexts(answer: String, visibleTexts: List<String>): Boolean {
        val t = answer.trim()
        if (t.isEmpty()) return false
        if (t.length <= 2 || t.all { it.isDigit() }) {
            return visibleTexts.any { it.trim().equals(t, ignoreCase = true) }
        }
        for (line in visibleTexts) {
            val u = line.trim()
            if (u.equals(t, ignoreCase = true)) return true
            if (u.contains(t, ignoreCase = true)) return true
            if (t.length > 4 && t.contains(u, ignoreCase = true) && u.length > 2) return true
        }
        return false
    }

    fun shouldAttemptClick(answer: String, visibleTexts: List<String>): Boolean =
        !isForbiddenAnswer(answer) && answerAppearsInVisibleTexts(answer, visibleTexts)
}
