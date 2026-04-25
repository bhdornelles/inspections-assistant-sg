package com.example.safeguardassistant

import android.content.Context
import android.os.Handler
import android.util.Log
import com.example.safeguardassistant.form.FormFillPipeline
import com.example.safeguardassistant.form.FormStepResult
import java.util.concurrent.Executor

/**
 * Um disparo (perfil no overlay) percorre o formulário em ciclo.
 * O preenchimento segue o pipeline: captura unificada → múltiplos blocos de pergunta
 * ([com.example.safeguardassistant.form.QuestionBlockBuilder]) →
 * regras conservadoras + JSON → toque apenas dentro do bloco ([FormFillPipeline]).
 * [onFeedback] deve publicar na thread principal.
 */
object FormFillOrchestrator {

    private const val TAG = "FormFillOrchestrator"
    private const val MAX_STEPS = 80
    // Ajuste fino de performance: menos espera por ciclo, mantendo margem para animações.
    private const val CLICK_SETTLE_MS = 360L
    /** Após performAction, o sistema pode devolver true sem mudança real — comparamos o texto lido. */
    private const val POST_CLICK_VERIFY_MS = 300L
    private const val POST_CLICK_VERIFY_RETRY_MS = 180L
    private const val SCROLL_SETTLE_MS = 300L
    private const val NO_ACTION_ITERATIONS_BEFORE_STOP = 8
    /** Primeiras iterações: só regras locais + scroll (dá tempo ao ecrã estabilizar). */
    private const val FIRST_STEP_ALLOW_AI = 3
    /** Máximo de pedidos à IA por sessão (evita custo e respostas com o mesmo contexto pobre). */
    private const val MAX_AI_CALLS_PER_SESSION = 8
    private const val MAX_VISION_CALLS_PER_SESSION = 14
    /** Após toque por coordenadas (IA visão), dar tempo ao ecrã animar. */
    private const val VISION_POST_TAP_MS = 650L

    private fun isShortYesNo(s: String): Boolean {
        val t = s.trim()
        return t.equals("yes", true) || t.equals("no", true)
    }

    /**
     * Delege ao [FormFillPipeline]: vários blocos por ecrã, toque só com âncora de pergunta
     * ou por centro OCR dentro do bloco.
     */
    private fun trySingleCardOcrDriven(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        ocrLines: List<OcrTextExtractor.OcrTextLine>,
        readLinesForRules: List<String>,
        fullScreen: android.graphics.Bitmap?,
        fullScreenBlob: String,
        bitmapW: Int,
        bitmapH: Int,
        onFeedback: (String, Long) -> Unit,
    ): StepOutcome {
        if (readLinesForRules.isEmpty()) return StepOutcome.NoAction
        val modalOpen = isApplyModalOpen(readLinesForRules)
        return when (val r = FormFillPipeline.runOneFormStep(
            appContext,
            profile,
            service,
            ocrLines,
            readLinesForRules,
            fullScreen,
            bitmapW,
            bitmapH,
            fullScreenBlob,
            modalOpen,
            onFeedback,
        )) {
            is FormStepResult.Clicked -> StepOutcome.Clicked(
                r.label, r.modalOpen,
            )
            is FormStepResult.CardAdvanced -> StepOutcome.CardAdvanced
            is FormStepResult.NoAction -> StepOutcome.NoAction
            is FormStepResult.StopWithMessage -> StepOutcome.StopHint(r.message)
        }
    }

    private sealed class StepOutcome {
        data class Clicked(val label: String, val modalOpen: Boolean) : StepOutcome()
        data object NoAction : StepOutcome()
        data object CardAdvanced : StepOutcome()
        /** Scroll pedido pela IA visão (já executado). */
        data object VisionScrolled : StepOutcome()
        data object StopAiManual : StepOutcome()
        data object StopNoEndpoint : StepOutcome()
        data class StopHint(val message: String) : StepOutcome()
    }

