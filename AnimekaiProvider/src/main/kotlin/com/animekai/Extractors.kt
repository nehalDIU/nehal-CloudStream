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
                if (streamUrl.isNullOrBlank() && !sourceRes?.enc.isNullOrBlank()) {
                    val decrypted = decryptEncPayload(sourceRes!!.enc!!)
                    if (!decrypted.isNullOrBlank()) {
                        val parsedUrl = Regex(""""file"\s*:\s*"([^"]+)"""")
                            .find(decrypted)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (!parsedUrl.isNullOrBlank()) {
                            streamUrl = parsedUrl
                        }
                    }
                }

                if (!streamUrl.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        name,
                        streamUrl,
                        "$mainUrl/",
                        headers = streamHeaders
                    ).forEach(callback)
                    return
                }
            }
        } catch (e: Exception) {
            Log.d("MegaPlay", "Direct extraction failed: ${e.message}")
        }

        // Tier 2: Headless WebView Fallback (Safety net if token cipher shifts)
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
            timeout = 8_000L
        )

        try {
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
            Log.e("MegaPlay", "WebView fallback failed: ${e.message}")
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: SourcesData? = null,
        @JsonProperty("tracks") val tracks: List<TrackData> = emptyList(),
        @JsonProperty("enc") val enc: String? = null
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
