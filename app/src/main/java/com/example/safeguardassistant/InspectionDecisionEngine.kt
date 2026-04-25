package com.example.safeguardassistant

/**
 * Regras rápidas (sem rede) + constante de revisão manual.
 * Ordem importa: frases mais específicas antes de termos genéricos como "occupied".
 */
object InspectionDecisionEngine {

    const val MANUAL_REVIEW = "MANUAL_REVIEW"

    /**
     * @return texto exatamente como aparece num botão/radio (para [MyAccessibilityService.clickByText]),
     * ou null para pedir decisão à IA / revisão manual.
     */
    fun quickAnswer(visibleTexts: List<String>, profile: InspectionProfile): String? {
        val blob = visibleTexts
            .joinToString(" ")
            .lowercase()
            .replace(Regex("\\s+"), " ")
        return when (profile) {
            InspectionProfile.FI_OCCUPIED -> quickAnswerFiOccupied(blob)
            InspectionProfile.FI_VACANT -> quickAnswerFiVacant(blob)
            InspectionProfile.DF -> quickAnswerDf(blob)
            InspectionProfile.E26RNN -> null
        }
    }

    @Suppress("SameReturnValue", "ComplexMethod")
    private fun quickAnswerFiOccupied(blob: String): String? {
        // Com orquestrador + leitura frágil, heurísticas por palavras soltas geravam cliques errados.
        // FI Ocupado: use form_field_answers_fi_occupied.json e IA; aqui só bloqueamos nada (null).
        if (blob.contains("grass height")) return null
        if (blob.contains("lawn") && blob.contains("yard") && blob.contains("conditions")) return null
        if (blob.contains("roof conditions") || blob.contains("roof condition")) return null
        if (blob.contains("outbuilding type")) return null
        if (blob.contains("uad") && blob.contains("condition rating")) return null
        if (blob.contains("grass") && blob.contains("inch")) return null
        return null
    }

    private fun quickAnswerFiVacant(blob: String): String? {
        if (blob.contains("confirm this property") && blob.contains("vacant")) return "Override to vacant"
        if (blob.contains("occupancy status") && blob.contains("vacant")) return "Vacant"
        return null
    }

    private fun quickAnswerDf(blob: String): String? {
        // DF: sem regras locais por agora
        if (blob.contains("damage") && blob.contains("flood")) return null
        return null
    }
}