    private class AiCallBudget {
        private var calls = 0
        private val signaturesUsed = HashSet<Int>()

        fun visibleSignature(texts: List<String>): Int =
            texts.joinToString("\u0001").hashCode()

        fun mayCallAi(step: Int, texts: List<String>): Boolean {
            if (step < FIRST_STEP_ALLOW_AI) return false
            if (calls >= MAX_AI_CALLS_PER_SESSION) return false
            val sig = visibleSignature(texts)
            if (signaturesUsed.contains(sig)) return false
            return true
        }

        fun recordAiCall(texts: List<String>) {
            calls++
            signaturesUsed.add(visibleSignature(texts))
        }
    }

    private class VisionCallBudget {
        private var calls = 0
        private val signaturesUsed = HashSet<Int>()

        fun visibleSignature(texts: List<String>): Int =
            texts.joinToString("\u0001").hashCode()

        fun mayCall(step: Int, texts: List<String>): Boolean {
            if (step < FIRST_STEP_ALLOW_AI) return false
            if (calls >= MAX_VISION_CALLS_PER_SESSION) return false
            val sig = visibleSignature(texts)
            if (signaturesUsed.contains(sig)) return false
            return true
        }

        fun recordCall(texts: List<String>) {
            calls++
            signaturesUsed.add(visibleSignature(texts))
        }
    }

    @Volatile
    private var sessionRunning = false

    fun tryStartSession(): Boolean = synchronized(this) {
        if (sessionRunning) return false
        sessionRunning = true
        true
    }

    fun endSession() {
        synchronized(this) { sessionRunning = false }
    }

