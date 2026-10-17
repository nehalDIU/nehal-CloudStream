package com.nehal.netshort

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random

object NetShortAuth {
    private const val LOGIN_URL = "https://appsecapi.netshort.com/prod-app-api/auth/login"
    private val mapper by lazy { jacksonObjectMapper() }

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

    private val HEX_CHARS = "0123456789abcdef"

    private fun generateDeviceCode(): String {
        return (1..16).map { HEX_CHARS[Random.nextInt(HEX_CHARS.length)] }.joinToString("")
    }

    suspend fun getValidToken(): String? {
        val custom = NetShortStorage.getCustomToken()
        if (!custom.isNullOrBlank()) {
            return custom
        }

        val now = System.currentTimeMillis()
        if (!cachedToken.isNullOrBlank() && now < tokenExpiry - 60_000L) {
            return cachedToken
        }

        return loginVisitor()
    }

    private suspend fun loginVisitor(): String? {
        return try {
            val deviceCode = generateDeviceCode()
            val payload = mapOf(
                "deviceCode" to deviceCode,
                "deviceId" to null,
                "model" to "Pixel 6",
                "os" to "Android",
                "osVer" to "13",
                "appVer" to "2.1.3",
                "source" to "visitor"
            )

            val jsonBody = mapper.writeValueAsString(payload)
            val (encKey, encBody) = NetShortCrypto.encryptPayload(jsonBody)

            val headers = mapOf(
                "Content-Type" to "application/json;charset=utf-8",
                "User-Agent" to "okhttp/4.9.0",
                "content-language" to "en_US",
                "encrypt-key" to encKey,
                "timestamp" to System.currentTimeMillis().toString(),
                "start_type" to "0",
                "push_switch" to "1",
                "network" to "WIFI,0,1"
            )

            val mediaType = "application/json;charset=utf-8".toMediaType()
            val response = app.post(
                LOGIN_URL,
                headers = headers,
                requestBody = encBody.toRequestBody(mediaType),
                timeout = 15L
            )

            val respKey = response.headers["encrypt-key"]
            val rawBody = response.text
            val decryptedJson = NetShortCrypto.decryptPayload(rawBody, respKey)

            val loginRes = parseJson<NetShortApiResponse<LoginResponseData>>(decryptedJson)
            val token = loginRes.data?.token

            if (!token.isNullOrBlank()) {
                cachedToken = token
                val timeoutSeconds = loginRes.data.timeout ?: 604799L
                tokenExpiry = System.currentTimeMillis() + (timeoutSeconds * 1000L)
                Log.d("NetShort", "Successfully established visitor guest session token")
                token
            } else {
                Log.e("NetShort", "Login response code: ${loginRes.code}, msg: ${loginRes.msg}")
                null
            }
        } catch (e: Throwable) {
            Log.e("NetShort", "Failed to login as guest visitor: ${e.message}")
            null
        }
    }
}
