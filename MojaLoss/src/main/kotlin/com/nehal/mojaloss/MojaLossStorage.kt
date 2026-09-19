package com.nehal.mojaloss

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app

object MojaLossStorage {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("MojaLossPrefs", Context.MODE_PRIVATE)
        }
    }

    fun saveCookie(cookie: String) {
        prefs?.edit()
            ?.putString("session_cookie", cookie.trim())
            ?.putLong("cookie_timestamp", System.currentTimeMillis())
            ?.apply()
    }

    fun getCookie(): String? {
        val cookie = prefs?.getString("session_cookie", null)
        return if (cookie.isNullOrBlank()) null else cookie
    }

    fun clearCookie() {
        prefs?.edit()
            ?.remove("session_cookie")
            ?.remove("cookie_timestamp")
            ?.apply()
    }

    fun saveCredentials(user: String, pass: String) {
        prefs?.edit()
            ?.putString("login_user", user.trim())
            ?.putString("login_pass", pass)
            ?.apply()
    }

    fun getCredentials(): Pair<String?, String?> {
        val user = prefs?.getString("login_user", null)
        val pass = prefs?.getString("login_pass", null)
        return Pair(user, pass)
    }

    fun clearCredentials() {
        prefs?.edit()
            ?.remove("login_user")
            ?.remove("login_pass")
            ?.apply()
    }

    suspend fun login(user: String, pass: String): Boolean {
        return try {
            val loginUrl = "https://www.mojaloss.stream/wp-login.php"
            val response = app.post(
                loginUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Cookie" to "wordpress_test_cookie=WP%20Cookie%20check",
                    "Referer" to loginUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                data = mapOf(
                    "log" to user,
                    "pwd" to pass,
                    "rememberme" to "forever",
                    "wp-submit" to "Sign in",
                    "testcookie" to "1"
                )
            )

            val setCookieHeaders = response.headers.values("Set-Cookie")
            val authCookies = setCookieHeaders.filter {
                it.startsWith("wordpress_logged_in_") || it.startsWith("wordpress_sec_") || it.startsWith("wordpress_")
            }.map { it.substringBefore(";") }

            if (authCookies.any { it.startsWith("wordpress_logged_in_") }) {
                val fullCookie = authCookies.joinToString("; ")
                saveCookie(fullCookie)
                saveCredentials(user, pass)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("MojaLoss", "Login failed: ${e.message}")
            false
        }
    }

    suspend fun getOrRefreshCookie(): String? {
        val existing = getCookie()
        if (!existing.isNullOrEmpty()) return existing
        val (user, pass) = getCredentials()
        if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            if (login(user, pass)) {
                return getCookie()
            }
        }
        return null
    }
}
