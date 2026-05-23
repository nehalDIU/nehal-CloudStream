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

        // Navigate from detail page to watch page if needed
        if (doc.selectFirst("a.ep-item") == null) {
            var watchUrl = doc.selectFirst("a.btn-play, a.btn-radius.btn-primary.btn-play")?.attr("href")
            if (watchUrl.isNullOrEmpty()) {
                watchUrl = doc.select("a[href*=-episode-]").firstOrNull {
                    !it.attr("title").equals("Home", ignoreCase = true) &&
                    !it.text().equals("Home", ignoreCase = true)
                }?.attr("href")
            }
            if (!watchUrl.isNullOrEmpty() && watchUrl.startsWith("http")) {
                doc = app.get(watchUrl).document
            }
        }

        val title = doc.selectFirst("h2.film-name a")?.text()
            ?: doc.selectFirst("h2.film-name")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown"

        val poster = doc.selectFirst(".anisc-poster img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".film-description .text")?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val genres = doc.select("a[href*=/genre/]").map { it.text() }.distinct()

        val episodes = mutableListOf<Episode>()
        doc.select("a.ep-item").forEach { ep ->
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
            if (hash.isNotEmpty()) {
                try {
                    val decodedUrl = base64DecodeArray(hash).toString(Charsets.UTF_8)
                    val realUrl = decodedUrl.replace("1anime.site/megaplay", "megaplay.buzz")

                    if (realUrl.startsWith("http")) {
                        loadExtractor(realUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e("Aniwatch", "Failed decoding or loading extractor: ${e.message}")
                }
            }
        }
        return true
    }
}
