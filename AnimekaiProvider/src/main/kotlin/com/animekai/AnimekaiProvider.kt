package com.animekai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * AnimeKai CloudStream Provider
 *
 * Target: https://animekai.beer
 * Platform: WordPress 7.1.3 + Zetaflix theme (v1.4.5)
 *
 * Architecture Highlights:
 *  - Search:    GET /?s={query}&post_type=tvshows (HTML .display-item)
 *  - Home:      /anime/, /movies/, /episodes/, /trending/ (paginated)
 *  - Detail:    Lightweight DOM extraction (TMDB high-res posters, backdrops, plot, genres)
 *  - Seasons:   Season 1 inline DOM; Seasons 2+ retrieved in parallel via admin-ajax zt_ajax_season_menu
 *  - Anti-Bot:  Sequential homepage requests + automated WebViewResolver fallback on CF challenge
 *  - Video:     ZetaPlayer REST API (/wp-json/zetaplayer/v2/) resolving all SUB & DUB mirrors
 *  - Telemetry: Integrated AnalyticsTracker for operational metrics
 */
class AnimekaiProvider : MainAPI() {
    override var mainUrl             = "https://animekai.beer"
    override var name                = "AnimeKai"
    override val hasMainPage         = true
    override var lang                = "en"
    override val hasDownloadSupport  = true
    override val supportedTypes      = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    // Rate-limiting mitigation for Cloudflare
    override var sequentialMainPage      = true
    override var sequentialMainPageDelay = 500L

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "AnimeKai"
    }

    private val defaultHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Referer"         to "$mainUrl/",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private val jsonHeaders = mapOf(
        "User-Agent"       to USER_AGENT,
        "Referer"          to "$mainUrl/",
        "Accept"           to "application/json",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // =========================================================================
    // Home Page
    // =========================================================================

    override val mainPage = mainPageOf(
        "$mainUrl/anime/"    to "Anime Series",
        "$mainUrl/movies/"   to "Anime Movies",
        "$mainUrl/episodes/" to "Latest Episodes",
        "$mainUrl/trending/" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        AnalyticsTracker.heartbeat(name)
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val doc = fetchDocument(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = parseDisplayItems(doc, request.name.contains("Movie", ignoreCase = true))
        val hasNext = doc.selectFirst("a.next.page-numbers, .next-page, [rel=next]") != null || items.size >= 20

        return newHomePageResponse(request.name, items, hasNext)
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        AnalyticsTracker.track(name, "search", mapOf("query" to query))
        val url = "$mainUrl/?s=${query.encodeUri()}&post_type=tvshows"
        val doc = fetchDocument(url) ?: return emptyList()
        return parseDisplayItems(doc, null)
    }

    // =========================================================================
    // Load (Detail Page)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        AnalyticsTracker.track(name, "view", mapOf("url" to url))
        val doc = fetchDocument(url) ?: run {
            Log.e(TAG, "Failed to load document for $url")
            return null
        }

        // Title
        val title = doc.selectFirst("h1.entry-title, .f-title h1, h1")
            ?.text()?.trim()
            ?: url.trimEnd('/').substringAfterLast("/").replace("-", " ")

        // Poster: Prefer high-res TMDB original attribute, fallback to src
        val poster = doc.selectFirst("img.mli-thumb, .tvshow-poster img, .poster img")
            ?.let { img ->
                img.attr("data-original").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

        // Backdrop art from hero style background-image
        val backdrop = doc.selectFirst(".splash-bg img, .featured-data")
            ?.let { el ->
                val style = el.attr("style")
                Regex("""url\(([^)]+)\)""").find(style)?.groupValues?.getOrNull(1)?.trim('\'', '"')
                    ?: el.selectFirst("img")?.attr("src")
            } ?: poster

        val plot = doc.selectFirst("div.details-desc p, .tvshow-desc p, .description p")
            ?.text()?.trim()

        val genres = doc.select("span.f-genre a, .genres a").map { it.text().trim() }

        val year = doc.selectFirst("span.f-year, .f-info span.f-year")
            ?.text()?.trim()?.toIntOrNull()

        val rating = doc.selectFirst("span.f-imdb")
            ?.text()?.replace("IMDb:", "")?.trim()?.toDoubleOrNull()

        val isMovie = url.contains("/movies/") || url.contains("/movie/")

        // WP Post ID from data attributes
        val postId = doc.selectFirst(".rating-stars[data-id], [data-itemid]")
            ?.let { it.attr("data-id").ifBlank { it.attr("data-itemid") } }
            ?.takeIf { it.isNotBlank() }
            ?: ""

        // ── MOVIE ────────────────────────────────────────────────────────────
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$postId|movies|$url") {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = plot
                this.year                = year
                this.tags                = genres
                if (rating != null) this.score = Score.from10(rating.toString())
            }
        }

        // ── TV SERIES ─────────────────────────────────────────────────────────
        val seasonLinks = doc.select("ul.seasons-list a.play-ss")
        val nonce       = seasonLinks.firstOrNull()?.attr("data-sec") ?: ""
        val seasonNums  = seasonLinks.mapNotNull { it.attr("data-snum").toIntOrNull() }.distinct()

        // Season 1 episodes (inline in DOM)
        val allEpisodes = mutableListOf<Episode>()
        val season1Eps = parseEpisodeList(doc.select("ul#season-listep-1 li a.play-ep"), url)
        allEpisodes.addAll(season1Eps)

        // Seasons 2+ (fetched in parallel with non-blocking amap)
        val otherSeasons = seasonNums.filter { it > 1 }
        if (otherSeasons.isNotEmpty() && postId.isNotBlank()) {
            val asyncSeasons = otherSeasons.amap { sNum ->
                fetchSeasonEpisodes(postId, sNum, nonce, url)
            }.flatten()
            allEpisodes.addAll(asyncSeasons)
        }

        // Sort episodes chronologically by season and episode number
        allEpisodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        val type = if (genres.any { it.contains("Animation", ignoreCase = true) || it.contains("Anime", ignoreCase = true) }) {
            TvType.Anime
        } else {
            TvType.TvSeries
        }

        return newTvSeriesLoadResponse(title, url, type, allEpisodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = backdrop
            this.plot                = plot
            this.year                = year
            this.tags                = genres
            if (rating != null) this.score = Score.from10(rating.toString())
        }
    }

    // =========================================================================
    // Load Links (Multi-Server Streaming)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        AnalyticsTracker.track(name, "play", mapOf("url" to data.take(100)))

        // Data payload format: "{epPostId}|{type}|{refererUrl}"
        val parts    = data.split("|", limit = 3)
        val epPostId = parts.getOrNull(0)?.trim() ?: return false
        val type     = parts.getOrNull(1)?.trim() ?: "tvep"
        val refUrl   = parts.getOrNull(2)?.trim() ?: mainUrl

        if (epPostId.isBlank()) {
            Log.e(TAG, "loadLinks: missing epPostId from data=$data")
            return false
        }

        // Step 1: Query server list
        val serversUrl = "$mainUrl/wp-json/zetaplayer/v2/$epPostId/$type/default"
        val serversResp = try {
            app.get(serversUrl, headers = jsonHeaders + mapOf("Referer" to refUrl), timeout = 15)
                .let { resp ->
                    val raw = resp.text.stripPhpWarnings()
                    parseJson<PlayerServersResponse>(raw)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get server list for $epPostId: ${e.message}")
            return false
        }

        if (serversResp.status != 1 || serversResp.embed.isNullOrEmpty()) {
            Log.e(TAG, "No servers available for $epPostId (status=${serversResp.status})")
            return false
        }

        // Step 2: Extract all SUB & DUB servers concurrently
        var success = false
        serversResp.embed.amap { server ->
            val serverUrl = "$mainUrl/wp-json/zetaplayer/v2/$epPostId/$type/${server.num}"
            try {
                val embedResp = app.get(
                    serverUrl,
                    headers = jsonHeaders + mapOf("Referer" to refUrl),
                    timeout = 15
                ).let { resp ->
                    val raw = resp.text.stripPhpWarnings()
                    parseJson<PlayerEmbedResponse>(raw)
                }

                if (embedResp.status == 1) {
                    val isDub = server.name.contains("DUB", ignoreCase = true)
                    val label = if (isDub) "DUB" else "SUB"
                    val embedSrc = resolveEmbedUrl(embedResp) ?: return@amap

                    Log.d(TAG, "Extracting server [${server.name}] ($label) -> $embedSrc")

                    val extracted = loadExtractor(
                        embedSrc,
                        refUrl,
                        subtitleCallback,
                        callback
                    )
                    if (extracted) success = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server ${server.num} (${server.name}) resolution failed: ${e.message}")
            }
        }

        return success
    }

    // =========================================================================
    // HTTP & Cloudflare WebView Fallback
    // =========================================================================

    /**
     * Fetches an HTML document with automated fallback to WebViewResolver
     * if a Cloudflare challenge or HTTP 403 block is detected.
     */
    private suspend fun fetchDocument(url: String, referer: String = mainUrl): Document? {
        return try {
            val response = app.get(
                url,
                headers = defaultHeaders + mapOf("Referer" to referer),
                timeout = 25
            )

            // Detect Cloudflare Turnstile / Managed Challenge
            val isBlocked = response.code in listOf(403, 503) ||
                response.text.contains("challenge-platform") ||
                response.text.contains("Just a moment...") ||
                response.text.contains("cf-browser-verification")

            if (isBlocked) {
                Log.w(TAG, "Cloudflare challenge detected on $url; launching WebViewResolver fallback")
                val cfResolver = WebViewResolver(interceptUrl = Regex(""".*animekai\.beer.*"""))
                val cfResp = app.get(
                    url,
                    headers = defaultHeaders + mapOf("Referer" to referer),
                    interceptor = cfResolver,
                    timeout = 35
                )
                cfResp.document
            } else {
                response.document
            }
        } catch (e: Exception) {
            Log.e(TAG, "Document fetch failed for $url: ${e.message}")
            null
        }
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    private suspend fun fetchSeasonEpisodes(
        postId: String,
        seasonNum: Int,
        nonce: String,
        refUrl: String
    ): List<Episode> {
        if (postId.isBlank()) return emptyList()
        return try {
            val resp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action"  to "zt_ajax_season_menu",
                    "tvshow"  to postId,
                    "season"  to seasonNum.toString(),
                    "nonce"   to nonce
                ),
                headers = mapOf(
                    "User-Agent"       to USER_AGENT,
                    "Referer"          to refUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                timeout = 15
            )

            val json = resp.parsedSafe<SeasonMenuResponse>()
            val htmlFrag = json?.episodes ?: return emptyList()

            val fragDoc = Jsoup.parseBodyFragment(htmlFrag)
            parseEpisodeList(fragDoc.select("li a.play-ep"), refUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSeasonEpisodes failed for Season $seasonNum: ${e.message}")
            emptyList()
        }
    }

    private fun parseEpisodeList(elements: org.jsoup.select.Elements, refUrl: String): List<Episode> {
        return elements.mapNotNull { anchor ->
            val epPostId = anchor.attr("data-pid").trim().ifBlank { return@mapNotNull null }
            val epNum    = anchor.attr("data-epid").toIntOrNull()
            val ssNum    = anchor.attr("data-ssid").toIntOrNull() ?: 1
            val type     = anchor.attr("data-type").ifBlank { "tvep" }
            val epTitle  = anchor.selectFirst("span.ep-title")?.text()?.trim()
            val epThumb  = anchor.selectFirst("img")
                ?.let { img ->
                    img.attr("src").ifBlank { null }
                        ?: img.attr("data-src").ifBlank { null }
                }

            newEpisode("$epPostId|$type|$refUrl") {
                this.name      = epTitle
                this.season    = ssNum
                this.episode   = epNum
                this.posterUrl = epThumb
            }
        }
    }

    private fun parseDisplayItems(doc: Document, isMovie: Boolean?): List<SearchResponse> {
        return doc.select("div.display-item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href][data-ptype], a[href*='/anime/'], a[href*='/movies/']")
                ?: return@mapNotNull null
            val href  = fixUrl(anchor.attr("href")).ifBlank { return@mapNotNull null }
            val title = anchor.attr("title").ifBlank { null }
                ?: item.selectFirst("h3, .item-desc-title h3")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = item.selectFirst("img.mli-thumb, img.thumb")?.let { img ->
                img.attr("data-original").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

            val ptype = anchor.attr("data-ptype")
            val forceMovie = isMovie ?: (ptype == "movies" || href.contains("/movies/"))

            if (forceMovie) {
                newAnimeSearchResponse(title, href, TvType.AnimeMovie) {
                    this.posterUrl = poster
                }
            } else {
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
        }
    }

    private fun resolveEmbedUrl(resp: PlayerEmbedResponse): String? {
        val playUrl = resp.playUrl?.trim()
        if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) {
            return playUrl
        }

        val embedUrl = resp.embedUrl?.trim() ?: return null
        if (embedUrl.isBlank()) return null

        if (embedUrl.startsWith("http")) return embedUrl

        if (embedUrl.contains("<iframe", ignoreCase = true)) {
            val fragDoc = Jsoup.parseBodyFragment(embedUrl)
            val src = fragDoc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!src.isNullOrBlank()) {
                return when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/")  -> "$mainUrl$src"
                    else                 -> src
                }
            }
        }

        return null
    }

    private fun String.stripPhpWarnings(): String {
        val jsonStart = this.indexOf('{')
        return if (jsonStart > 0) this.substring(jsonStart) else this
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

// =============================================================================
// Data Classes
// =============================================================================

data class SeasonMenuResponse(
    @JsonProperty("episodes") val episodes: String? = null
)

data class PlayerServersResponse(
    @JsonProperty("pid")     val pid: String?              = null,
    @JsonProperty("status")  val status: Int               = 0,
    @JsonProperty("msg")     val msg: String?              = null,
    @JsonProperty("error")   val error: String?            = null,
    @JsonProperty("mode")    val mode: String?             = null,
    @JsonProperty("type")    val type: String?             = null,
    @JsonProperty("embed")   val embed: List<EmbedServer>?  = null,
    @JsonProperty("default") val default: String?          = null
)

data class EmbedServer(
    @JsonProperty("num")   val num: Int     = 0,
    @JsonProperty("type")  val type: String = "",
    @JsonProperty("title") val title: String = "",
    @JsonProperty("name")  val name: String  = ""
)

data class PlayerEmbedResponse(
    @JsonProperty("pid")       val pid: String?      = null,
    @JsonProperty("status")    val status: Int        = 0,
    @JsonProperty("type")      val type: String?      = null,
    @JsonProperty("embed_url") val embedUrl: String?  = null,
    @JsonProperty("play_url")  val playUrl: String?   = null,
    @JsonProperty("msg")       val msg: String?       = null
)
