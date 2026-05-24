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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    private fun cleanTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("""(?:season|ss)\s*\d+"""), "")
            .replace(Regex("""\d+(?:st|nd|rd|th)\s*season"""), "")
            .replace(Regex("""\s*\(dub\)"""), "")
            .replace(Regex("""\s*\(sub\)"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseSeason(title: String): Int {
        val lowercaseTitle = title.lowercase()
        val regexes = listOf(
            Regex("""(?:season|ss)\s*(\d+)"""),
            Regex("""(\d+)(?:st|nd|rd|th)\s*season""")
        )
        for (regex in regexes) {
            val match = regex.find(lowercaseTitle)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) return num
            }
        }
        return 1
    }

    private suspend fun getWatchDoc(url: String, initialDoc: Document? = null): Document {
        val doc = initialDoc ?: app.get(url).document
        val detailUrl = doc.selectFirst("a:contains(View detail), a.btn-light[href*=/anime/]")?.attr("href")
            ?: doc.selectFirst(".block a[href*=/anime/]")?.attr("href")
            ?: doc.selectFirst("h2.film-name a[href*=/anime/]")?.attr("href")

        var detailsDoc = doc
        if (!detailUrl.isNullOrEmpty() && detailUrl.contains("/anime/")) {
            detailsDoc = app.get(detailUrl).document
        }

        var watchDoc = detailsDoc
        if (detailsDoc.selectFirst("a.ep-item") == null) {
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
        return watchDoc
    }

    private fun parseEpisodesFromDoc(watchDoc: Document, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        watchDoc.select("a.ep-item").forEach { ep ->
            val epNum = ep.attr("data-number").toIntOrNull()
            val epName = ep.selectFirst(".ep-name")?.text() ?: ep.attr("title")
            val epUrl = ep.attr("href")

            if (epUrl.isNotEmpty()) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }
        }
        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val initialDoc = app.get(url).document
        val watchDoc = getWatchDoc(url, initialDoc)

        val detailUrl = initialDoc.selectFirst("a:contains(View detail), a.btn-light[href*=/anime/]")?.attr("href")
            ?: initialDoc.selectFirst(".block a[href*=/anime/]")?.attr("href")
            ?: initialDoc.selectFirst("h2.film-name a[href*=/anime/]")?.attr("href")

        val detailsDoc = if (!detailUrl.isNullOrEmpty() && detailUrl.contains("/anime/")) {
            app.get(detailUrl).document
        } else {
            initialDoc
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

        val genres = detailsDoc.select(".item-list a[href*=/genre/], .item a[href*=/genre/]")
            .map { it.text() }.distinct()
            .ifEmpty {
                watchDoc.select(".item-list a[href*=/genre/], .item a[href*=/genre/]")
                    .map { it.text() }.distinct()
            }

        val targetClean = cleanTitle(title)
        val currentSeason = parseSeason(title)
        val currentEpisodes = parseEpisodesFromDoc(watchDoc, currentSeason)

        val allEpisodes = mutableListOf<Episode>()

        try {
            val searchUrl = "$mainUrl/?s=$targetClean"
            val searchDoc = app.get(searchUrl).document
            val matchedSeasons = mutableListOf<Pair<Int, String>>()

            searchDoc.select(".flw-item, .item, .swiper-slide").forEach { item ->
                val name = item.selectFirst(".film-name a, a.dynamic-name")?.text()
                    ?: item.selectFirst("a.film-poster-ahref")?.attr("title")
                    ?: item.selectFirst("img")?.attr("alt")
                    ?: return@forEach

                val sUrl = item.selectFirst(".film-poster a, a.film-poster, a.film-poster-ahref")?.attr("href")
                    ?: item.selectFirst(".film-name a")?.attr("href")
                    ?: return@forEach

                if (cleanTitle(name) == targetClean) {
                    val seasonNum = parseSeason(name)
                    matchedSeasons.add(Pair(seasonNum, sUrl))
                }
            }

            if (matchedSeasons.isNotEmpty()) {
                coroutineScope {
                    val deferreds = matchedSeasons.distinctBy { it.first }.map { (seasonNum, sUrl) ->
                        async {
                            try {
                                if (seasonNum == currentSeason) {
                                    currentEpisodes
                                } else {
                                    val sWatchDoc = getWatchDoc(sUrl)
                                    parseEpisodesFromDoc(sWatchDoc, seasonNum)
                                }
                            } catch (e: Exception) {
                                Log.e("Aniwatch", "Failed to fetch episodes for season $seasonNum: ${e.message}")
                                emptyList()
                            }
                        }
                    }
                    deferreds.awaitAll().forEach { allEpisodes.addAll(it) }
                }
            } else {
                allEpisodes.addAll(currentEpisodes)
            }
        } catch (e: Exception) {
            Log.e("Aniwatch", "Failed to search/load other seasons: ${e.message}")
            allEpisodes.addAll(currentEpisodes)
        }

        if (allEpisodes.isEmpty()) {
            allEpisodes.addAll(currentEpisodes)
        }

        if (allEpisodes.isEmpty()) {
            allEpisodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                    this.season = currentSeason
                }
            )
        }

        allEpisodes.sortWith(compareBy({ it.season }, { it.episode }))

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, allEpisodes)
            addEpisodes(DubStatus.Dubbed, allEpisodes)
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
            val type = server.attr("data-type")
            val serverName = server.attr("data-server-name")
            val isDub = type.equals("dub", ignoreCase = true)

            if (hash.isNotEmpty()) {
                try {
                    val decodedUrl = base64DecodeArray(hash).toString(Charsets.UTF_8)
                    val realUrl = decodedUrl.replace("1anime.site/megaplay", "megaplay.buzz")

                    if (realUrl.startsWith("http")) {
                        if (realUrl.contains("my.1anime.site")) {
                            val fileName = realUrl.substringAfter("file=", "").substringBefore("&")
                            if (fileName.isNotEmpty()) {
                                val directUrl = "https://my.1anime.site/videos/$fileName"
                                val displayName = if (isDub) "${if (serverName.isNotEmpty()) serverName else "HD-1"} Dub" else "${if (serverName.isNotEmpty()) serverName else "HD-1"} Sub"
                                callback(
                                    newExtractorLink(
                                        source = if (serverName.isNotEmpty()) serverName else "HD-1",
                                        name = displayName,
                                        url = directUrl
                                    ) {
                                        this.referer = "https://my.1anime.site/"
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                            }
                        } else {
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
