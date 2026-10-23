package com.nehal.netshort

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink

class ToonBoxProvider : MainAPI() {
    override var mainUrl = "https://api.toonbox.com"
    override var name = "ToonBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries)

    companion object {
        private const val TAG = "ToonBox"
        private const val APP_VERSION = "1.18.0"
        private const val VERSION_CODE = "320"
        private val mapper by lazy { jacksonObjectMapper() }
        private val volcEngineExtractor by lazy { VolcEngineExtractor() }
    }

    private fun getHeaders(): Map<String, String> {
        val currentLang = NetShortStorage.getToonBoxLanguage().ifBlank { "en" }
        val headers = mutableMapOf(
            "platform" to "Android",
            "version" to APP_VERSION,
            "version_code" to VERSION_CODE,
            "language" to currentLang,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json"
        )

        val customToken = NetShortStorage.getToonBoxToken()
        if (!customToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $customToken"
        }

        headers.putAll(NetShortStorage.getToonBoxCustomHeaders())
        return headers
    }

    private fun ToonBoxDramaItem.toSearchResponse(): SearchResponse? {
        val id = dramaId ?: return null
        val title = dramaName ?: return null
        return newTvSeriesSearchResponse(title, id, TvType.AsianDrama) {
            this.posterUrl = coverUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/v1/drama/fresh/list" to "New Releases",
        "$mainUrl/api/v1/home/recommend/video" to "Recommended",
        "$mainUrl/api/v1/ranking/drama/list" to "Top Ranked"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        AnalyticsTracker.heartbeat(name)
        val items = mutableListOf<SearchResponse>()
        var hasNextPage = false

        try {
            val response = app.get(
                request.data,
                params = mapOf(
                    "page_num" to "$page",
                    "page_size" to "20",
                    "platform" to "Android",
                    "version" to APP_VERSION,
                    "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                ),
                headers = getHeaders(),
                timeout = 15L
            )

            val parsed = response.parsedSafe<ToonBoxListResponse>()
            if (parsed != null) {
                if (parsed.code != null && parsed.code != 0 && parsed.code != 200) {
                    val errMsg = parsed.msg ?: parsed.message ?: "Code ${parsed.code}"
                    Log.w(TAG, "Main page request returned code ${parsed.code}: $errMsg")
                }

                val dramaList = parsed.data?.list
                if (!dramaList.isNullOrEmpty()) {
                    items.addAll(dramaList.mapNotNull { it.toSearchResponse() })
                    val total = parsed.data?.totalCount ?: 0
                    hasNextPage = (page * 20) < total
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getMainPage error: ${e.message}")
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        AnalyticsTracker.heartbeat(name, query)
        val results = mutableListOf<SearchResponse>()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return results

        try {
            // Try with 'keyword' parameter first
            var response = app.get(
                "$mainUrl/api/v1/search/list",
                params = mapOf(
                    "keyword" to trimmed,
                    "page_num" to "1",
                    "page_size" to "20",
                    "platform" to "Android",
                    "version" to APP_VERSION,
                    "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                ),
                headers = getHeaders(),
                timeout = 15L
            )

            var parsed = response.parsedSafe<ToonBoxListResponse>()
            var list = parsed?.data?.list

            // Fallback to 'search_key' if keyword yielded nothing or error
            if (list.isNullOrEmpty()) {
                response = app.get(
                    "$mainUrl/api/v1/search/list",
                    params = mapOf(
                        "search_key" to trimmed,
                        "page_num" to "1",
                        "page_size" to "20",
                        "platform" to "Android",
                        "version" to APP_VERSION,
                        "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                    ),
                    headers = getHeaders(),
                    timeout = 15L
                )
                parsed = response.parsedSafe<ToonBoxListResponse>()
                list = parsed?.data?.list
            }

            if (!list.isNullOrEmpty()) {
                results.addAll(list.mapNotNull { it.toSearchResponse() })
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Search error: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = url.substringAfterLast("drama_id=").substringAfterLast("/").trim()

        val headers = getHeaders()
        val lang = NetShortStorage.getToonBoxLanguage().ifBlank { "en" }

        // 1. Fetch Drama Metadata
        var dramaDetail: ToonBoxDramaDetail? = null
        try {
            val infoResp = app.get(
                "$mainUrl/api/v1/drama/info",
                params = mapOf(
                    "drama_id" to dramaId,
                    "platform" to "Android",
                    "version" to APP_VERSION,
                    "language" to lang
                ),
                headers = headers,
                timeout = 15L
            ).parsedSafe<ToonBoxDramaInfoResponse>()
            dramaDetail = infoResp?.data
        } catch (e: Throwable) {
            Log.d(TAG, "drama/info error: ${e.message}")
        }

        val title = dramaDetail?.dramaName ?: "ToonBox Drama $dramaId"
        val poster = dramaDetail?.coverUrl
        val plot = dramaDetail?.description ?: dramaDetail?.intro
        val tags = dramaDetail?.tagList?.filter { it.isNotBlank() }

        // 2. Fetch Episode List
        val episodes = mutableListOf<Episode>()
        try {
            val videoResp = app.get(
                "$mainUrl/api/v1/drama/video/list",
                params = mapOf(
                    "drama_id" to dramaId,
                    "platform" to "Android",
                    "version" to APP_VERSION,
                    "language" to lang
                ),
                headers = headers,
                timeout = 15L
            ).parsedSafe<ToonBoxVideoListResponse>()

            val videoList = videoResp?.data?.videoList ?: videoResp?.data?.list
            if (!videoList.isNullOrEmpty()) {
                videoList.forEachIndexed { idx, item ->
                    val epNo = item.episode ?: (idx + 1)
                    val epId = item.videoId ?: "$dramaId-$epNo"
                    val epTitle = item.subTitle?.ifBlank { null } ?: "Episode $epNo"
                    val epCover = item.coverUrl ?: poster

                    val linkData = ToonBoxEpisodeLinkData(
                        videoId = epId,
                        dramaId = dramaId,
                        episodeNo = epNo,
                        title = epTitle
                    )

                    episodes.add(
                        newEpisode(linkData.toJson()) {
                            this.name = epTitle
                            this.episode = epNo
                            this.posterUrl = epCover
                        }
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "drama/video/list error: ${e.message}")
        }

        // 3. Fallback episode if empty
        if (episodes.isEmpty()) {
            val fallbackData = ToonBoxEpisodeLinkData(
                videoId = dramaId,
                dramaId = dramaId,
                episodeNo = 1,
                title = "Episode 1"
            )
            episodes.add(
                newEpisode(fallbackData.toJson()) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = "$mainUrl/api/v1/drama/info?drama_id=$dramaId",
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = try {
            parseJson<ToonBoxEpisodeLinkData>(data)
        } catch (_: Throwable) {
            null
        } ?: return false

        var streamFound = false

        try {
            val playResp = app.get(
                "$mainUrl/api/v1/video/get_play_url",
                params = mapOf(
                    "video_id" to linkData.videoId,
                    "drama_id" to linkData.dramaId,
                    "platform" to "Android",
                    "version" to APP_VERSION,
                    "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                ),
                headers = getHeaders(),
                timeout = 15L
            ).parsedSafe<ToonBoxPlayResponse>()

            val playInfo = playResp?.data
            if (playInfo != null) {
                // Subtitles
                playInfo.subtitleList?.forEach { sub ->
                    val subUrl = sub.url
                    if (!subUrl.isNullOrBlank()) {
                        subtitleCallback(
                            SubtitleFile(
                                lang = sub.language ?: "en",
                                url = subUrl
                            )
                        )
                    }
                }

                val directUrl = playInfo.videoUrl
                val sdkVid = playInfo.sdkVid
                val voucher = playInfo.playVoucher

                if (!directUrl.isNullOrBlank() || !sdkVid.isNullOrBlank() || !voucher.isNullOrBlank()) {
                    volcEngineExtractor.resolveStream(
                        videoUrlOrVid = directUrl ?: sdkVid ?: voucher ?: "",
                        sdkVid = sdkVid,
                        playVoucher = voucher,
                        sourceName = name,
                        callback = callback
                    )
                    streamFound = true
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loadLinks error: ${e.message}")
        }

        return streamFound
    }
}
