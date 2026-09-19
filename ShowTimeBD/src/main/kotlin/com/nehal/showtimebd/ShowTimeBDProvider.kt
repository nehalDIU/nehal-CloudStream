package com.nehal.showtimebd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

open class ShowTimeBDProvider : MainAPI() {
    override var mainUrl = "http://10.100.100.10"
    override var name = "ShowTime BD"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override var lang = "bn"
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    override val mainPage = mainPageOf(
        "movie/today_upload" to "Today's Uploads",
        "movie/all_movie" to "All Movies",
        "movie/category_movie/1" to "Hollywood Movies",
        "movie/category_movie/3" to "Bollywood Movies",
        "movie/category_movie/6" to "Tamil & Telugu Movies",
        "movie/category_movie/27" to "Indian Bangla Movies",
        "movie/category_movie/5" to "Animation Movies",
        "movie/hindi_dubbed" to "Hindi Dubbed",
        "movie/SubCategoryMovie/7" to "English TV Series",
        "movie/SubCategoryMovie/8" to "Hindi TV Series",
        "movie/SubCategoryMovie/15" to "Indian Bangla TV Series",
        "movie/SubCategoryMovie/12" to "Animation Series",
        "movie/SubCategoryMovie/16" to "Foreign TV Series",
        "movie/SubCategoryMovie/4" to "Chinese Movies",
        "movie/SubCategoryMovie/5" to "Korean Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimStart('/')
        val url = if (page <= 1) {
            "$mainUrl/$path"
        } else {
            if (path.contains("?")) {
                "$mainUrl/$path&page=$page"
            } else {
                "$mainUrl/$path?page=$page"
            }
        }

        val doc = app.get(url, timeout = 30L, cacheTime = 60).document
        val items = doc.select("div.single_movie").mapNotNull { it.toSearchResponse() }

        val hasNextPage = doc.selectFirst("a[href*='page=${page + 1}']") != null ||
                (items.size >= 20 && !path.contains("today_upload"))

        return newHomePageResponse(
            name = request.name,
            list = items,
            hasNext = hasNextPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val doc = app.get(
            "$mainUrl/movie/search",
            params = mapOf("search" to cleanQuery),
            timeout = 30L
        ).document
        return doc.select("div.single_movie").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.toSearchResponse(): SearchResponse? {
        // Priority 1: figcaption > a (direct title link with text)
        // Priority 2: any anchor in figcaption that is not a download link
        // Priority 3: any figcaption anchor
        val titleElement = selectFirst("figcaption > a")
            ?: selectFirst("figcaption a:not([href*='data1']):not(:has(i.fa-download))")
            ?: selectFirst("figcaption a")

        var rawTitle = titleElement?.text()?.trim() ?: ""
        if (rawTitle.isEmpty()) {
            rawTitle = selectFirst("div.photo_grid img")?.attr("alt")?.trim() ?: ""
        }
        if (rawTitle.isEmpty()) return null

        val href = fixUrlNull(titleElement?.attr("href")?.trim())
            ?: fixUrlNull(selectFirst("div.photo_grid a")?.attr("href")?.trim())
            ?: return null

        val title = rawTitle.substringBefore("Views").trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(selectFirst("div.photo_grid img")?.attr("src"))

        // Detect TV Series from title or card download URL
        val downloadHref = selectFirst("figcaption a[href*='data1']")?.attr("href")?.trim() ?: ""
        val isTvSeries = title.contains(Regex("""(?i)\b(season|series|s\d+|episodes?|show)\b""")) ||
                downloadHref.contains("/TVSERIES/", ignoreCase = true) ||
                href.contains("/TVSERIES/", ignoreCase = true)

        val type = if (isTvSeries) TvType.TvSeries else TvType.Movie

        val quality = when {
            title.contains(Regex("""(?i)\b(2160p|4k)\b""")) -> SearchQuality.UHD
            title.contains(Regex("""(?i)\b(1080p|720p|web-dl|bluray|hdrip)\b""")) -> SearchQuality.HD
            title.contains(Regex("""(?i)\b(camrip|cam|ts|hdts)\b""")) -> SearchQuality.Cam
            else -> null
        }

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "http:$trimmed"
            trimmed.startsWith("/") -> mainUrl.trimEnd('/') + trimmed
            else -> mainUrl.trimEnd('/') + "/" + trimmed
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30L, cacheTime = 60).document
        val singlePage = doc.selectFirst(".single_page") ?: throw ErrorLoadingException("Invalid page layout")
        val title = singlePage.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Missing title")

        val videoTag = doc.selectFirst("video#movie-video, video.movie-video, video")
        val videoPoster = videoTag?.attr("poster")?.ifBlank { null }
        val sliderPoster = doc.selectFirst("img[src*='movie-slider']")?.attr("src")?.ifBlank { null }
        val backdrop = fixUrlNull(videoPoster ?: sliderPoster)
        val poster = fixUrlNull(doc.selectFirst(".single_page img, div.photo_grid img")?.attr("src")) ?: backdrop

        // Find download button or direct video link
        val downloadBtnUrl = singlePage.selectFirst("a:has(button)")?.attr("href")?.trim()
            ?: singlePage.selectFirst("a[href*='data1']")?.attr("href")?.trim()
            ?: singlePage.selectFirst("a:matches((?i)download)")?.attr("href")?.trim()
        val videoSrc = videoTag?.attr("src")?.trim()?.ifBlank { null } ?: videoTag?.attr("data-fallback-src")?.trim()
        val anyDataLink = singlePage.select("a[href]").map { it.attr("href").trim() }
            .firstOrNull { it.contains("data1", ignoreCase = true) || it.endsWith(".mkv", ignoreCase = true) || it.endsWith(".mp4", ignoreCase = true) || it.contains("/TVSERIES/", ignoreCase = true) }

        val targetUrl = downloadBtnUrl?.ifBlank { null } ?: videoSrc?.ifBlank { null } ?: anyDataLink
            ?: throw ErrorLoadingException("No stream or download link available")

        var year: Int? = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        var tags: List<String>? = null
        var actors: List<String>? = null
        var duration: Int? = null

        doc.select(".single_page li").forEach { li ->
            val text = li.text().trim()
            val parts = text.split(":", limit = 2)
            if (parts.size < 2) return@forEach
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when {
                key.contains("genre") -> {
                    tags = value.split(",", "/").mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
                }
                key.contains("realese") || key.contains("release") || key.contains("publish") -> {
                    if (year == null) {
                        year = Regex("""\b(19\d\d|20\d\d)\b""").find(value)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
                key.contains("cast") || key.contains("actor") -> {
                    actors = value.split(",", ";").mapNotNull { it.trim().takeIf { a -> a.isNotEmpty() } }
                }
                key.contains("duration") || key.contains("runtime") -> {
                    val minMatch = Regex("""(\d+)\s*min""").find(value)
                    duration = minMatch?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        }

        val isDirectoryOrSeries = targetUrl.endsWith("/") ||
                targetUrl.contains("/TVSERIES/", ignoreCase = true) ||
                title.contains(Regex("""(?i)\b(season|series|s\d+)\b"""))

        if (isDirectoryOrSeries) {
            val dirUrl = if (targetUrl.endsWith("/")) targetUrl else "$targetUrl/"
            val encodedDirUrl = dirUrl.replace(" ", "%20")
            val episodes = mutableListOf<Episode>()

            try {
                val dirDoc = app.get(encodedDirUrl, timeout = 30L).document
                val links = dirDoc.select("a[href]").map { it.attr("href") }
                val videoExtensions = listOf(".mkv", ".mp4", ".avi", ".webm", ".ts")

                val titleSeason = Regex("""(?i)Season\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                // Check for subfolders (e.g. Season 1, Season 2)
                val subfolders = links.filter {
                    it.endsWith("/") && !it.startsWith("?") && it != "../" && it != "/"
                }

                if (subfolders.isNotEmpty()) {
                    for (sub in subfolders) {
                        val subSeasonNum = Regex("""(?i)Season\s*(\d+)""").find(sub)?.groupValues?.get(1)?.toIntOrNull() ?: titleSeason
                        val subDirUrl = dirUrl.trimEnd('/') + "/" + sub
                        val subEncoded = subDirUrl.replace(" ", "%20")
                        try {
                            val subDoc = app.get(subEncoded, timeout = 30L).document
                            val subLinks = subDoc.select("a[href]").map { it.attr("href") }
                            subLinks.forEachIndexed { sIdx, sHref ->
                                val sLower = sHref.lowercase()
                                if (sHref.startsWith("?") || sHref == "../" || sHref == "/") return@forEachIndexed
                                if (videoExtensions.any { sLower.endsWith(it) }) {
                                    val sFileUrl = subDirUrl.trimEnd('/') + "/" + sHref
                                    val seMatch = Regex("""(?i)[sS](\d+)[eE](\d+)""").find(sHref)
                                    val epOnlyMatch = Regex("""(?i)[eE][pP]?(\d+)""").find(sHref)

                                    val epSeason = seMatch?.groupValues?.get(1)?.toIntOrNull() ?: subSeasonNum
                                    val epNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                                        ?: epOnlyMatch?.groupValues?.get(1)?.toIntOrNull()
                                        ?: (sIdx + 1)

                                    val decodedName = try {
                                        URLDecoder.decode(sHref, StandardCharsets.UTF_8.name())
                                    } catch (_: Exception) {
                                        sHref
                                    }

                                    val epName = decodedName.substringBeforeLast(".")
                                        .replace(".", " ")
                                        .replace("_", " ")
                                        .replace(Regex("""(?i)\b(1080p|720p|480p|2160p|4k|web-dl|amzn|ddp5\.1|hevc|x265|x264|av1|bluray|hindi|multi|esub|aac\d\.\d)\b.*"""), "")
                                        .trim()

                                    episodes.add(
                                        newEpisode(sFileUrl) {
                                            this.name = epName.ifBlank { "Episode $epNum" }
                                            this.season = epSeason
                                            this.episode = epNum
                                            this.posterUrl = backdrop ?: poster
                                        }
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // If no subfolders or no episodes from subfolders, parse direct files in folder
                if (episodes.isEmpty()) {
                    links.forEachIndexed { index, href ->
                        val lowerHref = href.lowercase()
                        if (href.startsWith("?") || href == "../" || href == "/") return@forEachIndexed

                        if (videoExtensions.any { lowerHref.endsWith(it) }) {
                            val fileUrl = dirUrl.trimEnd('/') + "/" + href

                            val seMatch = Regex("""(?i)[sS](\d+)[eE](\d+)""").find(href)
                            val epOnlyMatch = Regex("""(?i)[eE][pP]?(\d+)""").find(href)

                            val seasonNum = seMatch?.groupValues?.get(1)?.toIntOrNull() ?: titleSeason
                            val epNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                                ?: epOnlyMatch?.groupValues?.get(1)?.toIntOrNull()
                                ?: (index + 1)

                            val decodedName = try {
                                URLDecoder.decode(href, StandardCharsets.UTF_8.name())
                            } catch (_: Exception) {
                                href
                            }

                            val epName = decodedName.substringBeforeLast(".")
                                .replace(".", " ")
                                .replace("_", " ")
                                .replace(Regex("""(?i)\b(1080p|720p|480p|2160p|4k|web-dl|amzn|ddp5\.1|hevc|x265|x264|av1|bluray|hindi|multi|esub|aac\d\.\d)\b.*"""), "")
                                .trim()

                            episodes.add(
                                newEpisode(fileUrl) {
                                    this.name = epName.ifBlank { "Episode $epNum" }
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = backdrop ?: poster
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore error and fall through to fallback episode
            }

            if (episodes.isEmpty()) {
                // If directory fetch fails or has no videos, fallback to standard single episode
                episodes.add(
                    newEpisode(targetUrl) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = backdrop ?: poster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.tags = tags
                this.duration = duration
                if (!actors.isNullOrEmpty()) {
                    this.addActors(actors)
                }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.tags = tags
                this.duration = duration
                if (!actors.isNullOrEmpty()) {
                    this.addActors(actors)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.trim().replace(" ", "%20")
        val isM3u8 = cleanUrl.contains(".m3u8", ignoreCase = true)
        val isMpd = cleanUrl.contains(".mpd", ignoreCase = true)
        val linkType = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isMpd -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val quality = when {
            cleanUrl.contains("2160p", ignoreCase = true) || cleanUrl.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            cleanUrl.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            cleanUrl.contains("720p", ignoreCase = true) -> Qualities.P720.value
            cleanUrl.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = cleanUrl,
                type = linkType
            ) {
                this.referer = "$mainUrl/"
                this.quality = quality
            }
        )
        return true
    }
}

