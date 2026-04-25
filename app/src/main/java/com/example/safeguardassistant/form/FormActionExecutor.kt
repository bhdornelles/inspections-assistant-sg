package com.example.safeguardassistant.form

import android.content.Context
import com.example.safeguardassistant.FormFillPolicies
import com.example.safeguardassistant.MyAccessibilityService
import com.example.safeguardassistant.R
import com.example.safeguardassistant.UiClickSafety
import java.util.concurrent.TimeUnit

object FormActionExecutor {

    private const val TAG = "FormActionExecutor"
    private const val POST_ACTION_MS = 380L
    private const val POST_ACTION_RETRY_MS = 180L

    data class ExecResult(
        val success: Boolean,
        val detail: String,
    )

    fun execute(
        appContext: Context,
        service: MyAccessibilityService,
        plan: PlannedAction,
        profile: com.example.safeguardassistant.InspectionProfile,
        lineSlice: List<String>,
        isApplyModalOpen: Boolean,
        onFeedback: (String, Long) -> Unit,
    ): ExecResult = when (plan) {
        is PlannedAction.TapOptionInBlock -> executeTapInBlock(
            appContext, service, plan, profile, lineSlice, isApplyModalOpen, onFeedback,
        )
        is PlannedAction.TypeInBlock -> executeType(
            appContext, service, plan, profile, onFeedback,
        )
        is PlannedAction.NextScreen, is PlannedAction.ScrollForward, is PlannedAction.ManualReview, is PlannedAction.NoOp -> ExecResult(
            false,
            "not executed here",
        )
    }

    private fun executeTapInBlock(
        appContext: Context,
        service: MyAccessibilityService,
        plan: PlannedAction.TapOptionInBlock,
        profile: com.example.safeguardassistant.InspectionProfile,
        lineSlice: List<String>,
        isApplyModalOpen: Boolean,
        onFeedback: (String, Long) -> Unit,
    ): ExecResult {
        val block = plan.block
        val click = plan.optionLabel.trim()
        val match = plan.formMatch
        val policy = FormFillPolicies.forProfile(appContext, profile)
        if (block.answered || block.answerState != AnswerStatus.UNANSWERED) {
            FormPipelineLog.action(
                "TapOption",
                "already answered -> skip block=${block.id} state=${block.answerState} reason=${block.answerReason}",
                "guard",
            )
            return ExecResult(false, "block already answered")
        }
        if (match != null) {
            val a = service.assessMappedRuleClick(
                match.matchAll,
                click,
                neverOverwriteExisting = policy.neverOverwriteExisting,
            )
            if (!a.canClick) {
                FormPipelineLog.action("TapOption", "assess blocked: ${a.log}", "a11y")
                return ExecResult(false, a.log)
            }
        } else {
            if (!UiClickSafety.shouldAttemptClick(click, lineSlice)) {
                return ExecResult(false, "not safe: $click")
            }
        }
        val before = service.snapshotSignature()
        var ok = false
        if (plan.ocrLine != null && plan.percentX >= 0f && plan.percentY >= 0f) {
            FormPipelineLog.action("TapOption", "OCR % ${plan.percentX} ${plan.percentY} rule=${plan.ruleId}", "ocr")
            ok = service.tapByPercent(plan.percentX, plan.percentY)
        } else {
            val anc = match?.matchAll?.filter { it.isNotBlank() }?.take(3)
                ?: listOf(block.questionText)
            FormPipelineLog.action("TapOption", "a11y near-question $click rule=${plan.ruleId}", "a11y")
            ok = service.clickByTextNearQuestion(click, anc)
        }
        if (!ok && isApplyModalOpen) {
            ok = service.clickByTextInApplyModal(click) || service.clickByText(click)
        }
        if (!ok) {
            return ExecResult(false, "gesture/click not accepted")
        }
        sleep(POST_ACTION_MS)
        var after = service.snapshotSignature()
        if (after == before) {
            sleep(POST_ACTION_RETRY_MS)
            after = service.snapshotSignature()
        }
        val good = (after != before) || (isApplyModalOpen && ok)
        if (good) {
            onFeedback(
                appContext.getString(
                    R.string.feedback_orchestration_rule_action,
                    block.questionText.take(40),
                    "tap:$click",
                ),
                2600L,
            )
        }
        return ExecResult(
            good, if (good) "tap accepted" else "no screen change after tap",
        )
    }

    private fun executeType(
        appContext: Context,
        service: MyAccessibilityService,
        plan: PlannedAction.TypeInBlock,
        profile: com.example.safeguardassistant.InspectionProfile,
        onFeedback: (String, Long) -> Unit,
    ): ExecResult {
        if (plan.block.answered || plan.block.answerState != AnswerStatus.UNANSWERED) {
            FormPipelineLog.action(
                "Type",
                "already answered -> skip block=${plan.block.id} state=${plan.block.answerState} reason=${plan.block.answerReason}",
                "guard",
            )
            return ExecResult(false, "block already answered")
        }
        if (FormFillPolicies.isDoNotChangeForRule(
                profile,
                listOf(plan.block.questionText),
                appContext,
            )
        ) {
            FormPipelineLog.action("Type", "blocked by do_not_change", "policy")
            return ExecResult(false, "do_not_change per policy")
        }
        val nq = plan.block.normalizedQuestion
        val needles = listOf(
            nq.split("?").first().split(".").first().trim(),
            plan.block.questionText,
        )
            .map { it.lowercase() }
            .distinct()
            .filter { it.length > 2 }
        FormPipelineLog.action("Type", "setText in block: ${plan.value} rule=builtin/json", "a11y")
        val ok = service.setTextByMatchAny(needles, plan.value)
        if (ok) {
            onFeedback(
                appContext.getString(
                    R.string.feedback_orchestration_rule_action,
                    plan.block.questionText.take(40),
                    "type",
                ),
                2400L,
            )
        }
        sleep(POST_ACTION_MS)
        return ExecResult(ok, if (ok) "setText" else "setText failed")
    }

    private fun sleep(ms: Long) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms.coerceIn(0, 3_000))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
