package com.cncverse

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import java.util.UUID

object AnalyticsTracker {
    // Configurable endpoint pointing to your deployed Vercel dashboard API
    var ENDPOINT = "https://nehal-cloudstream-dashboard.vercel.app/api/track"

    // Anonymous random session device identifier
    val deviceId: String by lazy {
        UUID.randomUUID().toString()
    }

    /**
     * Dispatch fire-and-forget telemetry event with strict 3-second timeout
     * Guaranteed to never block, freeze UI, or disrupt video streaming
     */
    fun track(
        provider: String,
        event: String,
        data: Map<String, Any?> = emptyMap()
    ) = ioSafe {
        try {
            if (ENDPOINT.isBlank() || ENDPOINT.contains("your-dashboard.vercel.app")) {
                // Safely skip network call if not yet configured with a live production URL
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
            // Silently suppress all errors to ensure 100% normal playback
        }
    }

    /**
     * Send live presence heartbeat
     */
    fun heartbeat(provider: String, currentTitle: String? = null) {
        track(provider, "heartbeat", mapOf("title" to currentTitle))
    }
}
