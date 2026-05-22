package com.nehal.oldftpbd

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
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
import org.jsoup.nodes.Element

open class OldFTPBDProvider : MainAPI() {
    override var mainUrl = "https://old.ftpbd.net"
    override var name = "Old FTPBD"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    private data class Category(val path: String, val name: String, val type: TvType)

    private val categories = listOf(
        Category("category/movies", "Movies", TvType.Movie),
        Category("category/3d-movies", "3D Movies", TvType.Movie),
        Category("category/3d-movies/3d-movies-2019", "3D Movies 2019", TvType.Movie),
        Category("category/movies/4k-movies", "4K Movies", TvType.Movie),
        Category("category/movies/dual-audio", "Dual Audio", TvType.Movie),
        Category("category/imdb-top-250", "IMDB Top 250", TvType.Movie),
        Category("category/movie-series", "Movie Series", TvType.Movie),

        Category("category/movies/english-movies", "English Movies", TvType.Movie),
        Category("category/movies/english-movies/english-movies-2023", "English Movies 2023", TvType.Movie),
        Category("category/movies/english-movies/english-movies-2024", "English Movies 2024", TvType.Movie),
        Category("category/movies/foreign-movies", "Foreign Movies", TvType.Movie),
        Category("category/movies/foreign-movies/foreign-movies-2023", "Foreign Movies 2023", TvType.Movie),
        Category("category/movies/foreign-movies/foreign-movies-2024", "Foreign Movies 2024", TvType.Movie),

        Category("category/movies/hindi-movies", "Hindi Movies", TvType.Movie),
        Category("category/movies/hindi-movies/hindi-movies-2023", "Hindi Movies 2023", TvType.Movie),
        Category("category/movies/hindi-movies/hindi-movies-2024", "Hindi Movies 2024", TvType.Movie),

        Category("category/movies/bangla-movies", "Bengali Movies", TvType.Movie),
        Category("category/movies/bangla-movies/bengali-movies-2022", "Bengali Movies 2022", TvType.Movie),
        Category("category/movies/bangla-movies/bengali-movies-2023", "Bengali Movies 2023", TvType.Movie),
        Category("category/movies/bangla-movies/bengali-movies-2024", "Bengali Movies 2024", TvType.Movie),

        Category("category/movies/south-indian-movies", "South Indian Movies", TvType.Movie),
        Category("category/movies/south-indian-movies/south-hindi-dubdual", "South Hindi Dub/Dual", TvType.Movie),
        Category("category/movies/south-indian-movies/south-indian-2023", "South Indian 2023", TvType.Movie),
        Category("category/movies/south-indian-movies/south-indian-2024", "South Indian 2024", TvType.Movie),

        Category("category/animation-movies", "Animation Movies", TvType.Movie),
        Category("category/animation-movies/animation-hindi-english-dubbed", "Animation Hindi & English Dubbed", TvType.Movie),
        Category("category/animation-movies/animation-movies-2023", "Animation Movies 2023", TvType.Movie),
        Category("category/animation-movies/animation-movies-2024", "Animation Movies 2024", TvType.Movie),

        Category("category/documentary", "Documentary", TvType.Movie),
        Category("category/documentary/awards-tv-shows", "Awards & TV Shows", TvType.TvSeries),

        Category("category/tv-series", "English & Foreign TV Series", TvType.TvSeries),
        Category("category/animation-movies/anime-cartoon-tv-series", "Anime & Cartoon TV Series", TvType.TvSeries),
        Category("category/indian-web-series", "Indian Web Series", TvType.TvSeries),
        Category("category/indian-web-series/hindi-tv-series", "Hindi TV Series", TvType.TvSeries),
        Category("category/movies/bangla-movies/bangla-web-series", "Bangla Web Series", TvType.TvSeries),
        Category("category/movies/south-indian-movies/south-indian-tv-series", "South Indian TV Series", TvType.TvSeries)
    )

    override val mainPage = mainPageOf(
        *categories.map { it.path to it.name }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildCategoryPageUrl(request.data, page)
        val doc = app.get(pageUrl).document
        val categoryType = categories.firstOrNull { it.path == request.data }?.type ?: TvType.Movie
        val items = parseSearchItems(doc, categoryType)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return parseSearchItems(doc, TvType.Movie)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Missing title")

        val poster = extractPoster(doc)
        val plot = extractPlot(doc)
        val year = extractYear(doc)
        val duration = extractDuration(doc)
        val tags = extractGenres(doc)
        val downloadLinks = extractDownloadLinks(doc)

        val isTvSeries = isTvSeries(doc, title)

        return if (isTvSeries && downloadLinks.isNotEmpty()) {
            val episodes = downloadLinks.mapIndexed { index, link ->
                newEpisode(link.url) {
                    this.name = link.name
                    this.episode = index + 1
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = if (looksLikeDownload(data)) {
            listOf(DownloadLink("Link", data))
        } else {
            val doc = app.get(data).document
            extractDownloadLinks(doc)
        }

        if (links.isEmpty()) return false

        links.forEach { link ->
            if (link.url.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        link.name,
                        url = link.url,
                        type = ExtractorLinkType.M3U8
                    )
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        link.name,
                        url = link.url
                    )
                )
            }
        }
        return true
    }

    private fun buildCategoryPageUrl(path: String, page: Int): String {
        val base = "${mainUrl.trimEnd('/')}/${path.trimStart('/')}".trimEnd('/')
        return if (page <= 1) {
            "$base/"
        } else {
            "$base/page/$page/"
        }
    }

