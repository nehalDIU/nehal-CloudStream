package com.nehal.netshort

import android.content.Context
import android.content.SharedPreferences

object NetShortStorage {
    private var prefs: SharedPreferences? = null
    var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("NetShortPrefs", Context.MODE_PRIVATE)
        }
    }

    fun showToast(message: String) {
        val ctx = appContext ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {
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
        val raw = prefs?.getString("toonbox_custom_headers", null)?.trim() ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()

        // Check if raw string is JSON format
        if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                val jsonMap = mapper.readValue(raw, Map::class.java)
                jsonMap.forEach { (k, v) ->
                    if (k != null && v != null) {
                        map[k.toString().trim()] = v.toString().trim()
                    }
                }
                if (map.isNotEmpty()) return map
            } catch (_: Throwable) {
            }
        }

        // Line-by-line fallback (e.g. Header: Value or Header=Value)
        raw.lines().forEach { line ->
            val sepIndex = line.indexOf(':').let { if (it != -1) it else line.indexOf('=') }
            if (sepIndex > 0) {
                val k = line.substring(0, sepIndex).trim()
                val v = line.substring(sepIndex + 1).trim()
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

