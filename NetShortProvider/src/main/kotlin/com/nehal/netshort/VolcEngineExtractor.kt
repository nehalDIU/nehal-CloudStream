package com.nehal.netshort

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

@JsonIgnoreProperties(ignoreUnknown = true)
data class VolcEnginePlayInfoResponse(
    @JsonProperty("ResponseMetadata") val responseMetadata: VolcResponseMetadata? = null,
    @JsonProperty("Result") val result: VolcResult? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VolcResponseMetadata(
    @JsonProperty("RequestId") val requestId: String? = null,
    @JsonProperty("Action") val action: String? = null,
    @JsonProperty("Version") val version: String? = null,
    @JsonProperty("Service") val service: String? = null,
    @JsonProperty("Region") val region: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VolcResult(
    @JsonProperty("PlayInfoList") val playInfoList: List<VolcPlayInfo>? = null,
    @JsonProperty("VideoId") val videoId: String? = null,
    @JsonProperty("PosterUrl") val posterUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VolcPlayInfo(
    @JsonProperty("MainPlayUrl") val mainPlayUrl: String? = null,
    @JsonProperty("BackupPlayUrl") val backupPlayUrl: String? = null,
    @JsonProperty("Bitrate") val bitrate: Int? = null,
    @JsonProperty("FileHash") val fileHash: String? = null,
    @JsonProperty("Format") val format: String? = null,
    @JsonProperty("Definition") val definition: String? = null,
    @JsonProperty("Width") val width: Int? = null,
    @JsonProperty("Height") val height: Int? = null,
    @JsonProperty("Url") val url: String? = null
)

class VolcEngineExtractor : ExtractorApi() {
    override val name = "VolcEngine"
    override val mainUrl = "https://vod.ap-southeast-1.volcengineapi.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolveStream(url, null, null, callback)
    }

    suspend fun resolveStream(
        videoUrlOrVid: String,
        sdkVid: String?,
        playVoucher: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Direct Video URL check (if already an HTTP .m3u8 or .mp4)
        if (videoUrlOrVid.startsWith("http://") || videoUrlOrVid.startsWith("https://")) {
            val isM3u8 = videoUrlOrVid.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = "NetShort",
                    name = "NetShort Direct",
                    url = videoUrlOrVid,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P720.value
                }
            )
            return
        }

        val vid = sdkVid ?: videoUrlOrVid.ifBlank { null } ?: return
        val voucher = playVoucher?.trim()

        // 2. Inspect playVoucher if it is a Base64-encoded JSON or URL
        if (!voucher.isNullOrBlank()) {
            try {
                val decodedVoucher = base64Decode(voucher)
                if (decodedVoucher.startsWith("http://") || decodedVoucher.startsWith("https://")) {
                    callback(
                        newExtractorLink(
                            source = "NetShort",
                            name = "NetShort CDN",
                            url = decodedVoucher,
                            type = if (decodedVoucher.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P720.value
                        }
                    )
                    return
                }

                // Check if decoded voucher is a JSON containing play URLs
                if (decodedVoucher.startsWith("{") && decodedVoucher.contains("http")) {
                    val volcResponse = parseJson<VolcEnginePlayInfoResponse>(decodedVoucher)
                    volcResponse.result?.playInfoList?.forEach { playInfo ->
                        val playUrl = playInfo.mainPlayUrl ?: playInfo.url ?: playInfo.backupPlayUrl
                        if (!playUrl.isNullOrBlank()) {
                            val quality = when (playInfo.definition?.lowercase()) {
                                "1080p", "fhd" -> Qualities.P1080.value
                                "720p", "hd" -> Qualities.P720.value
                                "480p", "sd" -> Qualities.P480.value
                                else -> Qualities.Unknown.value
                            }
                            val isM3u8 = playUrl.contains(".m3u8") || playInfo.format.equals("m3u8", ignoreCase = true)
                            callback(
                                newExtractorLink(
                                    source = "NetShort",
                                    name = "VolcEngine ${playInfo.definition ?: "HD"}",
                                    url = playUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                }
                            )
                        }
                    }
                    return
                }
            } catch (_: Throwable) {
                // Voucher might be a raw JWT or token, continue to API calls
            }
        }

        // 3. VolcEngine / ByteDance VOD API: GetPlayInfo
        val vodEndpoints = listOf(
            "https://vod.ap-southeast-1.volcengineapi.com/?Action=GetPlayInfo&Version=2020-08-01&VideoId=$vid${if (!voucher.isNullOrBlank()) "&AuthToken=$voucher" else ""}",
            "https://vod.bytedanceapi.com/?Action=GetPlayInfo&Version=2020-08-01&VideoId=$vid${if (!voucher.isNullOrBlank()) "&AuthToken=$voucher" else ""}",
            "https://vod.ap-southeast-1.volcengineapi.com/v1/fetchAuditVideoInfo?vid=$vid"
        )

        for (endpoint in vodEndpoints) {
            try {
                val headers = mutableMapOf(
                    "User-Agent" to "okhttp/4.9.0",
                    "Accept" to "application/json"
                )
                if (!voucher.isNullOrBlank()) {
                    headers["Authorization"] = voucher
                    headers["X-Playback-Token"] = voucher
                }

                val response = app.get(endpoint, headers = headers, timeout = 10L).text
                if (response.contains("PlayInfoList") || response.contains("MainPlayUrl") || response.contains("http")) {
                    val parsed = parseJson<VolcEnginePlayInfoResponse>(response)
                    var found = false
                    parsed.result?.playInfoList?.forEach { playInfo ->
                        val playUrl = playInfo.mainPlayUrl ?: playInfo.url ?: playInfo.backupPlayUrl
                        if (!playUrl.isNullOrBlank()) {
                            found = true
                            val quality = when (playInfo.definition?.lowercase()) {
                                "1080p", "fhd" -> Qualities.P1080.value
                                "720p", "hd" -> Qualities.P720.value
                                "480p", "sd" -> Qualities.P480.value
                                else -> Qualities.P720.value
                            }
                            val isM3u8 = playUrl.contains(".m3u8") || playInfo.format.equals("m3u8", ignoreCase = true)
                            callback(
                                newExtractorLink(
                                    source = "NetShort",
                                    name = "NetShort ${playInfo.definition ?: "HD"}",
                                    url = playUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                }
                            )
                        }
                    }
                    if (found) return
                }
            } catch (e: Throwable) {
                Log.d("NetShort", "VolcEngine endpoint error: ${e.message}")
            }
        }
    }
}
