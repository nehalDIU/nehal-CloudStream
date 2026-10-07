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
            if (ENDPOINT.isBlank()) return@ioSafe
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
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 15L
            )
        } catch (_: Throwable) {
        }
    }

    fun heartbeat(provider: String, currentTitle: String? = null) {
        track(provider, "heartbeat", if (currentTitle != null) mapOf("title" to currentTitle) else emptyMap())
    }
}
