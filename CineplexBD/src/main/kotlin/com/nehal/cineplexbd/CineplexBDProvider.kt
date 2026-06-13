package com.nehal.cineplexbd

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

open class CineplexBDProvider : MainAPI() {
    override var name = "CineplexBD"
    override var mainUrl = "http://cineplexbd.net"
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "trending" to "Trending: Weekly Top 20",
        "latest_movies" to "Latest Movies",
        "latest_series" to "Latest TV Series",
        "cat:English" to "Movies: English",
        "cat:Bangla+Movies" to "Movies: Bangla",
        "cat:Hindi" to "Movies: Hindi",
        "cat:Animation" to "Movies: Animation",
        "tcat:English+Series" to "Series: English",
        "tcat:Bangla+Series" to "Series: Bangla",
        "tcat:Hindi+Series" to "Series: Hindi",
        "tcat:Web+Series" to "Series: Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = when {
            request.data == "trending" || request.data == "latest_movies" || request.data == "latest_series" -> {
                if (page > 1) return null
                "$mainUrl/index.php"
            }
            request.data.startsWith("cat:") -> {
                val category = request.data.removePrefix("cat:")
                "$mainUrl/category.php?category=$category&page=$page"
            }
            request.data.startsWith("tcat:") -> {
                val category = request.data.removePrefix("tcat:")
                "$mainUrl/tcategory.php?category=$category&page=$page"
            }
            else -> return null
        }

        val doc = app.get(url).document

        val items = when {
            request.data == "trending" -> {
                doc.select("#carouselWrapper a[href]").mapNotNull { el ->
                    val href = el.attr("href")
                    val isTv = href.contains("watch.php")
                    el.toSearchResult(isTv)
                }
            }
            request.data == "latest_series" -> {
                doc.select("#tvRow a[href]").mapNotNull { el ->
                    el.toSearchResult(true)
                }
            }
            request.data == "latest_movies" -> {
                doc.select("a.group[href*=\"view.php\"]").mapNotNull { el ->
                    el.toSearchResult(false)
                }
            }
            request.data.startsWith("cat:") -> {
                doc.select("a.group[href*=\"view.php\"]").mapNotNull { el ->
                    el.toSearchResult(false)
                }
            }
            request.data.startsWith("tcat:") -> {
                doc.select("a.group[href*=\"watch.php\"]").mapNotNull { el ->
                    el.toSearchResult(true)
                }
            }
            else -> emptyList()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/search.php?q=$query"
        val doc = app.get(url).document
        return doc.select("a.group").mapNotNull { el ->
            val href = el.attr("href")
            val isTv = href.contains("watch.php")
            el.toSearchResult(isTv)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("(?:id|series_id)=(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        return if (url.contains("watch.php")) {
            loadTvSeries(url, id)
        } else {
            loadMovie(url, id)
        }
    }

    private suspend fun loadMovie(url: String, id: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().substringBefore("(").trim()
        val poster = fixUrlNull(doc.selectFirst("img[alt=\"Poster\"]")?.attr("src") ?: doc.selectFirst("img")?.attr("src"))
        
        val chips = doc.select(".chip").map { it.text().trim() }
        val year = chips.mapNotNull { it.toIntOrNull() }.firstOrNull() ?: Regex("\\b(19|20)\\d{2}\\b").find(doc.title())?.value?.toIntOrNull()
        val genres = chips.filter { !it.matches(Regex("\\d{4}")) && !it.contains(Regex("\\d+h|\\d+m")) }
        
        val plot = doc.select("p.text-slate-100").text().trim()
        val rating = doc.select(".pill").firstOrNull { it.text().contains("★") }?.text()?.removePrefix("★")?.trim()

        val playerUrl = "$mainUrl/player.php?id=$id"

        return newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    private suspend fun loadTvSeries(url: String, id: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().substringBefore("— Watch").trim()
        val poster = fixUrlNull(doc.selectFirst("img[alt=\"Poster\"]")?.attr("src") ?: doc.selectFirst("img")?.attr("src"))

        val seasonOptions = doc.select("select[name=\"season\"] option").map { it.attr("value") }.filter { it != "Show All" }
        val seasons = if (seasonOptions.isEmpty()) listOf("1") else seasonOptions

        val episodes = ArrayList<Episode>()
        var synopsis: String? = null
        var rating: String? = null

        seasons.forEach { seasonNum ->
            try {
                val metaUrl = "$mainUrl/watch.php?id=$id&season=$seasonNum&meta=1"
                val metaJson = app.get(metaUrl).text
                val metaResponse = mapper.readValue<TvMetaResponse>(metaJson)
                if (synopsis == null) synopsis = metaResponse.synopsis
                if (rating == null) rating = metaResponse.rating
                
                metaResponse.episodes?.values?.forEach { ep ->
                    episodes.add(
                        newEpisode(ep.path ?: "") {
                            this.name = ep.title?.substringBefore(".mp4")?.substringBefore(".mkv")?.trim()
                            this.episode = ep.episode_number
                            this.season = seasonNum.toIntOrNull()
                            this.posterUrl = ep.still
                        }
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        val chips = doc.select(".chip").map { it.text().trim() }
        val year = chips.mapNotNull { it.toIntOrNull() }.firstOrNull() ?: Regex("\\b(19|20)\\d{2}\\b").find(doc.title())?.value?.toIntOrNull()
        val genres = chips.filter { !it.matches(Regex("\\d{4}")) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis ?: doc.select("p.text-slate-100").text().trim()
            this.tags = genres
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http") && (data.contains(".mp4") || data.contains(".mkv") || data.contains(".m3u8"))) {
            val type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    name = "Direct Stream",
                    source = this.name,
                    url = data,
                    type = type
                )
            )
            return true
        }

        if (data.contains("player.php")) {
            val doc = app.get(data).document
            val html = doc.html()
            val videoSrc = Regex("videoSrc\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (!videoSrc.isNullOrBlank()) {
                val type = if (videoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name = "Player Stream",
                        source = this.name,
                        url = videoSrc,
                        type = type
                    )
                )
            }

            // Extract subtitles
            doc.select("track[kind=\"captions\"]").forEach { track ->
                val label = track.attr("label").takeIf { it.isNotBlank() } ?: "English"
                val src = track.attr("src")
                if (src.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(label, fixUrl(src))
                    )
                }
            }
            return true
        }

        return false
    }

    private fun Element.toSearchResult(isTvSeries: Boolean): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        val fixHref = fixUrl(href)
        var title = this.selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank() || title.equals("poster", ignoreCase = true)) {
            title = this.selectFirst("p")?.text()?.trim() 
                ?: this.text().trim().split("\n").lastOrNull()?.trim()
                ?: this.text().trim()
        }
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        
        val text = this.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(text)?.value?.toIntOrNull()

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fixHref, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> mainUrl.trimEnd('/') + "/" + url
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    private val mapper = jacksonObjectMapper().configure(
        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        false
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TvMetaResponse(
        val rating: String? = null,
        val ratingSrc: String? = null,
        val synopsis: String? = null,
        val country: String? = null,
        val cast: List<CastItem>? = null,
        val episodes: Map<String, TvMetaEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CastItem(
        val name: String? = null,
        val profile: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TvMetaEpisode(
        val id: Long? = null,
        val episode_number: Int? = null,
        val title: String? = null,
        val path: String? = null,
        val still: String? = null
    )
}
