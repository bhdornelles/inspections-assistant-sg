package com.example.safeguardassistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * Respostas mapeáveis a partir de ficheiros raw por perfil. Entre regras candidatas: primeiro a
 * cuja pergunta ([matchAll]) aparece **mais cedo** na leitura (de cima para baixo), depois
 * [priority], depois tamanho do *match* e *id*.
 *
 * Em **debug**, o serviço de acessibilidade pode acrescentar ` @@package:id/...` ao texto lido;
 * podes usar um fragmento desse id em [matchAll] se for estável na app alvo.
 */
object FormFieldMapRules {

    private const val TAG = "FormFieldMap"
    private const val LONG_PHRASE_MIN = 18

    private fun resIdForProfile(profile: InspectionProfile): Int = when (profile) {
        InspectionProfile.E26RNN -> R.raw.form_field_answers_e26rnn
        InspectionProfile.FI_OCCUPIED -> R.raw.form_field_answers_fi_occupied
        InspectionProfile.FI_VACANT -> R.raw.form_field_answers_fi_vacant
        InspectionProfile.DF -> R.raw.form_field_answers_fi_occupied
    }

    @Volatile
    private var cacheByResId: Map<Int, List<Rule>> = emptyMap()

    private data class Rule(
        val id: String,
        val priority: Int,
        val profileFilter: Set<String>,
        val matchAll: List<String>,
        val clickText: String,
        val shortOptionExactLine: Boolean,
    )

    data class MatchResult(
        val id: String,
        val matchAll: List<String>,
        val clickText: String,
        val shortOptionExactLine: Boolean,
    )

