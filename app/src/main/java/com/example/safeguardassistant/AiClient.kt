package com.example.safeguardassistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Chamada HTTP ao teu backend (proxy Anthropic).
 * A **API key fica no servidor** (`ANTHROPIC_API_KEY` em `.env`), não no Android.
 */
object AiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Anthropic + rede: primeiro pedido pode ser lento; evitar timeout falso-positivo.
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    fun client(): OkHttpClient = httpClient

    data class InspectResponse(
        /** Valor para [MyAccessibilityService.clickByText] ou [InspectionDecisionEngine.MANUAL_REVIEW]. */
        val answer: String,
        /** Se não for null, mostrar ao utilizador (servidor offline, chave em falta, etc.). */
        val userHint: String? = null,
    )

    data class InspectVisionResponse(
        val action: String,
        val answer: String,
        val xPercent: Float,
        val yPercent: Float,
        val confidence: Float,
        val reason: String,
        val userHint: String? = null,
    )

    private fun loadFiOccupiedRulesJson(context: Context): String =
        runCatching {
            context.resources.openRawResource(R.raw.fi_occupied_rules).bufferedReader().use { it.readText() }
        }.getOrDefault("{}")

    private fun buildSystemPrompt(profile: InspectionProfile, rulesJson: String): String =
        """
        You are an assistant completing Safeguard property inspection forms (Safeguard-style survey).
        Inspection Type: ${profile.name}
        Business rules (JSON — apply when they clearly match the situation on screen):
        $rulesJson
        Your job:
        - Read the numbered lines from the user message (accessibility strings). They may be incomplete or out of order.
        - Infer which SINGLE question is the best one to answer next; prefer an unanswered or invalid "Select" / red-style required field if obvious from wording.
        - The "answer" you return MUST appear verbatim (or as the same word after trimming case) as one of those lines, because the Android app will tap by that text. Do not invent labels.
        - Never return toolbar or media actions: Camera, Gallery, Label, Badge, QUEUE, Stations, Survey, or photo slot names (Door Knock, Front of House, etc.).
        - If several different Yes/No pairs exist and you cannot tell which question your Yes/No belongs to, return MANUAL_REVIEW.
        - If the screen does not contain enough text to justify a tap, return MANUAL_REVIEW.
        Output format: exactly one JSON object on one line:
        {"question_focus":"short quote or empty","answer":"Yes"} or {"question_focus":"","answer":"MANUAL_REVIEW"}
        """.trimIndent()

    /**
     * POST ao proxy. [InspectResponse.userHint] explica falhas (rede, 500 sem chave, corpo inválido).
     */
    fun requestInspect(context: Context, visibleTexts: List<String>, profile: InspectionProfile): InspectResponse {
        val endpoint = context.getString(R.string.ai_inspection_endpoint).trim()
        if (endpoint.isEmpty()) {
            return InspectResponse(
                answer = InspectionDecisionEngine.MANUAL_REVIEW,
                userHint = null,
            )
        }

        val rulesJson = if (profile == InspectionProfile.FI_OCCUPIED) {
            loadFiOccupiedRulesJson(context)
        } else {
            "{}"
        }
        val systemPrompt = buildSystemPrompt(profile, rulesJson)
        val visibleArray = JSONArray(visibleTexts)

        val rulesObject = runCatching { JSONObject(rulesJson) }.getOrElse { JSONObject() }
        val body = JSONObject()
            .put("profile", profile.name)
            .put("visibleTexts", visibleArray)
            .put("rulesJson", rulesObject)
            .put("systemPrompt", systemPrompt)
            .toString()

        val saved = AuthStore.getToken(context)
        val authValue = when {
            !saved.isNullOrBlank() -> "Bearer $saved"
            else -> {
                val b = context.getString(R.string.ai_inspection_bearer).trim()
                if (b.isNotEmpty()) "Bearer $b" else null
            }
        }
        val request = Request.Builder()
            .url(endpoint)
            .apply { authValue?.let { header("Authorization", it) } }
            .post(body.toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401) {
                        return InspectResponse(
                            InspectionDecisionEngine.MANUAL_REVIEW,
                            context.getString(R.string.error_login_required),
                        )
                    }
                    if (response.code == 403) {
                        val d = extractServerDetail(raw)
                        if (d.contains("pending_approval", ignoreCase = true)) {
                            return InspectResponse(
                                InspectionDecisionEngine.MANUAL_REVIEW,
                                context.getString(R.string.error_pending_approval),
                            )
                        }
                    }
                    val detail = extractServerDetail(raw)
                    val hint = context.getString(
                        R.string.feedback_server_http,
                        response.code,
                        detail,
                    )
                    return InspectResponse(InspectionDecisionEngine.MANUAL_REVIEW, hint)
                }
                val answer = parseAnswerJson(raw)
                // Só mostramos "parse falhou" se o corpo NÃO tiver {"answer":...} válido.
                // {"answer":"MANUAL_REVIEW"} da IA é resposta válida, não erro de JSON.
                val hasAnswerField = jsonHasAnswerKey(raw)
                if (answer == InspectionDecisionEngine.MANUAL_REVIEW && raw.isNotBlank() && !hasAnswerField) {
                    InspectResponse(
                        answer,
                        context.getString(R.string.feedback_ia_parse_failed, raw.take(120)),
                    )
                } else {
                    InspectResponse(answer, null)
                }
            }
        }.getOrElse { e ->
            InspectResponse(
                answer = InspectionDecisionEngine.MANUAL_REVIEW,
                userHint = connectionHint(context, endpoint, e),
            )
        }
    }

    /** Compatível com código que só precisa da resposta. */
    fun requestAnswer(context: Context, visibleTexts: List<String>, profile: InspectionProfile): String =
        requestInspect(context, visibleTexts, profile).answer

    /**
     * POST /inspect-vision — imagem + texto; resposta com coordenadas em percentagem.
     */
    fun requestInspectVision(
        context: Context,
        visibleTexts: List<String>,
        profile: InspectionProfile,
        screenshotBase64: String,
        step: Int,
    ): InspectVisionResponse {
        val endpoint = context.getString(R.string.ai_inspection_vision_endpoint).trim()
        if (endpoint.isEmpty()) {
            return InspectVisionResponse(
                action = "manual_review",
                answer = "",
                xPercent = 0f,
                yPercent = 0f,
                confidence = 0f,
                reason = "endpoint",
                userHint = null,
            )
        }

        val visibleArray = JSONArray(visibleTexts)
        val body = JSONObject()
            .put("profile", profile.name)
            .put("visibleTexts", visibleArray)
            .put("screenshotBase64", screenshotBase64)
            .put("step", step)
            .toString()

        val saved = AuthStore.getToken(context)
        val authValue = when {
            !saved.isNullOrBlank() -> "Bearer $saved"
            else -> {
                val b = context.getString(R.string.ai_inspection_bearer).trim()
                if (b.isNotEmpty()) "Bearer $b" else null
            }
        }
        val request = Request.Builder()
            .url(endpoint)
            .apply { authValue?.let { header("Authorization", it) } }
            .post(body.toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401) {
                        return InspectVisionResponse(
                            action = "manual_review",
                            answer = "",
                            xPercent = 0f,
                            yPercent = 0f,
                            confidence = 0f,
                            reason = "auth",
                            userHint = context.getString(R.string.error_login_required),
                        )
                    }
                    if (response.code == 403) {
                        val d = extractServerDetail(raw)
                        if (d.contains("pending_approval", ignoreCase = true)) {
                            return InspectVisionResponse(
                                action = "manual_review",
                                answer = "",
                                xPercent = 0f,
                                yPercent = 0f,
                                confidence = 0f,
                                reason = "approval",
                                userHint = context.getString(R.string.error_pending_approval),
                            )
                        }
                    }
                    val detail = extractServerDetail(raw)
                    val hint = context.getString(
                        R.string.feedback_server_http,
                        response.code,
                        detail,
                    )
                    return InspectVisionResponse(
                        action = "manual_review",
                        answer = "",
                        xPercent = 0f,
                        yPercent = 0f,
                        confidence = 0f,
                        reason = "http",
                        userHint = hint,
                    )
                }
                parseVisionDecision(raw) ?: InspectVisionResponse(
                    action = "manual_review",
                    answer = "",
                    xPercent = 0f,
                    yPercent = 0f,
                    confidence = 0f,
                    reason = "parse",
                    userHint = context.getString(R.string.feedback_ia_parse_failed, raw.take(120)),
                )
            }
        }.getOrElse { e ->
            InspectVisionResponse(
                action = "manual_review",
                answer = "",
                xPercent = 0f,
                yPercent = 0f,
                confidence = 0f,
                reason = "network",
                userHint = connectionHint(context, endpoint, e),
            )
        }
    }

    private fun parseVisionDecision(raw: String): InspectVisionResponse? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = JSONObject(trimmed)
            val action = obj.optString("action", "").trim().lowercase()
            if (action.isEmpty()) return null
            InspectVisionResponse(
                action = action,
                answer = obj.optString("answer", "").trim(),
                xPercent = obj.optDouble("xPercent", 0.0).toFloat(),
                yPercent = obj.optDouble("yPercent", 0.0).toFloat(),
                confidence = obj.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                reason = obj.optString("reason", "").trim(),
                userHint = null,
            )
        }.getOrNull()
    }

    private fun extractServerDetail(raw: String): String =
        runCatching {
            JSONObject(raw).optString("detail", "").trim().ifEmpty { raw.take(200) }
        }.getOrElse { raw.take(200) }

    private fun connectionHint(context: Context, endpoint: String, e: Throwable): String {
        return when (e) {
            is ConnectException, is UnknownHostException ->
                context.getString(R.string.feedback_no_connection, endpoint)
            is SocketTimeoutException ->
                context.getString(R.string.feedback_timeout, endpoint)
            else -> context.getString(R.string.feedback_request_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseAnswerJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return InspectionDecisionEngine.MANUAL_REVIEW
        return runCatching {
            val obj = JSONObject(trimmed)
            val answer = obj.optString("answer", "").trim()
            if (answer.isEmpty()) InspectionDecisionEngine.MANUAL_REVIEW else answer
        }.getOrElse { InspectionDecisionEngine.MANUAL_REVIEW }
    }

    private fun jsonHasAnswerKey(raw: String): Boolean =
        runCatching { JSONObject(raw.trim()).has("answer") }.getOrDefault(false)
}
