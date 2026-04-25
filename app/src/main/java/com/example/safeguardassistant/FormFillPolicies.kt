package com.example.safeguardassistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lê sinalizadores de [R.raw.fi_occupied_rules] (JSON canónico) para o perfil FI Ocupado.
 * Idempotente, cache simples.
 */
object FormFillPolicies {

    private const val TAG = "FormFillPolicies"

    @Volatile
    private var fiOccupied: FiOccupiedPolicy? = null

    data class FiOccupiedPolicy(
        val doNotChangeIfFilled: Set<String>,
        val onlyFillRequiredRed: Boolean,
        val neverOverwriteExisting: Boolean,
    ) {
        companion object {
            val DEFAULT = FiOccupiedPolicy(
                doNotChangeIfFilled = emptySet(),
                onlyFillRequiredRed = false,
                neverOverwriteExisting = true,
            )
        }
    }

    private fun loadFiOccupied(context: Context): FiOccupiedPolicy {
        fiOccupied?.let { return it }
        val s = runCatching {
            context.applicationContext.resources.openRawResource(R.raw.fi_occupied_rules).bufferedReader()
                .use { it.readText() }
        }.getOrNull() ?: return FiOccupiedPolicy.DEFAULT.also { fiOccupied = it }
        return try {
            val root = JSONObject(s)
            val arr = root.optJSONArray("do_not_change_if_filled") ?: JSONArray()
            val set = buildSet {
                for (i in 0 until arr.length()) {
                    add(arr.getString(i).trim())
                }
            }
            val pol = FiOccupiedPolicy(
                doNotChangeIfFilled = set,
                onlyFillRequiredRed = root.optBoolean("only_fill_required_red", false),
                neverOverwriteExisting = root.optBoolean("never_overwrite_existing_answer", true),
            )
            fiOccupied = pol
            pol
        } catch (e: Exception) {
            Log.e(TAG, "parse fi_occupied_rules", e)
            FiOccupiedPolicy.DEFAULT.also { fiOccupied = it }
        }
    }

    fun forProfile(context: Context, profile: InspectionProfile): FiOccupiedPolicy =
        when (profile) {
            InspectionProfile.FI_OCCUPIED -> loadFiOccupied(context)
            else -> FiOccupiedPolicy.DEFAULT
        }

    /** A pergunta/regra afecta um campo em [doNotChangeIfFilled] (compara com blob da regra). */
    fun isDoNotChangeForRule(profile: InspectionProfile, matchAll: List<String>, appContext: Context): Boolean {
        if (profile != InspectionProfile.FI_OCCUPIED) return false
        val labels = forProfile(appContext, profile).doNotChangeIfFilled
        if (labels.isEmpty()) return false
        val blob = matchAll.joinToString(" ").lowercase()
        return labels.any { key ->
            val k = key.lowercase()
            blob.contains(k) || k.split(" ").all { w -> w.length > 2 && blob.contains(w) }
        }
    }

    fun onlyFillRequiredEnabled(profile: InspectionProfile, appContext: Context): Boolean {
        if (profile != InspectionProfile.FI_OCCUPIED) return false
        return forProfile(appContext, profile).onlyFillRequiredRed
    }
}
