package com.example.safeguardassistant.form

object QuestionPrioritySelector {

    /**
     * 1) Required + [AnswerStatus.UNANSWERED]
     * 2) Primeiro sem resposta (não incerto)
     */
    fun selectNextToFill(blocks: List<QuestionBlockF>): QuestionBlockF? {
        val sorted = blocks.sortedBy { it.bounds.top }
        sorted.firstOrNull { it.required && it.answerState == AnswerStatus.UNANSWERED }
            ?.let { return it }
        return sorted.firstOrNull { it.answerState == AnswerStatus.UNANSWERED }
    }

    fun allAnsweredOrHandled(blocks: List<QuestionBlockF>): Boolean {
        if (blocks.isEmpty()) return true
        return blocks.all {
            it.answerState == AnswerStatus.ANSWERED ||
                it.answerState == AnswerStatus.UNCERTAIN
        }
    }

    fun hasRequiredUncertain(blocks: List<QuestionBlockF>): Boolean =
        blocks.any { it.required && it.answerState == AnswerStatus.UNCERTAIN }
}