    fun runSession(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        onFeedback: (String, Long) -> Unit,
    ) {
        val endpointConfigured = appContext.getString(R.string.ai_inspection_endpoint).trim().isNotEmpty()
        val visionEndpointConfigured =
            appContext.getString(R.string.ai_inspection_vision_endpoint).trim().isNotEmpty()
        var steps = 0
        var clicks = 0
        var noActionStreak = 0
        var modalOptionClickedPendingApply = false
        val aiBudget = AiCallBudget()
        val visionBudget = VisionCallBudget()

        try {
            onFeedback(appContext.getString(R.string.feedback_orchestration_started), 3500L)

            sessionLoop@ while (steps++ < MAX_STEPS) {
                var ocrForBlocks: List<OcrTextExtractor.OcrTextLine>? = null
                var sessionBitmap: android.graphics.Bitmap? = null
                var ocrBitmapW = 0
                var ocrBitmapH = 0
                if (ScreenCaptureHolder.hasProjection()) {
                    val p = ScreenCaptureHolder.getProjection()
                    if (p != null) {
                        try {
                            val bm = ScreenshotManager.captureScreenBitmap(appContext, p, 0)
                            if (bm != null) {
                                ocrBitmapW = bm.width
                                ocrBitmapH = bm.height
                                sessionBitmap = bm
                                OcrTextExtractor.recognizeSync(bm).fold(
                                    onSuccess = { (lines, _) ->
                                        ocrForBlocks = lines.sortedBy { it.y } // permitir vazio (ecrã sem texto ML Kit)
                                    },
                                    onFailure = { e ->
                                        Log.w(TAG, "OCR failed, will use a11y text for rules", e)
                                    },
                                )
                            } else {
                                Log.w(TAG, "captureScreenBitmap null, a11y-only read for this pass")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "OCR/capture", e)
                        }
                    }
                } else {
                    Log.d(TAG, "no media projection, this cycle: a11y (no *Required* colour / icon_next on screenshot)")
                }

                val a11yTexts: List<String> = try {
                    service.getVisibleTexts()
                } catch (e: Exception) {
                    Log.e(TAG, "getVisibleTexts", e)
                    emptyList()
                }
                val readLinesForRules: List<String> = a11yTexts.ifEmpty {
                    ocrForBlocks?.map { it.text } ?: emptyList()
                }
                if (readLinesForRules.isEmpty()) {
                    onFeedback(appContext.getString(R.string.feedback_no_texts), 7000L)
                    sessionBitmap?.let { b -> if (!b.isRecycled) b.recycle() }
                    return
                }

                if (steps == 1) {
                    Log.d(
                        TAG,
                        "firstPass capture=${sessionBitmap != null} ocr=${ocrForBlocks?.size ?: 0} a11y=${a11yTexts.size} " +
                            "read=${readLinesForRules.size} preview=${readLinesForRules.take(10)}",
                    )
                }

                if (FormFillHeuristics.shouldStopAtCommentsSection(a11yTexts)) {
                    onFeedback(
                        appContext.getString(R.string.feedback_orchestration_stopped_comments, clicks),
                        8000L,
                    )
                    sessionBitmap?.let { b -> if (!b.isRecycled) b.recycle() }
                    return
                }

                // Se estamos num modal com botão Apply e acabámos de escolher uma opção, tenta fechar.
                val modalOpen = isApplyModalOpen(a11yTexts)
                if (modalOpen && modalOptionClickedPendingApply) {
                    if (service.clickByText("Apply")) {
                        modalOptionClickedPendingApply = false
                        clicks++
                        noActionStreak = 0
                        Thread.sleep(CLICK_SETTLE_MS)
                        sessionBitmap?.let { b -> if (!b.isRecycled) b.recycle() }
                        continue
                    }
                    if (MyAccessibilityService.isApplyModalAnsweredFor(service, null)) {
                        Thread.sleep(240L)
                        sessionBitmap?.let { b -> if (!b.isRecycled) b.recycle() }
                        continue
                    }
                } else if (!modalOpen) {
                    modalOptionClickedPendingApply = false
                }

                val fiOutcome = tryFiOccupiedTypedFields(profile, a11yTexts, service, appContext, onFeedback)
                if (fiOutcome is StepOutcome.Clicked) {
                    clicks++
                    noActionStreak = 0
                    modalOptionClickedPendingApply = fiOutcome.modalOpen && fiOutcome.label.lowercase() != "apply"
                    Thread.sleep(CLICK_SETTLE_MS)
                    sessionBitmap?.let { b -> if (!b.isRecycled) b.recycle() }
                    continue@sessionLoop
                }

                val fullScreenBlob = readLinesForRules.joinToString(" | ")
                val ocrLineList = ocrForBlocks.orEmpty()
                val bw = sessionBitmap?.width?.takeIf { it > 0 } ?: ocrBitmapW
                val bh = sessionBitmap?.height?.takeIf { it > 0 } ?: ocrBitmapH
                val wSafe = if (bw > 0) bw else 1
                val hSafe = if (bh > 0) bh else 1
                var stepOutcome: StepOutcome = trySingleCardOcrDriven(
                    appContext,
                    profile,
                    service,
                    ocrLineList,
                    readLinesForRules,
                    fullScreen = sessionBitmap,
                    fullScreenBlob = fullScreenBlob,
                    bitmapW = wSafe,
                    bitmapH = hSafe,
                    onFeedback = onFeedback,
                )
                if (stepOutcome is StepOutcome.NoAction) {
                    stepOutcome = trySupplementalAfterOcr(
                        appContext,
                        profile,
                        service,
                        a11yTexts,
                        modalOpen = modalOpen,
                        endpointConfigured = endpointConfigured,
                        visionEndpointConfigured = visionEndpointConfigured,
                        onFeedback = onFeedback,
                        step = steps,
                        aiBudget = aiBudget,
                        visionBudget = visionBudget,
                    )
                }
                sessionBitmap?.let { b -> if (!b.isRecycled) b.recycle() }
                sessionBitmap = null

                when (stepOutcome) {
                    is StepOutcome.Clicked -> {
                        clicks++
                        noActionStreak = 0
                        modalOptionClickedPendingApply = stepOutcome.modalOpen && stepOutcome.label.lowercase() != "apply"
                        Thread.sleep(CLICK_SETTLE_MS)
                    }
                    is StepOutcome.CardAdvanced -> {
                        noActionStreak = 0
                        // O atraso já é aplicado em [NextQuestionNavigation.moveToNextQuestion].
                    }
                    is StepOutcome.VisionScrolled -> {
                        noActionStreak = 0
                        Thread.sleep(SCROLL_SETTLE_MS)
                    }
                    is StepOutcome.NoAction -> {
                        if (noActionStreak == 0) {
                            Log.d(
                                TAG,
                                "no action (single-card + suplemento) -> *next* ou scroll",
                            )
                        }
                        noActionStreak++
                        if (noActionStreak >= NO_ACTION_ITERATIONS_BEFORE_STOP) {
                            if (!aiBudget.mayCallAi(steps, a11yTexts) && clicks == 0) {
                                onFeedback(
                                    appContext.getString(R.string.feedback_orchestration_weak_context),
                                    9000L,
                                )
                            } else {
                                onFeedback(
                                    appContext.getString(R.string.feedback_orchestration_stagnant, clicks),
                                    8000L,
                                )
                            }
                            return
                        }
                        if (NextQuestionNavigation.moveToNextQuestion(
                                appContext, service, null,
                            )
                        ) {
                            Thread.sleep(NextQuestionNavigation.POST_MOVE_SETTLE_MS)
                        } else if (service.performScrollForward()) {
                            Thread.sleep(SCROLL_SETTLE_MS)
                        } else {
                            Thread.sleep(320L)
                        }
                        if (!FormFillHeuristics.hasRequiredMarker(a11yTexts) && noActionStreak < 5) {
                            if (service.performScrollForward()) {
                                Thread.sleep(220L)
                            }
                        }
                    }
                    is StepOutcome.StopAiManual -> return
                    is StepOutcome.StopNoEndpoint -> return
                    is StepOutcome.StopHint -> {
                        onFeedback(stepOutcome.message, 9000L)
                        return
                    }
                }
            }

            onFeedback(appContext.getString(R.string.feedback_orchestration_max_steps, clicks), 7000L)
        } finally {
            endSession()
        }
    }

