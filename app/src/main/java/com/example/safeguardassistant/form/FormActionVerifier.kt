package com.example.safeguardassistant.form

import com.example.safeguardassistant.MyAccessibilityService

object FormActionVerifier {

    /**
     * Verificação pós-toque: árvore mudou ou pergunta passou a respondida no bloco.
     */
    fun verifyOptionSelection(
        service: MyAccessibilityService,
        beforeSig: Int,
    ): Boolean {
        var after = service.snapshotSignature()
        if (after != beforeSig) {
            return true
        }
        return false
    }
}
