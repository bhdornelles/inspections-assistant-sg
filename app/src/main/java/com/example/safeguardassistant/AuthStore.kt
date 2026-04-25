package com.example.safeguardassistant

import android.content.Context

/**
 * Salva o JWT do proxy (SharedPreferences; em versoes futuras pode migrar p/ DataStore criptografado).
 */
object AuthStore {

    private const val PREF = "safeguard_assistant_auth"
    private const val KEY_JWT = "access_token"
    private const val KEY_EMAIL = "email"

    private fun pref(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getToken(context: Context): String? = pref(context).getString(KEY_JWT, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getEmail(context: Context): String? = pref(context).getString(KEY_EMAIL, null)

    fun saveSession(context: Context, email: String, accessToken: String) {
        pref(context).edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_JWT, accessToken.trim())
            .apply()
    }

    fun clear(context: Context) {
        pref(context).edit().remove(KEY_JWT).remove(KEY_EMAIL).apply()
    }
}