    /**
     * Só conta como sucesso se a árvore de acessibilidade mudar após o toque (evita spam de
     * "Tocou em: …" quando o clique não faz nada).
     */
    private fun tryClickWithVerifiedScreenChange(
        service: MyAccessibilityService,
        answer: String,
        appContext: Context,
        onFeedback: (String, Long) -> Unit,
    ): Boolean {
        val before = service.snapshotSignature()
        if (!service.clickByText(answer)) return false
        Thread.sleep(POST_CLICK_VERIFY_MS)
        var after = service.snapshotSignature()
        if (before == after) {
            Thread.sleep(POST_CLICK_VERIFY_RETRY_MS)
            after = service.snapshotSignature()
        }
        if (before == after) {
            Log.w(TAG, "click reported success but screen text unchanged, ignoring: $answer")
            return false
        }
        Log.d(TAG, "verified click: $answer")
        onFeedback(appContext.getString(R.string.feedback_answer_ok, answer), 2200L)
        return true
    }

    private fun isApplyModalOpen(visibleTexts: List<String>): Boolean =
        visibleTexts.any { it.trim().equals("Apply", ignoreCase = true) }

    private fun tryClickAllowingModalNoChange(
        service: MyAccessibilityService,
        answer: String,
        appContext: Context,
        onFeedback: (String, Long) -> Unit,
        modalOpen: Boolean,
    ): Boolean {
        if (!modalOpen) return tryClickWithVerifiedScreenChange(service, answer, appContext, onFeedback)
        // Em modais, marcar checkbox às vezes não altera textos lidos; aceitamos clique sem verificação.
        val ok = service.clickByText(answer)
        if (ok) onFeedback(appContext.getString(R.string.feedback_answer_ok, answer), 2000L)
        return ok
    }

