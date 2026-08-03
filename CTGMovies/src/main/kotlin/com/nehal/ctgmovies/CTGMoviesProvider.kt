package com.nehal.ctgmovies

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CTGMoviesProvider : MainAPI() {
    override var name = "CTGMovies"
    override var mainUrl = "https://ctgmovies.com"
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Others
    )
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "" to "Featured",
        "/movies?collection=6a0cf22a21249bf80a0464ff&collectionName=English%20Movies" to "English Movies",
        "/movies?collection=6a0c75de4d24c52d35da61fb&collectionName=Hindi%20Movies" to "Hindi Movies",
        "/movies?collection=6a0c75de4d24c52d35da61fa&collectionName=South%20Indian" to "South Indian Movies",
        "/movies?collection=6a0c75de4d24c52d35da61fc&collectionName=Asian" to "Asian Movies",
        "/movies?collection=6a0cf22a21249bf80a046500&collectionName=European" to "European Movies",
        "/tv" to "TV Shows",
        "/anime" to "Anime"
    )

    private val mapper = jacksonObjectMapper().apply {
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val targetUrl = if (request.data.isEmpty()) {
            if (page > 1) return null
            mainUrl
        } else {
            val pageParam = if (page > 1) "&page=$page" else ""
            "$mainUrl${request.data}$pageParam"
        }

        val res = app.get(targetUrl).text
        val doc = Jsoup.parse(res)

        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        doc.select("a[href^=\"/movies/\"], a[href^=\"/tv/\"], a[href^=\"/anime/\"]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("/page-") || href.contains("/watch/")) return@forEach
            val cleanUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seenUrls.add(cleanUrl)) return@forEach

            val cardText = a.text().trim()
            if (cardText == "Watch Now" || cardText == "Details" || cardText == "Play") return@forEach

            val isAnimeSection = request.data == "/anime" || cleanUrl.contains("/anime/")
            val isTv = cleanUrl.contains("/tv/")
            val img = a.selectFirst("img")
            var poster = img?.attr("src") ?: img?.attr("srcset") ?: ""
            if (poster.contains("url=")) {
                poster = poster.substringAfter("url=").substringBefore("&")
                poster = URLDecoder.decode(poster, StandardCharsets.UTF_8.name())
            }

            val qualityMatch = Regex("""\b(4K|2160p|1080p|720p|480p|HDTS|CAM|WEBRip|WebRip|WEB-DL|BluRay)\b""", RegexOption.IGNORE_CASE).find(cardText)
            val qualityStr = qualityMatch?.value

            val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(cardText)
            val yearVal = yearMatch?.value?.toIntOrNull()

            var rawTitle = a.selectFirst("h2, h3, .font-display")?.text()?.trim()
            if (rawTitle.isNullOrEmpty()) {
                rawTitle = cardText
            }
            var finalTitle = rawTitle.replace(Regex("""^\d{3,4}p.*?\s"""), "").trim()
            finalTitle = finalTitle.replace(Regex("""\b(4K|2160p|1080p|720p|WEBRip|WebRip|HDTS|BluRay|WEB-DL)\b""", RegexOption.IGNORE_CASE), "").trim()
            finalTitle = finalTitle.replace(Regex("""\b(19\d{2}|20\d{2})\b$"""), "").trim()
            if (finalTitle.isEmpty()) {
                finalTitle = href.substringAfterLast("/").replace("-", " ").capitalizeWords()
            }

            val tvType = when {
                isAnimeSection -> TvType.Anime
                isTv -> TvType.TvSeries
                else -> TvType.Movie
            }

            if (tvType == TvType.Anime) {
                items.add(newAnimeSearchResponse(finalTitle, cleanUrl, TvType.Anime) {
                    this.posterUrl = poster.ifEmpty { null }
                    if (yearVal != null) this.year = yearVal
                    if (!qualityStr.isNullOrEmpty()) this.addQuality(qualityStr)
                })
            } else if (tvType == TvType.TvSeries) {
                items.add(newTvSeriesSearchResponse(finalTitle, cleanUrl, TvType.TvSeries) {
                    this.posterUrl = poster.ifEmpty { null }
                    if (yearVal != null) this.year = yearVal
                    if (!qualityStr.isNullOrEmpty()) this.addQuality(qualityStr)
                })
            } else {
                items.add(newMovieSearchResponse(finalTitle, cleanUrl, TvType.Movie) {
                    this.posterUrl = poster.ifEmpty { null }
                    if (yearVal != null) this.year = yearVal
                    if (!qualityStr.isNullOrEmpty()) this.addQuality(qualityStr)
                })
            }
        }

        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}"
        val res = app.get(searchUrl).text
        val doc = Jsoup.parse(res)

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        doc.select("a[href^=\"/movies/\"], a[href^=\"/tv/\"], a[href^=\"/anime/\"]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("/page-")) return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(fullUrl)) return@forEach

            val cardText = a.text().trim()
            if (cardText == "Watch Now" || cardText == "Details" || cardText == "Play") return@forEach

            val isTv = fullUrl.contains("/tv/")
            val isAnime = fullUrl.contains("/anime/")
            val img = a.selectFirst("img")
            var poster = img?.attr("src") ?: img?.attr("srcset") ?: ""
            if (poster.contains("url=")) {
                poster = poster.substringAfter("url=").substringBefore("&")
                poster = URLDecoder.decode(poster, StandardCharsets.UTF_8.name())
            }

            val qualityMatch = Regex("""\b(4K|2160p|1080p|720p|480p|HDTS|CAM|WEBRip|WebRip|WEB-DL|BluRay)\b""", RegexOption.IGNORE_CASE).find(cardText)
            val qualityStr = qualityMatch?.value

            val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(cardText)
            val yearVal = yearMatch?.value?.toIntOrNull()

            var rawTitle = a.selectFirst("h2, h3, .font-display")?.text()?.trim()
            if (rawTitle.isNullOrEmpty()) {
                rawTitle = cardText
            }
            var finalTitle = rawTitle.replace(Regex("""\d{3,4}p\s+\w+"""), "").trim()
            finalTitle = finalTitle.replace(Regex("""\b(4K|2160p|1080p|720p|WEBRip|WebRip|HDTS|BluRay|WEB-DL)\b""", RegexOption.IGNORE_CASE), "").trim()
            finalTitle = finalTitle.replace(Regex("""\b(19\d{2}|20\d{2})\b$"""), "").trim()
            if (finalTitle.isEmpty()) {
                finalTitle = href.substringAfterLast("/").replace("-", " ").capitalizeWords()
            }

            val tvType = when {
                isAnime -> TvType.Anime
                isTv -> TvType.TvSeries
                else -> TvType.Movie
            }

            if (tvType == TvType.Anime) {
                results.add(newAnimeSearchResponse(finalTitle, fullUrl, TvType.Anime) {
                    this.posterUrl = poster.ifEmpty { null }
                    if (yearVal != null) this.year = yearVal
                    if (!qualityStr.isNullOrEmpty()) this.addQuality(qualityStr)
                })
            } else if (tvType == TvType.TvSeries) {
                results.add(newTvSeriesSearchResponse(finalTitle, fullUrl, TvType.TvSeries) {
                    this.posterUrl = poster.ifEmpty { null }
                    if (yearVal != null) this.year = yearVal
                    if (!qualityStr.isNullOrEmpty()) this.addQuality(qualityStr)
                })
            } else {
                results.add(newMovieSearchResponse(finalTitle, fullUrl, TvType.Movie) {
                    this.posterUrl = poster.ifEmpty { null }
                    if (yearVal != null) this.year = yearVal
                    if (!qualityStr.isNullOrEmpty()) this.addQuality(qualityStr)
                })
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val doc = Jsoup.parse(html)
        val payload = decodeNextPayload(html)

        val isTvOrAnime = url.contains("/tv/") || url.contains("/anime/") || payload.contains("\"episodes\":[")
        val title = doc.selectFirst("h1, h2")?.text()?.trim() 
            ?: url.substringAfterLast("/").replace("-", " ").capitalizeWords()

        var poster: String? = null
        val posterMatch = Regex(""""(?:poster_path|poster_url)"\s*:\s*"([^"]+)"""").find(payload)
        if (posterMatch != null) {
            poster = posterMatch.groupValues[1]
            if (poster.startsWith("/")) {
                poster = "https://image.tmdb.org/t/p/w500$poster"
            }
        }
        if (poster.isNullOrEmpty()) {
            val img = doc.selectFirst("img[src*=\"tmdb.org\"]")
            poster = img?.attr("src")
        }

        var backdrop: String? = null
        val backdropMatch = Regex(""""(?:backdrop_path|backdrop_url)"\s*:\s*"([^"]+)"""").find(payload)
        if (backdropMatch != null) {
            backdrop = backdropMatch.groupValues[1]
            if (backdrop.startsWith("/")) {
                backdrop = "https://image.tmdb.org/t/p/w1280$backdrop"
            }
        }

        val overviewMatch = Regex(""""(?:overview)"\s*:\s*"([^"]+)"""").find(payload)
        val plot = overviewMatch?.groupValues?.get(1) ?: doc.selectFirst("p")?.text()?.trim()

        val yearMatch = Regex(""""(?:release_date|first_air_date)"\s*:\s*"(\d{4})""").find(payload)
            ?: Regex(""""(?:year)"\s*:\s*(\d{4})""").find(payload)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val ratingMatch = Regex(""""(?:vote_average|rating)"\s*:\s*(\d+(?:\.\d+)?)""").find(payload)
        val rating = ratingMatch?.groupValues?.get(1)?.toFloatOrNull()

        val subtitleTracks = extractSubtitles(payload, html)
        val allVideoUrls = extractVideoUrls(payload, html).distinct()

        if (isTvOrAnime) {
            val episodesList = mutableListOf<Episode>()
            val episodeObjects = extractJsonArray(payload, "episodes")

            episodeObjects.forEach { epObj ->
                try {
                    val epNum = epObj["episode_number"]?.toString()?.toIntOrNull() ?: 1
                    val sNum = epObj["season_number"]?.toString()?.toIntOrNull() ?: 1
                    val epName = epObj["name"]?.toString() ?: "Episode $epNum"
                    val epOverview = epObj["overview"]?.toString()
                    val stillUrl = epObj["still_url"]?.toString()

                    val p1 = Regex("""s0*${sNum}e0*${epNum}(?!\d)""", RegexOption.IGNORE_CASE)
                    val p2 = Regex("""[._\-\s/]e0*${epNum}(?!\d)""", RegexOption.IGNORE_CASE)

                    val epVideoUrls = allVideoUrls.filter { vUrl ->
                        val uLower = vUrl.lowercase()
                        p1.containsMatchIn(uLower) || p2.containsMatchIn(uLower)
                    }.distinct()

                    val epDataMap = mapOf(
                        "video_urls" to epVideoUrls,
                        "subtitles" to subtitleTracks
                    )
                    val epDataString = mapper.writeValueAsString(epDataMap)

                    episodesList.add(newEpisode(epDataString) {
                        this.name = epName
                        this.season = sNum
                        this.episode = epNum
                        this.description = epOverview
                        this.posterUrl = stillUrl
                    })
                } catch (_: Exception) {}
            }

            val mainType = if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries

            return newTvSeriesLoadResponse(title, url, mainType, episodesList.distinctBy { Pair(it.season, it.episode) }) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                if (rating != null) {
                    this.score = Score.from10(rating)
                }
            }
        } else {
            // Movie
            val movieDataMap = mapOf(
                "video_urls" to allVideoUrls,
                "subtitles" to subtitleTracks
            )
            val movieDataString = mapper.writeValueAsString(movieDataMap)

            return newMovieLoadResponse(title, url, TvType.Movie, movieDataString) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                if (rating != null) {
                    this.score = Score.from10(rating)
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
        if (data.isEmpty()) return false

        try {
            var foundLink = false

            if (data.startsWith("{")) {
                val mapData: Map<String, Any?> = mapper.readValue(data)

                // Subtitles
                val subsList = mapData["subtitles"] as? List<Map<String, String>>
                subsList?.forEach { sub ->
                    val sUrl = sub["url"] ?: return@forEach
                    val sLang = sub["language"] ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(sLang, sUrl))
                }

                // Video Streams
                val vUrls = mapData["video_urls"] as? List<String>
                vUrls?.forEach { videoUrl ->
                    if (emitFastVideoLink(videoUrl, callback)) {
                        foundLink = true
                    }
                }
            } else if (data.startsWith("http")) {
                foundLink = emitFastVideoLink(data, callback)
            }

            return foundLink
        } catch (_: Exception) {
            if (data.startsWith("http")) {
                return emitFastVideoLink(data, callback)
            }
            return false
        }
    }

    private suspend fun emitFastVideoLink(
        videoUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uLower = videoUrl.lowercase()
        if (isAudioOrSubtitleUrl(uLower)) return false

        val qualityVal = getQualityFromString(videoUrl)
        val serverName = extractServerName(videoUrl)
        val detectedLangs = extractLanguagesFromUrl(videoUrl)

        val mainLabel = buildString {
            append("CTGMovies [$serverName")
            if (detectedLangs.isNotEmpty()) {
                append(" - ")
                append(detectedLangs)
            }
            append("]")
        }

        callback(
            newExtractorLink(
                name = mainLabel,
                source = mainLabel,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = qualityVal
            }
        )

        return true
    }

    private fun extractVideoUrls(payload: String, html: String): List<String> {
        val rawUrls = mutableSetOf<String>()
        val pattern = Regex("""https?://[^\s"'\\]+""")
        
        pattern.findAll(payload).forEach { rawUrls.add(it.value) }
        pattern.findAll(html).forEach { rawUrls.add(it.value) }

        val videoUrls = mutableListOf<String>()

        rawUrls.forEach { u ->
            val uLower = u.lowercase()
            if (isAudioOrSubtitleUrl(uLower)) return@forEach

            if (uLower.endsWith(".mp4") || uLower.endsWith(".mkv") || uLower.endsWith(".m3u8") || uLower.contains("ctgfun.com")) {
                if (uLower.contains("/.hls/") && !uLower.endsWith(".m3u8") && !uLower.endsWith(".mp4") && !uLower.endsWith(".mkv")) {
                    return@forEach
                }
                videoUrls.add(u)
            }
        }

        return videoUrls
    }

    private fun extractSubtitles(payload: String, html: String): List<Map<String, String>> {
        val subs = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()
        val pattern = Regex("""https?://[^\s"'\\]+""")

        fun checkSub(u: String) {
            val uLower = u.lowercase()
            if ((uLower.endsWith(".vtt") || uLower.endsWith(".srt")) && seen.add(u)) {
                val lang = if (uLower.contains(".eng.")) "English" else if (uLower.contains(".hin.")) "Hindi" else "Subtitle"
                subs.add(mapOf("url" to u, "language" to lang))
            }
        }

        pattern.findAll(payload).forEach { checkSub(it.value) }
        pattern.findAll(html).forEach { checkSub(it.value) }

        return subs
    }

    private fun isAudioOrSubtitleUrl(uLower: String): Boolean {
        if (uLower.contains(".audio.") || uLower.contains("/audio.") || uLower.contains(".audio/")) return true
        if (uLower.endsWith(".m4a") || uLower.endsWith(".aac") || uLower.endsWith(".mp3") || uLower.endsWith(".ogg") || uLower.endsWith(".wav")) return true
        if (uLower.endsWith(".vtt") || uLower.endsWith(".srt")) return true
        return false
    }

    private fun extractLanguagesFromUrl(url: String): String {
        val decoded = try { URLDecoder.decode(url, StandardCharsets.UTF_8.name()) } catch (_: Exception) { url }
        val uLower = decoded.lowercase()
        val langs = mutableListOf<String>()

        if (uLower.contains("hindi") || uLower.contains("hin")) langs.add("Hindi")
        if (uLower.contains("english") || uLower.contains("eng")) langs.add("English")
        if (uLower.contains("bangla") || uLower.contains("bengali") || uLower.contains("ben")) langs.add("Bangla")
        if (uLower.contains("tamil")) langs.add("Tamil")
        if (uLower.contains("telugu")) langs.add("Telugu")

        return langs.joinToString(" + ")
    }

    private fun extractServerName(url: String): String {
        return when {
            url.contains("movie.ctgfun.com") -> "Movie Server"
            url.contains("ftp.ctgfun.com") -> "FTP Server"
            url.contains("data.ctgfun.com") -> "Data Server"
            else -> "Server"
        }
    }

    private fun decodeNextPayload(html: String): String {
        val regex = Regex("""self\.__next_f\.push\(\[1,\s*"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        regex.findAll(html).forEach { match ->
            val inner = match.groupValues[1]
            try {
                val unescaped = mapper.readValue<String>("\"$inner\"")
                sb.append(unescaped)
            } catch (_: Exception) {
                sb.append(inner)
            }
        }
        return sb.toString()
    }

    private fun extractJsonArray(text: String, key: String): List<Map<String, Any?>> {
        val pattern = Regex("""""$key"\s*:\s*\[""")
        val match = pattern.find(text) ?: return emptyList()
        val startIdx = match.range.last

        var depth = 0
        var inString = false
        var escape = false

        for (i in startIdx until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '[') {
                    depth++
                } else if (c == ']') {
                    depth--
                    if (depth == 0) {
                        val arrayStr = text.substring(startIdx, i + 1)
                        return try {
                            mapper.readValue(arrayStr)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            }
        }
        return emptyList()
    }

    private fun getQualityFromString(q: String): Int {
        return when {
            q.contains("4K", true) || q.contains("2160", true) -> Qualities.P2160.value
            q.contains("1080", true) -> Qualities.P1080.value
            q.contains("720", true) -> Qualities.P720.value
            q.contains("480", true) -> Qualities.P480.value
            q.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }
}
