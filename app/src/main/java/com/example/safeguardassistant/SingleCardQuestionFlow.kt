package com.example.safeguardassistant

import android.graphics.Bitmap

/**
 * Uma pergunta por ecrã (card Safeguard): indica se o cartão visível parece **já respondido**
 * com base na árvore de acessibilidade, para o orquestrador avançar em vez de aplicar toques
 * de regras. [fullScreen] e [ocrLines] reservam-se a heurísticas visuais futuras (p.ex. *Required*).
 */
object SingleCardQuestionFlow {

    enum class AnswerStatus {
        ANSWERED,
        UNANSWERED,
        /** Sinal ambíguo: não forçar cliques, avançar para não bloquear. */
        UNCERTAIN,
    }

    data class AnswerState(
        val status: AnswerStatus,
    ) {
        val treatAsAnswered: Boolean
            get() = status == AnswerStatus.ANSWERED || status == AnswerStatus.UNCERTAIN
    }

    @Suppress("UNUSED_PARAMETER")
    fun isCurrentQuestionAnswered(
        fullScreen: Bitmap?,
        ocrLines: List<OcrTextExtractor.OcrTextLine>,
        service: MyAccessibilityService,
    ): AnswerState {
        if (service.isApplyModalAnswered()) {
            return AnswerState(AnswerStatus.ANSWERED)
        }
        if (service.isAnyCheckableOrSelectedInWindow()) {
            return AnswerState(AnswerStatus.ANSWERED)
        }
        if (service.hasEditTextWithNonTrivialValueInWindow()) {
            return AnswerState(AnswerStatus.ANSWERED)
        }
        return AnswerState(AnswerStatus.UNANSWERED)
    }
}