    private fun parseSearchItems(doc: Document, defaultType: TvType): List<SearchResponse> {
        val articles = doc.select("article")
        val items = if (articles.isNotEmpty()) articles else doc.select(".post, .type-post")
        return items.mapNotNull { it.toSearchResponse(defaultType) }
    }

    private fun Element.toSearchResponse(defaultType: TvType): SearchResponse? {
        val link = selectFirst("a:has(img)")
            ?: selectFirst(".entry-title a, .post-title a, h1 a, h2 a, h3 a")
            ?: return null
        val href = fixUrlNull(link.attr("href").trim()) ?: return null

        val title = link.attr("title").trim().ifEmpty {
            selectFirst(".entry-title a, .post-title a, h1 a, h2 a, h3 a")?.text()?.trim()
                ?: link.text().trim()
        }
        if (title.isEmpty()) return null

        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }?.trim()

        return if (defaultType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    private fun extractPoster(doc: Document): String? {
        val primary = doc.selectFirst(
            ".post-thumbnail img, .post-image img, .entry-content img, .single-post img"
        )
        val fromPrimary = primary?.attr("data-src")?.ifBlank { primary.attr("src") }?.trim()
        if (!fromPrimary.isNullOrBlank()) return fromPrimary

        val fallback = doc.select("img[src*='/wp-content/uploads/']").firstOrNull()
        return fallback?.attr("src")?.trim()
    }

    private fun extractPlot(doc: Document): String? {
        val overview = doc.select("p, li").firstOrNull { element ->
            val text = element.text().trim()
            text.startsWith("Overview", ignoreCase = true) ||
                element.selectFirst("strong, b")?.text()?.trim()?.equals("Overview", true) == true
        }

        if (overview != null) {
            val raw = overview.text().trim()
            val cleaned = raw.substringAfter("Overview", raw).trim(':', '-', ' ')
            if (cleaned.isNotBlank()) return cleaned
        }

        return doc.selectFirst(".entry-content p")?.text()?.trim()
    }

    private fun extractYear(doc: Document): Int? {
        val byDateLink = doc.select("a[href*='/date/']")
            .mapNotNull { element ->
                Regex("(19|20)\\d{2}").find(element.text())?.value?.toIntOrNull()
            }
            .firstOrNull()

        if (byDateLink != null) return byDateLink

        val textBlock = doc.select("p, li").joinToString(" ") { it.text() }
        return Regex("(19|20)\\d{2}").find(textBlock)?.value?.toIntOrNull()
    }

    private fun extractDuration(doc: Document): Int? {
        val runtimeText = doc.select("p, li").firstOrNull { element ->
            element.text().contains("Runtime", ignoreCase = true)
        }?.text()?.substringAfter("Runtime", "")?.trim()

        return runtimeText?.takeIf { it.isNotBlank() }?.let { getDurationFromString(it) }
    }

    private fun extractGenres(doc: Document): List<String>? {
        val genres = doc.select("a[href*='/genre/']")
            .mapNotNull { it.text().trim().ifEmpty { null } }
            .distinct()
        return genres.takeIf { it.isNotEmpty() }
    }

    private fun isTvSeries(doc: Document, title: String): Boolean {
        if (title.contains("tv series", ignoreCase = true)) return true
        if (title.contains("web series", ignoreCase = true)) return true

        val categoryLinks = doc.select("a[href*='/category/']")
            .map { it.attr("href").lowercase() }

        return categoryLinks.any { href ->
            href.contains("/tv-series") ||
                href.contains("web-series") ||
                href.contains("anime-cartoon-tv-series") ||
                href.contains("bangla-web-series") ||
                href.contains("south-indian-tv-series") ||
                href.contains("awards-tv-shows")
        }
    }

    private data class DownloadLink(val name: String, val url: String)

    private val downloadExtensions = setOf(
        "mkv",
        "mp4",
        "avi",
        "m4v",
        "webm",
        "mov",
        "wmv",
        "m3u8",
        "mpd",
        "ts"
    )

    private fun extractDownloadLinks(doc: Document): List<DownloadLink> {
        val links = doc.select("a[href]").mapNotNull { element ->
            val href = element.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val label = element.text().trim()
            val looksLikeDownload = looksLikeDownload(href) ||
                label.contains("download", ignoreCase = true) ||
                label.contains("dwn", ignoreCase = true)

            if (!looksLikeDownload) return@mapNotNull null

            val fixedUrl = fixUrlNull(href) ?: return@mapNotNull null
            val name = label.ifBlank { element.attr("title").trim() }
            val finalName = name.ifBlank { guessNameFromUrl(fixedUrl) }
            DownloadLink(finalName, fixedUrl)
        }

        return links.distinctBy { it.url }
    }

    private fun looksLikeDownload(url: String): Boolean {
        val clean = url.lowercase()
        if (clean.startsWith("magnet:")) return true
        if (downloadExtensions.any { clean.contains(".$it") }) return true
        if (clean.contains("ftpbd.net") && (clean.contains("/ftp-") || clean.contains("server"))) return true
        if (clean.contains("media.ftpbd.net")) return true
        return false
    }

    private fun guessNameFromUrl(url: String): String {
        val fileName = url.substringAfterLast('/').substringBefore('?')
        return fileName.replace('-', ' ').replace('_', ' ').trim()
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> mainUrl.trimEnd('/') + "/" + url
        }
    }
}
