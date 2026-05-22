package com.nehal.ftpbdindex

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromName
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

open class FTPBDIndexProvider : MainAPI() {
    override var mainUrl = "https://server2.ftpbd.net"
    override var name = "FTPBD Index"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    private val server2Host = "https://server2.ftpbd.net"
    private val server4Host = "https://server4.ftpbd.net"
    private val server5Host = "https://server5.ftpbd.net"

    private data class Category(
        val key: String,
        val name: String,
        val type: TvType,
        val host: String,
        val path: String
    )

    private val categories = listOf(
        Category(
            key = "english-movies",
            name = "English Movies",
            type = TvType.Movie,
            host = server2Host,
            path = "/FTP-2/English%20Movies/"
        ),
        Category(
            key = "english-foreign-tv",
            name = "English & Foreign TV Series",
            type = TvType.TvSeries,
            host = server4Host,
            path = "/FTP-4/English-Foreign-TV-Series/"
        ),
        Category(
            key = "anime-cartoon",
            name = "Anime & Cartoon TV Series",
            type = TvType.TvSeries,
            host = server5Host,
            path = "/FTP-5/Anime--Cartoon-TV-Series/"
        )
    )

    private val categoryMap = categories.associateBy { it.key }

    override val mainPage = mainPageOf(
        *categories.map { it.key to it.name }.toTypedArray()
    )

