package com.example.safeguardassistant.form

import android.content.Context
import com.example.safeguardassistant.FormFieldMapRules
import com.example.safeguardassistant.FormFillHeuristics
import com.example.safeguardassistant.FormFillPolicies
import com.example.safeguardassistant.InspectionProfile
import com.example.safeguardassistant.MyAccessibilityService
import com.example.safeguardassistant.NextQuestionNavigation
import com.example.safeguardassistant.OcrTextExtractor
import com.example.safeguardassistant.UiClickSafety

sealed class FormStepResult {
    data class Clicked(val label: String, val modalOpen: Boolean) : FormStepResult()
    data object CardAdvanced : FormStepResult()
    data object NoAction : FormStepResult()
    data class StopWithMessage(val message: String) : FormStepResult()
}

/**
 * Um ciclo: captura unificada → blocos (várias perguntas) → estado → prioridade
 * → [FormRuleEngine] → [ActionPlanner] → [FormActionExecutor].
 */
object FormFillPipeline {

    fun runOneFormStep(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        ocrLines: List<OcrTextExtractor.OcrTextLine>,
        readLinesForRules: List<String>,
        bitmap: android.graphics.Bitmap?,
        bitmapW: Int,
        bitmapH: Int,
        fullScreenBlob: String,
        isApplyModalOpen: Boolean,
        onFeedback: (String, Long) -> Unit,
    ): FormStepResult {
        if (readLinesForRules.isEmpty()) return FormStepResult.NoAction
        val onlyRequired = FormFillPolicies.onlyFillRequiredEnabled(profile, appContext)
        val policy = FormFillPolicies.forProfile(appContext, profile)
        val snapshot = ScreenSnapshotProvider.capture(
            appContext, service, ocrLines, bitmap,
        )
        val blocks = QuestionBlockBuilder.build(snapshot)
        if (blocks.isEmpty()) {
            return FormStepResult.NoAction
        }
        AnswerStateDetector.applyAll(
            service = service,
            blocks = blocks,
            a11y = snapshot.accessibilityNodes,
            ocrLinesRaw = snapshot.ocrLines.map { it.line },
            screenshot = snapshot.screenshot,
        )
        FormPipelineLog.screen(
            snapshot.accessibilityNodes.size,
            snapshot.ocrLines.size,
            blocks.size,
        )
        blocks.forEachIndexed { i, b -> FormPipelineLog.block(i, b) }
        if (FormFillHeuristics.shouldStopAtCommentsSection(readLinesForRules)) {
            return FormStepResult.StopWithMessage("Comments / check-out: fim de automação.")
        }
        if (QuestionPrioritySelector.hasRequiredUncertain(blocks)) {
            FormPipelineLog.action("Priority", "required+UNCERTAIN", "safety")
            return FormStepResult.NoAction
        }
        var target = QuestionPrioritySelector.selectNextToFill(blocks)
        if (target == null) {
            if (QuestionPrioritySelector.allAnsweredOrHandled(blocks)) {
                FormPipelineLog.action("Flow", "all visible blocks answered/skipped -> scroll down", "pipeline")
                if (service.performScrollForward()) return FormStepResult.CardAdvanced
                if (NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)) {
                    return FormStepResult.CardAdvanced
                }
            }
            return FormStepResult.NoAction
        }
        var lineSlice = target.textSliceForRuleMatching(readLinesForRules)
        if (lineSlice.isEmpty()) {
            lineSlice = target.textLinesForRuleEngine()
        }
        FormPipelineLog.binding(
            blockId = target.id,
            question = target.questionText,
            lineCount = lineSlice.size,
            detail = "block-local line slice",
        )
        if (isApplyModalOpen) {
            if (readLinesForRules.any { t -> t.contains("How was new address", true) && t.contains("verif", true) }) {
                return FormStepResult.StopWithMessage("Pergunta condicional de endereço no modal — revisão manual.")
            }
            if (readLinesForRules.any { t -> t.contains("Why were you unable to complete this inspection", true) }) {
                return FormStepResult.StopWithMessage("Pergunta condicional 'why unable' — revisão manual.")
            }
        }
        if (shouldDoNotChangeAndSkip(
                appContext, profile, lineSlice, fullScreenBlob, target,
            )
        ) {
            NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
            return FormStepResult.CardAdvanced
        }
        when (val res = FormRuleEngine.resolve(
            appContext, profile, target, lineSlice, fullScreenBlob,
        )) {
            is RuleResolution.Manual -> {
                FormPipelineLog.decision(target.id, null, null, res.reason, 0.4f)
                return FormStepResult.StopWithMessage(res.reason)
            }
            is RuleResolution.None -> {
                if (QuestionPrioritySelector.allAnsweredOrHandled(blocks)) {
                    FormPipelineLog.action("Flow", "all visible blocks answered/skipped -> scroll down", "pipeline")
                    if (service.performScrollForward()) return FormStepResult.CardAdvanced
                    if (NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)) return FormStepResult.CardAdvanced
                }
                return FormStepResult.NoAction
            }
            is RuleResolution.Matched -> {
                FormPipelineLog.binding(
                    blockId = target.id,
                    question = target.questionText,
                    lineCount = lineSlice.size,
                    detail = "matched rule=${res.ruleId} formMatch=${res.formMatch?.id}",
                )
                if (isApplyModalOpen) {
                    val cl = (res.answer as? RuleAnswer.ClickOption)?.label
                    if (cl != null && MyAccessibilityService.isApplyModalAnsweredFor(service, cl)) {
                        NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
                        return FormStepResult.CardAdvanced
                    }
                }
                val mForRequired = res.formMatch?.matchAll ?: listOf(target.questionText)
                if (!FormFillHeuristics.canApplyOnlyRequiredRule(onlyRequired, readLinesForRules, mForRequired, fullScreenBlob)
                ) {
                    NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
                    return FormStepResult.CardAdvanced
                }
                if (res.formMatch != null && FormFillPolicies.isDoNotChangeForRule(
                        profile, res.formMatch.matchAll, appContext,
                    )
                ) {
                    NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
                    return FormStepResult.CardAdvanced
                }
                val targetAnswer = when (val a = res.answer) {
                    is RuleAnswer.ClickOption -> a.label
                    is RuleAnswer.TypeText -> a.value
                    is RuleAnswer.SelectDropdown -> a.value
                    is RuleAnswer.ManualReview, is RuleAnswer.Skip -> null
                }
                FormPipelineLog.decision(target.id, res.ruleId, targetAnswer, "rule", 0.9f)
                if (res.formMatch != null && res.answer is RuleAnswer.ClickOption) {
                    val click = (res.answer as RuleAnswer.ClickOption).label
                    val a = service.assessMappedRuleClick(
                        res.formMatch.matchAll, click, policy.neverOverwriteExisting,
                    )
                    if (!a.canClick) {
                        if (a.needsReview) {
                            NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
                            return FormStepResult.CardAdvanced
                        }
                        return FormStepResult.NoAction
                    }
                } else if (res.answer is RuleAnswer.ClickOption) {
                    val click = (res.answer as RuleAnswer.ClickOption).label
                    if (FormFillHeuristics.shouldTrySkipAlreadySelected(click) && service.isLikelyAlreadySelected(click)) {
                        NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
                        return FormStepResult.CardAdvanced
                    }
                    if (!UiClickSafety.shouldAttemptClick(click, lineSlice)) {
                        NextQuestionNavigation.moveToNextQuestion(appContext, service, bitmap)
                        return FormStepResult.CardAdvanced
                    }
                }
                val w = if (bitmapW > 0) bitmapW else snapshot.width
                val h = if (bitmapH > 0) bitmapH else snapshot.height
                val plan = ActionPlanner.fromRule(res, target, w, h) ?: return FormStepResult.NoAction
                if (plan is PlannedAction.ManualReview) {
                    return FormStepResult.StopWithMessage(plan.reason)
                }
                when (plan) {
                    is PlannedAction.TapOptionInBlock -> {
                        val r = FormActionExecutor.execute(
                            appContext, service, plan, profile, lineSlice, isApplyModalOpen, onFeedback,
                        )
                        FormPipelineLog.verification(r.success, r.detail)
                        return if (r.success) {
                            FormStepResult.Clicked(plan.optionLabel, isApplyModalOpen)
                        } else {
                            FormStepResult.NoAction
                        }
                    }
                    is PlannedAction.TypeInBlock -> {
                        val r = FormActionExecutor.execute(
                            appContext, service, plan, profile, lineSlice, isApplyModalOpen, onFeedback,
                        )
                        FormPipelineLog.verification(r.success, r.detail)
                        return if (r.success) {
                            FormStepResult.Clicked(plan.value, false)
                        } else {
                            FormStepResult.NoAction
                        }
                    }
                    else -> return FormStepResult.NoAction
                }
            }
        }
    }
}

private fun shouldDoNotChangeAndSkip(
    appContext: Context,
    profile: InspectionProfile,
    lineSlice: List<String>,
    @Suppress("UNUSED_PARAMETER") fullScreenBlob: String,
    target: QuestionBlockF,
): Boolean {
    val m = FormFieldMapRules.findMatch(appContext, lineSlice, profile) ?: return false
    if (!FormFillPolicies.isDoNotChangeForRule(profile, m.matchAll, appContext)) {
        return false
    }
    FormPipelineLog.decision(target.id, m.id, m.clickText, "do_not_change_list", 1f)
    return true
}
