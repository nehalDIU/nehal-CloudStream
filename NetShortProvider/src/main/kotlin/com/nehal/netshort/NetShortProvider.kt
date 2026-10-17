package com.nehal.netshort

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        "$mainUrl/en" to "Featured & Recommended",
        "$mainUrl/en/drama/Revenge-1983832092270911490" to "Revenge",
        "$mainUrl/en/drama/Modern%20Romance-1983832092736479234" to "Modern Romance",
        "$mainUrl/en/drama/Underdog%20Rise-1984208314792402946" to "Underdog Rise",
        "$mainUrl/en/drama/Fantasy%20Romance-1983832092417712131" to "Fantasy Romance",
        "$mainUrl/en/drama/Rich%20Family%20Feud-1983832091889229827" to "Rich Family Feud",
        "$mainUrl/en/drama/Love%20After%20Divorce-1983832091641765889" to "Love After Divorce",
        "$mainUrl/en/drama/Contract%20Lovers-1983832091947950083" to "Contract Lovers"
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
            val searchUrl = "$mainUrl/en/search?keyword=$encodedQuery"
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
            url.startsWith("/") -> "$mainUrl/en$url"
            else -> "$mainUrl/en/full-episodes/$url"
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

        val customToken = NetShortStorage.getCustomToken()

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "okhttp/4.9.0",
            "language" to (NetShortStorage.getLanguage().ifBlank { lang }),
            "Accept" to "application/json"
        )
        if (!customToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $customToken"
        }

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
                "$API_BASE/video/shortPlay/base/episode/detail_info",
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

        if (!directUrl.isNullOrBlank()) {
            volcEngineExtractor.resolveStream(directUrl, sdkVid, playVoucher, callback)
            return true
        }

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
