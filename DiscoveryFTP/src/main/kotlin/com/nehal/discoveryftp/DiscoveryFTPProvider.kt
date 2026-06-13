package com.nehal.discoveryftp

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

open class DiscoveryFTPProvider : MainAPI() {
    override var mainUrl = "https://movies.discoveryftp.net"
    override var name = "DiscoveryFTP"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override var lang = "bn"

    override val mainPage = mainPageOf(
        "m" to "Movies: Latest",
        "s" to "Series: Latest",
        "m/category/Bangla" to "Movies: Bangla",
        "m/category/English" to "Movies: English",
        "m/category/Hindi" to "Movies: Hindi",
        "s/category/Bangla" to "Series: Bangla",
        "s/category/Foreign" to "Series: Foreign / English",
        "s/category/Hindi" to "Series: Hindi"
    )

    private data class HomePageCacheEntry(
        val timestamp: Long,
        val items: List<SearchResponse>
    )

    private val homePageCache = java.util.concurrent.ConcurrentHashMap<String, HomePageCacheEntry>()
    private val cacheTtl = 5 * 60 * 1000L // 5 minutes cache TTL

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "${request.data}|$page"
        homePageCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cacheTtl) {
                return newHomePageResponse(request.name, cached.items, hasNext = cached.items.isNotEmpty())
            }
        }

        val url = when {
            request.data == "m" -> {
                if (page <= 1) "$mainUrl/m" else "$mainUrl/m/recent/${page - 1}"
            }
            request.data == "s" -> {
                if (page <= 1) "$mainUrl/s" else return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
            else -> {
                "$mainUrl/${request.data}/$page"
            }
        }

        val doc = app.get(url).document

        val items = if (request.data.startsWith("m/") || request.data == "m") {
            doc.select("div.card, div.moviegrid div.card").mapNotNull { card ->
                val linkEl = card.selectFirst("a") ?: return@mapNotNull null
                val href = linkEl.attr("href").trim()
                if (href.isEmpty() || !href.contains("/view/")) return@mapNotNull null

                val title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val cleanedTitle = cleanTitle(title)
                val posterUrl = card.selectFirst("img")?.attr("src")
                val year = card.selectFirst(".movie_details_span")?.text()?.trim()?.toIntOrNull()
                    ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

                newMovieSearchResponse(cleanedTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.year = year
                }
            }
        } else {
            doc.select("a[href*=\"/s/view/\"]").mapNotNull { linkEl ->
                val href = linkEl.attr("href").trim()
                if (href.isEmpty() || !href.contains("/view/")) return@mapNotNull null

                val fcard = linkEl.selectFirst(".fcard") ?: return@mapNotNull null
                val title = fcard.selectFirst(".ftitle")?.text()?.trim()
                    ?: fcard.selectFirst(".fdetails")?.text()?.trim()
                    ?: return@mapNotNull null
                val cleanedTitle = cleanTitle(title)
                val posterUrl = fcard.selectFirst("img")?.attr("src")
                val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

                newTvSeriesSearchResponse(cleanedTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.year = year
                }
            }
        }

        homePageCache[cacheKey] = HomePageCacheEntry(System.currentTimeMillis(), items)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private val nonAlphaNumericRegex = Regex("[^\\p{L}\\p{N}]")
    private val whitespaceRegex = Regex("\\s+")

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val moviesDeferred = async {
            try {
                val html = app.post(
                    "$mainUrl/search",
                    data = mapOf("term" to query, "types" to "m")
                ).text
                parseSearchAjax(html, TvType.Movie)
            } catch (e: Exception) {
                emptyList()
            }
        }

        val tvShowsDeferred = async {
            try {
                val html = app.post(
                    "$mainUrl/search",
                    data = mapOf("term" to query, "types" to "s")
                ).text
                parseSearchAjax(html, TvType.TvSeries)
            } catch (e: Exception) {
                emptyList()
            }
        }

        val results = (moviesDeferred.await() + tvShowsDeferred.await()).distinctBy { it.url }

        results.map { res ->
            Pair(res, getRelevanceScore(res.name, query))
        }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
    }

    private fun getRelevanceScore(title: String, query: String): Int {
        val cleanTitle = title.lowercase()
        val cleanQuery = query.lowercase()

        if (cleanTitle == cleanQuery) return 1000
        if (cleanTitle.startsWith(cleanQuery)) return 800
        
        val titleTokens = cleanTitle.replace(nonAlphaNumericRegex, " ").split(whitespaceRegex).filter { it.isNotBlank() }
        if (titleTokens.contains(cleanQuery)) return 600
        if (cleanTitle.contains(cleanQuery)) return 400

        val queryTokens = cleanQuery.replace(nonAlphaNumericRegex, " ").split(whitespaceRegex).filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return 0

        var matchedTokens = 0
        for (qToken in queryTokens) {
            if (titleTokens.any { it.contains(qToken) }) {
                matchedTokens++
            }
        }

        if (matchedTokens == 0) return 0
        return (matchedTokens.toDouble() / queryTokens.size * 200).toInt()
    }

    private fun parseSearchAjax(html: String, type: TvType): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select(".moviesearchiteam").mapNotNull { item ->
            val linkEl = item.selectFirst("a") ?: return@mapNotNull null
            val href = linkEl.attr("href").trim()
            if (href.isEmpty() || !href.contains("/view/")) return@mapNotNull null

            val title = item.selectFirst(".searchtitle")?.text()?.trim() ?: return@mapNotNull null
            val posterUrl = item.selectFirst("img")?.attr("src")
            val detailsText = item.select(".searchdetails").text()
            val year = Regex("\\b(19|20)\\d{2}\\b").find(detailsText)?.value?.toIntOrNull()
                ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(cleanTitle(title), fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.year = year
                }
            } else {
                newMovieSearchResponse(cleanTitle(title), fixUrl(href), TvType.Movie) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.year = year
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return if (url.contains("/s/view/")) {
            loadTvSeries(url)
        } else {
            loadMovie(url)
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(".movie-detail-content h3")?.text()?.trim()
            ?: doc.selectFirst("h3")?.text()?.trim()
            ?: throw ErrorLoadingException("Missing title")
        val poster = doc.selectFirst(".movie-detail-banner img")?.attr("src")
            ?: doc.selectFirst("figure img")?.attr("src")
        val plot = doc.selectFirst(".storyline")?.text()?.trim()
        val genres = doc.select(".ganre-wrapper a").map { it.text().replace(",", "").trim() }.filter { it.isNotEmpty() }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
            ?: doc.select(".badge-wrapper .badge").map { it.text() }.mapNotNull { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }.firstOrNull()

        return newMovieLoadResponse(cleanTitle(title), url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.plot = plot
            this.tags = genres
        }
    }

    private suspend fun loadTvSeries(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(".movie-detail-content-test h3")?.text()?.trim()
            ?: doc.selectFirst("h3")?.text()?.trim()
            ?: throw ErrorLoadingException("Missing title")
        val poster = doc.selectFirst(".movie-detail-banner img")?.attr("src")
        val plot = doc.selectFirst(".storyline")?.text()?.trim()
        val genres = doc.select(".ganre-wrapper a").map { it.text().replace(",", "").trim() }.filter { it.isNotEmpty() }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        val seasonElements = doc.select("div.my-custom-scrollbar a[href*=\"/s/view/\"], table a[href*=\"/s/view/\"]")
        val seasonUrls = seasonElements.map { fixUrl(it.attr("href")) }.distinct()

        val episodes = ArrayList<Episode>()
        if (seasonUrls.isNotEmpty()) {
            coroutineScope {
                val deferreds = seasonUrls.map { seasonUrl ->
                    async {
                        try {
                            val seasonNum = Regex("/s/view/\\d+/(\\d+)").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()
                            val seasonDoc = app.get(seasonUrl).document
                            parseEpisodes(seasonDoc, seasonNum)
                        } catch (e: Exception) {
                            emptyList<Episode>()
                        }
                    }
                }
                episodes.addAll(deferreds.awaitAll().flatten())
            }
        } else {
            val seasonNum = Regex("/s/view/\\d+/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
            episodes.addAll(parseEpisodes(doc, seasonNum))
        }

        val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))

        return newTvSeriesLoadResponse(cleanTitle(title), url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.plot = plot
            this.tags = genres
        }
    }

    private fun parseEpisodes(doc: Document, defaultSeason: Int?): List<Episode> {
        return doc.select("div.card:has(h5)").mapNotNull { card ->
            val h5Text = card.selectFirst("h5")?.text() ?: return@mapNotNull null
            val seasonNumber = Regex("S(\\d+)").find(h5Text)?.groupValues?.get(1)?.toIntOrNull() ?: defaultSeason
            val episodeNumber = Regex("EP\\s*(\\d+)").find(h5Text)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null

            val videoLink = card.selectFirst("h5 a[href]")?.attr("href") ?: return@mapNotNull null

            val epTitle = card.selectFirst("h4")?.ownText()?.trim()
                ?: card.selectFirst("h4")?.text()?.substringBefore("1080P")?.substringBefore("STREAM")?.trim()

            val overview = card.selectFirst(".season_overview p")?.text()?.trim()

            newEpisode(fixUrl(videoLink)) {
                this.name = epTitle
                this.season = seasonNumber
                this.episode = episodeNumber
                this.description = overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("cdn") && (data.contains(".mkv") || data.contains(".mp4") || data.contains(".m3u8"))) {
            val type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    name = "CDN Direct",
                    source = this.name,
                    url = data,
                    type = type
                )
            )
            return true
        }

        val doc = app.get(data).document

        val primaryLink = doc.selectFirst("a[href*=\"cdn\"].btn")?.attr("href")
            ?: doc.select("a.btn").firstOrNull { it.text().contains("Download", ignoreCase = true) }?.attr("href")

        if (!primaryLink.isNullOrBlank()) {
            val qualityLabel = doc.select(".badge-wrapper .badge").map { it.text() }
                .firstOrNull { it.contains("1080") || it.contains("720") || it.contains("4K") || it.contains("UHD") }
                ?: "Direct"

            val urlFixed = fixUrl(primaryLink)
            val type = if (urlFixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    name = "CDN - $qualityLabel",
                    source = this.name,
                    url = urlFixed,
                    type = type
                )
            )
        }

        val altPages = doc.select(".badge-wrapper a[href*=\"/view/\"]").map { it.attr("href") }
        altPages.forEach { altPath ->
            val altUrl = fixUrl(altPath)
            try {
                val altDoc = app.get(altUrl).document
                val altLink = altDoc.selectFirst("a[href*=\"cdn\"].btn")?.attr("href")
                    ?: altDoc.select("a.btn").firstOrNull { it.text().contains("Download", ignoreCase = true) }?.attr("href")

                if (!altLink.isNullOrBlank()) {
                    val altQualityLabel = altDoc.select(".badge-wrapper .badge").map { it.text() }
                        .firstOrNull { it.contains("1080") || it.contains("720") || it.contains("4K") || it.contains("UHD") }
                        ?: "Alt"

                    val altUrlFixed = fixUrl(altLink)
                    val type = if (altUrlFixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = "CDN - $altQualityLabel",
                            source = this.name,
                            url = altUrlFixed,
                            type = type
                        )
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> mainUrl.trimEnd('/') + "/" + url
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\(\\(\\d{4}\\)\\)"), "").replace(Regex("\\(\\d{4}\\)"), "").trim()
    }
}
