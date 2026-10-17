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
}
