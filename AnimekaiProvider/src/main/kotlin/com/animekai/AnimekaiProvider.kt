package com.animekai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

/**
 * AnimeKai CloudStream Provider
 *
 * Site: https://animekai.beer
 * Platform: WordPress 7.1.3 + Zetaflix theme v1.4.5
 *
 * Architecture:
 *  - Search:    GET /?s={query}&post_type=tvshows  → HTML scrape .display-item
 *  - Home:      GET /anime/page/{N}/  etc.         → HTML scrape .display-item
 *  - Load:      GET /anime/{slug}/                  → HTML scrape + AJAX for season eps
 *  - Player:    GET /wp-json/zetaplayer/v2/{pid}/{type}/{serverNum}
 *               → JSON { embed_url, play_url } → loadExtractor()
 *
 * Episode data format: "{epPostId}|{type}|{refererUrl}"
 *   e.g. "9490|tvep|https://animekai.beer/anime/naruto-shippuden/"
 */
class AnimekaiProvider : MainAPI() {
    override var mainUrl    = "https://animekai.beer"
    override var name       = "AnimeKai"
    override val hasMainPage = true
    override var lang       = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    // ─── Cloudflare: sequential home-page requests to avoid rate-limiting ────
    override var sequentialMainPage     = true
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

    /**
     * mainPageOf sources:
     *  - /anime/page/{N}/    → Anime Series (TvSeries/Anime)
     *  - /movies/page/{N}/   → Anime Movies
     *  - /episodes/page/{N}/ → Latest Episodes (individual episode cards)
     *  - /trending/          → Trending
     */
    override val mainPage = mainPageOf(
        "$mainUrl/anime/"    to "Anime Series",
        "$mainUrl/movies/"   to "Anime Movies",
        "$mainUrl/episodes/" to "Latest Episodes",
        "$mainUrl/trending/" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build paginated URL: /anime/ → /anime/page/2/
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}page/$page/"
        }

        val doc = app.get(url, headers = defaultHeaders, timeout = 30).document
        val items = parseDisplayItems(doc, request.name.contains("Movie", ignoreCase = true))
        val hasNext = doc.selectFirst("a.next.page-numbers, .next-page, [rel=next]") != null
            || items.size >= 20

        return newHomePageResponse(request.name, items, hasNext)
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        // URL: /?s={query}&post_type=tvshows
        val url = "$mainUrl/?s=${query.encodeUri()}&post_type=tvshows"
        val doc = app.get(url, headers = defaultHeaders, timeout = 30).document
        return parseDisplayItems(doc, null)
    }

    // =========================================================================
    // Load (Detail Page)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = defaultHeaders, timeout = 30).document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $url: ${e.message}")
            return null
        }

        // ── Basic metadata ────────────────────────────────────────────────────
        val title = doc.selectFirst("h1.entry-title, .f-title h1, h1")
            ?.text()?.trim()
            ?: url.trimEnd('/').substringAfterLast("/").replace("-", " ")

        // Poster: prefer data-original (full-res TMDB), fall back to src
        val poster = doc.selectFirst("img.mli-thumb, .tvshow-poster img, .poster img")
            ?.let { img ->
                img.attr("data-original").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

        // Backdrop from TMDB background in hero
        val backdrop = doc.selectFirst(".splash-bg img, .featured-data")
            ?.let { el ->
                val style = el.attr("style")
                Regex("""url\(([^)]+)\)""").find(style)?.groupValues?.getOrNull(1)?.trim('\'', '"')
                    ?: el.selectFirst("img")?.attr("src")
            }

        val plot = doc.selectFirst("div.details-desc p, .tvshow-desc p, .description p")
            ?.text()?.trim()

        val genres = doc.select("span.f-genre a, .genres a").map { it.text().trim() }

        val year = doc.selectFirst("span.f-year, .f-info span.f-year")
            ?.text()?.trim()?.toIntOrNull()

        val rating = doc.selectFirst("span.f-imdb")
            ?.text()?.replace("IMDb:", "")?.trim()?.toDoubleOrNull()

        // Determine if it's a movie or TV series by URL pattern
        val isMovie = url.contains("/movies/") || url.contains("/movie/")

        // ── WP Post ID (needed for player API) ───────────────────────────────
        // It appears in: data-id on rating stars, data-itemid on buttons, etc.
        val postId = doc.selectFirst(".rating-stars[data-id], [data-itemid]")
            ?.let { it.attr("data-id").ifBlank { it.attr("data-itemid") } }
            ?.takeIf { it.isNotBlank() }
            ?: ""

        // ── MOVIE ────────────────────────────────────────────────────────────
        if (isMovie) {
            // For movies, the play button has data-type="mv"
            // Episode data: "{postId}|movies|{url}"
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$postId|movies|$url") {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = plot
                this.year                = year
                this.tags                = genres
            }
        }

        // ── TV SERIES ─────────────────────────────────────────────────────────
        // Season list: each .play-ss has data-sec (nonce), data-tvs (postId), data-snum
        val seasonLinks  = doc.select("ul.seasons-list a.play-ss")
        val nonce        = seasonLinks.firstOrNull()?.attr("data-sec") ?: ""
        val seasonNums   = seasonLinks.mapNotNull { it.attr("data-snum").toIntOrNull() }

        // Season 1 episodes are always inline in the HTML
        val allEpisodes  = mutableListOf<Episode>()
        val season1Eps   = parseEpisodeList(doc.select("ul#season-listep-1 li a.play-ep"), url)
        allEpisodes.addAll(season1Eps)

        // Seasons 2+ are loaded via admin-ajax action=zt_ajax_season_menu
        for (sNum in seasonNums.filter { it > 1 }) {
            val ajaxEps = fetchSeasonEpisodes(postId, sNum, nonce, url)
            allEpisodes.addAll(ajaxEps)
        }

        // Detect Anime vs generic TvSeries by genre
        val type = if (genres.any { it.contains("Animation", ignoreCase = true) ||
                                    it.contains("Anime", ignoreCase = true) }) {
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
    // Load Links (Video Resolution)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "{epPostId}|{type}|{refererUrl}"
        val parts    = data.split("|", limit = 3)
        val epPostId = parts.getOrNull(0)?.trim() ?: return false
        val type     = parts.getOrNull(1)?.trim() ?: "tvep"
        val refUrl   = parts.getOrNull(2)?.trim() ?: mainUrl

        if (epPostId.isBlank()) {
            Log.e(TAG, "loadLinks: missing epPostId from data=$data")
            return false
        }

        // Step 1: GET /wp-json/zetaplayer/v2/{pid}/{type}/default
        //         → returns embed[] with {num, type, name} for each available server
        val serversUrl = "$mainUrl/wp-json/zetaplayer/v2/$epPostId/$type/default"
        val serversResp = try {
            app.get(serversUrl, headers = jsonHeaders + mapOf("Referer" to refUrl), timeout = 15)
                .let { resp ->
                    // Response may have PHP warnings before JSON — strip them
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

        // Step 2: For each server, GET /wp-json/zetaplayer/v2/{pid}/{type}/{serverNum}
        //         → returns embed_url / play_url → pass to loadExtractor
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
                    // Determine dub/sub from server name (e.g., "DUB: 1", "SUB: 2")
                    val isDub = server.name.contains("DUB", ignoreCase = true)
                    val label = if (isDub) "Dubbed" else "Subbed"

                    // Resolve embed URL
                    val embedSrc = resolveEmbedUrl(embedResp) ?: return@amap
                    Log.d(TAG, "Server ${server.name} ($label) → $embedSrc")

                    // loadExtractor handles all known embed hosts (Filemoon, StreamTape, Vidcloud, etc.)
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

    // =========================================================================
    // Private Helpers
    // =========================================================================

    /**
     * Fetch episodes for a given season via admin-ajax.
     * POST /wp-admin/admin-ajax.php with:
     *   action=zt_ajax_season_menu, tvshow={postId}, season={N}, nonce={sec}
     *
     * Response JSON: { "episodes": "<ul>...</ul>" }
     */
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

            data class SeasonMenuResponse(
                @JsonProperty("episodes") val episodes: String? = null
            )

            val json = resp.parsedSafe<SeasonMenuResponse>()
            val htmlFrag = json?.episodes ?: return emptyList()

            val fragDoc = org.jsoup.Jsoup.parseBodyFragment(htmlFrag)
            parseEpisodeList(fragDoc.select("li a.play-ep"), refUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSeasonEpisodes failed for season $seasonNum: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse episode anchor elements from .episodes-list li a.play-ep
     *
     * Each anchor has:
     *   data-pid   = WP post ID of the episode post
     *   data-epid  = episode number within season
     *   data-ssid  = season number
     *   data-type  = "tvep"
     *   data-sec   = nonce (not needed for player API with wp_json method)
     */
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
                name     = epTitle
                season   = ssNum
                episode  = epNum
                posterUrl = epThumb
            }
        }
    }

    /**
     * Parse search/listing result cards from .display-item elements.
     *
     * @param isMovie Force movie type (null = auto-detect from URL)
     */
    private fun parseDisplayItems(doc: org.jsoup.nodes.Document, isMovie: Boolean?): List<SearchResponse> {
        return doc.select("div.display-item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href][data-ptype], a[href*='/anime/'], a[href*='/movies/']")
                ?: return@mapNotNull null
            val href  = fixUrl(anchor.attr("href")).ifBlank { return@mapNotNull null }
            val title = anchor.attr("title").ifBlank { null }
                ?: item.selectFirst("h3, .item-desc-title h3")?.text()?.trim()
                ?: return@mapNotNull null

            // TMDB poster: prefer data-original (full-res), fall back to src
            val poster = item.selectFirst("img.mli-thumb, img.thumb")?.let { img ->
                img.attr("data-original").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

            val ptype  = anchor.attr("data-ptype")   // "tvshows", "movies", "episodes"
            val forceMovie = isMovie
                ?: (ptype == "movies" || href.contains("/movies/"))

            if (forceMovie) {
                newAnimeSearchResponse(title, href, TvType.AnimeMovie) {
                    posterUrl = poster
                }
            } else {
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    posterUrl = poster
                }
            }
        }
    }

    /**
     * Resolves the actual embeddable URL from a PlayerEmbedResponse.
     * The API can return:
     *  - play_url:  the direct iframe src (cleanest)
     *  - embed_url: raw HTML containing an iframe, or a direct URL
     */
    private fun resolveEmbedUrl(resp: PlayerEmbedResponse): String? {
        // Prefer play_url if it's a real URL
        val playUrl = resp.playUrl?.trim()
        if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) {
            return playUrl
        }

        val embedUrl = resp.embedUrl?.trim() ?: return null
        if (embedUrl.isBlank()) return null

        // If embed_url is already a URL, return it
        if (embedUrl.startsWith("http")) return embedUrl

        // If it's HTML (e.g., an iframe block), extract the src
        if (embedUrl.contains("<iframe", ignoreCase = true)) {
            val fragDoc = org.jsoup.Jsoup.parseBodyFragment(embedUrl)
            val src = fragDoc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!src.isNullOrBlank()) {
                return if (src.startsWith("//")) "https:$src"
                else if (src.startsWith("/")) "$mainUrl$src"
                else src
            }
        }

        return null
    }

    /**
     * Strip PHP warning/notice HTML that sometimes precedes the JSON response
     * from the Zetaflix player API (known server-side bug in zeta_player.php).
     */
    private fun String.stripPhpWarnings(): String {
        val jsonStart = this.indexOf('{')
        return if (jsonStart > 0) this.substring(jsonStart) else this
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}

