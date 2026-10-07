package com.nehal.ftpbdmedia

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
    @JsonProperty("AccessToken") val AccessToken: String = "",
    @JsonProperty("User") val User: UserInfo = UserInfo()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserInfo(
    @JsonProperty("Id") val Id: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbyItem(
    @JsonProperty("Name") val Name: String = "",
    @JsonProperty("Id") val Id: String = "",
    @JsonProperty("Type") val Type: String = "",
    @JsonProperty("ProductionYear") val ProductionYear: Int? = null,
    @JsonProperty("Overview") val Overview: String? = null,
    @JsonProperty("RunTimeTicks") val RunTimeTicks: Long? = null,
    @JsonProperty("Genres") val Genres: List<String>? = null,
    @JsonProperty("IndexNumber") val IndexNumber: Int? = null,
    @JsonProperty("ParentIndexNumber") val ParentIndexNumber: Int? = null,
    @JsonProperty("CommunityRating") val CommunityRating: Double? = null,
    @JsonProperty("CriticRating") val CriticRating: Double? = null,
    @JsonProperty("ImageTags") val ImageTags: ImageTagsInfo? = null,
    @JsonProperty("BackdropImageTags") val BackdropImageTags: List<String>? = null,
    @JsonProperty("MediaSources") val MediaSources: List<MediaSourceInfo>? = null,
    @JsonProperty("People") val People: List<PersonInfo>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PersonInfo(
    @JsonProperty("Name") val Name: String = "",
    @JsonProperty("Type") val Type: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageTagsInfo(
    @JsonProperty("Primary") val Primary: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaSourceInfo(
    @JsonProperty("Id") val Id: String = "",
    @JsonProperty("Name") val Name: String? = null,
    @JsonProperty("Container") val Container: String? = null,
    @JsonProperty("Path") val Path: String? = null,
    @JsonProperty("MediaStreams") val MediaStreams: List<MediaStreamInfo>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaStreamInfo(
    @JsonProperty("Index") val Index: Int = 0,
    @JsonProperty("Type") val Type: String = "",
    @JsonProperty("Codec") val Codec: String? = null,
    @JsonProperty("Language") val Language: String? = null,
    @JsonProperty("DisplayTitle") val DisplayTitle: String? = null,
    @JsonProperty("Title") val Title: String? = null,
    @JsonProperty("Height") val Height: Int? = null,
    @JsonProperty("Width") val Width: Int? = null,
    @JsonProperty("IsExternal") val IsExternal: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemsResponse(
    @JsonProperty("Items") val Items: List<EmbyItem> = emptyList(),
    @JsonProperty("TotalRecordCount") val TotalRecordCount: Int = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackInfoResponse(
    @JsonProperty("MediaSources") val MediaSources: List<MediaSourceInfo> = emptyList()
)

class FTPBDMediaProvider : MainAPI() {
    override var name = "FTPBD Media"
    override var mainUrl = "http://media.ftpbd.net:8096"
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    private var token: String? = null
    private var userId: String? = null
    private val authMutex = Mutex()
    private val mapper = jacksonObjectMapper()

    private data class EmbyCategory(
        val key: String,
        val name: String,
        val parentId: String,
        val type: TvType
    )

    private val categories = listOf(
        EmbyCategory("english-movies", "English Movies", "842588", TvType.Movie),
        EmbyCategory("english-tv-shows", "English & Foreign TV Series", "1125176", TvType.TvSeries),
        EmbyCategory("foreign-movies", "Foreign Language Movies", "1063475", TvType.Movie),
        EmbyCategory("hindi-movies", "Hindi Movies", "805018", TvType.Movie),
        EmbyCategory("hindi-tv-series", "Hindi TV Series", "1456165", TvType.TvSeries),
        EmbyCategory("south-indian-movies", "South Indian Movies", "825725", TvType.Movie),
        EmbyCategory("english-movies-4k", "English Movies - 4K", "1786361", TvType.Movie),
        EmbyCategory("anime-cartoon-tv", "Anime & Cartoon TV Series", "1260392", TvType.Anime)
    )

    override val mainPage = mainPageOf(
        *categories.map { it.key to it.name }.toTypedArray()
    )

    private suspend fun getSession(forceRefresh: Boolean = false): Pair<String, String> {
        val currentToken = token
        val currentUserId = userId
        if (!forceRefresh && currentToken != null && currentUserId != null) {
            return Pair(currentToken, currentUserId)
        }
        return authMutex.withLock {
            val lockedToken = token
            val lockedUserId = userId
            if (!forceRefresh && lockedToken != null && lockedUserId != null) {
                return@withLock Pair(lockedToken, lockedUserId)
            }

            val authUrl = "$mainUrl/Users/AuthenticateByName"
            val clientName = "CloudStream"
            val device = "Android"
            val deviceId = "cs-ftpbd-media-device-1"
            val authHeaderVal = "MediaBrowser Client=\"$clientName\", Device=\"$device\", DeviceId=\"$deviceId\", Version=\"1.0.0\""

            val jsonBody = "{\"Username\": \"Info Internet Service\", \"Pw\": \"\"}"
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

    private fun getBackdropUrl(itemId: String): String {
        return "$mainUrl/Items/$itemId/Images/Backdrop"
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
                "SortBy" to "DateCreated",
                "SortOrder" to "Descending",
                "Fields" to "PrimaryImageAspectRatio,ProductionYear,Overview,Genres",
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

            when (category.type) {
                TvType.Movie -> newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
                TvType.Anime -> newAnimeSearchResponse(title, itemUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
                else -> newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.ProductionYear
                }
            }
        }

        return newHomePageResponse(request.name, searchResponses, hasNext = searchResponses.size >= limit)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val (token, userId) = getSession()

        val response = app.get(
            "$mainUrl/Users/$userId/Items",
            params = mapOf(
                "SearchTerm" to query,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series",
                "Fields" to "PrimaryImageAspectRatio,ProductionYear,Overview,Genres",
                "Limit" to "50",
                "api_key" to token
            )
        )

        val itemsResp = mapper.readValue<ItemsResponse>(response.text)
        return itemsResp.Items.map { item ->
            val title = item.Name
            val itemUrl = "$mainUrl/Items/${item.Id}"
            val poster = getPosterUrl(item.Id)

            if (item.Type.equals("Series", ignoreCase = true)) {
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
            params = mapOf(
                "Fields" to "Overview,ProductionYear,Genres,MediaSources,MediaStreams,ImageTags,BackdropImageTags,RunTimeTicks,CommunityRating,People",
                "api_key" to token
            )
        )
        val item = mapper.readValue<EmbyItem>(response.text)

        val title = item.Name
        val plot = item.Overview
        val year = item.ProductionYear
        val poster = getPosterUrl(item.Id)
        val backdrop = if (!item.BackdropImageTags.isNullOrEmpty()) getBackdropUrl(item.Id) else null
        val genres = item.Genres
        val duration = item.RunTimeTicks?.let { (it / 10_000_000 / 60).toInt() }
        val actors = item.People?.filter { it.Type.equals("Actor", ignoreCase = true) }?.map { it.Name }

        if (item.Type.equals("Series", ignoreCase = true)) {
            val seasonsResponse = app.get(
                "$mainUrl/Shows/$itemId/Seasons",
                params = mapOf(
                    "userId" to userId,
                    "api_key" to token
                )
            )
            val seasonsResp = mapper.readValue<ItemsResponse>(seasonsResponse.text)

            val episodes = seasonsResp.Items.amap { season ->
                val epsResponse = app.get(
                    "$mainUrl/Shows/$itemId/Episodes",
                    params = mapOf(
                        "seasonId" to season.Id,
                        "userId" to userId,
                        "Fields" to "Overview,MediaSources,MediaStreams,ImageTags,RunTimeTicks,IndexNumber,ParentIndexNumber",
                        "api_key" to token
                    )
                )
                val epsResp = mapper.readValue<ItemsResponse>(epsResponse.text)
                epsResp.Items.map { ep ->
                    val epName = ep.Name
                    val epDesc = ep.Overview
                    val epIndex = ep.IndexNumber ?: 1
                    val epSeason = ep.ParentIndexNumber ?: season.IndexNumber ?: 1
                    val epUrl = "$mainUrl/Items/${ep.Id}"
                    val epPoster = getPosterUrl(ep.Id)
                    val epDuration = ep.RunTimeTicks?.let { (it / 10_000_000 / 60).toInt() }

                    newEpisode(epUrl) {
                        this.name = epName
                        this.description = epDesc
                        this.episode = epIndex
                        this.season = epSeason
                        this.posterUrl = epPoster
                        this.runTime = epDuration
                    }
                }
            }.flatten()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.duration = duration
                if (!actors.isNullOrEmpty()) {
                    addActors(actors)
                }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.duration = duration
                if (!actors.isNullOrEmpty()) {
                    addActors(actors)
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
            val streamUrl = "$mainUrl/Videos/$itemId/stream?static=true&MediaSourceId=${source.Id}&api_key=$token"

            // Quality determination from media streams or source name
            val videoStream = source.MediaStreams?.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
            val height = videoStream?.Height ?: 0
            val displayTitle = videoStream?.DisplayTitle ?: source.Name ?: ""

            val quality = when {
                height >= 2160 || displayTitle.contains("4K", ignoreCase = true) || displayTitle.contains("2160", ignoreCase = true) -> Qualities.P2160.value
                height >= 1440 || displayTitle.contains("1440", ignoreCase = true) -> Qualities.P1440.value
                height >= 1080 || displayTitle.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                height >= 720 || displayTitle.contains("720", ignoreCase = true) -> Qualities.P720.value
                height >= 480 || displayTitle.contains("480", ignoreCase = true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            val sourceName = source.Name?.takeIf { it.isNotBlank() } ?: "Direct Stream"

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $sourceName",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )

            // Extract subtitle tracks
            source.MediaStreams?.filter { it.Type.equals("Subtitle", ignoreCase = true) }?.forEach { sub ->
                val subIdx = sub.Index
                val codec = sub.Codec ?: "vtt"
                val ext = when (codec.lowercase()) {
                    "subrip", "srt" -> "srt"
                    "ass", "ssa" -> "ass"
                    else -> "vtt"
                }
                val subUrl = "$mainUrl/Videos/$itemId/${source.Id}/Subtitles/$subIdx/Stream.$ext?api_key=$token"
                val langLabel = sub.Language ?: sub.DisplayTitle ?: sub.Title ?: "Subtitle"

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
