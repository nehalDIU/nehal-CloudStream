package com.nehal.animedekho

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

open class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "Anime Dekho"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "/category/anime/" to "Anime",
        "/category/cartoon/" to "Cartoon",
        "/category/crunchyroll/" to "Crunchyroll",
        "/category/hindi-dub/" to "Hindi",
        "/category/tamil/" to "Tamil",
        "/category/telugu/" to "Telugu"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val link = "$mainUrl${request.data}"
        val document = app.get(link).document
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val title = this.selectFirst("header h2")?.text() ?: "null"
        var posterUrl = this.selectFirst("div figure img")?.attr("src")
        if (posterUrl != null && posterUrl.contains("data:image")) {
            posterUrl = this.selectFirst("div figure img")?.attr("data-lazy-src")
        }
        return newAnimeSearchResponse(title, Media(href, posterUrl).toJson(), TvType.Anime, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("ul[data-results] li article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = parseJson<Media>(url)
        val document = app.get(media.url).document
        val rawTitle = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "No Title"

        val title = rawTitle
            .substringBefore(" – Watch Online")
            .substringBefore(" - Watch Online")
            .substringBefore(" Watch Online")
            .substringBefore(" | AnimeDekho")
            .substringBefore(" Movie (Hindi Dubbed)")
            .substringBefore(" (Hindi Dubbed)")
            .substringBefore(" (Hindi")
            .substringBefore(" Movie in Hindi Dubbed Free")
            .substringAfter("Watch Online ")
            .trim()
            .ifBlank { rawTitle }

        val poster = fixUrlNull(
            document.selectFirst("div.post-thumbnail figure img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: media.poster
        )
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        val year = (document.selectFirst("span.year")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:updated_time]")?.attr("content")?.substringBefore("-"))?.toIntOrNull()
        val tags = document.select("span.s-tag a, div.entry-content p a[rel=tag]").map { it.text().trim() }

        val anilistUrl = document.selectFirst("a[href*='anilist.co/anime']")?.attr("href")
        val malUrl = document.selectFirst("a[href*='myanimelist.net/anime']")?.attr("href")
        val tmdbId = Regex("""themoviedb\.org/(?:tv|movie)/(\d+)""").find(document.html())?.groupValues?.getOrNull(1)

        val anilistId = anilistUrl?.let { Regex("""anilist\.co/anime/(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val malId = malUrl?.let { Regex("""myanimelist\.net/anime/(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val urlsToTry = listOfNotNull(
            tmdbId?.let { "https://api.ani.zip/mappings?themoviedb_id=$it" },
            anilistId?.let { "https://api.ani.zip/mappings?anilist_id=$it" },
            malId?.let { "https://api.ani.zip/mappings?mal_id=$it" }
        )

        var aniZipData: MetaAnimeData? = null
        for (aniUrl in urlsToTry) {
            try {
                aniZipData = app.get(aniUrl).parsedSafe<MetaAnimeData>()
                if (aniZipData != null) break
            } catch (e: Throwable) {
                Log.e("AnimeDekho", "Error fetching ani.zip data: $e")
            }
        }

        val backgroundPoster = aniZipData?.images?.firstOrNull { it.imageType == "banner" }?.url
        val metaPoster = aniZipData?.images?.firstOrNull { it.imageType == "poster" }?.url ?: poster

        val lst = document.select("ul.seasons-lst li")

        return if (lst.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, Media(media.url, mediaType = 1).toJson()) {
                this.posterUrl = metaPoster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.year = year
                this.tags = tags
                addMalId(malId)
                addAniListId(anilistId)
                addTMDbId(tmdbId)
            }
        } else {
            val tmdbIdFinal = tmdbId ?: aniZipData?.mappings?.themoviedbId?.toString()
            val apiKey = "94e1d17d598506e792f39cb32e6040e3"
            val tmdbSeasonData = mutableMapOf<Int, TmdbSeasonResponse>()

            val seasonsPresent = lst.mapNotNull {
                it.selectFirst("h3.title > span")?.text()?.substringAfter("S")?.substringBefore("-")?.toIntOrNull()
            }.distinct()

            if (tmdbIdFinal != null) {
                seasonsPresent.amap { s ->
                    try {
                        val res = app.get("https://api.themoviedb.org/3/tv/$tmdbIdFinal/season/$s?api_key=$apiKey")
                            .parsedSafe<TmdbSeasonResponse>()
                        if (res != null) {
                            tmdbSeasonData[s] = res
                        }
                    } catch (e: Throwable) {
                        Log.e("AnimeDekho", "Error fetching tmdb season: $e")
                    }
                }
            }

            val episodes = lst.mapNotNull { it ->
                val epName = it.selectFirst("h3.title")?.ownText() ?: "null"
                val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = it.selectFirst("div > div > figure > img")?.attr("src")
                val seasonNumber = it.selectFirst("h3.title > span")?.text()?.substringAfter("S")?.substringBefore("-")?.toIntOrNull()
                val epNum = Regex("""(?:E|EP|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val tmdbEp = epNum?.let { num ->
                    tmdbSeasonData[seasonNumber]?.episodes?.firstOrNull { ep ->
                        ep.name != null && (ep.overview != null || ep.still_path != null)
                    }
                }
                val metaEp = epNum?.let { num -> aniZipData?.episodes?.get(num.toString()) }

                val finalEpName = tmdbEp?.name ?: metaEp?.title?.get("en") ?: metaEp?.title?.get("x-jat") ?: epName
                val finalEpPoster = tmdbEp?.still_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: metaEp?.image ?: epPoster
                val finalOverview = tmdbEp?.overview ?: metaEp?.overview

                newEpisode(Media(href, mediaType = 2).toJson()) {
                    this.name = finalEpName
                    this.posterUrl = finalEpPoster
                    this.season = seasonNumber
                    this.episode = epNum
                    this.description = finalOverview
                    if (tmdbEp?.air_date != null) {
                        this.addDate(tmdbEp.air_date)
                    } else if (metaEp?.airDateUtc != null) {
                        this.addDate(metaEp.airDateUtc)
                    }
                    if (tmdbEp?.vote_average != null && tmdbEp.vote_average > 0.0) {
                        this.score = Score.from10(tmdbEp.vote_average.toString())
                    } else if (metaEp?.rating != null) {
                        this.score = Score.from10(metaEp.rating)
                    }
                }
            }

            val recommendations = document.select("div.swiper-wrapper article").mapNotNull {
                val recName = it.selectFirst("h2")?.text() ?: return@mapNotNull null
                val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val recPoster = it.selectFirst("figure img")?.attr("src")
                val mediaData = Media(url = recHref, poster = recPoster, mediaType = 0)
                newTvSeriesSearchResponse(recName, mediaData.toJson(), TvType.TvSeries) {
                    this.posterUrl = recPoster
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = metaPoster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                addMalId(malId)
                addAniListId(anilistId)
                addTMDbId(tmdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = try {
            parseJson<Media>(data)
        } catch (e: Throwable) {
            Log.e("AnimeDekho", "Failed to parse media JSON: $e")
            return false
        }
        val headers = mapOf("Cookie" to "toronites_server=vidstream")
        try {
            val doc = app.get(media.url, headers = headers).document
            doc.select("iframe.serversel[src]").amap { iframe ->
                val serverUrl = iframe.attr("src")
                if (serverUrl.isNotBlank()) {
                    val innerIframeUrl = app.get(serverUrl).document.selectFirst("iframe[src]")?.attr("src")
                    if (!innerIframeUrl.isNullOrBlank()) {
                        loadExtractor(innerIframeUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("AnimeDekho", "Error loading direct server iframe: $e")
        }

        val mediaType = media.mediaType ?: 2
        try {
            val bodyClass = app.get(media.url).document.selectFirst("body")?.attr("class") ?: ""
            val term = Regex("""(?:term|postid)-(\d+)""").find(bodyClass)?.groupValues?.getOrNull(1)
            if (term != null) {
                (0..4).toList().amap { i ->
                    try {
                        val iframeUrl = app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=$mediaType")
                            .document.selectFirst("iframe")?.attr("src")
                        if (!iframeUrl.isNullOrBlank()) {
                            loadExtractor(iframeUrl, subtitleCallback, callback)
                        }
                    } catch (e: Throwable) {
                        Log.e("AnimeDekho", "Error loading trdekho iframe $i: $e")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("AnimeDekho", "Error loading secondary links: $e")
        }
        return true
    }

    data class Media(
        @JsonProperty("url") val url: String,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("mediaType") val mediaType: Int? = null
    )

    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<MetaImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>? = null,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    data class MetaImage(
        @JsonProperty("imageType") val imageType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class MetaEpisode(
        @JsonProperty("title") val title: Map<String, String>? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("airDateUtc") val airDateUtc: String? = null
    )

    data class MetaMappings(
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("themoviedbId") val themoviedbId: Int? = null
    )

    data class TmdbSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("still_path") val still_path: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val air_date: String? = null,
        @JsonProperty("vote_average") val vote_average: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null
    )
}
