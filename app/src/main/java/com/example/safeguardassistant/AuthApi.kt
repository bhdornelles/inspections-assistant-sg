package com.example.safeguardassistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object AuthApi {

    private val json = "application/json; charset=utf-8".toMediaType()

    /** Ex.: `https://api.empresa.com/inspect` -> `https://api.empresa.com` */
    fun apiBaseFromInspectUrl(inspectUrl: String): String? {
        val t = inspectUrl.trim()
        if (t.isEmpty()) return null
        val i = t.indexOf("/inspect")
        return if (i >= 0) t.substring(0, i).trimEnd('/').ifEmpty { null } else t.trimEnd('/')
    }

    data class Result(val ok: Boolean, val message: String)

    fun register(context: Context, email: String, password: String): Result {
        return postAuth(context, "/auth/register", email, password, expectToken = false)
    }

    fun login(context: Context, email: String, password: String): Result {
        return postAuth(context, "/auth/login", email, password, expectToken = true)
    }

    private fun postAuth(
        context: Context,
        path: String,
        email: String,
        password: String,
        expectToken: Boolean,
    ): Result {
        val base = apiBaseFromInspectUrl(context.getString(R.string.ai_inspection_endpoint).trim())
            ?: return Result(false, context.getString(R.string.error_auth_no_endpoint))
        val url = "$base$path"
        val body = JSONObject()
            .put("email", email.trim().lowercase())
            .put("password", password)
            .toString()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(json))
            .header("Content-Type", "application/json; charset=utf-8")
            .build()
        return runCatching {
            AiClient.client().newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                fun detail() = runCatching { JSONObject(raw).optString("detail", raw) }.getOrElse { raw }
                when (response.code) {
                    200, 201 -> {
                        if (expectToken) {
                            val obj = runCatching { JSONObject(raw) }.getOrNull()
                            val token = obj?.optString("access_token", "")?.trim().orEmpty()
                            if (token.isEmpty()) {
                                Result(false, context.getString(R.string.error_auth_bad_response))
                            } else {
                                AuthStore.saveSession(context, email, token)
                                Result(true, context.getString(R.string.auth_login_ok))
                            }
                        } else {
                            Result(true, context.getString(R.string.auth_register_ok))
                        }
                    }
                    403 -> {
                        val d = detail()
                        if (d.equals("pending_approval", ignoreCase = true) ||
                            d.contains("pending_approval", ignoreCase = true)
                        ) {
                            Result(false, context.getString(R.string.error_pending_approval))
                        } else {
                            Result(false, d.ifEmpty { raw.take(200) })
                        }
                    }
                    401, 404, 409, 503 -> {
                        val d = detail()
                        Result(false, d.ifEmpty { raw.take(200) })
                    }
                    else -> {
                        val d = detail()
                        Result(false, d.ifEmpty { "HTTP ${response.code}: ${raw.take(200)}" })
                    }
                }
            }
        }.getOrElse { e ->
            val base = apiBaseFromInspectUrl(context.getString(R.string.ai_inspection_endpoint)) ?: "—"
            Result(false, e.toConnectionMessage(context, base))
        }
    }
}

private fun Throwable.toConnectionMessage(context: Context, apiBase: String): String {
    return when (this) {
        is ConnectException, is UnknownHostException ->
            context.getString(R.string.error_auth_cannot_reach, apiBase)
        is SocketTimeoutException, is IOException -> message ?: javaClass.simpleName
        else -> message ?: javaClass.simpleName
    }
}