    private fun tryFiOccupiedTypedFields(
        profile: InspectionProfile,
        texts: List<String>,
        service: MyAccessibilityService,
        appContext: Context,
        onFeedback: (String, Long) -> Unit,
    ): StepOutcome? {
        if (profile != InspectionProfile.FI_OCCUPIED) return null
        val blob = texts.joinToString("\n").lowercase()
        // Completed Date: se já existe uma data no campo, não mexer.
        if (blob.contains("completed date") && Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b").containsMatchIn(blob)) {
            return null
        }
        // Grass Height: só com placeholder; se já há número, não escrever por cima
        if (blob.contains("grass height") && !blob.contains("enter grass height") && !blob.contains("enter the grass height")) {
            return null
        }
        if (blob.contains("grass height") && blob.contains("enter grass height")) {
            if (service.setTextByMatchAny(listOf("enter grass height", "grass height"), "3")) {
                onFeedback(appContext.getString(R.string.feedback_answer_ok, "3"), 2000L)
                return StepOutcome.Clicked("3", modalOpen = false)
            }
        }
        if ((blob.contains("evidence of this interaction") || blob.contains("we need evidence")) && blob.contains("mortgage")) {
            return null
        }
        if ((blob.contains("evidence of this interaction") || blob.contains("we need evidence")) && blob.contains("enter")) {
            val v = "That they will contact the mortgage"
            if (service.setTextByMatchAny(
                    listOf(
                        "evidence of this interaction",
                        "we need evidence of this interaction",
                        "what did they specifically say",
                        "enter we need evidence",
                    ),
                    v,
                )
            ) {
                onFeedback(appContext.getString(R.string.feedback_answer_ok, "evidence"), 2400L)
                return StepOutcome.Clicked("evidence", modalOpen = false)
            }
        }
        return null
    }

    private fun tryTapPercentWithVerifiedScreenChange(
        service: MyAccessibilityService,
        xPercent: Float,
        yPercent: Float,
        appContext: Context,
        onFeedback: (String, Long) -> Unit,
        reason: String,
    ): Boolean {
        val before = service.snapshotSignature()
        if (!service.tapByPercent(xPercent, yPercent)) return false
        Thread.sleep(VISION_POST_TAP_MS)
        var after = service.snapshotSignature()
        if (before == after) {
            Thread.sleep(POST_CLICK_VERIFY_RETRY_MS)
            after = service.snapshotSignature()
        }
        if (before == after) {
            Log.w(TAG, "tapByPercent reported OK but screen unchanged ($xPercent%, $yPercent%)")
            return false
        }
        val detail = reason.ifBlank { "…" }
        onFeedback(
            appContext.getString(
                R.string.feedback_vision_tap_reason,
                String.format("%.1f", xPercent),
                String.format("%.1f", yPercent),
                detail,
            ),
            2600L,
        )
        return true
    }

