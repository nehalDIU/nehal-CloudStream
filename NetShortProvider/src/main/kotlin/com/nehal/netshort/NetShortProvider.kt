package com.nehal.netshort

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.regex.Pattern

class NetShortProvider : MainAPI() {
    override var mainUrl = "https://netshort.com"
    override var name = "NetShort"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries)

    companion object {
        private const val API_BASE = "https://appsecapi.netshort.com/prod-app-api"
        private val mapper by lazy { jacksonObjectMapper() }
        private val volcEngineExtractor by lazy { VolcEngineExtractor() }

        private val DRAMA_REGEX = Pattern.compile(
            """\\\"shortPlayId\\\":\\\"(\d+)\\\",\\\"shortPlayName\\\":\\\"([^\\\"]+)\\\".*?\\\"(?:fullEpisodeNameUrl|shortPlayNameUrl)\\\":\\\"([^\\\"]+)\\\",\\\"shortPlayCover\\\":\\\"([^\\\"]+)\\\""""
        )

        private val EPISODE_REGEX = Pattern.compile(
            """\\\"episodeId\\\":\\\"(\d+)\\\",\\\"episodeNo\\\":(\d+)(?:,\\\"episodeCover\\\":\\\"([^\\\"]+)\\\")?"""
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured & Recommended",
        "$mainUrl/drama/Revenge-1983832092270911490" to "Revenge",
        "$mainUrl/drama/Modern%20Romance-1983832092736479234" to "Modern Romance",
        "$mainUrl/drama/Underdog%20Rise-1984208314792402946" to "Underdog Rise",
        "$mainUrl/drama/Fantasy%20Romance-1983832092417712131" to "Fantasy Romance",
        "$mainUrl/drama/Rich%20Family%20Feud-1983832091889229827" to "Rich Family Feud",
        "$mainUrl/drama/Love%20After%20Divorce-1983832091641765889" to "Love After Divorce",
        "$mainUrl/drama/Contract%20Lovers-1983832091947950083" to "Contract Lovers"
    )

    private fun cleanCover(raw: String): String {
        return raw.replace("""\u0026""", "&")
            .replace("""\u6bd4""", "比")
            .replace("""\/""", "/")
    }

    private fun extractDramasFromHtml(html: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        val matcher = DRAMA_REGEX.matcher(html)

        while (matcher.find()) {
            val id = matcher.group(1) ?: continue
            val title = matcher.group(2) ?: continue
            val linkUrl = matcher.group(3) ?: "/full-episodes/$id"
            val rawCover = matcher.group(4) ?: ""

            if (!seen.add(id)) continue

            val fullUrl = if (linkUrl.startsWith("http")) linkUrl else "$mainUrl/en$linkUrl"
            val cover = cleanCover(rawCover)

            list.add(
                newTvSeriesSearchResponse(
                    name = title.trim(),
                    url = fullUrl,
                    type = TvType.AsianDrama
                ) {
                    this.posterUrl = cover.ifBlank { null }
                }
            )
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        AnalyticsTracker.heartbeat(name)
        val dramas = mutableListOf<SearchResponse>()

        try {
            val targetUrl = request.data
            val res = app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                timeout = 20L
            )

            dramas.addAll(extractDramasFromHtml(res.text))
        } catch (e: Throwable) {
            Log.e("NetShort", "Failed to load main page: ${e.message}")
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = dramas,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        AnalyticsTracker.heartbeat(name, query)
        val results = mutableListOf<SearchResponse>()

        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$mainUrl/search?keyword=$encodedQuery"
            val res = app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                timeout = 20L
            )

            results.addAll(extractDramasFromHtml(res.text))
        } catch (e: Throwable) {
            Log.e("NetShort", "Search failed: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val targetUrl = when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/full-episodes/$url"
        }

        val res = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 20L
        )
        val html = res.text

        // Extract Title
        val titleMatcher = Pattern.compile("""\\\"shortPlayName\\\":\\\"([^\\\"]+)\\\"""").matcher(html)
        val title = if (titleMatcher.find()) titleMatcher.group(1) else "Short Drama"

        // Extract ID
        val idMatcher = Pattern.compile("""\\\"shortPlayId\\\":\\\"(\d+)\\\"""").matcher(html)
        val shortPlayId = if (idMatcher.find()) idMatcher.group(1) else targetUrl.substringAfterLast("-").substringAfterLast("/")

        // Extract Plot
        val plotMatcher = Pattern.compile("""\\\"shotIntroduce\\\":\\\"([^\\\"]+)\\\"""").matcher(html)
        val plot = if (plotMatcher.find()) plotMatcher.group(1) else null

        // Extract Cover
        val coverMatcher = Pattern.compile("""\\\"shortPlayCover\\\":\\\"([^\\\"]+)\\\"""").matcher(html)
        val cover = if (coverMatcher.find()) cleanCover(coverMatcher.group(1)) else null

        // Extract Labels / Tags
        val tags = mutableListOf<String>()
        val tagMatcher = Pattern.compile("""\\\"labelName\\\":\\\"([^\\\"]+)\\\"""").matcher(html)
        while (tagMatcher.find()) {
            val tag = tagMatcher.group(1)
            if (!tag.isNullOrBlank() && !tags.contains(tag)) {
                tags.add(tag)
            }
        }

        // Extract Episodes
        val episodes = mutableListOf<Episode>()
        val epMatcher = EPISODE_REGEX.matcher(html)
        val seenEp = HashSet<Int>()

        while (epMatcher.find()) {
            val epId = epMatcher.group(1) ?: continue
            val epNo = epMatcher.group(2)?.toIntOrNull() ?: continue
            val epCoverRaw = epMatcher.group(3)
            val epCover = if (!epCoverRaw.isNullOrBlank()) cleanCover(epCoverRaw) else cover

            if (!seenEp.add(epNo)) continue

            val linkData = EpisodeStreamLinkData(
                shortPlayId = shortPlayId,
                episodeId = epId,
                episodeNo = epNo,
                isLock = false
            )

            episodes.add(
                newEpisode(linkData.toJson()) {
                    this.name = "Episode $epNo"
                    this.episode = epNo
                    this.posterUrl = epCover
                }
            )
        }

        // If no episodes were found via regex (fallback), create Episode 1
        if (episodes.isEmpty()) {
            val linkData = EpisodeStreamLinkData(
                shortPlayId = shortPlayId,
                episodeId = shortPlayId,
                episodeNo = 1,
                isLock = false
            )
            episodes.add(
                newEpisode(linkData.toJson()) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.posterUrl = cover
                }
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = targetUrl,
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            this.posterUrl = cover
            this.backgroundPosterUrl = cover
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
            parseJson<EpisodeStreamLinkData>(data)
        } catch (_: Throwable) {
            null
        } ?: return false

        // 1. Get automated visitor session token (zero config!)
        val token = NetShortAuth.getValidToken()
        if (token.isNullOrBlank()) {
            Log.e("NetShort", "Failed to obtain guest session token for video resolution")
            return false
        }

        // Try episodeType = 1 first (unlocked ad-free / direct stream), then fallback to 0 or 2
        val candidateTypes = listOf(1, 0, 2)
        var streamFound = false

        for (epType in candidateTypes) {
            val payload = mapOf(
                "shortPlayId" to linkData.shortPlayId,
                "episodeId" to linkData.episodeId,
                "episodeNo" to linkData.episodeNo,
                "episodeType" to epType,
                "codec" to "h264",
                "playClarity" to "720p"
            )

            try {
                val jsonBody = mapper.writeValueAsString(payload)
                val (encKey, encBody) = NetShortCrypto.encryptPayload(jsonBody)

                val headers = mapOf(
                    "Content-Type" to "application/json;charset=utf-8",
                    "User-Agent" to "okhttp/4.9.0",
                    "content-language" to (NetShortStorage.getLanguage().ifBlank { "en_US" }),
                    "Authorization" to "Bearer $token",
                    "encrypt-key" to encKey,
                    "timestamp" to System.currentTimeMillis().toString(),
                    "start_type" to "0",
                    "push_switch" to "1",
                    "network" to "WIFI,0,1"
                )

                val mediaType = "application/json;charset=utf-8".toMediaType()
                val response = app.post(
                    "$API_BASE/video/shortPlay/base/episode/detail_info",
                    headers = headers,
                    requestBody = encBody.toRequestBody(mediaType),
                    timeout = 15L
                )

                val respKey = response.headers["encrypt-key"]
                val decryptedJson = NetShortCrypto.decryptPayload(response.text, respKey)
                val parsed = parseJson<NetShortApiResponse<VideoEpisodeResponse>>(decryptedJson)

                val epList = parsed.data?.episodeList
                if (!epList.isNullOrEmpty()) {
                    // Match either current episode or take first available
                    val matchingEp = epList.find { it.episodeId == linkData.episodeId || it.episodeNo == linkData.episodeNo }
                        ?: epList.first()

                    // Handle Subtitles
                    matchingEp.subtitleList?.forEach { sub ->
                        val subUrl = sub.url
                        if (!subUrl.isNullOrBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = sub.subtitleLanguage ?: "en",
                                    url = subUrl
                                )
                            )
                        }
                    }

                    val playVoucher = matchingEp.playVoucher ?: matchingEp.playVoucherBak
                    val sdkVid = matchingEp.sdkVid

                    if (!playVoucher.isNullOrBlank() || !sdkVid.isNullOrBlank()) {
                        volcEngineExtractor.resolveStream(
                            videoUrlOrVid = playVoucher ?: sdkVid ?: "",
                            sdkVid = sdkVid,
                            playVoucher = playVoucher,
                            callback = callback
                        )
                        streamFound = true
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.d("NetShort", "Attempt with episodeType=$epType notice: ${e.message}")
            }
        }

        return streamFound
    }
}

