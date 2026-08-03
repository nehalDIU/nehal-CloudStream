package com.nehal.fmftp

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class FmFtpProvider : MainAPI() {
    override var mainUrl = "https://fmftp.net"
    override var name = "FM FTP"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "movies|" to "All Movies",
        "movies|1" to "Bollywood",
        "movies|2" to "Hollywood",
        "movies|3" to "Animation",
        "movies|4" to "Korean Movies",
        "movies|5" to "Hindi Dubbed",
        "movies|6" to "Horror",
        "movies|7" to "Indian Bangla",
        "movies|8" to "Tamil",
        "movies|14" to "Foreign Movies",
        "tv-shows|" to "All TV Series",
        "tv-shows|9" to "English TV Series",
        "tv-shows|10" to "Indian TV Series",
        "tv-shows|11" to "Korean TV Series",
        "tv-shows|12" to "Bangla TV Series",
        "tv-shows|13" to "Turkish TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("|")
        val endpoint = parts.getOrNull(0) ?: "movies"
        val libraryId = parts.getOrNull(1) ?: ""

        val url = if (libraryId.isBlank()) {
            "$mainUrl/api/$endpoint?limit=30&page=$page"
        } else {
            "$mainUrl/api/$endpoint?library=$libraryId&limit=30&page=$page"
        }

        val res = app.get(url).parsed<FmFtpListResponse>()
        val isTv = endpoint == "tv-shows"

        val items = res.data.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val itemUrl = "$mainUrl/$endpoint/${item.id}"
            val poster = fixPosterUrl(item.posterPath)

            if (isTv) {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, items, hasNext = page < res.pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/api/search?search=$encodedQuery"
        val response = app.get(searchUrl).parsed<List<FmFtpItem>>()

        return response.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val isTv = isTvItem(item)
            val endpoint = if (isTv) "tv-shows" else "movies"
            val itemUrl = "$mainUrl/$endpoint/${item.id}"
            val poster = fixPosterUrl(item.posterPath)

            if (isTv) {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val isTv = url.contains("/tv-shows")
        val id = url.substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid content ID")

        if (isTv) {
            val apiUrl = "$mainUrl/api/tv-shows/$id?fields=episodes"
            val show = app.get(apiUrl).parsed<FmFtpItem>()
            val title = show.title ?: throw ErrorLoadingException("Missing title")
            val poster = fixPosterUrl(show.posterPath)
            val backdrop = fixPosterUrl(show.backdropPath)
            val year = show.year ?: parseYear(show.releaseDate)
            val genres = show.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val castList = show.casts?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

            val episodes = show.episodes.map { ep ->
                val streamUrl = "$mainUrl/api/stream/video/stream?type=tv_shows&id=${ep.id}"
                newEpisode(streamUrl) {
                    this.name = ep.name
                    this.season = ep.seasonNumber
                    this.episode = ep.episodeNumber
                    this.posterUrl = fixPosterUrl(ep.stillPath)
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = show.overview
                this.tags = genres
                if (!castList.isNullOrEmpty()) {
                    addActors(castList)
                }
            }
        } else {
            val apiUrl = "$mainUrl/api/movies/$id"
            val movie = app.get(apiUrl).parsed<FmFtpItem>()
            val title = movie.title ?: throw ErrorLoadingException("Missing title")
            val poster = fixPosterUrl(movie.posterPath)
            val backdrop = fixPosterUrl(movie.backdropPath)
            val year = movie.year ?: parseYear(movie.releaseDate)
            val genres = movie.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val castList = movie.casts?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

            val streamUrl = "$mainUrl/api/stream/video/stream?type=movies&id=${movie.id}"

            return newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = movie.overview
                this.tags = genres
                if (!castList.isNullOrEmpty()) {
                    addActors(castList)
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
        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = data,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    private fun isTvItem(item: FmFtpItem): Boolean {
        val tvLibraries = setOf(9, 10, 11, 12, 13)
        if (item.library != null && tvLibraries.contains(item.library)) return true
        if (!item.path.isNullOrBlank() && item.path.contains("/tvseries/")) return true
        if (item.episodes.isNotEmpty()) return true
        return false
    }

    private fun fixPosterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return "https://image.tmdb.org/t/p/w500$path"
    }

    private fun parseYear(releaseDate: String?): Int? {
        if (releaseDate.isNullOrBlank()) return null
        return Regex("^(\\d{4})").find(releaseDate)?.value?.toIntOrNull()
    }

    data class FmFtpListResponse(
        @JsonProperty("total") val total: Int = 0,
        @JsonProperty("pages") val pages: Int = 1,
        @JsonProperty("current_page") val currentPage: Int = 1,
        @JsonProperty("limit") val limit: Int = 30,
        @JsonProperty("data") val data: List<FmFtpItem> = emptyList()
    )

    data class FmFtpItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("library") val library: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("casts") val casts: String? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("episodes") val episodes: List<FmFtpEpisode> = emptyList()
    )

    data class FmFtpEpisode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("still_path") val stillPath: String? = null
    )
}
