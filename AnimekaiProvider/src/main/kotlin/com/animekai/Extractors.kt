package com.animekai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

open class MegaPlay(private val customName: String = "MegaPlay") : ExtractorApi() {
    override val name = customName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val resolvedReferer = referer ?: "https://animekai.be/"

        // Tier 1: Fast direct HTTP extraction
        try {
            val pageHeaders = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to resolvedReferer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )

            val pageRes = app.get(url, headers = pageHeaders)
            val playerId = pageRes.document.selectFirst("#megaplay-player")?.attr("data-id")

            if (!playerId.isNullOrBlank()) {
                val apiUrl = "$mainUrl/stream/getSources?id=$playerId&platform=OTHER"
                val sourceRes = app.get(apiUrl, headers = pageHeaders).parsedSafe<MegaPlayResponse>()

                sourceRes?.tracks?.forEach { track ->
                    val file = track.file ?: return@forEach
                    val label = track.label ?: "English"
                    if (track.kind == "captions" || track.kind == "subtitles") {
                        subtitleCallback(
                            newSubtitleFile(label, file) {
                                this.headers = mapOf("Referer" to "$mainUrl/")
                            }
                        )
                    }
                }

                val directFile = sourceRes?.sources?.file
                if (!directFile.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        name,
                        directFile,
                        "$mainUrl/",
                        headers = streamHeaders
                    ).forEach(callback)
                    return
                }
            }
        } catch (e: Exception) {
            Log.d("MegaPlay", "Direct extraction failed, falling back to WebView: ${e.message}")
        }

        // Tier 2: Resilient Headless WebView Fallback (Immune to Token/Rot Cipher Shifts)
        val jsClick = """
            (() => {
                const btn = document.querySelector('.jw-icon-display, .play-btn, button[aria-label="Play"]');
                if (btn) { btn.click(); return "clicked"; }
                return "not found";
            })();
        """.trimIndent()

        val m3u8Resolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),
            script = jsClick,
            useOkhttp = false,
            timeout = 15_000L
        )

        val vttResolver = WebViewResolver(
            interceptUrl = Regex("""\.vtt"""),
            script = jsClick,
            useOkhttp = false,
            timeout = 15_000L
        )

        try {
            // Sniff Subtitles via WebView
            val vttResponse = app.get(url = url, referer = resolvedReferer, interceptor = vttResolver)
            val subUrl = vttResponse.url
            if (subUrl.endsWith(".vtt") && !subUrl.contains("thumbnail", ignoreCase = true)) {
                subtitleCallback(newSubtitleFile("English", subUrl))
            }
        } catch (_: Exception) {}

        try {
            // Sniff M3U8 Master Playlist via WebView
            val m3u8Response = app.get(url = url, referer = resolvedReferer, interceptor = m3u8Resolver)
            val m3u8Url = m3u8Response.url
            if (m3u8Url.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    "$mainUrl/",
                    headers = streamHeaders
                ).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("MegaPlay", "WebView fallback also failed: ${e.message}")
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: SourcesData? = null,
        @JsonProperty("tracks") val tracks: List<TrackData> = emptyList()
    )

    data class SourcesData(
        @JsonProperty("file") val file: String? = null
    )

    data class TrackData(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
