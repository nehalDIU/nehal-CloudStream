package com.nehal.mojaloss

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app

object MojaLossStorage {
    private var prefs: SharedPreferences? = null

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("MojaLossPrefs", Context.MODE_PRIVATE)
        }
    }

    fun saveCookie(cookie: String) {
        val clean = cookie.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
        prefs?.edit()
            ?.putString("session_cookie", clean)
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

    suspend fun verifySession(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val cleanCookie = cookie.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
        return try {
            val res = app.get(
                "https://www.mojaloss.stream/wp-admin/",
                headers = mapOf(
                    "Cookie" to cleanCookie,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                allowRedirects = false,
                timeout = 15L
            )
            val location = res.headers["Location"] ?: ""
            if (res.code == 200 || (res.code in 300..399 && !location.contains("wp-login.php"))) {
                return true
            }

            val homeRes = app.get(
                "https://www.mojaloss.stream/",
                headers = mapOf(
                    "Cookie" to cleanCookie,
                    "User-Agent" to USER_AGENT
                ),
                timeout = 15L
            )
            val doc = homeRes.document
            doc.selectFirst("body.logged-in, [data-authed='1'], a[href*='action=logout'], .mj-nav-user") != null
        } catch (e: Exception) {
            false
        }
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

            val initRes = try {
                app.get(
                    loginUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    ),
                    timeout = 15L
                )
            } catch (e: Exception) {
                null
            }

            val testCookie = initRes?.headers?.values("Set-Cookie")
                ?.firstOrNull { it.contains("wordpress_test_cookie") }
                ?.substringBefore(";")
                ?: "wordpress_test_cookie=WP%20Cookie%20check"

            val response = app.post(
                loginUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Cookie" to testCookie,
                    "Referer" to loginUrl,
                    "Origin" to "https://www.mojaloss.stream",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                data = mapOf(
                    "log" to user,
                    "pwd" to pass,
                    "rememberme" to "forever",
                    "wp-submit" to "Sign in",
                    "redirect_to" to "https://www.mojaloss.stream/wp-admin/",
                    "testcookie" to "1"
                ),
                allowRedirects = false
            )

            val setCookieHeaders = response.headers.values("Set-Cookie")
            val authFromHeaders = setCookieHeaders.filter {
                it.startsWith("wordpress_logged_in_") || it.startsWith("wordpress_sec_") || it.startsWith("wordpress_")
            }.map { it.substringBefore(";") }

            val authFromCookies = response.cookies.filter { (k, _) ->
                k.startsWith("wordpress_logged_in_") || k.startsWith("wordpress_sec_") || k.startsWith("wordpress_")
            }.map { "${it.key}=${it.value}" }

            val combined = (authFromHeaders + authFromCookies).distinct()

            if (combined.any { it.startsWith("wordpress_logged_in_") }) {
                val fullCookie = combined.joinToString("; ")
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