    /**
     * IA com imagem + texto — **depois** de regras JSON e [InspectionDecisionEngine]
     * (a11y-first). Se [null], segue com IA de texto.
     */
    /**
     * Depois de regras por bloco OCR: visão (tap em %) e IA de texto, **sem** cliques
     * globais em "Yes/No" por texto (OCR-first).
     */
    private fun trySupplementalAfterOcr(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        texts: List<String>,
        modalOpen: Boolean,
        endpointConfigured: Boolean,
        visionEndpointConfigured: Boolean,
        onFeedback: (String, Long) -> Unit,
        step: Int,
        aiBudget: AiCallBudget,
        visionBudget: VisionCallBudget,
    ): StepOutcome {
        tryVisionDecision(
            appContext,
            profile,
            service,
            texts,
            visionEndpointConfigured,
            onFeedback,
            step,
            visionBudget,
        )?.let { return it }

        if (!endpointConfigured) {
            onFeedback(
                appContext.getString(R.string.feedback_manual) + "\n\n" +
                    appContext.getString(R.string.feedback_no_texts_hint),
                8000L,
            )
            return StepOutcome.StopNoEndpoint
        }
        if (!aiBudget.mayCallAi(step, texts)) {
            Log.d(TAG, "OCR path: skip AI (budget or warm-up or same signature)")
            return StepOutcome.NoAction
        }
        aiBudget.recordAiCall(texts)
        val response = try {
            AiClient.requestInspect(appContext, texts, profile)
        } catch (e: Exception) {
            Log.e(TAG, "requestInspect", e)
            return StepOutcome.StopHint(
                appContext.getString(
                    R.string.feedback_request_failed,
                    e.message ?: e.javaClass.simpleName,
                ),
            )
        }
        if (response.userHint != null) {
            return StepOutcome.StopHint(response.userHint)
        }
        if (response.answer == InspectionDecisionEngine.MANUAL_REVIEW) {
            onFeedback(appContext.getString(R.string.feedback_ia_chose_manual), 7000L)
            return StepOutcome.StopAiManual
        }
        val ans = response.answer.trim()
        if (isShortYesNo(ans)) {
            Log.d(TAG, "OCR path: skip AI answer Yes/No without OCR bounds (forbidden global tap): $ans")
            return StepOutcome.NoAction
        }
        if (FormFillHeuristics.shouldTrySkipAlreadySelected(ans) && service.isLikelyAlreadySelected(ans)) {
            return StepOutcome.NoAction
        }
        if (!UiClickSafety.shouldAttemptClick(ans, texts)) {
            onFeedback(appContext.getString(R.string.feedback_answer_not_safe, ans), 6000L)
            return StepOutcome.NoAction
        }
        if (tryClickWithVerifiedScreenChange(service, ans, appContext, onFeedback)) {
            Log.d(TAG, "OCR path: AI click (verified) non Yes/No: $ans")
            return StepOutcome.Clicked(ans, modalOpen = modalOpen)
        }
        return StepOutcome.NoAction
    }

    private fun tryVisionDecision(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        texts: List<String>,
        visionEndpointConfigured: Boolean,
        onFeedback: (String, Long) -> Unit,
        step: Int,
        visionBudget: VisionCallBudget,
    ): StepOutcome? {
        if (!visionEndpointConfigured) return null
        if (!ScreenCaptureHolder.hasProjection()) return null
        val projection = ScreenCaptureHolder.getProjection() ?: return null
        if (!visionBudget.mayCall(step, texts)) return null

        val b64 = try {
            ScreenshotManager.captureJpegBase64(appContext, projection)
        } catch (e: Exception) {
            Log.e(TAG, "captureJpegBase64", e)
            null
        }
        if (b64.isNullOrBlank()) {
            onFeedback(appContext.getString(R.string.feedback_vision_screenshot_failed), 4000L)
            return null
        }
        visionBudget.recordCall(texts)

        val response = AiClient.requestInspectVision(appContext, texts, profile, b64, step)
        if (response.userHint != null) {
            return StepOutcome.StopHint(response.userHint)
        }

        val action = response.action.trim().lowercase()
        when (action) {
            "stop" -> {
                onFeedback(
                    appContext.getString(
                        R.string.feedback_vision_stop,
                        response.reason.ifBlank { action },
                    ),
                    9000L,
                )
                return StepOutcome.StopAiManual
            }
            "manual_review" -> {
                onFeedback(
                    appContext.getString(
                        R.string.feedback_vision_manual_review,
                        response.reason.ifBlank { action },
                    ),
                    9000L,
                )
                return StepOutcome.StopAiManual
            }
            "scroll" -> {
                return if (service.performScrollForward()) {
                    StepOutcome.VisionScrolled
                } else {
                    null
                }
            }
            "tap" -> {
                if (response.confidence < BuildConfig.VISION_TAP_MIN_CONFIDENCE) {
                    onFeedback(
                        appContext.getString(
                            R.string.feedback_vision_manual_review,
                            appContext.getString(
                                R.string.feedback_vision_low_confidence,
                                response.confidence,
                                response.reason,
                            ),
                        ),
                        9000L,
                    )
                    return StepOutcome.StopAiManual
                }
                if (tryTapPercentWithVerifiedScreenChange(
                        service,
                        response.xPercent,
                        response.yPercent,
                        appContext,
                        onFeedback,
                        response.reason,
                    )
                ) {
                    return StepOutcome.Clicked(
                        label = response.answer.ifBlank { "tap" },
                        modalOpen = false,
                    )
                }
                return null
            }
            else -> return null
        }
    }

