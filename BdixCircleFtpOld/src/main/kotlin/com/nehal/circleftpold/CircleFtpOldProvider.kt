package com.nehal.circleftpold

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class CircleFtpOldProvider : MainAPI() {
    override var mainUrl = "http://main.circleftp.net"
    private var mainApiUrl = "http://new.circleftp.net:5000"
    private val apiUrl = "http://15.1.1.50:5000"
    override var name = "(BDIX) Circle FTP Old"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.OVA,
        TvType.Others
    )

    override val mainPage = mainPageOf(
        "category/english-movies" to "English Movies",
        "category/hindi-movies" to "Hindi Movies",
        "category/english-foreign-tv-series" to "English & Foreign TV Series",
        "category/dubbed-tv-series-shows" to "Dubbed TV Series & Shows",
        "category/hindi-tv-serials" to "Hindi TV Serials",
        "category/english-hindi-dubbed" to "English & Foreign Hindi Dubbed Movies",
        "category/south-indian-movies" to "South Indian Movies",
        "category/south-indian-dubbed" to "South Indian Dubbed Movies",
        "category/foreign-language-movies" to "Foreign Language Movies",
        "category/animation-movies" to "Animation Movies",
        "category/wwe" to "WWE"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val cleanPath = request.data.trim('/')
        val url = if (page <= 1) {
            "$mainUrl/$cleanPath/"
        } else {
            "$mainUrl/$cleanPath/page/$page/"
        }

        val doc = app.get(url, verify = false, cacheTime = 60, timeout = 30).document
        val articles = doc.select("article.category-listing")
        val items = articles.mapNotNull { parseArticleToSearchResponse(it) }
        val hasNext = doc.selectFirst(".pagination .next, .nav-links .next, a.next") != null || items.size >= 80

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun parseArticleToSearchResponse(article: Element): SearchResponse? {
        val linkElement = article.selectFirst("a[href*='/cn/']") ?: article.selectFirst(".entry-title a") ?: article.selectFirst("a")
            ?: return null
        val rawHref = linkElement.attr("href").trim()
        if (rawHref.isBlank()) return null
        val href = fixUrl(rawHref)

        val title = article.selectFirst(".entry-title a")?.text()?.trim()
            ?: article.selectFirst(".entry-title")?.text()?.trim()
            ?: linkElement.text().trim()
        if (title.isBlank()) return null

        val img = article.selectFirst("img")
        val rawPoster = img?.let {
            val dataSrc = it.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else it.attr("src").takeIf { src -> !src.startsWith("data:image") && src.isNotBlank() }
        }
        val poster = fixUrlNull(rawPoster)

        val isTv = href.contains("tv-series") ||
                title.contains("tv series", ignoreCase = true) ||
                title.contains("tv shows", ignoreCase = true) ||
                title.contains("tv serials", ignoreCase = true) ||
                article.hasClass("genre-tv-series")

        val quality = getSearchQuality(title)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val res = try {
            app.get(
                "$mainApiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false,
                cacheTime = 60,
                timeout = 30
            )
        } catch (_: Exception) {
            try {
                app.get(
                    "$apiUrl/api/posts?searchTerm=$query&order=desc",
                    verify = false,
                    cacheTime = 60,
                    timeout = 30
                )
            } catch (_: Exception) {
                return emptyList()
            }
        }

        return try {
            res.parsedSafe<PageData>()?.posts?.mapNotNull { post ->
                toSearchResult(post)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toSearchResult(post: Post): SearchResponse? {
        val postType = post.type ?: return null
        if (postType == "singleVideo" || postType == "series") {
            val title = post.title?.takeIf { it.isNotBlank() }
                ?: post.name?.takeIf { it.isNotBlank() }
                ?: "No Title"
            val isTv = postType == "series" || title.contains("tv series", ignoreCase = true)
            val loadUrl = "$mainApiUrl/api/posts/${post.id}"
            val posterUrl = post.imageSm?.let { "$mainApiUrl/uploads/$it" }
            val quality = getSearchQuality(title)

            return if (isTv) {
                newTvSeriesSearchResponse(title, loadUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            } else {
                newMovieSearchResponse(title, loadUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.quality = quality
                }
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("/api/posts/")) {
            loadFromApi(url)
        } else {
            loadFromWeb(url)
        }
    }

    private suspend fun loadFromWeb(url: String): LoadResponse {
        val doc = app.get(url, verify = false, cacheTime = 60, timeout = 30).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.title().replace(" - Circle Network", "").replace("Circle Network |", "").trim()

        val rawPoster = doc.selectFirst(".entry-thumb img, .entry-featured img, article img.wp-post-image, article img")?.let { img ->
            val dataSrc = img.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else img.attr("src").takeIf { src -> !src.startsWith("data:image") && src.isNotBlank() }
        }
        val poster = fixUrlNull(rawPoster)

        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.value?.toIntOrNull()
        val plot = doc.selectFirst(".wpmoly.movie .overview.value")?.text()?.takeIf { it != "—" && it.isNotBlank() }
            ?: doc.selectFirst(".entry-content p")?.text()?.trim()

        val tags = doc.select(".wpmoly.movie .genres.value a, .entry-categories a, .post-tags a, a[rel='category tag'], a[rel='tag']")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .distinct()

        val actors = doc.select(".wpmoly.movie .cast.value a, .cast a")
            .mapNotNull { it.text().trim().takeIf { a -> a.isNotBlank() } }
            .distinct()

        val trailer = doc.selectFirst("iframe[src*='youtube.com'], iframe[src*='youtu.be']")?.attr("src")

        val panes = doc.select(".su-tabs-pane")
        val isTv = panes.isNotEmpty() || url.contains("tv-series") || title.contains("tv series", ignoreCase = true)

        if (isTv && panes.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            panes.forEachIndexed { index, pane ->
                val paneTitle = pane.attr("data-title").ifBlank { "Season ${index + 1}" }
                val seasonNum = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(paneTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)

                val rows = pane.select("tr")
                var epCount = 0
                for (row in rows) {
                    val linkEl = row.selectFirst("a[href*='.mkv'], a[href*='.mp4'], a[href*='.avi'], a[href*='circleftp']") ?: continue
                    val rawHref = linkEl.attr("href").trim()
                    if (rawHref.isBlank()) continue
                    val href = fixUrl(rawHref)
                    epCount++

                    val epName = row.selectFirst("td:not(:has(a))")?.text()?.trim()
                        ?: linkEl.text().trim()
                    val epNum = Regex("[Ss]\\d+[Ee](\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("[Ee]pisode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: epCount

                    episodes.add(newEpisode(href) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    if (actors.isNotEmpty()) addActors(actors)
                    if (!trailer.isNullOrBlank()) addTrailer(trailer)
                }
            }
        }

        // Fallback or single video / movie
        val movieUrl = doc.selectFirst("video source[src]")?.attr("src")?.trim()
            ?: doc.selectFirst("video[src]")?.attr("src")?.trim()
            ?: doc.selectFirst("a.downloadbtn[href]")?.attr("href")?.trim()
            ?: doc.selectFirst("a[href*='.mkv'], a[href*='.mp4']")?.attr("href")?.trim()
            ?: extractVidSwap(doc.html())
            ?: doc.selectFirst("iframe[src*='circleftp'], iframe[src*='embed'], .entry-content iframe")?.attr("src")?.trim()
            ?: url

        val duration = doc.selectFirst(".wpmoly.movie .runtime.value")?.text()?.takeIf { it != "—" }?.let {
            getDurationFromString(it)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(movieUrl)) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.duration = duration
            if (actors.isNotEmpty()) addActors(actors)
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    private suspend fun loadFromApi(url: String): LoadResponse {
        val res = try {
            app.get(url, verify = false, cacheTime = 60, timeout = 30)
        } catch (_: Exception) {
            val fallbackUrl = url.replace(mainApiUrl, apiUrl)
            app.get(fallbackUrl, verify = false, cacheTime = 60, timeout = 30)
        }

        val loadData = res.parsedSafe<Data>() ?: throw ErrorLoadingException("Invalid API response")
        val title = loadData.title?.takeIf { it.isNotBlank() }
            ?: loadData.name?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        val poster = loadData.image?.let { "$mainApiUrl/uploads/$it" }
        val description = loadData.metaData
        val year = selectUntilNonInt(loadData.year)

        if (loadData.type == "singleVideo") {
            val movieUrl = res.parsedSafe<Movies>()?.content ?: url
            val duration = getDurationFromString(loadData.watchTime)
            return newMovieLoadResponse(title, url, TvType.Movie, movieUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.year = year
                this.plot = description
                this.duration = duration
            }
        } else {
            val tvData = res.parsedSafe<TvSeries>()
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData?.content?.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes?.forEach { ep ->
                    val epLink = ep.link ?: return@forEach
                    episodeNum++
                    episodesData.add(
                        newEpisode(epLink) {
                            this.episode = episodeNum
                            this.season = seasonNum
                            this.name = ep.title
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.year = year
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || data == mainUrl) return false

        // Check if data is an embed link (e.g. iframe or external embed)
        if (!data.contains("circleftp") && !isDirectVideoUrl(data)) {
            val loaded = loadExtractor(data, mainUrl, subtitleCallback, callback)
            if (loaded) return true
        }

        val quality = getStreamQuality(data)
        val linkType = if (data.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        // Standard hostname stream
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = linkType
            ) {
                this.quality = quality
            }
        )

        // Direct BDIX IP stream
        val ipLink = linkToIp(data)
        if (ipLink.isNotBlank() && ipLink != data) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} (Direct IP)",
                    url = ipLink,
                    type = linkType
                ) {
                    this.quality = quality
                }
            )
        }
        return true
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                lower.endsWith(".m3u8") || lower.endsWith(".webm")
    }

    private fun getStreamQuality(name: String): Int {
        val lower = name.lowercase()
        return when {
            "2160p" in lower || "4k" in lower -> Qualities.P2160.value
            "1080p" in lower -> Qualities.P1080.value
            "720p" in lower -> Qualities.P720.value
            "480p" in lower -> Qualities.P480.value
            "360p" in lower -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun extractVidSwap(html: String): String? {
        val regex = Regex("vidSwap\\(['\"]([^'\"]+)['\"]\\)")
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun linkToIp(data: String?): String {
        if (data == null) return ""
        return when {
            "index.circleftp.net" in data -> data.replace("index.circleftp.net", "15.1.4.2")
            "index1.circleftp.net" in data -> data.replace("index1.circleftp.net", "15.1.4.9")
            "index2.circleftp.net" in data -> data.replace("index2.circleftp.net", "15.1.4.5")
            "ftp3.circleftp.net" in data -> data.replace("ftp3.circleftp.net", "15.1.5.62")
            "ftp4.circleftp.net" in data -> data.replace("ftp4.circleftp.net", "15.1.1.30")
            "ftp5.circleftp.net" in data -> data.replace("ftp5.circleftp.net", "15.1.1.10")
            "ftp6.circleftp.net" in data -> data.replace("ftp6.circleftp.net", "15.1.2.3")
            "ftp7.circleftp.net" in data -> data.replace("ftp7.circleftp.net", "15.1.4.8")
            "ftp8.circleftp.net" in data -> data.replace("ftp8.circleftp.net", "15.1.2.2")
            "ftp9.circleftp.net" in data -> data.replace("ftp9.circleftp.net", "15.1.2.12")
            "ftp10.circleftp.net" in data -> data.replace("ftp10.circleftp.net", "15.1.4.3")
            "ftp11.circleftp.net" in data -> data.replace("ftp11.circleftp.net", "15.1.2.6")
            "ftp12.circleftp.net" in data -> data.replace("ftp12.circleftp.net", "15.1.2.10")
            "ftp13.circleftp.net" in data -> data.replace("ftp13.circleftp.net", "15.1.1.18")
            "ftp15.circleftp.net" in data -> data.replace("ftp15.circleftp.net", "15.1.4.12")
            "ftp16.circleftp.net" in data -> data.replace("ftp16.circleftp.net", "15.1.5.22")
            "ftp17.circleftp.net" in data -> data.replace("ftp17.circleftp.net", "15.1.5.30")
            else -> data
        }
    }

    private fun selectUntilNonInt(string: String?): Int? {
        return string?.let { Regex("^.*?(?=\\D|$)").find(it)?.value?.toIntOrNull() }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercase = check?.lowercase() ?: return null
        return when {
            lowercase.contains("webrip") || lowercase.contains("web-dl") || lowercase.contains("web") -> SearchQuality.WebRip
            lowercase.contains("bluray") || lowercase.contains("bdrip") || lowercase.contains("brrip") -> SearchQuality.BlueRay
            lowercase.contains("hdts") || lowercase.contains("hdcam") || lowercase.contains("hdtc") -> SearchQuality.HdCam
            lowercase.contains("dvd") -> SearchQuality.DVD
            lowercase.contains("cam") -> SearchQuality.Cam
            lowercase.contains("camrip") || lowercase.contains("rip") -> SearchQuality.CamRip
            lowercase.contains("hdrip") || lowercase.contains("1080p") || lowercase.contains("720p") || lowercase.contains("hdtv") -> SearchQuality.HD
            lowercase.contains("telesync") -> SearchQuality.Telesync
            lowercase.contains("telecine") -> SearchQuality.Telecine
            else -> null
        }
    }

    data class PageData(
        val posts: List<Post>? = null
    )

    data class Post(
        val id: Int? = null,
        val type: String? = null,
        val imageSm: String? = null,
        val title: String? = null,
        val name: String? = null
    )

    data class Data(
        val type: String? = null,
        val imageSm: String? = null,
        val title: String? = null,
        val image: String? = null,
        val metaData: String? = null,
        val name: String? = null,
        val quality: String? = null,
        val year: String? = null,
        val watchTime: String? = null
    )

    data class TvSeries(
        val content: List<Content>? = null
    )

    data class Content(
        val episodes: List<EpisodeData>? = null,
        val seasonName: String? = null
    )

    data class EpisodeData(
        val link: String? = null,
        val title: String? = null
    )

    data class Movies(
        val content: String? = null
    )
}
