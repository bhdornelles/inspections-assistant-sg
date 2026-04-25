package com.example.safeguardassistant

/**
 * Primeiro passo do overlay. Para [FI] o utilizador escolhe a seguir [FiSituation] (Ocupado / Vago).
 * [DF] e [E26RNN] mapeiam directamente para [InspectionProfile].
 */
enum class InspectionFamily { FI, DF, E26RNN }

enum class FiSituation { OCCUPIED, VACANT }

fun resolveInspectionProfile(
    family: InspectionFamily,
    fiSituation: FiSituation?,
): InspectionProfile? = when (family) {
    InspectionFamily.DF -> InspectionProfile.DF
    InspectionFamily.E26RNN -> InspectionProfile.E26RNN
    InspectionFamily.FI -> when (fiSituation) {
        FiSituation.OCCUPIED -> InspectionProfile.FI_OCCUPIED
        FiSituation.VACANT -> InspectionProfile.FI_VACANT
        null -> null
    }
}
