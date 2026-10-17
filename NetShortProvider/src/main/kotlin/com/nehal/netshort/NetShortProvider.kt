package com.nehal.netshort

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.UUID

class NetShortProvider : MainAPI() {
    override var mainUrl = "https://appsecapi.netshort.com/prod-app-api"
    override var name = "NetShort"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries)

    companion object {
        private val mapper by lazy { jacksonObjectMapper() }
        private val volcEngineExtractor by lazy { VolcEngineExtractor() }
        @Volatile private var cachedToken: String? = null
        @Volatile private var tokenFetchTime: Long = 0L
    }

    override val mainPage = mainPageOf(
        "$mainUrl/video/shortPlay/recommend/load_user_recommend" to "Recommended",
        "$mainUrl/video/shortPlay/tab/load_all_chart" to "Top Charts",
        "$mainUrl/video/shortPlay/tab/load_page_free_group" to "Free Dramas",
        "$mainUrl/video/classes/page?classType=1" to "Revenge & CEO",
        "$mainUrl/video/classes/page?classType=2" to "Romance",
        "$mainUrl/video/classes/page?classType=3" to "Fantasy & Werewolf"
    )

    private suspend fun getAuthHeaders(): Map<String, String> {
        val customToken = NetShortStorage.getCustomToken()
        val token = if (!customToken.isNullOrBlank()) {
            customToken
        } else {
            getOrRefreshGuestToken()
        }

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "okhttp/4.9.0",
            "language" to (NetShortStorage.getLanguage().ifBlank { lang }),
            "Accept" to "application/json"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private suspend fun getOrRefreshGuestToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && (now - tokenFetchTime < 3_600_000L)) {
            return cachedToken
        }

        val savedGuest = NetShortStorage.getGuestToken()
        if (!savedGuest.isNullOrBlank() && cachedToken == null) {
            cachedToken = savedGuest
            return cachedToken
        }

        // Attempt automated guest session handshake
        return try {
            val deviceId = UUID.randomUUID().toString()
            val guestPayload = mapOf(
                "deviceId" to deviceId,
                "deviceNo" to deviceId,
                "loginType" to 0,
                "appVersion" to "2.1.3"
            )
            val res = app.post(
                "$mainUrl/auth/login",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to "okhttp/4.9.0",
                    "language" to lang
                ),
                json = guestPayload,
                timeout = 10L
            )
            val json = res.text
            val root = mapper.readTree(json)
            val token = root.get("data")?.get("token")?.asText()
                ?: root.get("data")?.get("access_token")?.asText()
                ?: root.get("token")?.asText()

            if (!token.isNullOrBlank()) {
                cachedToken = token
                tokenFetchTime = now
                NetShortStorage.saveGuestToken(token)
                token
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.d("NetShort", "Guest handshake notice: ${e.message}")
            cachedToken
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        AnalyticsTracker.heartbeat(name)
        val headers = getAuthHeaders()
        val dramas = mutableListOf<SearchResponse>()

        val url = request.data
        try {
            val body = when {
                url.contains("load_user_recommend") -> {
                    mapOf(
                        "offset" to (page - 1) * 20,
                        "limit" to 20,
                        "policyType" to 0,
                        "reload" to false,
                        "codec" to "h264",
                        "playClarity" to "720p"
                    )
                }
                url.contains("load_all_chart") -> {
                    mapOf(
                        "pageNo" to page,
                        "pageSize" to 20
                    )
                }
                url.contains("load_page_free_group") -> {
                    mapOf(
                        "pageNo" to page,
                        "pageSize" to 20
                    )
                }
                url.contains("classes/page") -> {
                    val classType = url.substringAfter("classType=").substringBefore("&").toIntOrNull() ?: 1
                    mapOf(
                        "pageNo" to page,
                        "pageSize" to 20,
                        "classType" to classType
                    )
                }
                else -> {
                    mapOf(
                        "offset" to (page - 1) * 20,
                        "limit" to 20
                    )
                }
            }

            val response = app.post(url.substringBefore("?"), headers = headers, json = body, timeout = 15L).text
            val root = mapper.readTree(response)
            val dataNode = root.get("data")

            val items: List<DramaItem> = when {
                dataNode == null -> emptyList()
                dataNode.isArray -> mapper.readValue(dataNode.toString())
                dataNode.has("dataList") -> mapper.readValue(dataNode.get("dataList").toString())
                dataNode.has("records") -> mapper.readValue(dataNode.get("records").toString())
                else -> emptyList()
            }

            items.forEach { drama ->
                drama.toSearchResponse()?.let { dramas.add(it) }
            }
        } catch (e: Throwable) {
            Log.e("NetShort", "Failed to load main page: ${e.message}")
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = dramas,
                isHorizontalImages = false
            ),
            hasNext = dramas.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        AnalyticsTracker.heartbeat(name, query)
        val headers = getAuthHeaders()
        val results = mutableListOf<SearchResponse>()

        try {
            val body = mapOf(
                "keyword" to query,
                "pageNo" to 1,
                "pageSize" to 30
            )
            val response = app.post(
                "$mainUrl/video/shortPlay/search/searchByKeyword",
                headers = headers,
                json = body,
                timeout = 15L
            ).text

            val root = mapper.readTree(response)
            val dataNode = root.get("data")
            val items: List<DramaItem> = when {
                dataNode == null -> emptyList()
                dataNode.isArray -> mapper.readValue(dataNode.toString())
                dataNode.has("records") -> mapper.readValue(dataNode.get("records").toString())
                dataNode.has("dataList") -> mapper.readValue(dataNode.get("dataList").toString())
                else -> emptyList()
            }

            items.forEach { drama ->
                drama.toSearchResponse()?.let { results.add(it) }
            }
        } catch (e: Throwable) {
            Log.e("NetShort", "Search failed: ${e.message}")
        }

        return results
    }

    private fun DramaItem.toSearchResponse(): SearchResponse? {
        val id = shortPlayId?.ifBlank { null } ?: return null
        val title = shortPlayName?.ifBlank { null } ?: return null
        val cover = shortPlayCover?.ifBlank { null }

        return newTvSeriesSearchResponse(
            name = title,
            url = id,
            type = TvType.AsianDrama
        ) {
            this.posterUrl = cover
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val shortPlayId = url.trim()
        val headers = getAuthHeaders()

        val body = mapOf(
            "shortPlayId" to shortPlayId,
            "codec" to "h264",
            "playClarity" to "720p",
            "isRequestReserve" to false
        )

        val response = app.post(
            "$mainUrl/video/shortPlay/base/detail_info/V2",
            headers = headers,
            json = body,
            timeout = 15L
        ).text

        val parsed = parseJson<NetShortApiResponse<DramaDetail>>(response)
        val detail = parsed.data ?: throw ErrorLoadingException("Failed to load drama details: ${parsed.msg ?: "Empty response"}")

        val title = detail.shortPlayName ?: "Short Drama"
        val cover = detail.shortPlayCover
        val plot = detail.shotIntroduce

        val tags = mutableListOf<String>()
        detail.classificationList?.forEach { c -> c.name?.let { tags.add(it) } }

        val episodes = mutableListOf<Episode>()
        detail.shortPlayEpisodeInfos?.forEachIndexed { index, ep ->
            val epNo = ep.episodeNo ?: (index + 1)
            val epId = ep.episodeId ?: epNo.toString()
            val isLocked = ep.isLock == true || ep.isVip == true

            val displayName = if (isLocked) {
                "Episode $epNo [🔒 Locked]"
            } else {
                "Episode $epNo"
            }

            val linkData = EpisodeStreamLinkData(
                shortPlayId = shortPlayId,
                episodeId = epId,
                episodeNo = epNo,
                isLock = isLocked,
                sdkVid = ep.sdkVid,
                playVoucher = ep.playVoucher,
                episodeType = ep.episodeType
            )

            episodes.add(
                newEpisode(linkData.toJson()) {
                    this.name = displayName
                    this.episode = epNo
                    this.posterUrl = ep.episodeCover ?: cover
                }
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = shortPlayId,
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            this.posterUrl = cover
            this.backgroundPosterUrl = cover
            this.plot = plot
            this.tags = tags
            detail.actorList?.mapNotNull { it.actorName }?.let { actors ->
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = try {
            parseJson<EpisodeStreamLinkData>(data)
        } catch (_: Throwable) {
            null
        } ?: return false

        val headers = getAuthHeaders()

        // 1. Fetch exact episode streaming payload from detail_info API
        val body = mapOf(
            "shortPlayId" to linkData.shortPlayId,
            "episodeId" to linkData.episodeId,
            "episodeType" to (linkData.episodeType ?: 0),
            "episodeNo" to linkData.episodeNo,
            "codec" to "h264",
            "playClarity" to "720p"
        )

        var sdkVid = linkData.sdkVid
        var playVoucher = linkData.playVoucher
        var directUrl: String? = null

        try {
            val response = app.post(
                "$mainUrl/video/shortPlay/base/episode/detail_info",
                headers = headers,
                json = body,
                timeout = 15L
            ).text

            val parsed = parseJson<NetShortApiResponse<EpisodeDetailData>>(response)
            val epDetail = parsed.data

            if (epDetail != null) {
                if (!epDetail.sdkVid.isNullOrBlank()) sdkVid = epDetail.sdkVid
                if (!epDetail.playVoucher.isNullOrBlank()) playVoucher = epDetail.playVoucher
                if (!epDetail.playUrl.isNullOrBlank()) directUrl = epDetail.playUrl

                epDetail.episodeClarityList?.forEach { clarity ->
                    if (!clarity.playUrl.isNullOrBlank()) {
                        directUrl = clarity.playUrl
                    }
                    if (!clarity.playVoucher.isNullOrBlank()) {
                        playVoucher = clarity.playVoucher
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d("NetShort", "Episode detail fetch notice: ${e.message}")
        }

        // If direct stream URL was returned:
        if (!directUrl.isNullOrBlank()) {
            volcEngineExtractor.resolveStream(directUrl, sdkVid, playVoucher, callback)
            return true
        }

        // Resolve via VolcEngine VOD extractor
        if (!sdkVid.isNullOrBlank() || !playVoucher.isNullOrBlank()) {
            volcEngineExtractor.resolveStream(
                videoUrlOrVid = sdkVid ?: "",
                sdkVid = sdkVid,
                playVoucher = playVoucher,
                callback = callback
            )
            return true
        }

        if (linkData.isLock) {
            throw ErrorLoadingException(
                "Episode ${linkData.episodeNo} is locked (coin/VIP required in NetShort). Please watch free unlocked episodes."
            )
        }

        return false
    }
}
