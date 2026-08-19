package com.nehal.banglaplex

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

open class StreamTapeCustom : ExtractorApi() {
    override val name: String = "StreamTape"
    override val mainUrl: String = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url)
            val doc = res.document
            val scripts = doc.select("script").map { it.html() }

            for (script in scripts) {
                if (script.contains("link')") || script.contains("captchalink") || script.contains("norobotlink") || script.contains("ideoooolink")) {
                    val match = Regex("""innerHTML\s*=\s*['"]([^'"]+)['"](?:\s*\+\s*['"][^'"]*['"])?\s*\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)(?:\.substring\((\d+)\))?""").find(script)
                    if (match != null) {
                        val prefix = match.groupValues[1]
                        val rawStr = match.groupValues[2]
                        val sub1 = match.groupValues[3].toIntOrNull() ?: 0
                        val sub2 = match.groupValues[4].toIntOrNull() ?: 0

                        var body = rawStr.substring(sub1)
                        if (sub2 > 0) body = body.substring(sub2)

                        var full = prefix + body
                        if (full.startsWith("//")) full = "https:$full"
                        else if (full.startsWith("/")) full = "https:/$full"
                        else if (!full.startsWith("http")) full = "https://$full"

                        val streamUrl = if (full.contains("?")) "$full&stream=1" else "$full?stream=1"

                        callback(
                            newExtractorLink(
                                name,
                                name,
                                streamUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://streamtape.com/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }

                    // Fallback to evaluating JS if pattern differs
                    val jsMatch = Regex("""document\.getElementById\(['"][^'"]*link['"]\)\.innerHTML\s*=\s*(.*?);""").find(script)
                    if (jsMatch != null) {
                        val expr = jsMatch.groupValues[1]
                        val evaluated = evalJs("var url = $expr", "url")?.toString()
                        if (!evaluated.isNullOrBlank()) {
                            val fixedUrl = when {
                                evaluated.startsWith("//") -> "https:$evaluated&stream=1"
                                evaluated.startsWith("http") -> "$evaluated&stream=1"
                                else -> "https://$evaluated&stream=1"
                            }
                            callback(
                                newExtractorLink(
                                    name,
                                    name,
                                    fixedUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://streamtape.com/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("StreamTape", "Extraction error: ${e.message}")
        }
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
                        h.contains("hubcloud.php") || h.contains("gamerxyt.com") || h.contains("sportverse.cc")
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

        val targetHref = if (href.isNotBlank()) href else realUrl
        val document = try {
            app.get(targetHref, referer = realUrl).document
        } catch (e: Exception) {
            return
        }

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header, h1, h2")?.text().orEmpty()
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (header.isNotBlank()) append(" $header")
            if (size.isNotBlank()) append(" [$size]")
        }

        document.select("a.btn[href], a[href*=\"gpdl\"], a[href*=\"workers.dev\"], a[href*=\"fsl\"], a[href*=\"download\"]").forEach { element ->
            val link = element.attr("href")
            val text = element.text()
            val label = text.lowercase()

            when {
                "fsl server" in label || "fsl" in label -> {
                    callback(
                        newExtractorLink(
                            "$ref [FSL Server]",
                            "$ref [FSL Server]$labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                "10gbps" in label || link.contains("gpdl") -> {
                    callback(
                        newExtractorLink(
                            "$ref [10Gbps Fast]",
                            "$ref [10Gbps Fast]$labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                "download file" in label || link.contains("workers.dev") -> {
                    callback(
                        newExtractorLink(
                            "$ref [Direct Cloud]",
                            "$ref [Direct Cloud]$labelExtras",
                            link,
                            ExtractorLinkType.VIDEO
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label -> {
                    try {
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
                    } catch (_: Exception) {}
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label || link.contains("pixeldrain") -> {
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

                "gofile" in label || link.contains("gofile") -> {
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

            doc.select("a.btn[href], a[href*=\"drive.google.com\"], a[href*=\"pixeldrain\"], a[href*=\"download\"], a[href*=\"file\"]").forEach { a ->
                val href = a.attr("href")
                val text = a.text().lowercase()

                if (href.isNotBlank() && href.startsWith("http")) {
                    when {
                        "instant download" in text || "direct" in text || "fast cloud" in text || "cloud" in text -> {
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
