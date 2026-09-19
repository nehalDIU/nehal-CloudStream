package com.nehal.mojaloss

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

@JsonIgnoreProperties(ignoreUnknown = true)
data class MojaLossMediaData(
    @JsonProperty("postId") val postId: String? = null,
    @JsonProperty("url") val url: String = "",
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchApiResponse(
    @JsonProperty("results") val results: List<SearchItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("display_title") val displayTitle: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("network") val network: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("display_year") val displayYear: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("tmdb_rating") val tmdbRating: Double? = null,
    @JsonProperty("poster") val poster: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TodayResponse(
    @JsonProperty("posts") val posts: List<TodayPost> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TodayPost(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("season_episode") val seasonEpisode: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CastResponse(
    @JsonProperty("media_url") val mediaUrl: String? = null,
    @JsonProperty("content_type") val contentType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("token_id") val tokenId: String? = null,
    @JsonProperty("cast_session_id") val castSessionId: String? = null,
    @JsonProperty("duration") val duration: Any? = null,
    @JsonProperty("tracks") val tracks: List<CastTrack>? = null,
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("message") val message: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CastTrack(
    @JsonProperty("content_id") val contentId: String? = null,
    @JsonProperty("content_type") val contentType: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("language") val language: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlyrTvConfig(
    @JsonProperty("baseUrl") val baseUrl: String? = null,
    @JsonProperty("base_url") val base_url: String? = null,
    @JsonProperty("showFolder") val showFolder: String? = null,
    @JsonProperty("show_name") val showName: String? = null,
    @JsonProperty("filenameBase") val filenameBase: String? = null,
    @JsonProperty("filename_pattern") val filenamePattern: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("includeYear") val includeYear: Boolean? = null,
    @JsonProperty("mediaToken") val mediaToken: String? = null,
    @JsonProperty("seasons") val seasons: List<PlyrSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlyrSeason(
    @JsonProperty("num") val num: Int? = null,
    @JsonProperty("folder") val folder: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("episode_files") val episodeFiles: Map<String, String>? = null,
    @JsonProperty("dash_manifests") val dashManifests: Map<String, String>? = null,
    @JsonProperty("subtitle_files") val subtitleFiles: Map<String, String>? = null,
    @JsonProperty("has_subs") val hasSubs: Boolean? = null
)

class MojaLossProvider : MainAPI() {
    override var mainUrl = "https://www.mojaloss.stream"
    override var name = "MojaLoss"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun parseDuration(text: String): Int? {
            var total = 0
            val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val mins = Regex("(\\d+)\\s*m").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (hours != null) total += hours * 60
            if (mins != null) total += mins
            return if (total > 0) total else null
        }

        fun extractPosterFromStyle(style: String?): String? {
            if (style == null) return null
            return Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.getOrNull(1)
        }
    }

    override val mainPage = mainPageOf(
        "wp-json/mojaloss/v1/today" to "Today's Updates",
        "page/%d/" to "Recent Releases",
        "category/movies/page/%d/" to "English Movies",
        "category/hindi/page/%d/" to "Hindi Movies",
        "category/bengali/page/%d/" to "Bengali Movies & Shows",
        "category/tv-shows/english-tv-shows/page/%d/" to "English TV Shows",
        "category/tv-shows/hindi-tv-shows/page/%d/" to "Hindi TV Shows",
        "category/tv-shows/korean-tv-shows/page/%d/" to "Korean TV Shows",
        "category/international-movies/page/%d/" to "International Movies",
        "4k/page/%d/" to "4K Movies",
        "trending/page/%d/" to "Trending"
    )

    private fun getRequestHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )
        val cookie = MojaLossStorage.getCookie()
        if (!cookie.isNullOrEmpty()) {
            headers["Cookie"] = cookie
        }
        return headers
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.data

        if (path == "wp-json/mojaloss/v1/today") {
            if (page > 1) return null
            val url = "$mainUrl/$path"
            val todayRes = try {
                app.get(url, headers = getRequestHeaders(), timeout = 30L, cacheTime = 60).parsedSafe<TodayResponse>()
            } catch (e: Exception) {
                Log.e("MojaLoss", "Error fetching today posts: ${e.message}")
                null
            } ?: return null

            val items = todayRes.posts.mapNotNull { post ->
                val link = post.link ?: (post.slug?.let { "$mainUrl/$it/" }) ?: return@mapNotNull null
                val title = post.title ?: return@mapNotNull null
                val poster = fixUrlNull(post.poster)
                val isTv = post.type.equals("tv", ignoreCase = true) || !post.seasonEpisode.isNullOrEmpty()

                if (isTv) {
                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }

            return newHomePageResponse(request.name, items, hasNext = false)
        }

        val targetUrl = if (path.contains("%d")) {
            if (page <= 1) {
                val cleanPath = path.replace("page/%d/", "").trimEnd('/')
                if (cleanPath.isEmpty()) "$mainUrl/page/1/" else "$mainUrl/$cleanPath/"
            } else {
                "$mainUrl/${path.format(page)}"
            }
        } else {
            if (page <= 1) "$mainUrl/$path" else "$mainUrl/$path/page/$page/"
        }

        val doc = try {
            app.get(targetUrl, headers = getRequestHeaders(), timeout = 30L, cacheTime = 60).document
        } catch (e: Exception) {
            Log.e("MojaLoss", "Error fetching main page ($targetUrl): ${e.message}")
            return null
        }

        val cards = doc.select("a.mj-fp-card, a.movie-card, div.movie-card > a")
        val items = cards.mapNotNull { it.toSearchResponse() }

        val hasNext = doc.selectFirst("a[rel=next], a.mj-archive-page--nav:contains(Next), a[href*='/page/${page + 1}/']") != null ||
                items.size >= 16

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        // Primary: REST API search
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val apiUrl = "$mainUrl/wp-json/mojaloss/v1/search?q=$encoded"
            val apiRes = app.get(apiUrl, headers = getRequestHeaders(), timeout = 25L).parsedSafe<SearchApiResponse>()

            val apiItems = apiRes?.results
            if (!apiItems.isNullOrEmpty()) {
                return apiItems.mapNotNull { item ->
                    val slug = item.slug ?: return@mapNotNull null
                    val href = "$mainUrl/$slug/"
                    val title = item.displayTitle?.ifBlank { null } ?: item.title ?: return@mapNotNull null
                    val poster = fixUrlNull(item.poster?.replace(Regex("-\\d+x\\d+\\."), "."))
                    val year = item.year?.take(4)?.toIntOrNull()
                    val isTv = item.type.equals("tv", ignoreCase = true)

                    if (isTv) {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MojaLoss", "REST API search failed: ${e.message}")
        }

        // Fallback: HTML search
        return try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val htmlUrl = "$mainUrl/?s=$encoded"
            val doc = app.get(htmlUrl, headers = getRequestHeaders(), timeout = 25L).document
            val cards = doc.select("a.mj-fp-card, a.movie-card")
            cards.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e("MojaLoss", "HTML search fallback failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (href == mainUrl || href.endsWith("/pro/") || href.endsWith("/trial/")) return null

        val title = selectFirst(".mj-fp-card-title, .movie-title")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val year = selectFirst(".mj-fp-card-year, .movie-year")?.text()?.trim()?.toIntOrNull()

        val badge = selectFirst(".mj-fp-card-badge, .movie-card-badge")?.text()?.trim() ?: ""
        val isTv = badge.contains("TV", ignoreCase = true) ||
                badge.contains("Series", ignoreCase = true) ||
                href.contains("/tv-shows/") ||
                hasAttr("data-episodes")

        val artStyle = selectFirst(".mj-fp-card-art, .movie-card-art")?.attr("style")
        val poster = fixUrlNull(extractPosterFromStyle(artStyle))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))
            ?: fixUrlNull(selectFirst("img")?.attr("data-src"))

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val headers = getRequestHeaders()
        val doc = app.get(url, headers = headers, timeout = 30L).document

        val title = doc.selectFirst("h1.mj-hero-title, h1.post_title, h1")?.ownText()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.replace(Regex("\\|.*"), "")?.trim()
            ?: throw ErrorLoadingException("Failed to load title")

        val year = doc.selectFirst(".mj-hero-title-meta")?.text()?.replace(Regex("[^0-9]"), "")?.take(4)?.toIntOrNull()
            ?: Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val plot = doc.selectFirst("p.mj-hero-overview, .post_text_area p, .overview")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(extractPosterFromStyle(doc.selectFirst(".hero-bg")?.attr("style")))
            ?: fixUrlNull(doc.selectFirst("img.poster")?.attr("src"))

        val backdrop = fixUrlNull(extractPosterFromStyle(doc.selectFirst(".hero-bg")?.attr("style")))

        val tags = doc.select(".mj-genre-chip, .post_tags a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val rating = doc.selectFirst(".mj-rating-value")?.text()?.trim()

        val duration = doc.selectFirst(".mj-hero-title-runtime")?.text()?.trim()?.let { parseDuration(it) }

        // Extract postId: e.g. from body class "postid-249917" or favorite buttons data-postid
        val bodyClass = doc.selectFirst("body")?.className() ?: ""
        val postId = Regex("postid-(\\d+)").find(bodyClass)?.groupValues?.getOrNull(1)
            ?: doc.selectFirst("[data-postid]")?.attr("data-postid")
            ?: doc.selectFirst("[data-post-id]")?.attr("data-post-id")

        val isTv = doc.selectFirst("#mj-tv-season-tabs, .mj-tv-season-tab, body.mj-tv-redesign, script#plyr-tv-scanned") != null ||
                doc.selectFirst(".mj-hero-title-runtime")?.text()?.contains("Season", ignoreCase = true) == true ||
                doc.selectFirst("meta[property='og:type']")?.attr("content")?.contains("tv", ignoreCase = true) == true

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val tabs = doc.select(".mj-tv-season-tab[data-season]")

            if (tabs.isNotEmpty()) {
                tabs.forEach { tab ->
                    val seasonNum = tab.attr("data-season").toIntOrNull() ?: 1
                    val epCount = tab.attr("data-episodes").toIntOrNull() ?: 1
                    for (ep in 1..epCount) {
                        val mediaData = MojaLossMediaData(
                            postId = postId,
                            url = url,
                            isTv = true,
                            season = seasonNum,
                            episode = ep
                        )
                        episodes.add(
                            newEpisode(mediaData.toJson()) {
                                this.name = "Season $seasonNum – Episode $ep"
                                this.season = seasonNum
                                this.episode = ep
                            }
                        )
                    }
                }
            } else {
                val mediaData = MojaLossMediaData(
                    postId = postId,
                    url = url,
                    isTv = true,
                    season = 1,
                    episode = 1
                )
                episodes.add(
                    newEpisode(mediaData.toJson()) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                if (!rating.isNullOrBlank()) this.score = Score.from10(rating)
            }
        } else {
            val mediaData = MojaLossMediaData(
                postId = postId,
                url = url,
                isTv = false
            )

            return newMovieLoadResponse(title, url, TvType.Movie, mediaData.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                if (!rating.isNullOrBlank()) this.score = Score.from10(rating)
                this.duration = duration
            }
        }
    }

    private fun isMediaStream(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.trim().lowercase().substringBefore("?").substringBefore("#")
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".m3u8") ||
                clean.endsWith(".mpd") || clean.endsWith(".webm") || clean.endsWith(".ts")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mediaData = try {
            AppUtils.parseJson<MojaLossMediaData>(data)
        } catch (e: Exception) {
            MojaLossMediaData(url = data)
        }

        val cookie = MojaLossStorage.getOrRefreshCookie()
        if (cookie.isNullOrEmpty()) {
            throw ErrorLoadingException(
                "MojaLoss requires a session cookie to stream. Please open Plugin Settings and configure your MojaLoss Session Cookie."
            )
        }

        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT,
            "Cookie" to cookie
        )

        var foundLinks = false
        val postId = mediaData.postId

        // 1. Try MojaLoss Cast API (REST stream resolver)
        if (!postId.isNullOrEmpty()) {
            val qualities = listOf("default", "4k")
            for (q in qualities) {
                try {
                    val castEndpoint = if (mediaData.isTv) {
                        "$mainUrl/wp-json/mojaloss/v1/cast/tv"
                    } else {
                        "$mainUrl/wp-json/mojaloss/v1/cast/movie"
                    }

                    val postMap = if (mediaData.isTv) {
                        mapOf(
                            "post_id" to postId,
                            "season" to (mediaData.season ?: 1),
                            "episode" to (mediaData.episode ?: 1),
                            "quality" to q
                        )
                    } else {
                        mapOf(
                            "post_id" to postId,
                            "quality" to q
                        )
                    }

                    val jsonBody = postMap.toJson()
                    val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                    val res = app.post(
                        castEndpoint,
                        headers = headers + mapOf("Content-Type" to "application/json"),
                        requestBody = requestBody,
                        timeout = 25L
                    ).parsedSafe<CastResponse>()

                    val streamUrl = res?.mediaUrl
                    if (!streamUrl.isNullOrEmpty() && (isMediaStream(streamUrl) || streamUrl.startsWith("http"))) {
                        val fullStreamUrl = fixUrl(streamUrl)
                        val is4k = q == "4k" || fullStreamUrl.contains("4k", ignoreCase = true)
                        val isM3u8 = fullStreamUrl.contains(".m3u8", ignoreCase = true) ||
                                res.contentType?.contains("mpegurl", ignoreCase = true) == true
                        val isMpd = fullStreamUrl.contains(".mpd", ignoreCase = true) ||
                                res.contentType?.contains("dash", ignoreCase = true) == true

                        val linkType = when {
                            isM3u8 -> ExtractorLinkType.M3U8
                            isMpd -> ExtractorLinkType.DASH
                            else -> ExtractorLinkType.VIDEO
                        }

                        callback(
                            newExtractorLink(
                                name = this.name,
                                source = if (is4k) "$name 4K" else "$name HD",
                                url = fullStreamUrl,
                                type = linkType
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = if (is4k) Qualities.P2160.value else Qualities.P1080.value
                                this.headers = headers
                            }
                        )
                        foundLinks = true

                        res.tracks?.forEach { track ->
                            val trackUrl = track.contentId
                            if (!trackUrl.isNullOrEmpty()) {
                                subtitleCallback(
                                    SubtitleFile(
                                        lang = track.language ?: track.name ?: "en",
                                        url = fixUrl(trackUrl)
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MojaLoss", "Cast API error for quality $q: ${e.message}")
                }
            }
        }

        // 2. Scrape page HTML for TV plyr config or video player sources
        if (mediaData.url.isNotEmpty()) {
            try {
                val doc = app.get(mediaData.url, headers = headers, timeout = 30L).document

                // Check for Plyr TV script config
                val configScript = doc.selectFirst("script#plyr-tv-scanned, script#plyr-config, script#plyr-tv-config")
                val scriptData = configScript?.data() ?: configScript?.html()
                if (!scriptData.isNullOrBlank()) {
                    try {
                        val plyrConfig = AppUtils.parseJson<PlyrTvConfig>(scriptData)
                        val rawBaseUrl = plyrConfig.baseUrl ?: plyrConfig.base_url
                        if (!rawBaseUrl.isNullOrBlank()) {
                            var baseUrl = rawBaseUrl.trimEnd('/')
                            var showFolder = plyrConfig.showFolder ?: plyrConfig.showName
                            if (showFolder.isNullOrBlank()) {
                                val parts = baseUrl.split('/')
                                showFolder = URLDecoder.decode(parts.last(), "UTF-8")
                                baseUrl = parts.dropLast(1).joinToString("/")
                            }
                            val seasonNum = mediaData.season ?: 1
                            val epNum = mediaData.episode ?: 1
                            val season = plyrConfig.seasons?.firstOrNull { it.num == seasonNum }

                            val seasonNumPadded = String.format("%02d", seasonNum)
                            val epNumPadded = String.format("%02d", epNum)
                            val showFolderEncoded = URLEncoder.encode(showFolder, "UTF-8").replace("+", "%20")
                            val seasonFolder = season?.folder?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                                ?: "Season%20$seasonNum"

                            val actualFilename = season?.episodeFiles?.get(epNum.toString())
                            val videoUrl = if (!actualFilename.isNullOrBlank()) {
                                val encodedFile = URLEncoder.encode(actualFilename, "UTF-8").replace("+", "%20")
                                "$baseUrl/$showFolderEncoded/$seasonFolder/$encodedFile"
                            } else {
                                var baseName = plyrConfig.filenameBase ?: showFolder.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").replace(" ", ".")
                                if (plyrConfig.includeYear == true && !plyrConfig.year.isNullOrBlank()) {
                                    baseName += ".${plyrConfig.year}"
                                }
                                "$baseUrl/$showFolderEncoded/$seasonFolder/$baseName.S${seasonNumPadded}E${epNumPadded}.mp4"
                            }

                            callback(
                                newExtractorLink(
                                    name = this.name,
                                    source = "$name Episode",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.P1080.value
                                    this.headers = headers
                                }
                            )
                            foundLinks = true

                            // DASH manifest if present
                            val dashPath = season?.dashManifests?.get(epNum.toString())
                            if (!dashPath.isNullOrBlank()) {
                                val manifestUrl = "$baseUrl/$showFolderEncoded/$seasonFolder/" + dashPath.split("/").joinToString("/") {
                                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                                }
                                callback(
                                    newExtractorLink(
                                        name = this.name,
                                        source = "$name DASH",
                                        url = manifestUrl,
                                        type = ExtractorLinkType.DASH
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.quality = Qualities.P1080.value
                                        this.headers = headers
                                    }
                                )
                            }

                            // Subtitles if present
                            val subFile = season?.subtitleFiles?.get(epNum.toString())
                            if (!subFile.isNullOrBlank()) {
                                val subUrl = "$baseUrl/$showFolderEncoded/$seasonFolder/Subs/" + URLEncoder.encode(subFile, "UTF-8").replace("+", "%20")
                                subtitleCallback(SubtitleFile("en", subUrl))
                            } else if (season?.hasSubs == true) {
                                val defaultSub = "$baseUrl/$showFolderEncoded/$seasonFolder/Subs/$showFolder.S${seasonNumPadded}E${epNumPadded}.vtt"
                                subtitleCallback(SubtitleFile("en", defaultSub))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MojaLoss", "Error parsing Plyr config: ${e.message}")
                    }
                }

                // Video player elements (#movie-video, #plyr-video, video)
                val videoEls = doc.select("#movie-video, #plyr-video, video")
                for (videoEl in videoEls) {
                    val defaultSrc = videoEl.attr("data-default-src").ifBlank { null }
                        ?: videoEl.attr("src").ifBlank { null }
                    val fourkSrc = videoEl.attr("data-4k-src").ifBlank { null }

                    if (!defaultSrc.isNullOrEmpty() && isMediaStream(defaultSrc)) {
                        val fullUrl = fixUrl(defaultSrc)
                        val isM3u8 = fullUrl.contains(".m3u8", ignoreCase = true)
                        val isMpd = fullUrl.contains(".mpd", ignoreCase = true)
                        callback(
                            newExtractorLink(
                                name = this.name,
                                source = "$name 1080p",
                                url = fullUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else if (isMpd) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                                this.headers = headers
                            }
                        )
                        foundLinks = true
                    }

                    if (!fourkSrc.isNullOrEmpty() && isMediaStream(fourkSrc)) {
                        val fullUrl = fixUrl(fourkSrc)
                        callback(
                            newExtractorLink(
                                name = this.name,
                                source = "$name 4K UHD",
                                url = fullUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P2160.value
                                this.headers = headers
                            }
                        )
                        foundLinks = true
                    }

                    // Captions & Subtitles
                    videoEl.select("track[src]").forEach { track ->
                        val src = fixUrlNull(track.attr("src")) ?: return@forEach
                        val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "en" }
                        subtitleCallback(SubtitleFile(lang, src))
                    }
                }

                // Explicit media links ONLY (must end with media extensions, NEVER match navigation pages like /directlink/)
                doc.select("a[href]").forEach { dl ->
                    val dlHref = dl.attr("href")
                    if (isMediaStream(dlHref)) {
                        val fullUrl = fixUrl(dlHref)
                        callback(
                            newExtractorLink(
                                name = this.name,
                                source = "$name Media",
                                url = fullUrl,
                                type = if (fullUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                                this.headers = headers
                            }
                        )
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                Log.e("MojaLoss", "Error scraping page for links: ${e.message}")
            }
        }

        if (!foundLinks) {
            throw ErrorLoadingException(
                "No stream link found. Your MojaLoss session cookie may have expired or this title requires an active subscription."
            )
        }

        return foundLinks
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("Referer", "$mainUrl/")
                .header("User-Agent", USER_AGENT)
            val cookie = MojaLossStorage.getCookie()
            if (!cookie.isNullOrEmpty()) {
                builder.header("Cookie", cookie)
            }
            chain.proceed(builder.build())
        }
    }
}
