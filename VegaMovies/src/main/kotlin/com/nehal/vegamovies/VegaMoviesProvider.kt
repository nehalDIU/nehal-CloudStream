package com.nehal.vegamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URI

open class VegaMoviesProvider : MainAPI() {
    override var mainUrl = "https://vegamovies.mq"
    override var name = "VegaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://v3-cinemeta.strem.io/meta"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    init {
        runBlocking {
            basemainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("vegamovies")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney Plus Hotstar",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/mx-original/page/%d/" to "MX Original",
        "$mainUrl/category/anime-series/page/%d/" to "Anime Series",
        "$mainUrl/category/korean-series/page/%d/" to "Korean Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page)).document
        val home = document.select("div.movies-grid > a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val imgEl = this.selectFirst("img")
        val title = imgEl?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        var posterUrl = imgEl?.attr("src") ?: ""
        if (!posterUrl.contains("https:")) {
            posterUrl = imgEl?.attr("data-src") ?: posterUrl
        }

        return buildSearchResponse(title, href, posterUrl)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = app.get("$mainUrl/search.php?q=$query&page=$page").text
        val response = tryParseJson<VegaSearchResponse>(json) ?: return null
        val results = response.hits.map { hit ->
            val doc = hit.document
            buildSearchResponse(doc.post_title, doc.permalink, doc.post_thumbnail)
        }
        return newSearchResponseList(results)
    }

    private fun buildSearchResponse(
        rawTitle: String,
        url: String,
        posterUrl: String?
    ): SearchResponse {
        val cleanTitle = rawTitle.replace(Regex("(?i)^Download\\s+"), "").trim()

        // Extract release year (e.g. 2024 or (2024))
        val year = Regex("""\b(19|20)\d{2}\b""").find(cleanTitle)?.value?.toIntOrNull()

        // Determine TvType (Movie vs TvSeries) based on title keywords
        val isSeries = cleanTitle.contains(Regex("(?i)\\b(Season|Series|S\\d+|Episode|Episodes|Anime Series|Korean Series)\\b"))
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        // Extract Quality Label Badge (4K, 1080p, 720p, 480p, CAM)
        val qualityLabel = when {
            cleanTitle.contains("2160p", true) || cleanTitle.contains("4K", true) -> "4K"
            cleanTitle.contains("1080p", true) -> "1080p"
            cleanTitle.contains("720p", true) -> "720p"
            cleanTitle.contains("480p", true) -> "480p"
            cleanTitle.contains("CAM", true) || cleanTitle.contains("PreDVD", true) -> "CAM"
            else -> null
        }

        // Extract Audio / Dub / Sub Status Badges
        val isDualAudio = cleanTitle.contains("Dual Audio", true) ||
                          cleanTitle.contains("Multi Audio", true) ||
                          cleanTitle.contains("Hindi", true)
        val isSubbed = cleanTitle.contains("Sub", true) ||
                       cleanTitle.contains("ESub", true) ||
                       cleanTitle.contains("MSub", true)

        return newAnimeSearchResponse(cleanTitle, fixUrl(url), tvType) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.year = year
            if (qualityLabel != null) {
                addQuality(qualityLabel)
            }
            if (isDualAudio || isSubbed) {
                addDubStatus(dubExist = isDualAudio, subExist = isSubbed)
            }
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(fixUrl(url)).document
        var title = document.select("title").text().replace("Download ", "")
        var posterUrl = document.select("p > img").attr("src")
        val imdbUrl =  document.select("a[href*=\"imdb\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")

        val tvtype = if (
            document.selectFirst("h3:matches((?i)Series-SYNOPSIS/PLOT)") != null ||
            document.selectFirst("h3:matches((?i)Series Info)") != null ||
            document.selectFirst("h3:matches((?i)Series synopsis/PLOT)") != null
        ) {
            "series"
        } else {
            "movie"
        }

        var description = document
            .selectFirst("h3:has(span:matches((?i)SYNOPSIS/PLOT))")
            ?.nextElementSibling()
            ?.text()

        val jsonResponse = app.get("$cinemeta_url/$tvtype/$imdbId.json").text
        val responseData = tryParseJson<ResponseData>(jsonResponse)

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        if(responseData != null) {
            description = responseData.meta.description ?: description
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: title
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year ?: ""
            posterUrl = responseData.meta.poster ?: posterUrl
            background = responseData.meta.background ?: background
        }

        if (tvtype == "series") {
            val tvSeriesEpisodes = mutableListOf<Episode>()
            val seasonLinkMap = mutableMapOf<Int, MutableList<EpisodeLink>>()
            val allDownloadLinks = mutableListOf<EpisodeLink>()

            val headersAndPs = document.select("main > h3, main > h4, main > h5, main > p, main > div")
            var currentSeason = 1

            for (element in headersAndPs) {
                val text = element.text()
                if (text.contains("Zip", ignoreCase = true)) continue

                val sMatch = Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE).find(text)
                if (sMatch != null) {
                    currentSeason = sMatch.groupValues[1].toIntOrNull() ?: currentSeason
                }

                val links = element.select("a").mapNotNull { a ->
                    val href = fixUrl(a.attr("href"))
                    val aText = a.text()
                    if (href.isNotBlank() && (
                            href.contains("vcloud", ignoreCase = true) ||
                            href.contains("hubcloud", ignoreCase = true) ||
                            href.contains("download", ignoreCase = true) ||
                            aText.contains("V-Cloud", ignoreCase = true) ||
                            aText.contains("Episode", ignoreCase = true) ||
                            aText.contains("Download", ignoreCase = true) ||
                            aText.contains("G-Direct", ignoreCase = true)
                        )) {
                        EpisodeLink(href)
                    } else null
                }

                if (links.isNotEmpty()) {
                    seasonLinkMap.getOrPut(currentSeason) { mutableListOf() }.addAll(links)
                    allDownloadLinks.addAll(links)
                }
            }

            val cinemetaVideos = responseData?.meta?.videos
            if (!cinemetaVideos.isNullOrEmpty()) {
                cinemetaVideos.sortedWith(compareBy({ it.season }, { it.episode })).forEach { epDetail ->
                    val sNum = epDetail.season
                    val eNum = epDetail.episode
                    val sLinks = seasonLinkMap[sNum] ?: allDownloadLinks

                    if (sLinks.isNotEmpty()) {
                        tvSeriesEpisodes.add(
                            newEpisode(sLinks) {
                                this.name = epDetail.name ?: epDetail.title ?: "Episode $eNum"
                                this.season = sNum
                                this.episode = eNum
                                this.posterUrl = epDetail.thumbnail
                                this.description = epDetail.overview
                            }
                        )
                    }
                }
            }

            if (tvSeriesEpisodes.isEmpty()) {
                if (seasonLinkMap.isNotEmpty()) {
                    seasonLinkMap.forEach { (sNum, links) ->
                        if (links.isNotEmpty()) {
                            tvSeriesEpisodes.add(
                                newEpisode(links) {
                                    this.name = "Season $sNum Episodes / Downloads"
                                    this.season = sNum
                                    this.episode = 1
                                }
                            )
                        }
                    }
                } else if (allDownloadLinks.isNotEmpty()) {
                    tvSeriesEpisodes.add(
                        newEpisode(allDownloadLinks) {
                            this.name = "Full Series Pack / Episodes"
                            this.season = 1
                            this.episode = 1
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes.distinctBy { Pair(it.season, it.episode) }) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull() ?: year.substringBefore("–").toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        } else {
            val buttons = document.select("a:has(button.dwd-button)")
            val data = buttons.mapNotNull { button ->
                val link = fixUrl(button.attr("href"))
                if (link.isNotBlank()) EpisodeLink(link) else null
            }
            val dataString = data.toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, dataString) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val sources = try {
            if (data.startsWith("[")) {
                tryParseJson<List<EpisodeLink>>(data) ?: emptyList()
            } else if (data.startsWith("{")) {
                listOfNotNull(tryParseJson<EpisodeLink>(data))
            } else {
                listOf(EpisodeLink(data))
            }
        } catch (_: Exception) {
            listOf(EpisodeLink(data))
        }

        var foundLink = false

        sources.amap { epLink ->
            var targetSource = epLink.source
            if (targetSource.isBlank()) return@amap

            if (!targetSource.contains("vcloud") && !targetSource.contains("hubcloud")) {
                try {
                    val doc = app.get(targetSource, timeout = 10_000L).document
                    val extracted = doc.select("a").mapNotNull { a ->
                        val href = a.attr("href")
                        val aText = a.text()
                        if (href.contains("vcloud", ignoreCase = true) || href.contains("hubcloud", ignoreCase = true) || aText.contains("V-Cloud", ignoreCase = true)) {
                            fixUrl(href)
                        } else null
                    }.firstOrNull()

                    if (!extracted.isNullOrBlank()) {
                        targetSource = extracted
                    }
                } catch (_: Exception) {}
            }

            if (targetSource.contains("vcloud") || targetSource.contains("hubcloud")) {
                VCloud().getUrl(targetSource, "", subtitleCallback) { link ->
                    foundLink = true
                    callback(link)
                }
            } else {
                loadExtractor(targetSource, "", subtitleCallback) { link ->
                    foundLink = true
                    callback(link)
                }
            }
        }
        return foundLink
    }

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?,
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
    )

    data class ResponseData(
        val meta: Meta,
    )

    data class EpisodeLink(
        val source: String
    )

    data class VegaSearchResponse(
        val hits: List<VegaHit>
    )

    data class VegaHit(
        val document: VegaDocument
    )

    data class VegaDocument(
        val id: String,
        val imdb_id: String?,
        val post_title: String,
        val permalink: String,
        val post_thumbnail: String
    )
}
