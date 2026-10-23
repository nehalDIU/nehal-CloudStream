package com.animekai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class MegaPlay(private val customName: String = "MegaPlay") : ExtractorApi() {
    override val name = customName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    companion object {
        private const val ENC_KEY = "i?LMTAx0Q6,:}50U"
        private const val ENC_IV = "W0;27ToaUpl_P%'c"

        fun decryptEncPayload(enc: String): String? {
            return try {
                val clean = enc.replace('-', '+').replace('_', '/')
                val padding = clean.length % 4
                val padded = if (padding > 0) clean + "=".repeat(4 - padding) else clean
                val ciphertext = Base64.getDecoder().decode(padded)

                val keyBytes = ByteArray(32)
                val rawKeyBytes = ENC_KEY.toByteArray(Charsets.UTF_8)
                System.arraycopy(rawKeyBytes, 0, keyBytes, 0, minOf(rawKeyBytes.size, 32))

                val ivBytes = ByteArray(16)
                val rawIvBytes = ENC_IV.toByteArray(Charsets.UTF_8)
                System.arraycopy(rawIvBytes, 0, ivBytes, 0, minOf(rawIvBytes.size, 16))

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                val decrypted = cipher.doFinal(ciphertext)
                String(decrypted, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e("MegaPlay", "Decryption failed: ${e.message}")
                null
            }
        }
    }

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

        // Tier 1: Fast Direct HTTP Extraction + Native AES-256-CBC Decryption (~50ms)
        try {
            val pageHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Referer" to resolvedReferer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val pageRes = app.get(url, headers = pageHeaders)
            val pageText = pageRes.text
            val playerId = Regex("""data-id=["'](\d+)["']""").find(pageText)?.groupValues?.get(1)
                ?: pageRes.document.selectFirst("#megaplay-player")?.attr("data-id")

            if (!playerId.isNullOrBlank()) {
                val apiHeaders = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )

                val apiUrl = "$mainUrl/stream/getSources?id=$playerId&platform=OTHER"
                val apiRes = app.get(apiUrl, headers = apiHeaders)
                val sourceRes = apiRes.parsedSafe<MegaPlayResponse>()
                val apiText = apiRes.text

                // Subtitle tracks provided directly by API
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

                // Check direct unencrypted file
                var streamUrl = sourceRes?.sources?.file

                // Decrypt AES-256-CBC enc payload
                val encPayload = sourceRes?.enc
                    ?: Regex(""""enc"\s*:\s*"([^"]+)"""").find(apiText)?.groupValues?.get(1)

                if (streamUrl.isNullOrBlank() && !encPayload.isNullOrBlank()) {
                    val decrypted = decryptEncPayload(encPayload)
                    if (!decrypted.isNullOrBlank()) {
                        val parsedUrl = Regex(""""file"\s*:\s*"([^"]+)"""")
                            .find(decrypted)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (!parsedUrl.isNullOrBlank()) {
                            streamUrl = parsedUrl
                        }
                    }
                }

                if (!streamUrl.isNullOrBlank()) {
                    // 1. Immediately emit master direct link so link is ALWAYS found
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            streamUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = streamHeaders
                            this.quality = Qualities.P1080.value
                        }
                    )

                    // 2. Expand into multi-quality bitrates if available
                    runCatching {
                        M3u8Helper.generateM3u8(
                            name,
                            streamUrl,
                            "$mainUrl/",
                            headers = streamHeaders
                        ).forEach(callback)
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.d("MegaPlay", "Direct extraction failed: ${e.message}")
        }

        // Tier 2: Real Headless WebView Fallback (Safety net if token cipher shifts)
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
            timeout = 10_000L
        )

        try {
            val resolved = runCatching {
                m3u8Resolver.resolveUsingWebView(url = url, referer = resolvedReferer)
            }.getOrNull()

            val webViewUrl = resolved?.first?.url?.toString()
            if (!webViewUrl.isNullOrBlank() && webViewUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        name,
                        "$name (Auto)",
                        webViewUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("MegaPlay", "WebView fallback failed: ${e.message}")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: SourcesData? = null,
        @JsonProperty("tracks") val tracks: List<TrackData> = emptyList(),
        @JsonProperty("enc") val enc: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesData(
        @JsonProperty("file") val file: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TrackData(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