    private fun tryOneDecision(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        texts: List<String>,
        endpointConfigured: Boolean,
        visionEndpointConfigured: Boolean,
        onFeedback: (String, Long) -> Unit,
        step: Int,
        aiBudget: AiCallBudget,
        visionBudget: VisionCallBudget,
    ): StepOutcome {
        val modalOpen = isApplyModalOpen(texts)
        val screenBlob = texts.joinToString(" | ")
        val policy = FormFillPolicies.forProfile(appContext, profile)
        val onlyRequired = FormFillPolicies.onlyFillRequiredEnabled(profile, appContext)

        val allMapped = FormFieldMapRules.findAllMatchResults(appContext, texts, profile)
        for (mapped in allMapped) {
            val label = questionLabelForLog(mapped)
            if (FormFillPolicies.isDoNotChangeForRule(profile, mapped.matchAll, appContext)) {
                logRuleDecision(mapped.id, label, "do_not_change list -> skip")
                continue
            }
            if (!FormFillHeuristics.canApplyOnlyRequiredRule(onlyRequired, texts, mapped.matchAll, screenBlob)) {
                logRuleDecision(mapped.id, label, "only required: no required marker near question -> skip")
                continue
            }
            val clickText = mapped.clickText
            if (!UiClickSafety.shouldAttemptClick(clickText, texts)) {
                logRuleDecision(mapped.id, label, "not safe to click: $clickText")
                continue
            }
            if (modalOpen && MyAccessibilityService.isApplyModalAnsweredFor(service, clickText)) {
                logRuleDecision(mapped.id, label, "modal Apply: option already marked -> skip")
                continue
            }
            if (FormFillHeuristics.shouldTrySkipAlreadySelected(clickText) && service.isLikelyAlreadySelected(clickText)) {
                logRuleDecision(mapped.id, label, "global: label already likely selected -> skip")
                continue
            }
            val a = service.assessMappedRuleClick(
                mapped.matchAll,
                clickText,
                neverOverwriteExisting = policy.neverOverwriteExisting,
            )
            val statusLine = a.log + (if (a.needsReview) " (needs_review)" else "")
            logRuleDecision(mapped.id, label, statusLine)
            if (!a.canClick) continue

            val ok = if (
                clickText.length <= 3 ||
                clickText.equals("yes", true) ||
                clickText.equals("no", true) ||
                clickText.equals("select", true)
            ) {
                service.clickByTextNearQuestion(clickText, mapped.matchAll) ||
                    tryClickAllowingModalNoChange(service, clickText, appContext, onFeedback, modalOpen)
            } else if (modalOpen && (clickText.equals("none", true) || clickText.startsWith("c", ignoreCase = true))) {
                service.clickByTextInApplyModal(clickText) || tryClickAllowingModalNoChange(
                    service,
                    clickText,
                    appContext,
                    onFeedback,
                    modalOpen,
                )
            } else {
                tryClickAllowingModalNoChange(service, clickText, appContext, onFeedback, modalOpen)
            }
            if (ok) {
                onFeedback(
                    appContext.getString(
                        R.string.feedback_orchestration_rule_action,
                        label,
                        statusLine,
                    ),
                    2800L,
                )
                return StepOutcome.Clicked(clickText, modalOpen = modalOpen)
            }
        }
        if (allMapped.isNotEmpty()) {
            Log.d(
                TAG,
                "all ${allMapped.size} local rule(s) for this screen skipped/needs_review; " +
                    "treating as screen clear -> heuristics / IA / scroll",
            )
        }

        val quick = InspectionDecisionEngine.quickAnswer(texts, profile)
        if (quick != null) {
            if (FormFillHeuristics.shouldTrySkipAlreadySelected(quick) && service.isLikelyAlreadySelected(quick)) {
                Log.d(TAG, "skip already selected (quick): $quick")
                return StepOutcome.NoAction
            }
            if (!UiClickSafety.shouldAttemptClick(quick, texts)) {
                return StepOutcome.NoAction
            }
            if (tryClickAllowingModalNoChange(service, quick, appContext, onFeedback, modalOpen)) {
                return StepOutcome.Clicked(quick, modalOpen = modalOpen)
            }
            return StepOutcome.NoAction
        }

        tryVisionDecision(
            appContext,
            profile,
            service,
            texts,
            visionEndpointConfigured,
            onFeedback,
            step,
            visionBudget,
        )?.let { return it }

        if (!endpointConfigured) {
            onFeedback(
                appContext.getString(R.string.feedback_manual) + "\n\n" +
                    appContext.getString(R.string.feedback_no_texts_hint),
                8000L,
            )
            return StepOutcome.StopNoEndpoint
        }

        if (!aiBudget.mayCallAi(step, texts)) {
            Log.d(TAG, "skip AI step=$step (budget or same signature or warm-up)")
            return StepOutcome.NoAction
        }

        aiBudget.recordAiCall(texts)

        val response = try {
            AiClient.requestInspect(appContext, texts, profile)
        } catch (e: Exception) {
            Log.e(TAG, "requestInspect", e)
            return StepOutcome.StopHint(
                appContext.getString(R.string.feedback_request_failed, e.message ?: e.javaClass.simpleName),
            )
        }

        if (response.userHint != null) {
            return StepOutcome.StopHint(response.userHint)
        }

        if (response.answer == InspectionDecisionEngine.MANUAL_REVIEW) {
            onFeedback(appContext.getString(R.string.feedback_ia_chose_manual), 7000L)
            return StepOutcome.StopAiManual
        }

        val ans = response.answer.trim()
        if (FormFillHeuristics.shouldTrySkipAlreadySelected(ans) && service.isLikelyAlreadySelected(ans)) {
            return StepOutcome.NoAction
        }
        if (!UiClickSafety.shouldAttemptClick(ans, texts)) {
            onFeedback(appContext.getString(R.string.feedback_answer_not_safe, ans), 7000L)
            return StepOutcome.NoAction
        }
        if (tryClickWithVerifiedScreenChange(service, ans, appContext, onFeedback)) {
            Log.d(TAG, "clicked AI (verified): $ans")
            return StepOutcome.Clicked(ans, modalOpen = modalOpen)
        }
        return StepOutcome.NoAction
    }

    private fun questionLabelForLog(m: FormFieldMapRules.MatchResult): String =
        m.matchAll.firstOrNull()?.trim()?.take(52)?.let { s ->
            if (s.length >= 50) s.take(47) + "…" else s
        } ?: m.id

    private fun logRuleDecision(ruleId: String, question: String, status: String) {
        Log.i(TAG, "Rule $ruleId | Q: $question | $status")
    }

    fun startOnExecutor(
        appContext: Context,
        profile: InspectionProfile,
        service: MyAccessibilityService,
        executor: Executor,
        mainHandler: Handler,
        onFeedbackMainThread: (String, Long) -> Unit,
    ) {
        val safe: (String, Long) -> Unit = { msg, dur ->
            mainHandler.post { onFeedbackMainThread(msg, dur) }
        }
        executor.execute {
            runSession(appContext, profile, service, safe)
        }
    }
}