    private fun loadRules(ctx: Context, resId: Int): List<Rule> {
        cacheByResId[resId]?.let { return it }
        val s = try {
            ctx.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "load form_field map", e)
            return emptyList()
        }
        val out = ArrayList<Rule>()
        val root = JSONObject(s)
        val arr = root.optJSONArray("mappings") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "rule_$i")
            val priority = o.optInt("priority", 0)
            val profArr = o.optJSONArray("profiles")
            val profSet = if (profArr == null) emptySet() else (0 until profArr.length())
                .map { profArr.getString(it) }
                .toSet()
            val marr = o.optJSONArray("matchAll")
            if (marr == null) continue
            val matches = (0 until marr.length())
                .map { marr.getString(it) }
            val click = o.optString("clickText", "")
            if (matches.isEmpty() || click.isBlank()) continue
            out += Rule(
                id = id,
                priority = priority,
                profileFilter = profSet,
                matchAll = matches,
                clickText = click.trim(),
                shortOptionExactLine = o.optBoolean("shortOptionExactLine", false),
            )
        }
        cacheByResId = cacheByResId + (resId to out)
        return out
    }

    fun findMatch(context: Context, visibleTexts: List<String>, profile: InspectionProfile): MatchResult? {
        if (visibleTexts.isEmpty()) return null
        val app = context.applicationContext
        val resId = resIdForProfile(profile)
        val rules = loadRules(app, resId)
        if (rules.isEmpty()) return null

        val pName = profile.name
        val screenBlob = buildBlob(visibleTexts)
        val linesLower = visibleTexts.map { it.trim().lowercase(Locale.ROOT) }

        val candidates = rules.asSequence()
            .filter { r -> r.profileFilter.isEmpty() || pName in r.profileFilter }
            .filter { r -> r.matchAll.all { phrase -> phraseMatchesScreen(phrase, linesLower, screenBlob) } }
            .mapNotNull { r -> if (optionOnScreen(r.clickText, visibleTexts, r)) r else null }
            .toList()
        if (candidates.isEmpty()) return null

        val best = candidates
            .minWith(
                compareBy<Rule>(
                    { anchorIndexForRule(it, linesLower, screenBlob) },
                )
                .thenByDescending { it.priority }
                .thenByDescending { it.matchAll.joinToString().length }
                .thenByDescending { it.id },
            )

        Log.d(
            TAG,
            "matched id=${best.id} clickText=${best.clickText} anchorLine=${anchorIndexForRule(best, linesLower, screenBlob)}",
        )
        return toMatchResult(best, linesLower, screenBlob)
    }

    /**
     * Todas as regras candidatas, ordenadas (a mesma ordem que [findMatch] – primeiro = mais prioritária
     * para a mesma tela: âncora Y, [priority], especificidade).
     */
    fun findAllMatchResults(
        context: Context,
        visibleTexts: List<String>,
        profile: InspectionProfile,
    ): List<MatchResult> {
        if (visibleTexts.isEmpty()) return emptyList()
        val app = context.applicationContext
        val resId = resIdForProfile(profile)
        val rules = loadRules(app, resId)
        if (rules.isEmpty()) return emptyList()

        val pName = profile.name
        val screenBlob = buildBlob(visibleTexts)
        val linesLower = visibleTexts.map { it.trim().lowercase(Locale.ROOT) }

        val candidates = rules.asSequence()
            .filter { r -> r.profileFilter.isEmpty() || pName in r.profileFilter }
            .filter { r -> r.matchAll.all { phrase -> phraseMatchesScreen(phrase, linesLower, screenBlob) } }
            .mapNotNull { r -> if (optionOnScreen(r.clickText, visibleTexts, r)) r else null }
            .toList()
        if (candidates.isEmpty()) return emptyList()

        return candidates
            .sortedWith(
                compareBy<Rule>(
                    { anchorIndexForRule(it, linesLower, screenBlob) },
                )
                    .thenByDescending { it.priority }
                    .thenByDescending { it.matchAll.joinToString().length }
                    .thenByDescending { it.id },
            )
            .map { toMatchResult(it, linesLower, screenBlob) }
    }

    private fun toMatchResult(
        best: Rule,
        linesLower: List<String>,
        screenBlob: String,
    ): MatchResult = MatchResult(
        id = best.id,
        matchAll = best.matchAll,
        clickText = best.clickText,
        shortOptionExactLine = best.shortOptionExactLine,
    )

    fun findAnswer(context: Context, visibleTexts: List<String>, profile: InspectionProfile): String? =
        findMatch(context, visibleTexts, profile)?.clickText

    private fun buildBlob(texts: List<String>) =
        texts.joinToString(" | ").lowercase(Locale.ROOT)

    /**
     * Índice da **primeira** linha (ordem = pré-ordem a11y) em que a frase aparece.
     * Frases longas: primeira linha onde o *join* 0..i ainda contém a frase.
     */
    private fun firstOccurrenceLineIndex(phrase: String, linesLower: List<String>, screenBlob: String): Int {
        val p = phrase.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return Int.MAX_VALUE
        if (p.length >= LONG_PHRASE_MIN) {
            if (!screenBlob.contains(p)) return Int.MAX_VALUE
            val acc = StringBuilder()
            for (i in linesLower.indices) {
                if (i > 0) acc.append(" | ")
                acc.append(linesLower[i])
                if (acc.toString().contains(p)) return i
            }
            return Int.MAX_VALUE
        }
        for (i in linesLower.indices) {
            if (linesLower[i].contains(p)) return i
        }
        return Int.MAX_VALUE
    }

    private fun anchorIndexForRule(rule: Rule, linesLower: List<String>, screenBlob: String): Int {
        if (rule.matchAll.isEmpty()) return Int.MAX_VALUE
        return rule.matchAll.minOf { firstOccurrenceLineIndex(it, linesLower, screenBlob) }
    }

    /**
     * Frases curtas têm de aparecer numa **linha** lida (evita combinar palavras de sítios diferentes).
     * Frases longas podem usar o blob completo (perguntas que se partem em vários nós).
     */
    private fun phraseMatchesScreen(phrase: String, linesLower: List<String>, screenBlob: String): Boolean {
        val p = phrase.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return false
        if (p.length >= LONG_PHRASE_MIN) {
            return screenBlob.contains(p) || linesLower.any { line -> line.contains(p) }
        }
        return linesLower.any { line -> line.contains(p) }
    }

    private fun optionOnScreen(click: String, visibleTexts: List<String>, rule: Rule): Boolean {
        if (click.isEmpty()) return false
        if (click.length <= 2 || rule.shortOptionExactLine) {
            if (visibleTexts.any { it.trim().equals(click, ignoreCase = true) }) return true
            if (click.length > 2) {
                // Opção de uma linha que contém o rótulo completo (p.ex. chip)
                return visibleTexts.any { it.trim().contains(click, ignoreCase = true) }
            }
            return false
        }
        for (line in visibleTexts) {
            val t = line.trim()
            if (t.equals(click, ignoreCase = true)) return true
            if (t.contains(click, ignoreCase = true) || click.contains(t, ignoreCase = true) && t.length > 1) {
                if (t.length in click.length - 1..click.length + 2) return true
            }
        }
        return visibleTexts.any { it.contains(click, ignoreCase = true) }
    }

    @Suppress("unused")
    fun clearCache() {
        cacheByResId = emptyMap()
    }
}
