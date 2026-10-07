package com.allwish

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import java.util.UUID

object AnalyticsTracker {
    var ENDPOINT = "https://nehal-cloudstream-dashboard.vercel.app/api/track"

    val deviceId: String by lazy {
        UUID.randomUUID().toString()
    }

    fun track(
        provider: String,
        event: String,
        data: Map<String, Any?> = emptyMap()
    ) = ioSafe {
        try {
            if (ENDPOINT.isBlank() || ENDPOINT.contains("your-dashboard.vercel.app")) {
                return@ioSafe
            }
            val payload = mapOf(
                "deviceId" to deviceId,
                "provider" to provider,
                "event" to event,
                "data" to data,
                "timestamp" to System.currentTimeMillis()
            )
            app.post(
                ENDPOINT,
                json = payload,
                timeout = 3L
            )
        } catch (_: Throwable) {
        }
    }

    fun heartbeat(provider: String, currentTitle: String? = null) {
        track(provider, "heartbeat", mapOf("title" to currentTitle))
    }
}
