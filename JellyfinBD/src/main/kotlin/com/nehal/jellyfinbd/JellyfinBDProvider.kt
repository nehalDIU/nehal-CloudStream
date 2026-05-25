package com.nehal.jellyfinbd

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthenticationResponse(
    val AccessToken: String = "",
    val User: UserInfo = UserInfo()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserInfo(
    val Id: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JellyfinItem(
    val Name: String = "",
    val Id: String = "",
    val Type: String = "",
    val ProductionYear: Int? = null,
    val Overview: String? = null,
    val RunTimeTicks: Long? = null,
    val Genres: List<String>? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val ImageTags: ImageTagsInfo? = null,
    val MediaSources: List<MediaSourceInfo>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageTagsInfo(
    val Primary: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaSourceInfo(
    val Id: String = "",
    val Name: String? = null,
    val Container: String? = null,
    val MediaStreams: List<MediaStreamInfo>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaStreamInfo(
    val Index: Int = 0,
    val Type: String = "",
    val Codec: String? = null,
    val Language: String? = null,
    val DisplayTitle: String? = null,
    val Title: String? = null,
    val IsExternal: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemsResponse(
    val Items: List<JellyfinItem> = emptyList(),
    val TotalRecordCount: Int = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackInfoResponse(
    val MediaSources: List<MediaSourceInfo> = emptyList()
)

class JellyfinBDProvider : MainAPI() {
    override var name = "JellyfinBD"
    override var mainUrl = "http://103.29.127.114:8096"
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private var token: String? = null
    private var userId: String? = null
    private val authMutex = Mutex()
    private val mapper = jacksonObjectMapper()

    private data class JellyfinCategory(
        val key: String,
        val name: String,
        val parentId: String,
        val type: TvType
    )

    private val categories = listOf(
        JellyfinCategory("english-movies", "English Movies", "fb6af48929bdab3445c3dc034d5dc92c", TvType.Movie),
        JellyfinCategory("english-tv-shows", "English TV Shows", "e1bee06a655fcac49a4c8fa3fbbb45e0", TvType.TvSeries),
        JellyfinCategory("bangla-movies", "Bangla Movies", "d6d7796e127b01138f8c2c4dc4b60f02", TvType.Movie),
        JellyfinCategory("bangla-tv-series", "Bangla Tv Series", "3a9208310ffe851603203fc86383a139", TvType.TvSeries),
        JellyfinCategory("hindi-movies", "Hindi Movies", "396e77fb30fb44562223be713bd1418c", TvType.Movie),
        JellyfinCategory("hindi-tv-series", "Hindi TV Series", "73ffaa16f8a96acd90889e181ae873ae", TvType.TvSeries),
        JellyfinCategory("hindi-dubbed-movies", "Hindi Dubbed Movies", "24b7f6eac33015984d3ecdf7ad99151d", TvType.Movie),
        JellyfinCategory("south-indian-hindi-dubbed", "South Indian Hindi Dubbed Movies", "07dfb2623c450c0babc8c8b989b81582", TvType.Movie),
        JellyfinCategory("south-indian-movies", "South Indian Movies", "e86381ceb9d70ff1d431b6153dc12f38", TvType.Movie),
        JellyfinCategory("foreign-movies", "Foreign Language Movies", "53f293aa610a3a51be006c95f83762cc", TvType.Movie),
        JellyfinCategory("foreign-tv-series", "Foreign Language TV Series", "573b7636b48e26a3e03c52b991de9e15", TvType.TvSeries)
    )

    override val mainPage = mainPageOf(
        *categories.map { it.key to it.name }.toTypedArray()
    )

    private suspend fun getSession(): Pair<String, String> {
        val currentToken = token
        val currentUserId = userId
        if (currentToken != null && currentUserId != null) {
            return Pair(currentToken, currentUserId)
        }
        return authMutex.withLock {
            val lockedToken = token
            val lockedUserId = userId
            if (lockedToken != null && lockedUserId != null) {
                return@withLock Pair(lockedToken, lockedUserId)
            }

            val authUrl = "$mainUrl/Users/AuthenticateByName"
            val clientName = "Cloudstream"
            val device = "Android"
            val deviceId = "nehal-cs-jellyfin-12345"
            val authHeaderVal = "MediaBrowser Client=\"$clientName\", Device=\"$device\", DeviceId=\"$deviceId\", Version=\"1.0.0\""

            val jsonBody = "{\"Username\": \"Relax Time\", \"Pw\": \"\"}"
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val response = app.post(
                authUrl,
                headers = mapOf(
                    "Authorization" to authHeaderVal
                ),
                requestBody = requestBody
            )

            val authResp = mapper.readValue<AuthenticationResponse>(response.text)
            token = authResp.AccessToken
            userId = authResp.User.Id
            Pair(authResp.AccessToken, authResp.User.Id)
        }
    }

    private fun getPosterUrl(itemId: String): String {
        return "$mainUrl/Items/$itemId/Images/Primary"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val category = categories.firstOrNull { it.key == request.data } ?: return null
        val (token, userId) = getSession()

        val limit = 40
        val startIndex = (page - 1) * limit

        val response = app.get(
            "$mainUrl/Users/$userId/Items",
            params = mapOf(
                "ParentId" to category.parentId,
                "IncludeItemTypes" to if (category.type == TvType.Movie) "Movie" else "Series",
                "Recursive" to "true",
                "Fields" to "PrimaryImageAspectRatio,ProductionYear,Overview",
                "Limit" to limit.toString(),
                "StartIndex" to startIndex.toString(),
                "api_key" to token
            )
        )

        val itemsResp = mapper.readValue<ItemsResponse>(response.text)
        val searchResponses = itemsResp.Items.map { item ->
            val title = item.Name
            val itemUrl = "$mainUrl/Items/${item.Id}"
            val poster = getPosterUrl(item.Id)

            if (category.type == TvType.Movie) {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
            } else {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
            }
        }

        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val (token, userId) = getSession()

        val response = app.get(
            "$mainUrl/Users/$userId/Items",
            params = mapOf(
                "searchTerm" to query,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series",
                "Fields" to "PrimaryImageAspectRatio,ProductionYear,Overview",
                "api_key" to token
            )
        )

        val itemsResp = mapper.readValue<ItemsResponse>(response.text)
        return itemsResp.Items.map { item ->
            val title = item.Name
            val itemUrl = "$mainUrl/Items/${item.Id}"
            val poster = getPosterUrl(item.Id)

            if (item.Type == "Series") {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val itemId = url.substringAfterLast("/")
        val (token, userId) = getSession()

        val response = app.get(
            "$mainUrl/Users/$userId/Items/$itemId",
            params = mapOf("api_key" to token)
        )
        val item = mapper.readValue<JellyfinItem>(response.text)

        val title = item.Name
        val plot = item.Overview
        val year = item.ProductionYear
        val poster = getPosterUrl(item.Id)
        val genres = item.Genres

        if (item.Type == "Series") {
            val epsResponse = app.get(
                "$mainUrl/Shows/$itemId/Episodes",
                params = mapOf(
                    "userId" to userId,
                    "api_key" to token
                )
            )
            val epsResp = mapper.readValue<ItemsResponse>(epsResponse.text)

            val episodes = epsResp.Items.map { ep ->
                val epName = ep.Name
                val epDesc = ep.Overview
                val epIndex = ep.IndexNumber ?: 1
                val epSeason = ep.ParentIndexNumber ?: 1
                val epUrl = "$mainUrl/Items/${ep.Id}"
                val epPoster = getPosterUrl(ep.Id)

                newEpisode(epUrl) {
                    this.name = epName
                    this.description = epDesc
                    this.episode = epIndex
                    this.season = epSeason
                    this.posterUrl = epPoster
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val itemId = data.substringAfterLast("/")
        val (token, userId) = getSession()

        val response = app.post(
            "$mainUrl/Items/$itemId/PlaybackInfo",
            params = mapOf(
                "userId" to userId,
                "api_key" to token
            ),
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        )

        val pbResp = mapper.readValue<PlaybackInfoResponse>(response.text)
        val mediaSources = pbResp.MediaSources

        if (mediaSources.isEmpty()) return false

        for (source in mediaSources) {
            val streamUrl = "$mainUrl/Videos/$itemId/stream?static=true&mediaSourceId=${source.Id}&api_key=$token"

            callback.invoke(
                newExtractorLink(
                    source.Name ?: this.name,
                    source.Name ?: this.name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P720.value
                }
            )

            source.MediaStreams?.filter { it.Type == "Subtitle" }?.forEach { sub ->
                val subIdx = sub.Index
                val codec = sub.Codec ?: "vtt"
                val ext = if (codec == "subrip") "srt" else if (codec == "ass") "ass" else "vtt"
                val subUrl = "$mainUrl/Videos/$itemId/${source.Id}/Subtitles/$subIdx/Stream.$ext?api_key=$token"

                val langLabel = sub.Language ?: sub.DisplayTitle ?: sub.Title ?: "Unknown"

                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = langLabel,
                        url = subUrl
                    )
                )
            }
        }

        return true
    }
}
