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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
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

        doc.select("a[href^=\"/movies/\"], a[href^=\"/tv/\"]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("/page-") || href.contains("/watch/")) return@forEach
            val cleanUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seenUrls.add(cleanUrl)) return@forEach

            val isTv = cleanUrl.contains("/tv/")
            val img = a.selectFirst("img")
            var poster = img?.attr("src") ?: img?.attr("srcset") ?: ""
            if (poster.contains("url=")) {
                poster = poster.substringAfter("url=").substringBefore("&")
                poster = URLDecoder.decode(poster, StandardCharsets.UTF_8.name())
            }

            var rawTitle = a.selectFirst("h2, h3, .font-display")?.text()?.trim()
            if (rawTitle.isNullOrEmpty()) {
                rawTitle = a.text().trim()
            }
            var finalTitle = rawTitle.replace(Regex("""^\d{3,4}p.*?"""), "").trim()
            if (finalTitle.isEmpty()) {
                finalTitle = href.substringAfterLast("/").replace("-", " ").capitalizeWords()
            }

            if (isTv) {
                items.add(newTvSeriesSearchResponse(finalTitle, cleanUrl, TvType.TvSeries) {
                    this.posterUrl = poster.ifEmpty { null }
                })
            } else {
                items.add(newMovieSearchResponse(finalTitle, cleanUrl, TvType.Movie) {
                    this.posterUrl = poster.ifEmpty { null }
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

        doc.select("a[href^=\"/movies/\"], a[href^=\"/tv/\"]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("/page-")) return@forEach
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(fullUrl)) return@forEach

            val isTv = fullUrl.contains("/tv/")
            val img = a.selectFirst("img")
            var poster = img?.attr("src") ?: img?.attr("srcset") ?: ""
            if (poster.contains("url=")) {
                poster = poster.substringAfter("url=").substringBefore("&")
                poster = URLDecoder.decode(poster, StandardCharsets.UTF_8.name())
            }

            var rawTitle = a.selectFirst("h2, h3, .font-display")?.text()?.trim()
            if (rawTitle.isNullOrEmpty()) {
                rawTitle = a.text().trim()
            }
            var finalTitle = rawTitle.replace(Regex("""\d{3,4}p\s+\w+"""), "").trim()
            if (finalTitle.isEmpty()) {
                finalTitle = href.substringAfterLast("/").replace("-", " ").capitalizeWords()
            }

            if (isTv) {
                results.add(newTvSeriesSearchResponse(finalTitle, fullUrl, TvType.TvSeries) {
                    this.posterUrl = poster.ifEmpty { null }
                })
            } else {
                results.add(newMovieSearchResponse(finalTitle, fullUrl, TvType.Movie) {
                    this.posterUrl = poster.ifEmpty { null }
                })
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val doc = Jsoup.parse(html)
        val payload = decodeNextPayload(html)

        val isTv = url.contains("/tv/")
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

        if (isTv) {
            val episodesList = mutableListOf<Episode>()
            val episodeObjects = extractJsonObjects(payload, "episode_number")

            episodeObjects.forEach { epObj ->
                try {
                    val epNum = epObj["episode_number"]?.toString()?.toIntOrNull() ?: 1
                    val sNum = epObj["season_number"]?.toString()?.toIntOrNull() ?: 1
                    val epName = epObj["name"]?.toString() ?: "Episode $epNum"
                    val epOverview = epObj["overview"]?.toString()
                    val stillUrl = epObj["still_url"]?.toString()
                    val epId = epObj["episode_id"]?.toString() ?: epObj["id"]?.toString() ?: ""

                    val epMediaFiles = extractJsonObjects(payload, "url").filter { fileObj ->
                        fileObj["episode_id"]?.toString() == epId ||
                        (fileObj["season_number"]?.toString()?.toIntOrNull() == sNum &&
                         fileObj["episode_number"]?.toString()?.toIntOrNull() == epNum)
                    }

                    val epDataString = mapper.writeValueAsString(epMediaFiles)

                    episodesList.add(newEpisode(epDataString) {
                        this.name = epName
                        this.season = sNum
                        this.episode = epNum
                        this.description = epOverview
                        this.posterUrl = stillUrl
                    })
                } catch (_: Exception) {}
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList.distinctBy { Pair(it.season, it.episode) }) {
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
            val movieFiles = extractJsonObjects(payload, "url").filter { fileObj ->
                val u = fileObj["url"]?.toString() ?: ""
                u.startsWith("http") && (u.contains("ctgfun.com") || u.endsWith(".mp4") || u.endsWith(".mkv"))
            }

            val allFiles = movieFiles.toMutableList()
            if (allFiles.isEmpty()) {
                doc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.contains("ctgfun.com") || href.endsWith(".mp4") || href.endsWith(".mkv")) {
                        allFiles.add(mapOf(
                            "url" to href,
                            "quality" to "HD",
                            "language" to "Hindi/English"
                        ))
                    }
                }
            }

            val movieDataString = mapper.writeValueAsString(allFiles)

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
            val files: List<Map<String, Any?>> = mapper.readValue(data)
            var foundLink = false

            files.forEach { fileObj ->
                val streamUrl = fileObj["url"]?.toString() ?: return@forEach
                if (!streamUrl.startsWith("http")) return@forEach

                val qualityStr = fileObj["quality"]?.toString() ?: "HD"
                val langStr = fileObj["language"]?.toString() ?: ""
                val sourceStr = fileObj["source_display"]?.toString() 
                    ?: fileObj["source"]?.toString() 
                    ?: "CTGMovies"

                val nameLabel = buildString {
                    append("CTGMovies [")
                    append(sourceStr)
                    if (langStr.isNotEmpty()) append(" - $langStr")
                    append("]")
                }

                val qualityVal = getQualityFromString(qualityStr)

                val subsList = fileObj["subtitle_tracks"] as? List<Map<String, Any?>>
                subsList?.forEach { subObj ->
                    val subUrl = subObj["url"]?.toString() ?: return@forEach
                    val subLang = subObj["label"]?.toString() ?: subObj["language"]?.toString() ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(subLang, subUrl))
                }

                callback(
                    newExtractorLink(
                        name = nameLabel,
                        source = nameLabel,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = qualityVal
                    }
                )
                foundLink = true
            }

            return foundLink
        } catch (_: Exception) {
            if (data.startsWith("http")) {
                callback(
                    newExtractorLink(
                        name = "CTGMovies Direct",
                        source = "CTGMovies Direct",
                        url = data,
                        type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            return false
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

    private fun extractJsonObjects(text: String, requiredKey: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val pattern = Regex("""\{[^{}]*?"$requiredKey"\s*:\s*[^}]*?\}""")
        pattern.findAll(text).forEach { m ->
            try {
                val jsonMap: Map<String, Any?> = mapper.readValue(m.value)
                results.add(jsonMap)
            } catch (_: Exception) {}
        }
        return results
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
