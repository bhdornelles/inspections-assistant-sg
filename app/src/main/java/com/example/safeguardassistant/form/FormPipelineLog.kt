package com.example.safeguardassistant.form

import android.util.Log

object FormPipelineLog {
    private const val TAG = "FormPipeline"

    fun screen(
        a11yNodeCount: Int,
        ocrLineCount: Int,
        blockCount: Int,
    ) {
        Log.i(
            TAG,
            "[SCREEN SNAPSHOT] a11y_nodes=$a11yNodeCount ocr_lines=$ocrLineCount question_blocks=$blockCount",
        )
    }

    fun block(index: Int, b: QuestionBlockF) {
        val opts = b.options.joinToString(", ") { o ->
            "${o.label} sel=${o.selected} clk=${o.clickable}"
        }
        Log.i(
            TAG,
            "[Block ${index + 1}] id=${b.id} Q=\"${b.questionText}\" required=${b.required} " +
                "type=${b.inputType} state=${b.answerState} answered=${b.answered} reason=${b.answerReason} " +
                "selected=${b.selectedAnswer} " +
                "options=[$opts] " +
                "bounds=${b.bounds} conf=${b.blockConfidence}",
        )
    }

    fun decision(
        selectedBlockId: String?,
        ruleId: String?,
        targetAnswer: String?,
        reason: String,
        confidence: Float,
    ) {
        Log.i(
            TAG,
            "[DECISION] block=$selectedBlockId rule=$ruleId target=\"$targetAnswer\" " +
                "reason=\"$reason\" conf=$confidence",
        )
    }

    fun action(
        kind: String,
        detail: String,
        source: String,
    ) {
        Log.i(TAG, "[ACTION] $kind $detail source=$source")
    }

    fun binding(
        blockId: String,
        question: String,
        lineCount: Int,
        detail: String,
    ) {
        Log.i(
            TAG,
            "[BINDING] block=$blockId question=\"${question.take(90)}\" lines=$lineCount detail=\"$detail\"",
        )
    }

    fun verification(ok: Boolean, detail: String) {
        val s = if (ok) "SUCCESS" else "FAIL"
        Log.i(TAG, "[VERIFICATION] $s: $detail")
    }
}
