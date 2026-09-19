package com.nehal.animedekho

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

open class StreamRuby : ExtractorApi() {
    override var name = "Streamruby"
    override var mainUrl = "streamruby.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.getOrNull(1) ?: mainUrl
        val code = url.trimEnd('/').substringAfterLast("/").substringBefore(".").substringBefore("?")
        val postUrl = "https://$host/dl"
        val postHeaders = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Referer" to url,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        val formData = mapOf(
            "op" to "embed",
            "file_code" to code,
            "auto" to "1",
            "referer" to (referer ?: "")
        )

        var streamUrl: String? = null
        try {
            val responseText = app.post(postUrl, headers = postHeaders, data = formData).text
            val packed = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\('\|'\)\)\)""").find(responseText)?.value
            val unpacked = packed?.let { JsUnpacker(it).unpack() } ?: responseText
            streamUrl = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.getOrNull(1)
                ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.getOrNull(1)
        } catch (_: Throwable) {
        }

        if (streamUrl.isNullOrBlank()) {
            val cleanedUrl = url.replace("/e", "")
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to cleanedUrl
            )
            try {
                val doc = app.get(cleanedUrl, headers = headers, referer = cleanedUrl).document
                val scriptData = doc.selectFirst("script:containsData(vplayer)")?.data() ?: ""
                streamUrl = Regex("""file:\s*"([^"]+)"""").find(scriptData)?.groupValues?.getOrNull(1)
                    ?: Regex("""file:\s*'([^']+)'""").find(scriptData)?.groupValues?.getOrNull(1)
            } catch (_: Throwable) {
            }
        }

        if (!streamUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.referer = "https://$host/"
                }
            )
        }
    }
}

open class Rubystm : StreamRuby() {
    override var name = "Rubystm"
    override var mainUrl = "rubystm.com"
}

open class AWSStream : ExtractorApi() {
    override var name = "AWSStream"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractedHash = url.substringAfterLast("/")
        val doc = app.get(url, referer = referer ?: "").document
        val m3u8Url = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"
        val header = mapOf("x-requested-with" to "XMLHttpRequest")
        val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)

        val res = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<Response>()
        res?.videoSource?.let { m3u8 ->
            callback.invoke(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8)
            )
        }

        val packed = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: ""
        val unpacked = JsUnpacker(packed).unpack()
        if (unpacked != null) {
            Regex(""""kind":\s*"captions"\s*,\s*"file":\s*"(https.*?\.srt)"""").find(unpacked)?.groupValues?.getOrNull(1)?.let { subUrl ->
                subtitleCallback.invoke(SubtitleFile("English", subUrl))
            }
        }
    }

    data class Response(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}

open class ascdn21 : AWSStream() {
    override var name = "Zephyrflick"
    override var mainUrl = "https://as-cdn21.top"
    override val requiresReferer = true
}

open class Blakiteapi : ExtractorApi() {
    override var name = "Blakiteapi"
    override var mainUrl = "https://blakiteapi.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/")
        val tmdbId = url.substringAfter("embed/", "").substringBefore("/")
        val apiUrl = "$mainUrl/api/get.php?id=$id&tmdbId=$tmdbId"

        val responseText = app.get(apiUrl, referer = referer ?: "").text
        val json = JSONObject(responseText)
        if (json.optBoolean("success", false)) {
            val data = json.optJSONObject("data") ?: return
            val quality = data.optString("quality", "480p")
            val format = data.optString("format", "MP4")
            val dataId = data.optString("dataId", "")
            val streamUrl = "$mainUrl/stream/$dataId.$format"

            callback.invoke(
                newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.quality = when {
                        quality.contains("1080") -> Qualities.P1080.value
                        quality.contains("720") -> Qualities.P720.value
                        quality.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
    }
}

open class Cloudy : VidStack() {
    override var mainUrl = "https://cloudy.upns.one"
}

open class vidcloudupns : VidStack() {
    override var mainUrl = "https://vidcloud.upns.ink"
}

open class FileMoonNL : Filesim() {
    override var name = "FileMoon"
    override var mainUrl = "https://filemoon.nl"
}

open class Multimovies : StreamWishExtractor() {
    override var name = "Multimovies Cloud"
    override var mainUrl = "https://multimovies.cloud"
    override val requiresReferer = true
}

open class Cdnwish : StreamWishExtractor() {
    override var name = "Streamwish"
    override var mainUrl = "https://cdnwish.com"
}

open class Animezia : VidhideExtractor() {
    override var name = "Animezia"
    override var mainUrl = "https://animezia.cloud"
    override val requiresReferer = true
}

open class Vidmolynet : Vidmoly() {
    override var mainUrl = "https://vidmoly.net"
}

open class VidmolyBiz : Vidmoly() {
    override var mainUrl = "https://vidmoly.biz"
}

open class GDMirrorbot : ExtractorApi() {
    override var name = "GDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url, referer = referer ?: "").document.select("ul#videoLinks li").forEach {
            val link = it.attr("data-link")
            if (link.isNotBlank()) {
                loadExtractor(link, subtitleCallback, callback)
            }
        }
    }
}

