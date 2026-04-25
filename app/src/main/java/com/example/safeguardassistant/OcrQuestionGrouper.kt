package com.example.safeguardassistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agrupa linhas de OCR em blocos de pergunta (só lógica, sem toques no ecrã).
 */
object OcrQuestionGrouper {

    data class QuestionBlock(
        val question: String,
        val yStart: Int,
        val yEnd: Int,
        val options: List<QuestionOption>,
        /** Linhas ML Kit da banda (Y), para mapear cliques por *bounding box*. */
        val ocrLinesInBand: List<OcrTextExtractor.OcrTextLine> = emptyList(),
    ) {
        fun toJsonObject(): JSONObject = JSONObject()
            .put("question", question)
            .put("y_start", yStart)
            .put("y_end", yEnd)
            .put("options", JSONArray().apply { options.forEach { put(it.toJsonObject()) } })
    }

    data class QuestionOption(
        val text: String,
        val y: Int,
    ) {
        fun toJsonObject(): JSONObject =
            JSONObject().put("text", text).put("y", y)
    }

    /**
     * Textos a ignorar (UI genérica / ruído).
     */
    private val ignoredExact = setOf("required", "camera", "gallery", "apply", "select", "ok")

    /** Padrões de pergunta conhecidas (contém, case insensitive). */
    private val knownQuestionSubstrings: List<String> = listOf(
        "change of address",
        "are you able to complete this inspection",
        "is this property located",
        "complete this inspection",
        "property located in a control",
        "we need evidence of this interaction",
    )

    /**
     * 1) Filtra lixo, ordena por Y. 2) Encontra linhas que parecem perguntas. 3) Cada bloco: [Y da pergunta, Y da próxima pergunta).
     * 4) Opções = outras linhas nesse intervalo, excepto a linha da pergunta.
     */
    fun groupQuestions(ocrResults: List<OcrTextExtractor.OcrTextLine>): List<QuestionBlock> {
        val sorted = ocrResults
            .filter { !isIgnoredText(it.text) }
            .sortedBy { it.y }

        if (sorted.isEmpty()) return emptyList()

        val questionRows = sorted
            .filter { isQuestionLine(it) }
            .sortedBy { it.y }
            .distinctBy { "${it.y}\u0001${it.text}" }
        if (questionRows.isEmpty()) {
            // Sem pergunta detectada: tudo fica num único bloco genérico
            val minY = sorted.minOf { it.y }
            val maxY = sorted.maxOf { it.y + it.height }
            return listOf(
                QuestionBlock(
                    question = "[no question line detected]",
                    yStart = minY,
                    yEnd = maxY,
                    options = sorted.map { QuestionOption(it.text, it.y) },
                    ocrLinesInBand = sorted,
                ),
            )
        }

        return buildList {
            for (i in questionRows.indices) {
                val qRow = questionRows[i]
                val yStart = qRow.y
                val yEnd = if (i + 1 < questionRows.size) {
                    questionRows[i + 1].y
                } else {
                    sorted.maxOf { it.y + it.height } + 4
                }

                val inBand = sorted.filter { line ->
                    line.y >= yStart && line.y < yEnd
                }
                // Opções: linhas na banda que não são a linha da pergunta (identidade texto+y)
                val options = inBand
                    .filter { line -> line != qRow }
                    .sortedBy { it.y }
                    .map { QuestionOption(it.text, it.y) }

                add(
                    QuestionBlock(
                        question = qRow.text,
                        yStart = yStart,
                        yEnd = yEnd,
                        options = options,
                        ocrLinesInBand = inBand,
                    ),
                )
            }
        }
    }

    private fun isIgnoredText(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        val low = t.lowercase()
        if (low in ignoredExact) return true
        if (t.length <= 2 && t.all { it.isLetterOrDigit() || it == '*' }) return true
        return false
    }

    private fun isQuestionLine(line: OcrTextExtractor.OcrTextLine): Boolean {
        val t = line.text.trim()
        if (t.isEmpty() || isIgnoredText(t)) return false
        val low = t.lowercase()
        if (knownQuestionSubstrings.any { low.contains(it) }) return true
        if ('?' in t) return true
        // "Frase" longa (não opção curta)
        if (t.length >= 32) return true
        if (t.length >= 18) {
            val wordCount = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            if (wordCount >= 4) return true
        }
        // Evitar confundir "Yes", "No", "Fair", "Poor"
        if (t.length <= 12) {
            val asOption = t.lowercase() in
                setOf("yes", "no", "none", "n/a", "unknown", "other", "fair", "poor", "good")
            if (asOption) return false
        }
        return false
    }

    fun blocksToJsonString(blocks: List<QuestionBlock>, indent: Int = 2): String =
        JSONArray().apply { blocks.forEach { put(it.toJsonObject()) } }.toString(indent)
}
