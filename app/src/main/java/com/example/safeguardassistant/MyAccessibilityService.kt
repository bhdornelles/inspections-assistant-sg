package com.example.safeguardassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.example.safeguardassistant.form.AccessibilityTextNode
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private val FORBIDDEN_NODE_SUBSTRINGS = listOf(
            "camera", "gallery", "label", "badge", "queue", "stations",
            "door knock", "direct contact - occupant", "front of house",
            "house #", "address sign", "street scene",
        )

        @Volatile
        private var instanceHolder: MyAccessibilityService? = null

        @JvmStatic
        fun getInstance(): MyAccessibilityService? = instanceHolder

        /**
         * Textos visíveis da janela activa (para UI fora do serviço, ex. overlay).
         * Evita chamadas a [MyAccessibilityService.getVisibleTexts] quando o compilador
         * não faz *smart cast* do resultado de [getInstance].
         */
        @JvmStatic
        fun readVisibleTextsOrEmpty(): List<String> {
            val s = instanceHolder ?: return emptyList()
            return s.getVisibleTexts()
        }

        /** Modal *Apply*: opção já marcada (ou qualquer, se [optionText] for null). */
        @JvmStatic
        fun isApplyModalAnsweredFor(service: MyAccessibilityService, optionText: String?): Boolean =
            service.isApplyModalAnswered(optionText)

        private const val TAG = "MyAccessibilityService"
    }

    /**
     * Converte percentagens do ecrã real em toque via [dispatchGesture].
     * Deve ser chamado a partir de uma thread em segundo plano (o ciclo do orquestrador).
     * Devolve true só se o callback reportar [GestureResultCallback.onCompleted].
     */
    fun tapByPercent(xPercent: Float, yPercent: Float): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "tapByPercent: chame a partir de uma thread em segundo plano")
            return false
        }
        val x = xPercent.coerceIn(0f, 100f)
        val y = yPercent.coerceIn(0f, 100f)
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels.toFloat().coerceAtLeast(1f)
            val h = metrics.heightPixels.toFloat().coerceAtLeast(1f)
            val px = (x / 100f * w).coerceIn(0f, w - 1f)
            val py = (y / 100f * h).coerceIn(0f, h - 1f)
            val path = Path().apply { moveTo(px, py) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 85)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null,
            )
            if (!ok) latch.countDown()
        }
        val waited = latch.await(4, TimeUnit.SECONDS)
        return waited && completed.get()
    }

    /**
     * *Swipe* de ([fromX],[fromY]) a ([toX],[toY]) em percentagem do ecrã (0–100). Thread de fundo.
     */
    fun swipeByPercent(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Int = 380,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "swipeByPercent: chame a partir de uma thread em segundo plano")
            return false
        }
        val x1 = fromX.coerceIn(0f, 100f)
        val y1 = fromY.coerceIn(0f, 100f)
        val x2 = toX.coerceIn(0f, 100f)
        val y2 = toY.coerceIn(0f, 100f)
        val d = durationMs.coerceIn(80, 900)
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels.toFloat().coerceAtLeast(1f)
            val h = metrics.heightPixels.toFloat().coerceAtLeast(1f)
            val px1 = (x1 / 100f * w).coerceIn(0f, w - 1f)
            val py1 = (y1 / 100f * h).coerceIn(0f, h - 1f)
            val px2 = (x2 / 100f * w).coerceIn(0f, w - 1f)
            val py2 = (y2 / 100f * h).coerceIn(0f, h - 1f)
            val path = Path().apply { moveTo(px1, py1); lineTo(px2, py2) }
            val stroke = GestureDescription.StrokeDescription(path, 0, d.toLong())
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null,
            )
            if (!ok) latch.countDown()
        }
        val waited = latch.await(4, TimeUnit.SECONDS)
        return waited && completed.get()
    }

    /**
     * Qualquer rádio ou *checkbox* marcado ou nó *selected* (pergunta ativa já respondida).
     */
    fun isAnyCheckableOrSelectedInWindow(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            anyCheckableOrSelectedInSubtree(root)
        } finally {
            root.recycle()
        }
    }

    private fun anyCheckableOrSelectedInSubtree(n: AccessibilityNodeInfo): Boolean {
        if ((n.isCheckable && n.isChecked) || n.isSelected) return true
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (anyCheckableOrSelectedInSubtree(c)) {
                c.recycle()
                return true
            }
            c.recycle()
        }
        return false
    }

    /**
     * *EditText* (ou idêntico) com texto visível que não parece só *placeholder* / *Select*.
     */
    fun hasEditTextWithNonTrivialValueInWindow(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            hasNonTrivialEditableInSubtree(root)
        } finally {
            root.recycle()
        }
    }

    private fun hasNonTrivialEditableInSubtree(n: AccessibilityNodeInfo): Boolean {
        val isEd = n.isEditable || n.className?.toString()?.contains("Edit", ignoreCase = true) == true
        if (isEd && n.isEnabled) {
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && !isPlaceholderish(t)) {
                return true
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (hasNonTrivialEditableInSubtree(c)) {
                c.recycle()
                return true
            }
            c.recycle()
        }
        return false
    }

    private fun isPlaceholderish(t: String): Boolean {
        val s = t.lowercase()
        if (s == "select" || s == "ok") return true
        if (s == "n/a" || s == "na" || s == "—" || s == "-") return true
        if (s.startsWith("enter ") && s.length < 22) return true
        if (s.startsWith("select ") && s.length < 28) return true
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceHolder = this
    }

    override fun onDestroy() {
        instanceHolder = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // MVP: events not required; we poll rootInActiveWindow on demand.
    }

    override fun onInterrupt() {
        // no-op
    }

    /**
     * Ordem = pré-ordem de árvore. **Não** fazer [distinct] global: o mesmo "Yes" / "No" pode
     * pertencer a blocos de perguntas distintos; o colapso quebrava [clickByTextNearQuestion].
     */
    fun getVisibleTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            val out = mutableListOf<String>()
            collectTexts(root, out)
            out
        } finally {
            root.recycle()
        }
    }

    /**
     * Árvore achatada com textos e limites (ecrã) para o pipeline de blocos de pergunta.
     * Cap de nós para evitar custo excessivo.
     */
    fun collectAccessibilityTextNodes(): List<AccessibilityTextNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityTextNode>(256)
        val scratch = Rect()
        val cap = 500
        try {
            fun visit(n: AccessibilityNodeInfo): Boolean {
                if (out.size >= cap) return false
                n.getBoundsInScreen(scratch)
                val t = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    n.hintText?.toString()?.trim().orEmpty()
                } else {
                    ""
                }
                val label = when {
                    t.isNotEmpty() -> t
                    desc.isNotEmpty() -> desc
                    hint.isNotEmpty() -> hint
                    else -> ""
                }
                val w = scratch.width()
                val h = scratch.height()
                val hasBox = w > 0 && h > 0
                val include = hasBox && (label.isNotEmpty() || n.isCheckable || n.isEditable)
                if (include) {
                    val display = when {
                        t.isNotEmpty() -> t
                        desc.isNotEmpty() -> desc
                        hint.isNotEmpty() -> hint
                        else -> n.className?.toString() ?: "·"
                    }
                    out.add(
                        AccessibilityTextNode(
                            text = display,
                            bounds = Rect(scratch),
                            className = n.className?.toString(),
                            isClickable = n.isClickable,
                            isChecked = n.isChecked,
                            isSelected = n.isSelected,
                            isEditable = n.isEditable,
                            viewId = n.viewIdResourceName,
                        ),
                    )
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    if (!visit(c)) {
                        c.recycle()
                        return false
                    }
                    c.recycle()
                }
                return true
            }
            visit(root)
        } finally {
            root.recycle()
        }
        return out
    }

    /** Hash do conteúdo lido; útil para verificar se um clique alterou o ecrã. */
    fun snapshotSignature(): Int =
        getVisibleTexts().joinToString("\u0001").hashCode()

    /**
     * Rádio/checkbox com o mesmo rótulo que [option] já seleccionado (evita clique redundante).
     * Não fiável para respostas curtas partilhadas por várias perguntas.
     */
    fun isLikelyAlreadySelected(option: String): Boolean {
        val t = option.trim()
        if (t.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            nodeShowsSelectedOption(root, t)
        } finally {
            root.recycle()
        }
    }

    /** Tenta fazer scroll para baixo no primeiro contentor scrollable da janela activa. */
    fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            scrollForwardInSubtree(root)
        } finally {
            root.recycle()
        }
    }

    private fun scrollForwardInSubtree(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollForwardInSubtree(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun nodeShowsSelectedOption(node: AccessibilityNodeInfo, option: String): Boolean {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val matchesLabel = option.equals(text, ignoreCase = true) ||
            option.equals(desc, ignoreCase = true)
        if (matchesLabel && (node.isChecked || node.isSelected)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeShowsSelectedOption(child, option)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    fun clickByText(answer: String): Boolean {
        val q = answer.trim()
        if (q.isEmpty()) return false
        if (UiClickSafety.isForbiddenAnswer(q)) return false
        if (tryExactClick(q)) return true
        if (q.length in 2..40) {
            if (tryContainsSingleMatchClick(q)) return true
        }
        return false
    }

    /**
     * Quando existem muitos botões repetidos (ex.: vários Yes/No), tenta clicar na opção [answer]
     * dentro do mesmo "bloco" da pergunta (ancorado por [questionMatchAny]).
     */
    fun clickByTextNearQuestion(answer: String, questionMatchAny: List<String>): Boolean {
        val ans = answer.trim()
        if (ans.isEmpty()) return false
        if (UiClickSafety.isForbiddenAnswer(ans)) return false
        val needles = questionMatchAny.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return false

        val root = rootInActiveWindow ?: return false
        return try {
            val questionNode = findFirstNodeContainingAny(root, needles) ?: return false
            try {
                // Sobe até um container razoável e procura a resposta dentro dele.
                var scope: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(questionNode)
                var hops = 0
                while (scope != null && hops < 3) {
                    if (tryClickAnswerInside(scope, ans)) {
                        scope.recycle()
                        return true
                    }
                    val parent = scope.parent
                    scope.recycle()
                    scope = parent
                    hops++
                }
                false
            } finally {
                questionNode.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Clique dentro de um modal que tenha botão "Apply" (checkbox list).
     * Evita falhar por rótulos curtos repetidos ("None").
     */
    fun clickByTextInApplyModal(answer: String): Boolean {
        val ans = answer.trim()
        if (ans.isEmpty()) return false
        if (UiClickSafety.isForbiddenAnswer(ans)) return false

        val root = rootInActiveWindow ?: return false
        return try {
            val applyNode = findFirstNodeContainingAny(root, listOf("apply")) ?: return false
            try {
                var scope: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(applyNode)
                var hops = 0
                while (scope != null && hops < 4) {
                    if (tryClickAnswerInside(scope, ans)) {
                        scope.recycle()
                        return true
                    }
                    val parent = scope.parent
                    scope.recycle()
                    scope = parent
                    hops++
                }
                false
            } finally {
                applyNode.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * True se a pergunta (ancorada por [questionMatchAny]) já tem algum rádio/checkbox marcado
     * OU se o próprio [answer] já está marcado no mesmo bloco.
     */
    fun isQuestionAnsweredNearQuestion(questionMatchAny: List<String>, answer: String? = null): Boolean {
        val needles = questionMatchAny.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return false
        val ans = answer?.trim()?.takeIf { it.isNotEmpty() }

        val root = rootInActiveWindow ?: return false
        return try {
            val questionNode = findFirstNodeContainingAny(root, needles) ?: return false
            try {
                var scope: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(questionNode)
                var hops = 0
                while (scope != null && hops < 3) {
                    // 1) Já existe algo checkable marcado no bloco? então está respondido.
                    if (subtreeHasChecked(scope)) {
                        scope.recycle()
                        return true
                    }
                    // 2) Se foi passado answer, verifica especificamente esse answer marcado.
                    if (ans != null && subtreeHasCheckedOption(scope, ans)) {
                        scope.recycle()
                        return true
                    }
                    val parent = scope.parent
                    scope.recycle()
                    scope = parent
                    hops++
                }
                false
            } finally {
                questionNode.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    data class FormRuleAssessment(
        val canClick: Boolean,
        val log: String,
        val needsReview: Boolean = false,
    )

    /**
     * Decisão de segurança: não tocar se já houver resposta, outra resposta, ou valor em texto
     * visível. Se incerto, [canClick] = false, [needsReview] = true.
     */
    fun assessMappedRuleClick(
        questionMatchAll: List<String>,
        targetClickText: String,
        neverOverwriteExisting: Boolean = true,
    ): FormRuleAssessment {
        val needles = questionMatchAll.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (needles.isEmpty() || targetClickText.isBlank()) {
            return FormRuleAssessment(false, "assess: empty match or target", needsReview = true)
        }
        val t = targetClickText.trim()
        val root = rootInActiveWindow ?: return FormRuleAssessment(
            canClick = false,
            log = "assess: no window -> needs_review",
            needsReview = true,
        )
        return try {
            val questionNode = findFirstNodeContainingAny(root, needles) ?: return FormRuleAssessment(
                canClick = false,
                log = "assess: question node not found -> needs_review",
                needsReview = true,
            )
            try {
                var scope: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(questionNode)
                var hops = 0
                var decision: FormRuleAssessment? = null
                while (scope != null && hops < 4) {
                    decision = assessInScope(scope, t, neverOverwriteExisting, needles)
                    if (decision != null) {
                        scope.recycle()
                        return decision
                    }
                    val parent = scope.parent
                    scope.recycle()
                    scope = parent
                    hops++
                }
                FormRuleAssessment(
                    canClick = true,
                    log = "unanswered in scope (no check conflict) -> target $t",
                )
            } finally {
                questionNode.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun assessInScope(
        scope: AccessibilityNodeInfo,
        target: String,
        neverOverwrite: Boolean,
        needles: List<String>,
    ): FormRuleAssessment? {
        if (subtreeHasCheckedOption(scope, target)) {
            return FormRuleAssessment(
                canClick = false,
                log = "already answered: target selected ($target) -> skip",
            )
        }
        if (subtreeHasChecked(scope) && !subtreeHasCheckedOption(scope, target)) {
            return FormRuleAssessment(
                canClick = false,
                log = "other option selected in block (not $target) -> needs_review; do not toggle",
                needsReview = true,
            )
        }
        if (neverOverwrite && editableSubtreeHasUserText(scope, target)) {
            return FormRuleAssessment(
                canClick = false,
                log = "text field has value in block -> skip (never overwrite)",
            )
        }
        if (isLikelyPreselectedValueScopeOnly(scope, target)) {
            return FormRuleAssessment(
                canClick = false,
                log = "target already set in block (selected chip / value) -> skip",
            )
        }
        return null
    }

    private fun isLikelyPreselectedValueScopeOnly(scope: AccessibilityNodeInfo, target: String): Boolean {
        val t = target.trim()
        if (t.isEmpty()) return false
        return subtreeContainsSelectedLabel(scope, t)
    }

    private fun subtreeContainsSelectedLabel(node: AccessibilityNodeInfo, value: String): Boolean {
        val t = value.trim()
        if (t.isEmpty()) return false
        val tx = node.text?.toString().orEmpty()
        if (node.isSelected && !node.isCheckable && (tx.equals(t, ignoreCase = true) || tx.contains(t, ignoreCase = true))) {
            return true
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val f = subtreeContainsSelectedLabel(c, t)
            c.recycle()
            if (f) return true
        }
        return false
    }

    private fun editableSubtreeHasUserText(
        block: AccessibilityNodeInfo,
        target: String,
    ): Boolean {
        if (!block.refresh()) return false
        val n = findEditableInSubtree(block) ?: return false
        return try {
            if (!n.refresh()) return false
            val s = n.text?.toString()?.trim().orEmpty()
            if (s.isEmpty() || s.equals("Select", true)) return false
            if (s.equals(target, true)) return false
            if (s.contains("enter ", true) && s.length < 16) return false
            s.length > 0
        } finally {
            n.recycle()
        }
    }

    private fun findEditableInSubtree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if ((node.isEditable || cls.contains("Edit", true)) && node.isEnabled) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val f = findEditableInSubtree(c)
            c.recycle()
            if (f != null) return f
        }
        return null
    }

    /**
     * True se dentro do modal (ancorado por "Apply") já existir alguma opção marcada,
     * ou se [answer] já estiver marcada.
     */
    fun isApplyModalAnswered(answer: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val ans = answer?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            val applyNode = findFirstNodeContainingAny(root, listOf("apply")) ?: return false
            try {
                var scope: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(applyNode)
                var hops = 0
                while (scope != null && hops < 4) {
                    if (subtreeHasChecked(scope)) {
                        scope.recycle()
                        return true
                    }
                    if (ans != null && subtreeHasCheckedOption(scope, ans)) {
                        scope.recycle()
                        return true
                    }
                    val parent = scope.parent
                    scope.recycle()
                    scope = parent
                    hops++
                }
                false
            } finally {
                applyNode.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Define texto no primeiro campo editável cujo texto/hint/descrição combine com [matchAny] (contains, lowercase).
     * Usar para números/textos curtos em campos do formulário Safeguard.
     */
    fun setTextByMatchAny(matchAny: List<String>, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        val needles = matchAny.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return false

        val root = rootInActiveWindow ?: return false
        return try {
            val target = findEditableByMatchAny(root, needles)
            if (target == null) return false
            try {
                // Garantir foco primeiro
                if (target.isFocusable) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                target.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun tryExactClick(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            findNodesWithText(root, target, matches)
            val safe = ArrayList<AccessibilityNodeInfo>()
            for (n in matches) {
                if (isForbiddenClickNode(n)) n.recycle() else safe.add(n)
            }
            if (isAmbiguousShortTarget(target) && safe.size != 1) {
                safe.forEach { it.recycle() }
                return false
            }
            for (node in safe) {
                try {
                    if (tryClickSelfOrAncestor(node)) return true
                } finally {
                    node.recycle()
                }
            }
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * Quando o rotulo nao bate 100% (espacos, "Sim"/"Yes", etc.): procura [target] contido
     * em text/contentDescription, so se houver exatamente um nó folha util.
     */
    private fun tryContainsSingleMatchClick(target: String): Boolean {
        val t = target.lowercase()
        val root = rootInActiveWindow ?: return false
        return try {
            val buf = mutableListOf<AccessibilityNodeInfo>()
            findNodesWithTextContains(root, t, buf)
            val safe = ArrayList<AccessibilityNodeInfo>()
            for (n in buf) {
                if (isForbiddenClickNode(n)) n.recycle() else safe.add(n)
            }
            // "contains" só é seguro com um único alvo; senão o primeiro na árvore pode ser o errado.
            if (safe.size != 1) {
                safe.forEach { it.recycle() }
                return false
            }
            val node = safe[0]
            return try {
                tryClickSelfOrAncestor(node)
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findNodesWithTextContains(
        node: AccessibilityNodeInfo,
        targetLower: String,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        fun sMatches(s: String?): Boolean {
            val a = s?.toString()?.trim()?.lowercase() ?: return false
            if (a == targetLower) return true
            // Evita "no" em "notice"/"notification", "yes" em palavras maiores raras, etc.
            if (targetLower.length < 4) return false
            return a.contains(targetLower)
        }
        if (sMatches(node.text?.toString()) || sMatches(node.contentDescription?.toString())) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesWithTextContains(child, targetLower, out)
            child.recycle()
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        // Percorrer toda a árvore: muitas apps de formulário marcam nós como não visíveis mas ainda têm texto útil.
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(annotateWithViewId(it, node)) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.add(annotateWithViewId(it, node))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                out.add(annotateWithViewId(it, node))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                out.add(annotateWithViewId(it, node))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.tooltipText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                out.add(annotateWithViewId(it, node))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.paneTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
            child.recycle()
        }
    }

    /**
     * Em **debug**, junta o `viewIdResourceName` (ex.: `com.safeguard:id/field_xyz`) ao texto lido.
     * Assim o "blob" e o JSON podem usar frases **estáveis** além do rótulo visível — se a app alvo os expuser.
     */
    private fun annotateWithViewId(visible: String, node: AccessibilityNodeInfo): String {
        if (!BuildConfig.ANNOTATE_A11Y_WITH_VIEW_IDS) return visible
        val rid = node.viewIdResourceName?.trim().orEmpty()
        if (rid.isEmpty() || rid.length > 180) return visible
        return "$visible @@$rid"
    }

    private fun findNodesWithText(
        node: AccessibilityNodeInfo,
        target: String,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (target.equals(text, ignoreCase = true) || target.equals(desc, ignoreCase = true)) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesWithText(child, target, out)
            child.recycle()
        }
    }

    private fun isAmbiguousShortTarget(target: String): Boolean {
        val t = target.trim().lowercase()
        if (t.length <= 3) return true
        return t in setOf("unknown", "owner", "fair", "poor", "good", "none")
    }

    private fun isForbiddenClickNode(node: AccessibilityNodeInfo): Boolean {
        val bag = buildString {
            append(node.text?.toString().orEmpty())
            append(' ')
            append(node.contentDescription?.toString().orEmpty())
        }.lowercase()
        if (bag.isBlank()) return false
        return FORBIDDEN_NODE_SUBSTRINGS.any { bag.contains(it) }
    }

    private fun tryClickSelfOrAncestor(match: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(match)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                node.recycle()
                return true
            }
            val parent = node.parent
            node.recycle()
            node = parent
        }
        return false
    }

    private fun findFirstNodeContainingAny(node: AccessibilityNodeInfo, needles: List<String>): AccessibilityNodeInfo? {
        val bag = buildString {
            append(node.text?.toString().orEmpty())
            append('\n')
            append(node.contentDescription?.toString().orEmpty())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                append('\n')
                append(node.hintText?.toString().orEmpty())
            }
        }.lowercase()
        if (needles.any { bag.contains(it) }) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstNodeContainingAny(child, needles)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun tryClickAnswerInside(scope: AccessibilityNodeInfo, answer: String): Boolean {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        findNodesWithText(scope, answer, matches)
        if (matches.isEmpty()) return false
        // Preferir o primeiro "seguro" clicável/ancestro.
        for (m in matches) {
            try {
                if (isForbiddenClickNode(m)) continue
                if (tryClickSelfOrAncestor(m)) return true
            } finally {
                m.recycle()
            }
        }
        return false
    }

    private fun subtreeHasChecked(node: AccessibilityNodeInfo): Boolean {
        if ((node.isCheckable && node.isChecked) || node.isSelected) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = subtreeHasChecked(child)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun subtreeHasCheckedOption(node: AccessibilityNodeInfo, answer: String): Boolean {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val matchesLabel = answer.equals(text, ignoreCase = true) || answer.equals(desc, ignoreCase = true)
        if (matchesLabel && ((node.isCheckable && node.isChecked) || node.isSelected)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = subtreeHasCheckedOption(child, answer)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun findEditableByMatchAny(node: AccessibilityNodeInfo, needles: List<String>): AccessibilityNodeInfo? {
        fun hay(node: AccessibilityNodeInfo): String {
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
            return "$t\n$d\n$h".lowercase()
        }
        val blob = hay(node)
        val matches = needles.any { blob.contains(it) }
        val isEditable = (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true)
        if (matches && isEditable && node.isEnabled) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableByMatchAny(child, needles)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
