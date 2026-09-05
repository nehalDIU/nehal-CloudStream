package com.nehal.circleftpold

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
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
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
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
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
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

        val doc = app.get(url, verify = false, cacheTime = 60).document
        val articles = doc.select("article.category-listing")
        val items = articles.mapNotNull { parseArticleToSearchResponse(it) }
        val hasNext = doc.selectFirst(".pagination .next, .nav-links .next, a.next") != null || items.size >= 80

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun parseArticleToSearchResponse(article: Element): SearchResponse? {
        val linkElement = article.selectFirst("a[href*='/cn/']") ?: article.selectFirst(".entry-title a") ?: article.selectFirst("a")
            ?: return null
        val href = linkElement.attr("href").trim()
        if (href.isBlank()) return null

        val title = article.selectFirst(".entry-title a")?.text()?.trim()
            ?: article.selectFirst(".entry-title")?.text()?.trim()
            ?: linkElement.text().trim()
        if (title.isBlank()) return null

        val img = article.selectFirst("img")
        val poster = img?.let {
            val dataSrc = it.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else it.attr("src").takeIf { src -> !src.startsWith("data:image") && src.isNotBlank() }
        }

        val isTv = href.contains("tv-series") ||
                title.contains("tv series", ignoreCase = true) ||
                title.contains("tv shows", ignoreCase = true) ||
                title.contains("tv serials", ignoreCase = true) ||
                article.hasClass("genre-tv-series")

        val quality = getSearchQuality(title)
        val isDubbed = title.contains("dubbed", ignoreCase = true) ||
                title.contains("dual audio", ignoreCase = true) ||
                title.contains("multi audio", ignoreCase = true)

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

    override suspend fun search(query: String): List<SearchResponse> {
        val json = try {
            app.get(
                "$mainApiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false,
                cacheTime = 60
            )
        } catch (_: Exception) {
            app.get(
                "$apiUrl/api/posts?searchTerm=$query&order=desc",
                verify = false,
                cacheTime = 60
            )
        }

        return try {
            AppUtils.parseJson<PageData>(json.text).posts.mapNotNull { post ->
                toSearchResult(post)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toSearchResult(post: Post): SearchResponse? {
        if (post.type == "singleVideo" || post.type == "series") {
            val title = post.title.ifBlank { post.name ?: "No Title" }
            val isTv = post.type == "series" || title.contains("tv series", ignoreCase = true)
            val loadUrl = "$mainApiUrl/api/posts/${post.id}"
            val posterUrl = "$mainApiUrl/uploads/${post.imageSm}"
            val quality = getSearchQuality(title)
            val isDubbed = title.contains("dubbed", ignoreCase = true) ||
                    title.contains("dual audio", ignoreCase = true) ||
                    title.contains("multi audio", ignoreCase = true)

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
        val doc = app.get(url, verify = false, cacheTime = 60).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.title().replace(" - Circle Network", "").replace("Circle Network |", "").trim()

        val poster = doc.selectFirst(".entry-thumb img, .entry-featured img, article img.wp-post-image, article img")?.let { img ->
            val dataSrc = img.attr("data-src")
            if (dataSrc.isNotBlank()) dataSrc else img.attr("src").takeIf { src -> !src.startsWith("data:image") && src.isNotBlank() }
        }

        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.value?.toIntOrNull()
        val plot = doc.selectFirst(".wpmoly.movie .overview.value")?.text()?.takeIf { it != "—" && it.isNotBlank() }
            ?: doc.selectFirst(".entry-content p")?.text()?.trim()

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
                    val href = linkEl.attr("href").trim()
                    if (href.isBlank()) continue
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
                    this.year = year
                    this.plot = plot
                }
            }
        }

        // Fallback or single video / movie
        val movieUrl = doc.selectFirst("video source[src]")?.attr("src")?.trim()
            ?: doc.selectFirst("video[src]")?.attr("src")?.trim()
            ?: doc.selectFirst("a.downloadbtn[href]")?.attr("href")?.trim()
            ?: doc.selectFirst("a[href*='.mkv'], a[href*='.mp4']")?.attr("href")?.trim()
            ?: extractVidSwap(doc.html())
            ?: url

        val duration = doc.selectFirst(".wpmoly.movie .runtime.value")?.text()?.takeIf { it != "—" }?.let {
            getDurationFromString(it)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, movieUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.duration = duration
        }
    }

    private suspend fun loadFromApi(url: String): LoadResponse {
        val json = try {
            app.get(url, verify = false, cacheTime = 60)
        } catch (_: Exception) {
            val fallbackUrl = url.replace(mainApiUrl, apiUrl)
            app.get(fallbackUrl, verify = false, cacheTime = 60)
        }

        val loadData = AppUtils.parseJson<Data>(json.text)
        val title = loadData.title
        val poster = "$mainApiUrl/uploads/${loadData.image}"
        val description = loadData.metaData
        val year = selectUntilNonInt(loadData.year)

        if (loadData.type == "singleVideo") {
            val movieUrl = json.parsed<Movies>().content ?: url
            val duration = getDurationFromString(loadData.watchTime)
            return newMovieLoadResponse(title, url, TvType.Movie, movieUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.duration = duration
            }
        } else {
            val tvData = json.parsed<TvSeries>()
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            tvData.content.forEach { season ->
                seasonNum++
                var episodeNum = 0
                season.episodes.forEach {
                    episodeNum++
                    episodesData.add(
                        newEpisode(it.link) {
                            this.episode = episodeNum
                            this.season = seasonNum
                            this.name = it.title
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = poster
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

        // Standard hostname stream
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data
            )
        )

        // Direct BDIX IP stream
        val ipLink = linkToIp(data)
        if (ipLink.isNotBlank() && ipLink != data) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} (Direct IP)",
                    url = ipLink
                )
            )
        }
        return true
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
        val posts: List<Post>
    )

    data class Post(
        val id: Int,
        val type: String,
        val imageSm: String?,
        val title: String,
        val name: String? = null
    )

    data class Data(
        val type: String,
        val imageSm: String?,
        val title: String,
        val image: String?,
        val metaData: String?,
        val name: String?,
        val quality: String?,
        val year: String?,
        val watchTime: String?
    )

    data class TvSeries(
        val content: List<Content>
    )

    data class Content(
        val episodes: List<EpisodeData>,
        val seasonName: String?
    )

    data class EpisodeData(
        val link: String,
        val title: String?
    )

    data class Movies(
        val content: String?
    )
}
