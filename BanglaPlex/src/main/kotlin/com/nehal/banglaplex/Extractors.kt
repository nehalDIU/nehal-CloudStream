package com.nehal.banglaplex

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI

fun getBaseUrl(url: String): String {
    return try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        url
    }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value

    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }

    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") || lowerStr.contains("2160p") -> 2160
        lowerStr.contains("2k") || lowerStr.contains("1440p") -> 1440
        lowerStr.contains("1080p") -> 1080
        lowerStr.contains("720p") -> 720
        lowerStr.contains("480p") -> 480
        lowerStr.contains("360p") -> 360
        else -> Qualities.Unknown.value
    }
}

open class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val ref = "HubCloud"

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        val href = runCatching {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val doc = app.get(realUrl).document
                val raw = doc.selectFirst("#download")?.attr("href")
                    ?: doc.select("a[href]").firstOrNull { el ->
                        val h = el.attr("href")
                        h.contains("hubcloud.php") || h.contains("gamerxyt.com")
                    }?.attr("href")
                    ?: ""

                when {
                    raw.startsWith("http", true) -> raw
                    raw.isNotEmpty() -> baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
                    else -> ""
                }
            }
        }.getOrElse {
            ""
        }

        if (href.isBlank()) return

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (header.isNotBlank()) append(" $header")
            if (size.isNotBlank()) append(" [$size]")
        }

        document.select("a.btn[href]").forEach { element ->
            val link = element.attr("href")
            val text = element.ownText()
            val label = text.lowercase()

            when {
                "fsl server" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSL Server]",
                            "$ref [FSL Server]$labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                "download file" in label -> {
                    callback(
                        newExtractorLink(
                            ref,
                            "$ref$labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label -> {
                    val resp = app.get("$link/download", referer = link, allowRedirects = false)
                    val dlink = resp.headers["hx-redirect"]
                        ?: resp.headers["HX-Redirect"].orEmpty()

                    if (dlink.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                "$ref [BuzzServer]",
                                "$ref [BuzzServer]$labelExtras",
                                dlink,
                                ExtractorLinkType.VIDEO
                            ) { this.quality = quality }
                        )
                    }
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                    val base = getBaseUrl(link)
                    val finalUrl = if (link.contains("download", true)) link
                    else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "$ref [PixelDrain]",
                            "$ref [PixelDrain]$labelExtras",
                            finalUrl,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                "gofile" in label -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }
}

open class GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override val mainUrl: String = "https://gdflix.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            val header = doc.selectFirst("h5, .card-header, title")?.text().orEmpty()
            val size = doc.selectFirst("i#size, .file-size")?.text().orEmpty()
            val quality = getIndexQuality(header)

            val labelExtras = buildString {
                if (header.isNotBlank()) append(" $header")
                if (size.isNotBlank()) append(" [$size]")
            }

            doc.select("a.btn[href], a[href*=\"drive.google.com\"], a[href*=\"pixeldrain\"], a[href*=\"download\"]").forEach { a ->
                val href = a.attr("href")
                val text = a.text().lowercase()

                if (href.isNotBlank() && href.startsWith("http")) {
                    when {
                        "instant download" in text || "direct" in text || "fast cloud" in text -> {
                            callback(
                                newExtractorLink(
                                    "GDFlix [Direct]",
                                    "GDFlix [Direct]$labelExtras",
                                    href,
                                    ExtractorLinkType.VIDEO
                                ) { this.quality = quality }
                            )
                        }
                        "pixeldrain" in text || "pixeldra" in href -> {
                            val base = getBaseUrl(href)
                            val finalUrl = if (href.contains("download", true)) href
                            else "$base/api/file/${href.substringAfterLast("/")}?download"

                            callback(
                                newExtractorLink(
                                    "GDFlix [PixelDrain]",
                                    "GDFlix [PixelDrain]$labelExtras",
                                    finalUrl,
                                    ExtractorLinkType.VIDEO
                                ) { this.quality = quality }
                            )
                        }
                        else -> {
                            loadExtractor(href, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Error extracting: ${e.message}")
        }
    }
}
