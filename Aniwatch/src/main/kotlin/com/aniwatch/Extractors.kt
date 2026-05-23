package com.aniwatch

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

open class MegaPlay : ExtractorApi() {
    override val name = "VidSrc"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mainheaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Connection" to "keep-alive",
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache"
        )

        try {
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            )

            // Extract stream ID directly from URL path to prevent an extra network round-trip.
            // Example: https://megaplay.buzz/stream/s-2/2142/sub -> 2142
            val pathId = url.substringBeforeLast("/").substringAfterLast("/")
            val id = if (pathId.all { it.isDigit() }) pathId else {
                app.get(url, headers = headers).document.selectFirst("#megaplay-player")?.attr("data-id")
            }

            val apiUrl = "$mainUrl/stream/getSources?id=$id&id=$id"

            val response = runCatching {
                app.get(apiUrl, headers).parsedSafe<MegaPlayResponse>()
            }.getOrNull()

            val m3u8 = response?.sources?.file
                ?: throw Exception("No sources found")

            val isDub = url.endsWith("/dub") || url.contains("/dub")
            val displayName = if (isDub) "$name Dub" else "$name Sub"

            M3u8Helper.generateM3u8(
                displayName,
                m3u8,
                mainUrl,
                headers = mainheaders
            ).forEach(callback)

            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                val label = track.label ?: "Unknown"

                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/"
                            )
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MegaPlay", "Primary method failed: ${e.message}")
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources")
        val sources: Sources? = null,
        @JsonProperty("tracks")
        val tracks: List<Track> = emptyList()
    )

    data class Sources(
        @JsonProperty("file")
        val file: String? = null
    )

    data class Track(
        @JsonProperty("file")
        val file: String? = null,
        @JsonProperty("label")
        val label: String? = null,
        @JsonProperty("kind")
        val kind: String? = null
    )
}
