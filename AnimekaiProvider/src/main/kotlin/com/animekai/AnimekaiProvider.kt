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
 * Features:
 *  - URL Normalization: Converts /episodes/... URLs to proper /anime/{slug}/ series pages
 *  - Sub & Dub Dropdown: newAnimeLoadResponse with addEpisodes(DubStatus.Subbed/Dubbed)
 *  - Multi-Season: Inline Season 1 + parallel asynchronous admin-ajax fetching for Seasons 2+
 *  - Poster Tags: addDubStatus(dubExist=true, subExist=true) renders SUB & DUB badges on posters
 *  - Cloudflare Protection: Automated WebViewResolver fallback when CF challenge/403 is encountered
 *  - Multi-Server Streaming: Queries ZetaPlayer mirrors, prioritizing the user's selected audio mode
 *  - Telemetry: Integrated AnalyticsTracker
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
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/ratings/"  to "Top Rated"
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

        // Auto-normalize episode permalink (/episodes/slug-2x3/) to main anime series page (/anime/slug/)
        val targetUrl = normalizeAnimeUrl(url)

        val doc = fetchDocument(targetUrl) ?: run {
            Log.e(TAG, "Failed to load document for $targetUrl")
            return null
        }

        val title = doc.selectFirst("h1.entry-title, .f-title h1, h1")
            ?.text()?.trim()
            ?: targetUrl.trimEnd('/').substringAfterLast("/").replace("-", " ")

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

        val isMovie = targetUrl.contains("/movies/") || targetUrl.contains("/movie/")

        // WP Post ID from data attributes
        val postId = doc.selectFirst(".rating-stars[data-id], [data-itemid]")
            ?.let { it.attr("data-id").ifBlank { it.attr("data-itemid") } }
            ?.takeIf { it.isNotBlank() }
            ?: ""

        // ── MOVIE ────────────────────────────────────────────────────────────
        if (isMovie) {
            return newMovieLoadResponse(title, targetUrl, TvType.AnimeMovie, "$postId|movies|SUB|$targetUrl") {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = plot
                this.year                = year
                this.tags                = genres
                if (rating != null) this.score = Score.from10(rating.toString())
            }
        }

        // ── ANIME SERIES (Dual SUB / DUB Lists + Multi-Season) ────────────────
        val seasonLinks = doc.select("ul.seasons-list a.play-ss")
        val nonce       = seasonLinks.firstOrNull()?.attr("data-sec") ?: ""
        val seasonNums  = seasonLinks.mapNotNull { it.attr("data-snum").toIntOrNull() }.distinct()

        // Season 1 episodes (inline in DOM)
        val rawSeason1 = parseEpisodeAnchors(doc.select("ul#season-listep-1 li a.play-ep"))

        // Seasons 2+ (fetched in parallel with non-blocking amap)
        val otherSeasons = seasonNums.filter { it > 1 }
        val rawOtherSeasons = if (otherSeasons.isNotEmpty() && postId.isNotBlank()) {
            otherSeasons.amap { sNum ->
                fetchSeasonEpisodeAnchors(postId, sNum, nonce, targetUrl)
            }.flatten()
        } else {
            emptyList()
        }

        val allRawEpisodes = (rawSeason1 + rawOtherSeasons)
            .distinctBy { "${it.season}_${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        // Build SUB and DUB episode lists to populate CloudStream's native Dub/Sub dropdown
        val subEpisodes = allRawEpisodes.map { ep ->
            newEpisode("${ep.postId}|tvep|SUB|$targetUrl") {
                this.name      = ep.title
                this.season    = ep.season
                this.episode   = ep.episode
                this.posterUrl = ep.thumb
            }
        }

        val dubEpisodes = allRawEpisodes.map { ep ->
            newEpisode("${ep.postId}|tvep|DUB|$targetUrl") {
                this.name      = ep.title
                this.season    = ep.season
                this.episode   = ep.episode
                this.posterUrl = ep.thumb
            }
        }

        return newAnimeLoadResponse(title, targetUrl, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
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

        // Data payload format: "{epPostId}|{type}|{preferredAudio}|{refererUrl}"
        // Strip automatically prepended mainUrl if CloudStream's newEpisode fixUrl added it
        val cleanData = data.removePrefix("$mainUrl/").removePrefix(mainUrl).trim()
        val parts     = cleanData.split("|")
        val rawPid    = parts.getOrNull(0)?.trim().orEmpty()
        val epPostId  = rawPid.substringAfterLast("/").trim()
        val type      = parts.getOrNull(1)?.trim() ?: "tvep"
        val prefAudio = parts.getOrNull(2)?.trim() ?: "SUB"
        val refUrl    = parts.getOrNull(3)?.trim() ?: mainUrl

        if (epPostId.isBlank()) {
            Log.e(TAG, "loadLinks: missing epPostId from data=$data")
            return false
        }

        // Step 1: Query server list from ZetaPlayer REST API
        val serversUrl = "$mainUrl/wp-json/zetaplayer/v2/$epPostId/$type/default"
        val serversResp = try {
            app.get(serversUrl, headers = jsonHeaders + mapOf("Referer" to refUrl), timeout = 15)
                .let { resp ->
                    val raw = resp.text.stripPhpWarnings()
                    parseJson<PlayerServersResponse>(raw)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get server list for $epPostId: ${e.message}")
            null
        }

        val servers = serversResp?.embed.orEmpty()
        if (servers.isEmpty()) {
            Log.e(TAG, "No servers available in ZetaPlayer for epPostId=$epPostId")
            return false
        }

        // Sort servers so the user's selected audio mode (DUB or SUB) is processed first
        val sortedServers = servers.sortedByDescending { s ->
            val nameUpper = s.name.uppercase()
            if (prefAudio == "DUB") nameUpper.contains("DUB") else nameUpper.contains("SUB")
        }

        // Step 2: Extract servers concurrently
        var success = false
        sortedServers.amap { server ->
            try {
                val isDub = server.name.contains("DUB", ignoreCase = true)
                val label = if (isDub) "DUB" else "SUB"

                // First attempt: ZetaPlayer REST API
                var embedSrc = getEmbedFromRest(epPostId, type, server.num, refUrl)

                // Fallback attempt: ZetaPlayer admin-ajax endpoint
                if (embedSrc == null) {
                    embedSrc = getEmbedFromAdminAjax(epPostId, server.num, type, refUrl)
                }

                if (!embedSrc.isNullOrBlank()) {
                    Log.d(TAG, "Resolving server [${server.name}] ($label) -> $embedSrc")
                    val extracted = loadExtractor(
                        embedSrc,
                        refUrl,
                        subtitleCallback,
                        callback
                    )
                    if (extracted) success = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server ${server.num} (${server.name}) failed: ${e.message}")
            }
        }

        return success
    }

    private suspend fun getEmbedFromRest(pid: String, type: String, serverNum: Int, refUrl: String): String? {
        val serverUrl = "$mainUrl/wp-json/zetaplayer/v2/$pid/$type/$serverNum"
        return try {
            val resp = app.get(serverUrl, headers = jsonHeaders + mapOf("Referer" to refUrl), timeout = 15)
            val raw = resp.text.stripPhpWarnings()
            val embedResp = parseJson<PlayerEmbedResponse>(raw)
            resolveEmbedUrl(embedResp)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getEmbedFromAdminAjax(pid: String, serverNum: Int, type: String, refUrl: String): String? {
        return try {
            val resp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "zeta_player_ajax",
                    "post"   to pid,
                    "nume"   to serverNum.toString(),
                    "type"   to type
                ),
                headers = mapOf(
                    "User-Agent"       to USER_AGENT,
                    "Referer"          to refUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                timeout = 15
            )
            val embedResp = parseJson<PlayerEmbedResponse>(resp.text.stripPhpWarnings())
            resolveEmbedUrl(embedResp)
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // HTTP & Cloudflare WebView Fallback
    // =========================================================================

    /**
     * Fetches an HTML document with automated fallback to WebViewResolver
     * if Cloudflare challenge or HTTP 403 block is detected.
     */
    private suspend fun fetchDocument(url: String, referer: String = mainUrl): Document? {
        try {
            val response = app.get(
                url,
                headers = defaultHeaders + mapOf("Referer" to referer),
                timeout = 15
            )
            if (response.code !in 400..599 &&
                !response.text.contains("challenge-platform") &&
                !response.text.contains("Just a moment...") &&
                !response.text.contains("cf-browser-verification")
            ) {
                return response.document
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct GET for $url failed (${e.message}), attempting WebViewResolver fallback")
        }

        // Cloudflare Challenge Fallback via Android WebView
        return try {
            val cfResolver = WebViewResolver(
                interceptUrl = Regex(""".*animekai\.beer.*"""),
                useOkhttp    = false
            )
            val cfResp = app.get(
                url,
                headers     = defaultHeaders + mapOf("Referer" to referer),
                interceptor = cfResolver,
                timeout     = 35
            )
            cfResp.document
        } catch (e: Exception) {
            Log.e(TAG, "WebView fallback failed for $url: ${e.message}")
            null
        }
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Converts any /episodes/{anime-slug}-{S}x{E}/ URL to its parent /anime/{anime-slug}/ URL.
     */
    private fun normalizeAnimeUrl(rawUrl: String): String {
        val fixed = fixUrl(rawUrl)
        if (fixed.contains("/episodes/")) {
            val slug = fixed.trimEnd('/')
                .substringAfterLast("/episodes/")
                .replace(Regex("""-\d+[xX×]\d+.*"""), "")
            return "$mainUrl/anime/$slug/"
        }
        return fixed
    }

    private data class RawEpisode(
        val postId: String,
        val season: Int,
        val episode: Int,
        val title: String?,
        val thumb: String?
    )

    private fun parseEpisodeAnchors(elements: org.jsoup.select.Elements): List<RawEpisode> {
        return elements.mapNotNull { anchor ->
            val epPostId = anchor.attr("data-pid").trim().ifBlank { return@mapNotNull null }
            val epNum    = anchor.attr("data-epid").toIntOrNull() ?: 1
            val ssNum    = anchor.attr("data-ssid").toIntOrNull() ?: 1
            val epTitle  = anchor.selectFirst("span.ep-title")?.text()?.trim()
            val epThumb  = anchor.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { null } ?: img.attr("data-src").ifBlank { null }
            }

            RawEpisode(epPostId, ssNum, epNum, epTitle, epThumb)
        }
    }

    private suspend fun fetchSeasonEpisodeAnchors(
        postId: String,
        seasonNum: Int,
        nonce: String,
        refUrl: String
    ): List<RawEpisode> {
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
            parseEpisodeAnchors(fragDoc.select("li a.play-ep"))
        } catch (e: Exception) {
            Log.e(TAG, "fetchSeasonEpisodeAnchors failed for Season $seasonNum: ${e.message}")
            emptyList()
        }
    }

    private fun parseDisplayItems(doc: Document, isMovie: Boolean?): List<SearchResponse> {
        return doc.select("div.display-item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href][data-ptype], a[href*='/anime/'], a[href*='/movies/'], a[href*='/episodes/']")
                ?: return@mapNotNull null
            val rawHref = anchor.attr("href").ifBlank { return@mapNotNull null }
            val seriesUrl = normalizeAnimeUrl(rawHref)

            val title = anchor.attr("title").ifBlank { null }
                ?: item.selectFirst("h3, .item-desc-title h3")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = item.selectFirst("img.mli-thumb, img.thumb")?.let { img ->
                img.attr("data-original").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

            val ptype = anchor.attr("data-ptype")
            val forceMovie = isMovie ?: (ptype == "movies" || seriesUrl.contains("/movies/"))

            if (forceMovie) {
                newAnimeSearchResponse(title, seriesUrl, TvType.AnimeMovie) {
                    this.posterUrl = poster
                    addDubStatus(dubExist = true, subExist = true)
                }
            } else {
                newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                    this.posterUrl = poster
                    addDubStatus(dubExist = true, subExist = true)
                }
            }
        }
    }

    private fun resolveEmbedUrl(resp: PlayerEmbedResponse?): String? {
        if (resp == null) return null
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
