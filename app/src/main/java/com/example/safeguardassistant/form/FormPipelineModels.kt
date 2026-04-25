package com.example.safeguardassistant.form

import android.graphics.Rect
import com.example.safeguardassistant.OcrTextExtractor

/**
 * Nó legível exposto na árvore de acessibilidade (com bounds em coordenadas de ecrã).
 */
data class AccessibilityTextNode(
    val text: String,
    val bounds: Rect,
    val className: String?,
    val isClickable: Boolean,
    val isChecked: Boolean,
    val isSelected: Boolean,
    val isEditable: Boolean,
    val viewId: String?,
)

/**
 * Linha OCR com grau de confiança (ML Kit não expõe sempre; default conservador).
 */
data class OcrTextLineF(
    val line: OcrTextExtractor.OcrTextLine,
    val confidence: Float = 0.88f,
)

data class ScreenSnapshot(
    val accessibilityNodes: List<AccessibilityTextNode>,
    val a11yPlainTexts: List<String>,
    val ocrLines: List<OcrTextLineF>,
    val screenshot: android.graphics.Bitmap?,
    val width: Int,
    val height: Int,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val screenW: Int get() = if (width > 0) width else 1
    val screenH: Int get() = if (height > 0) height else 1
}

/** Origem de um campo ou rótulo (fusão a11y+OCR). */
enum class SourceBlend { ACCESSIBILITY, OCR, BLENDED, UNKNOWN }

enum class InputType {
    YES_NO,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    DROPDOWN,
    TEXT,
    DATE,
    NUMBER,
    UNKNOWN,
}

data class QuestionOptionF(
    val label: String,
    val normalizedLabel: String,
    val bounds: Rect,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val source: SourceBlend = SourceBlend.OCR,
)

data class QuestionBlockF(
    val id: String,
    val questionText: String,
    val normalizedQuestion: String,
    val options: List<QuestionOptionF>,
    val inputType: InputType,
    val required: Boolean,
    /** Preenchido em [AnswerStateDetector]. */
    var answered: Boolean = false,
    var answerReason: String? = null,
    var selectedAnswer: String? = null,
    var answerState: AnswerStatus = AnswerStatus.UNANSWERED,
    val bounds: Rect,
    val blockConfidence: Float = 0.7f,
    val source: SourceBlend = SourceBlend.OCR,
    val ocrLinesInBand: List<OcrTextExtractor.OcrTextLine> = emptyList(),
)

enum class AnswerStatus {
    ANSWERED,
    UNANSWERED,
    UNCERTAIN,
}

sealed class RuleAnswer {
    data class ClickOption(val label: String) : RuleAnswer()
    data class TypeText(val value: String) : RuleAnswer()
    data class SelectDropdown(val value: String) : RuleAnswer()
    data class ManualReview(val reason: String) : RuleAnswer()
    data class Skip(val reason: String) : RuleAnswer()
}

sealed class RuleCondition {
    data class ScreenContains(val phrases: List<String>, val all: Boolean = true) : RuleCondition()
}

data class FormRuleF(
    val id: String,
    val questionPatterns: List<String>,
    val answer: RuleAnswer,
    val profile: com.example.safeguardassistant.InspectionProfile? = null,
    val requiredOnly: Boolean = true,
    val neverOverwrite: Boolean = true,
    val confidenceRequired: Float = 0.85f,
    val conditional: RuleCondition? = null,
    val manualReviewIfAppears: Boolean = false,
    val notes: String? = null,
)

sealed class PlannedAction {
    data class TapOptionInBlock(
        val block: QuestionBlockF,
        val optionLabel: String,
        val ocrLine: OcrTextExtractor.OcrTextLine?,
        val percentX: Float,
        val percentY: Float,
        val ruleId: String,
        val formMatch: com.example.safeguardassistant.FormFieldMapRules.MatchResult?,
    ) : PlannedAction()

    data class TypeInBlock(
        val block: QuestionBlockF,
        val value: String,
    ) : PlannedAction()

    data class NextScreen(
        val reason: String,
    ) : PlannedAction()

    data class ScrollForward(
        val reason: String,
    ) : PlannedAction()

    data class ManualReview(
        val reason: String,
    ) : PlannedAction()

    data class NoOp(
        val reason: String,
    ) : PlannedAction()
}

