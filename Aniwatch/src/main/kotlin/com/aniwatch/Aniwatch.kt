package com.aniwatch

import com.lagradost.api.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

class Aniwatch : MainAPI() {
    override var mainUrl = Aniwatch.mainUrl
    override var name = Aniwatch.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        val mainUrl = "https://aniwatch.co.at"
        var name = "Aniwatch"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/anime/" to "Recently Updated",
        "$mainUrl/top-airing/" to "Top Airing",
        "$mainUrl/most-popular-anime/" to "Most Popular",
        "$mainUrl/most-favorite-anime/" to "Most Favorite"
    )

    private fun searchResponseBuilder(res: Document): List<AnimeSearchResponse> {
        val results = mutableListOf<AnimeSearchResponse>()
        res.select(".flw-item, .item, .swiper-slide").forEach { item ->
            val name = item.selectFirst(".film-name a, a.dynamic-name")?.text()
                ?: item.selectFirst("a.film-poster-ahref")?.attr("title")
                ?: item.selectFirst("img")?.attr("alt")
                ?: return@forEach

            val url = item.selectFirst(".film-poster a, a.film-poster, a.film-poster-ahref")?.attr("href")
                ?: item.selectFirst(".film-name a")?.attr("href")
                ?: return@forEach

            val posterUrl = item.selectFirst("img")?.attr("data-src")
                ?: item.selectFirst("img")?.attr("src")

            val tickSub = item.selectFirst(".tick-sub, .tick-item.tick-sub")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            val tickDub = item.selectFirst(".tick-dub, .tick-item.tick-dub")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            results += newAnimeSearchResponse(name, url) {
                this.posterUrl = posterUrl
                addDubStatus(tickDub != null, tickSub != null, tickDub, tickSub)
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page > 1) {
            "$mainUrl/page/$page/?s=$query"
        } else {
            "$mainUrl/?s=$query"
        }
        val res = app.get(url).document
        return searchResponseBuilder(res).toNewSearchResponseList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (request.data == "$mainUrl/") {
            if (page == 1) {
                request.data
            } else {
                "$mainUrl/anime/page/$page/"
            }
        } else {
            if (page == 1) {
                request.data
            } else {
                "${request.data}page/$page/"
            }
        }
        val res = app.get(url).document
        val searchRes = searchResponseBuilder(res)
        return newHomePageResponse(request.name, searchRes, true)
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document

        // Check if we are on a watch page by looking for the "View detail" button.
        // If so, redirect to the detail page first to get the main series page.
        val isDetailsPage = url.contains("/anime/")
        val detailUrl = if (isDetailsPage) null else {
            doc.selectFirst("a:contains(View detail), a.btn-light[href*=/anime/]")?.attr("href")
                ?: doc.selectFirst(".block a[href*=/anime/]")?.attr("href")
                ?: doc.selectFirst("h2.film-name a[href*=/anime/]")?.attr("href")
        }

        var detailsDoc = doc
        if (!detailUrl.isNullOrEmpty() && detailUrl.contains("/anime/")) {
            detailsDoc = app.get(detailUrl).document
        }

        // Now find the "Watch now" or "Play" button on the details page to get the watch page.
        var watchDoc = detailsDoc
        if (isDetailsPage || detailsDoc.selectFirst("a.ep-item") == null) {
            var watchUrl = detailsDoc.selectFirst("a.btn-play, a.btn-radius.btn-primary.btn-play")?.attr("href")
            if (watchUrl.isNullOrEmpty()) {
                watchUrl = detailsDoc.select("a[href*=-episode-]").firstOrNull {
                    !it.attr("title").equals("Home", ignoreCase = true) &&
                    !it.text().equals("Home", ignoreCase = true)
                }?.attr("href")
            }
            if (!watchUrl.isNullOrEmpty() && watchUrl.startsWith("http")) {
                watchDoc = app.get(watchUrl).document
            }
        }

        val title = detailsDoc.selectFirst("h2.film-name")?.text()
            ?: detailsDoc.selectFirst("h2.film-name a")?.text()
            ?: watchDoc.selectFirst("h2.film-name a")?.text()
            ?: watchDoc.selectFirst("h2.film-name")?.text()
            ?: detailsDoc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: watchDoc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown"

        val poster = detailsDoc.selectFirst(".anisc-poster img")?.attr("src")
            ?: detailsDoc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: watchDoc.selectFirst(".anisc-poster img")?.attr("src")
            ?: watchDoc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = detailsDoc.selectFirst(".film-description .text")?.text()
            ?: detailsDoc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: watchDoc.selectFirst(".film-description .text")?.text()
            ?: watchDoc.selectFirst("meta[property=og:description]")?.attr("content")

        // Parse genres from detail/info containers
        val genres = detailsDoc.select(".item-list a[href*=/genre/], .item a[href*=/genre/]")
            .map { it.text() }.distinct()
            .ifEmpty {
                watchDoc.select(".item-list a[href*=/genre/], .item a[href*=/genre/]")
                    .map { it.text() }.distinct()
            }

        val episodes = mutableListOf<Episode>()
        val epElements = watchDoc.select(".ss-list .ep-item, #episodes-content .ep-item")
            .ifEmpty { watchDoc.select(".ssl-item.ep-item") }
            .ifEmpty { watchDoc.select("a.ep-item") }

        epElements.forEach { ep ->
            val epNum = ep.attr("data-number").toIntOrNull()
            val epName = ep.selectFirst(".ep-name")?.text() ?: ep.attr("title")
            val epUrl = ep.attr("href")

            if (epUrl.isNotEmpty()) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val servers = doc.select(".server-item")

        servers.forEach { server ->
            val hash = server.attr("data-hash")
            val serverName = server.attr("data-server-name").ifEmpty { server.text().trim() }
            val dataType = server.attr("data-type") // "sub" or "dub"
            val typeLabel = if (dataType.equals("dub", ignoreCase = true)) "Dub" else "Sub"

            if (hash.isNotEmpty()) {
                try {
                    val decodedUrl = base64DecodeArray(hash).toString(Charsets.UTF_8)
                    
                    if (decodedUrl.contains("my.1anime.site") || decodedUrl.contains("index.php?action=play")) {
                        // Handle HD-1 / direct video player
                        val responseDoc = app.get(decodedUrl).document
                        val source = responseDoc.selectFirst("video source")?.attr("src")
                        if (!source.isNullOrEmpty()) {
                            val videoUrl = if (source.startsWith("http")) source else {
                                "https://my.1anime.site/" + source.trimStart('/')
                            }
                            val displayName = "$serverName $typeLabel"
                            callback(
                                newExtractorLink(
                                    source = displayName,
                                    name = displayName,
                                    url = videoUrl
                                ) {
                                    this.headers = mapOf("Referer" to "https://my.1anime.site/")
                                }
                            )
                        }
                    } else {
                        val realUrl = decodedUrl.replace("1anime.site/megaplay", "megaplay.buzz")
                        if (realUrl.startsWith("http")) {
                            loadExtractor(realUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Aniwatch", "Failed decoding or loading extractor: ${e.message}")
                }
            }
        }
        return true
    }
}