// =============================================================================
// Data Classes — Player API
// =============================================================================

/**
 * Response from GET /wp-json/zetaplayer/v2/{pid}/{type}/default
 * Lists all available video servers for an episode/movie.
 */
data class PlayerServersResponse(
    @JsonProperty("pid")     val pid: String?             = null,
    @JsonProperty("status")  val status: Int              = 0,
    @JsonProperty("msg")     val msg: String?             = null,
    @JsonProperty("error")   val error: String?           = null,
    @JsonProperty("mode")    val mode: String?            = null,
    @JsonProperty("type")    val type: String?            = null,
    @JsonProperty("embed")   val embed: List<EmbedServer>? = null,
    @JsonProperty("default") val default: String?         = null
)

/**
 * A single video server entry in the embed[] array.
 * [name] values: "DUB: 1", "DUB: 2", "SUB: 1", "SUB: 2", etc.
 * [type] values: "ztshcode" (most common), "mp4", "hls", "trailer"
 */
data class EmbedServer(
    @JsonProperty("num")   val num: Int    = 0,
    @JsonProperty("type")  val type: String = "",
    @JsonProperty("title") val title: String = "",
    @JsonProperty("name")  val name: String  = ""
)

/**
 * Response from GET /wp-json/zetaplayer/v2/{pid}/{type}/{serverNum}
 * Contains the actual embed URL or HTML for the selected server.
 */
data class PlayerEmbedResponse(
    @JsonProperty("pid")       val pid: String?     = null,
    @JsonProperty("status")    val status: Int       = 0,
    @JsonProperty("type")      val type: String?     = null,
    @JsonProperty("embed_url") val embedUrl: String? = null,
    @JsonProperty("play_url")  val playUrl: String?  = null,
    @JsonProperty("msg")       val msg: String?      = null
)
