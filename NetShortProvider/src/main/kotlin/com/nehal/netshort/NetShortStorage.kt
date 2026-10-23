package com.nehal.netshort

import android.content.Context
import android.content.SharedPreferences

object NetShortStorage {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("NetShortPrefs", Context.MODE_PRIVATE)
        }
    }

    fun saveCustomToken(token: String) {
        val clean = token.trim().removePrefix("Bearer ").trim()
        prefs?.edit()
            ?.putString("custom_token", clean)
            ?.putLong("token_timestamp", System.currentTimeMillis())
            ?.apply()
    }

    fun getCustomToken(): String? {
        val token = prefs?.getString("custom_token", null)
        return if (token.isNullOrBlank()) null else token
    }

    fun clearCustomToken() {
        prefs?.edit()
            ?.remove("custom_token")
            ?.remove("token_timestamp")
            ?.apply()
    }

    fun saveGuestToken(token: String) {
        val clean = token.trim().removePrefix("Bearer ").trim()
        prefs?.edit()
            ?.putString("guest_token", clean)
            ?.putLong("guest_token_timestamp", System.currentTimeMillis())
            ?.apply()
    }

    fun getGuestToken(): String? {
        val token = prefs?.getString("guest_token", null)
        return if (token.isNullOrBlank()) null else token
    }

    fun getEffectiveToken(): String? {
        return getCustomToken() ?: getGuestToken()
    }

    fun saveLanguage(lang: String) {
        prefs?.edit()?.putString("app_language", lang.trim())?.apply()
    }

    fun getLanguage(): String {
        return prefs?.getString("app_language", "en") ?: "en"
    }

    // --- ToonBox Preferences ---

    fun saveToonBoxToken(token: String) {
        val clean = token.trim().removePrefix("Bearer ").trim()
        prefs?.edit()
            ?.putString("toonbox_custom_token", clean)
            ?.putLong("toonbox_token_timestamp", System.currentTimeMillis())
            ?.apply()
    }

    fun getToonBoxToken(): String? {
        val token = prefs?.getString("toonbox_custom_token", null)
        return if (token.isNullOrBlank()) null else token
    }

    fun clearToonBoxToken() {
        prefs?.edit()
            ?.remove("toonbox_custom_token")
            ?.remove("toonbox_token_timestamp")
            ?.apply()
    }

    fun saveToonBoxCustomHeaders(headersStr: String) {
        prefs?.edit()
            ?.putString("toonbox_custom_headers", headersStr.trim())
            ?.apply()
    }

    fun getToonBoxCustomHeaders(): Map<String, String> {
        val raw = prefs?.getString("toonbox_custom_headers", null) ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        raw.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    map[k] = v
                }
            }
        }
        return map
    }

    fun saveToonBoxLanguage(lang: String) {
        prefs?.edit()?.putString("toonbox_app_language", lang.trim())?.apply()
    }

    fun getToonBoxLanguage(): String {
        return prefs?.getString("toonbox_app_language", "en") ?: "en"
    }
}