open class Techinmind : GDMirrorbot() {
    override var name = "Techinmind Cloud AIO"
    override var mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true
}

open class Animedekhoco : ExtractorApi() {
    override var name = "Animedekhoco"
    override var mainUrl = "https://animedekho.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val isMain = url.contains(mainUrl)
        val doc = if (isMain) app.get(url, referer = referer ?: "").document else null
        val text = if (!isMain) app.get(url, referer = referer ?: "").text else null

        val links = mutableListOf<Pair<String, String>>()
        doc?.select("select#serverSelector option")?.forEach {
            val serverUrl = it.attr("value")
            val serverName = it.text().ifBlank { "Unknown" }
            if (serverUrl.isNotBlank()) {
                links.add(serverName to serverUrl)
            }
        }
        if (text != null) {
            Regex("""file\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)?.let {
                links.add("Player File" to it)
            }
        }

        links.forEach { (serverName, serverUrl) ->
            callback.invoke(
                newExtractorLink(
                    serverName,
                    serverName,
                    serverUrl,
                    INFER_TYPE
                )
            )
        }
    }
}

open class AnimedekhoPixel : ExtractorApi() {
    override var name = "PixelDrain"
    override var mainUrl = "https://animedekho.app/aaa/pixel"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = Regex("""slug=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: url.substringAfterLast("slug=").substringBefore("&")
        if (slug.isNotBlank()) {
            val streamUrl = "https://pixeldrain.net/api/file/$slug"
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    streamUrl,
                    INFER_TYPE
                ) {
                    this.quality = Qualities.P720.value
                }
            )
        }
    }
}

open class Abyass : ExtractorApi() {
    override var name = "Abyass"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer ?: "").document
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(scripts)?.groupValues?.getOrNull(1) ?: return

        val jsonBody = """{"text": "$encrypted"}""".trimIndent()
        val mediaType = "application/json".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)

        val decrypted = app.post("https://enc-dec.app/api/dec-abyss", requestBody = requestBody)
            .parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources?.forEach { source ->
            val sourceUrl = source.url ?: source.file ?: return@forEach
            val label = source.type ?: source.label ?: "Abyss"
            val quality = when {
                label.contains("1080") -> Qualities.P1080.value
                label.contains("720") -> Qualities.P720.value
                label.contains("480") -> Qualities.P480.value
                label.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            callback.invoke(
                newExtractorLink(name, "$name $label", sourceUrl, INFER_TYPE) {
                    this.quality = quality
                    this.referer = url
                }
            )
        }
    }

    data class AbyssResponse(
        @JsonProperty("status") val status: Long? = null,
        @JsonProperty("result") val result: Result? = null
    )

    data class Result(
        @JsonProperty("sources") val sources: List<AbyssSource>? = null
    )

    data class AbyssSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("codec") val codec: String? = null,
        @JsonProperty("status") val status: Boolean? = null
    )
}