    private val mapper = jacksonObjectMapper()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class H5ItemsResponse(val items: List<H5Item> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class H5Item(
        val href: String = "",
        val time: Long? = null,
        val size: Long? = null
    ) {
        val isFolder: Boolean
            get() = size == null && href.endsWith("/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = categoryMap[request.data] ?: return newHomePageResponse(request.name, emptyList())
        val items = fetchDirectChildren(category.host, category.path)
            .filter { it.isFolder }
            .sortedByDescending { it.time ?: 0L }
            .mapNotNull { item ->
                val title = cleanName(decodeNameFromHref(item.href))
                if (title.isEmpty()) return@mapNotNull null
                buildSearchResponse(title, absoluteUrl(category.host, item.href), category.type)
            }

        val pageSize = 60
        val paged = items.drop((page - 1) * pageSize).take(pageSize)
        return newHomePageResponse(request.name, paged)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()

        val queryLower = normalized.lowercase()
        val maxResults = 60
        val results = ArrayList<SearchResponse>()

        for (category in categories) {
            if (results.size >= maxResults) break
            val items = fetchDirectChildren(category.host, category.path)
                .filter { it.isFolder }

            for (item in items) {
                if (results.size >= maxResults) break
                val title = cleanName(decodeNameFromHref(item.href))
                if (title.lowercase().contains(queryLower)) {
                    results.add(
                        buildSearchResponse(
                            title,
                            absoluteUrl(category.host, item.href),
                            category.type
                        )
                    )
                }
            }
        }

        return results
    }

    private fun buildSearchResponse(title: String, url: String, type: TvType): SearchResponse {
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, type)
        } else {
            newMovieSearchResponse(title, url, type)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val type = categoryTypeForUrl(url) ?: if (url.contains("/English%20Movies/")) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        return if (type == TvType.Movie) {
            loadMovie(url)
        } else {
            loadTvSeries(url, type)
        }
    }

    private fun categoryTypeForUrl(url: String): TvType? {
        val normalizedPath = pathFromUrl(url)
        return categories.firstOrNull { normalizedPath.startsWith(normalizePath(it.path)) }?.type
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val host = hostFromUrl(url)
        val path = pathFromUrl(url)
        val title = cleanName(decodeNameFromUrl(url))
        if (title.isEmpty()) throw ErrorLoadingException("Missing title")

        val items = fetchDirectChildren(host, path)
        val posterUrl = pickPoster(host, items)
        val year = extractYear(title)
        val videoItem = items.firstOrNull { isVideoFile(it.href) }
        val dataUrl = videoItem?.let { absoluteUrl(host, it.href) } ?: url

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    private suspend fun loadTvSeries(url: String, type: TvType): LoadResponse {
        val host = hostFromUrl(url)
        val path = pathFromUrl(url)
        val title = cleanName(decodeNameFromUrl(url))
        if (title.isEmpty()) throw ErrorLoadingException("Missing title")

        val items = fetchDirectChildren(host, path)
        val posterUrl = pickPoster(host, items)
        val year = extractYear(title)

        val episodes = ArrayList<Episode>()
        val seasonFolders = items.filter { it.isFolder && isSeasonFolder(it.href) }

        if (seasonFolders.isNotEmpty()) {
            val sortedSeasons = seasonFolders.sortedBy {
                extractSeasonNumber(decodeNameFromHref(it.href)) ?: Int.MAX_VALUE
            }

            for (season in sortedSeasons) {
                val seasonNumber = extractSeasonNumber(decodeNameFromHref(season.href))
                val seasonItems = fetchDirectChildren(host, season.href)
                    .filter { isVideoFile(it.href) }

                seasonItems.sortedBy { it.href }.forEachIndexed { index, item ->
                    val fileName = decodeNameFromHref(item.href)
                    val episodeNumber = extractEpisodeNumber(fileName) ?: (index + 1)
                    val derivedSeason = extractSeasonFromFileName(fileName) ?: seasonNumber

                    episodes.add(
                        newEpisode(absoluteUrl(host, item.href)) {
                            this.name = cleanFileName(fileName)
                            this.episode = episodeNumber
                            this.season = derivedSeason ?: seasonNumber
                        }
                    )
                }
            }
        } else {
            val seasonItems = items.filter { isVideoFile(it.href) }
            seasonItems.sortedBy { it.href }.forEachIndexed { index, item ->
                val fileName = decodeNameFromHref(item.href)
                val episodeNumber = extractEpisodeNumber(fileName) ?: (index + 1)
                val derivedSeason = extractSeasonFromFileName(fileName)

                episodes.add(
                    newEpisode(absoluteUrl(host, item.href)) {
                        this.name = cleanFileName(fileName)
                        this.episode = episodeNumber
                        this.season = derivedSeason
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = if (looksLikeVideoUrl(data)) {
            listOf(data)
        } else {
            val host = hostFromUrl(data)
            val path = pathFromUrl(data)
            fetchDirectChildren(host, path)
                .filter { isVideoFile(it.href) }
                .map { absoluteUrl(host, it.href) }
        }

        if (links.isEmpty()) return false

        links.forEach { link ->
            val fileName = link.substringAfterLast('/').substringBefore('?')
            val quality = getQualityFromName(fileName)

            when {
                link.contains(".m3u8", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(this.name, this.name, url = link, type = ExtractorLinkType.M3U8) {
                            this.quality = quality
                        }
                    )
                }
                link.contains(".mpd", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(this.name, this.name, url = link, type = ExtractorLinkType.DASH) {
                            this.quality = quality
                        }
                    )
                }
                else -> {
                    callback.invoke(
                        newExtractorLink(this.name, this.name, url = link) {
                            this.quality = quality
                        }
                    )
                }
            }
        }

        return true
    }

    private suspend fun fetchDirectChildren(host: String, href: String): List<H5Item> {
        val path = normalizePath(href)
        val jsonItems = fetchH5AiItems(host, path)
        if (jsonItems.isNotEmpty()) return directChildren(jsonItems, path)

        val doc = app.get("$host$path").document
        val htmlItems = parseHtmlItems(doc, path)
        return directChildren(htmlItems, path)
    }

    private suspend fun fetchH5AiItems(host: String, path: String): List<H5Item> {
        val response = runCatching {
            app.post(
                "$host$path?",
                data = mapOf(
                    "action" to "get",
                    "items[href]" to path,
                    "items[what]" to "1"
                )
            )
        }.getOrNull() ?: return emptyList()

        return runCatching {
            mapper.readValue<H5ItemsResponse>(response.text).items
        }.getOrElse { emptyList() }
    }

    private fun parseHtmlItems(doc: Document, parentPath: String): List<H5Item> {
        return doc.select("a[href]")
            .mapNotNull { element ->
                val rawHref = element.attr("href").trim()
                if (rawHref.isEmpty() || rawHref == "#" || rawHref.startsWith("?")) return@mapNotNull null

                val path = toAbsolutePath(parentPath, rawHref) ?: return@mapNotNull null
                val isFolder = rawHref.endsWith("/") || path.endsWith("/")
                val finalHref = if (isFolder && !path.endsWith("/")) "$path/" else path

                H5Item(
                    href = finalHref,
                    time = null,
                    size = if (isFolder) null else 0L
                )
            }
            .distinctBy { it.href }
    }

    private fun toAbsolutePath(parentPath: String, href: String): String? {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> runCatching {
                URI(href).rawPath
            }.getOrNull()
            href.startsWith("/") -> href
            else -> normalizePath(parentPath) + href
        }
    }

    private fun directChildren(items: List<H5Item>, parentHref: String): List<H5Item> {
        val parent = normalizePath(parentHref)
        return items.mapNotNull { item ->
            if (!item.href.startsWith(parent) || item.href == parent) return@mapNotNull null
            val relative = item.href.removePrefix(parent)
            val clean = relative.trim('/')
            if (clean.isEmpty() || clean.contains('/')) return@mapNotNull null
            item
        }.distinctBy { it.href }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        var normalized = if (path.startsWith("/")) path else "/$path"
        if (!normalized.endsWith("/")) normalized += "/"
        return normalized
    }

    private fun hostFromUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.host != null) {
            val scheme = uri.scheme ?: "https"
            return "$scheme://${uri.host}"
        }
        return mainUrl
    }

    private fun pathFromUrl(url: String): String {
        val uri = URI(url)
        val rawPath = uri.rawPath ?: "/"
        return normalizePath(rawPath)
    }

    private fun absoluteUrl(host: String, href: String): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") -> host.trimEnd('/') + href
            else -> host.trimEnd('/') + "/" + href
        }
    }

    private fun decodeNameFromHref(href: String): String {
        val name = href.trimEnd('/').substringAfterLast('/')
        return decodeComponent(name)
    }

    private fun decodeNameFromUrl(url: String): String {
        val rawPath = URI(url).rawPath ?: url
        val name = rawPath.trimEnd('/').substringAfterLast('/')
        return decodeComponent(name)
    }

    private fun decodeComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun cleanName(name: String): String {
        return name.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanFileName(name: String): String {
        val base = name.substringBeforeLast('.')
        return base.replace('.', ' ').replace('_', ' ').trim()
    }

    private fun extractYear(text: String): Int? {
        return Regex("(19|20)\\d{2}").find(text)?.value?.toIntOrNull()
    }

    private fun isSeasonFolder(href: String): Boolean {
        val name = decodeNameFromHref(href).lowercase()
        return name.contains("season") || Regex("\\bs\\d{1,2}\\b").containsMatchIn(name)
    }

    private fun extractSeasonNumber(name: String): Int? {
        return Regex("season\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\bs(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSeasonFromFileName(name: String): Int? {
        return Regex("s(\\d{1,2})e\\d{1,3}", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(name: String): Int? {
        return Regex("s\\d{1,2}e(\\d{1,3})", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("e(\\d{1,3})", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("episode\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun pickPoster(host: String, items: List<H5Item>): String? {
        val posterItem = items.firstOrNull { isImageFile(it.href) && it.href.contains("poster", true) }
            ?: items.firstOrNull { isImageFile(it.href) }

        return posterItem?.let { absoluteUrl(host, it.href) }
    }

    private val videoExtensions = setOf(
        "mkv",
        "mp4",
        "m4v",
        "avi",
        "webm",
        "mov",
        "ts",
        "m3u8",
        "mpd"
    )

    private val imageExtensions = setOf(
        "jpg",
        "jpeg",
        "png",
        "webp"
    )

    private fun isVideoFile(href: String): Boolean {
        return hasExtension(href, videoExtensions)
    }

    private fun isImageFile(href: String): Boolean {
        return hasExtension(href, imageExtensions)
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        return hasExtension(url, videoExtensions)
    }

    private fun hasExtension(url: String, extensions: Set<String>): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return extensions.any { ext -> clean.endsWith(".$ext") }
    }
}
