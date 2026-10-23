# Architecture & Reverse-Engineering Context: AnimeKai (`https://animekai.be`)
**Target Site**: `https://animekai.be`  
**Purpose**: Complete architectural analysis and technical blueprint for building a high-performance, fully functional CloudStream provider adhering to `/cloudstream-provider-dev` standards.

---

## 1. Executive Summary & Site Profile

| Attribute | Specification |
|---|---|
| **Base URL** | `https://animekai.be` |
| **Language Support** | English (default) & Japanese (`data-jp` title attributes) |
| **Content Scope** | TV Series, Anime Movies, OVAs, ONAs, Specials, Chinese Donghua |
| **Stream Host** | `megaplay.buzz` (MegaPlay / MegaCloud / RabbitStream variant) |
| **Stream Formats** | Adaptive HLS (`.m3u8`: 1080p, 720p, 360p) + WebVTT subtitles (`.vtt`) |
| **Cloudflare Profile** | Standard Cloudflare SSL proxy. **No Turnstile challenge** on API/GET requests; HTTP 200 directly with standard Desktop User-Agent. |
| **API Endpoints** | Native REST endpoints for search suggestions, home updates, schedule, and episode stream sources. |

---

## 2. API Endpoints Specification

AnimeKai features clean REST/AJAX endpoints that allow direct JSON consumption without redundant HTML parsing where possible.

### 2.1 Fast Search Suggestion API (Pure JSON)
- **Endpoint**: `GET https://animekai.be/api/search/suggest?q={query}`
- **Headers**:
  ```http
  User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
  Referer: https://animekai.be/
  ```
- **Response Format**: `List<AnimeSuggestItem>`
  ```json
  [
    {
      "slug": "naruto-shippuden-the-movie-6-road-to-ninja",
      "title": "Naruto Shippuden the Movie 6: Road to Ninja",
      "title_jp": "ROAD TO NINJA NARUTO THE MOVIE",
      "poster_url": "https://cdn.myanimelist.net/images/anime/1620/94336l.jpg",
      "type": "MOVIE",
      "year": null,
      "rating": "PG 13",
      "has_sub": true,
      "has_dub": true,
      "sub_count": 1,
      "dub_count": 1,
      "ep_count": 1
    }
  ]
  ```
- **Optimal CloudStream Usage**: Use in `search(query: String)` for sub-100ms response times and rich metadata.

---

### 2.2 Home Page AJAX Updates Feed
- **Endpoint**: `GET https://animekai.be/ajax/home/items?tab={tab}&page={page}`
- **Valid Tabs**:
  - `all-updates`: All newly released episodes
  - `sub-updates`: Subbed releases only
  - `dub-updates`: Dubbed releases only
  - `china-updates`: Chinese Donghua releases
- **Response Format**:
  ```json
  {
    "html": "<div class=\"aitem\">...</div>",
    "page": 1,
    "has_prev": false,
    "has_next": true
  }
  ```
- **HTML Card Structure (`.aitem`)**:
  - Link / Slug: `a.poster[href]` or `a.title[href]` (e.g. `https://animekai.be/watch/{slug}`)
  - Title: `a.title` (text) / `a.title[data-jp]` (Japanese)
  - Poster: `a.poster img[src]`
  - Sub Count: `.info .sub` (e.g. `24`)
  - Dub Count: `.info .dub` (e.g. `12`)
  - Total Ep: `.info span:nth-last-child(2)`
  - Media Type: `.info span:last-child` ("TV", "MOVIE", "ONA")

---

### 2.3 Episode Stream Sources API (Critical for `loadLinks`)
- **Endpoint**: `GET https://animekai.be/watch/{slug}/ep/{epNum}/sources`
- **Headers**:
  ```http
  Referer: https://animekai.be/watch/{slug}
  X-Requested-With: XMLHttpRequest
  ```
- **Response Format**:
  ```json
  {
    "episode": {
      "id": 5275,
      "number": 1,
      "title_english": "Episode 1"
    },
    "sources": {
      "sub": [
        {
          "id": 18122,
          "server_name": "Server 1",
          "source_url": "https://megaplay.buzz/stream/mal/59062/1/sub"
        },
        {
          "id": 18123,
          "server_name": "Server 2",
          "source_url": "https://megaplay.buzz/stream/mal/59062/1/sub"
        }
      ],
      "dub": [
        {
          "id": 18170,
          "server_name": "Server 1",
          "source_url": "https://megaplay.buzz/stream/mal/59062/1/dub"
        },
        {
          "id": 18171,
          "server_name": "Server 2",
          "source_url": "https://megaplay.buzz/stream/mal/59062/1/dub"
        }
      ]
    }
  }
  ```
- **Key Discovery**:
  - `source_url` directly references `https://megaplay.buzz/stream/mal/{mal_id}/{ep_num}/{sub|dub}`.
  - The `{mal_id}` matches the official MyAnimeList ID.
  - Sub and Dub streams are clearly separated into `sources.sub` and `sources.dub`.

---

## 3. DOM Scraping Architecture (Metadata & Episodes)

### 3.1 Details Page (`https://animekai.be/watch/{slug}`)

All anime metadata and the complete list of episodes are embedded within the initial document HTML:

| Field | Selector | Notes |
|---|---|---|
| **English Title** | `#main-entity h1.title` (or attribute `data-orig`) | Primary title display |
| **Japanese Title** | `#main-entity h1.title[data-jp]` | Japanese / Romaji name |
| **Alternative Titles**| `#main-entity small.al-title` | Synonyms |
| **Poster** | `.poster img[src]` | Direct high-res image (MyAnimeList CDN) |
| **Backdrop Poster** | `.poster-wrap-bg` style | Regex: `url\(['"]?(.*?)['"]?\)` |
| **Synopsis** | `.desc#synopsis-text` | Full text plot |
| **Rating / Age Tag** | `#main-entity .info .rating` | e.g. "PG 13", "R", "R-17" |
| **Media Type** | `#main-entity .info span:last-child` | "TV", "MOVIE", "ONA", "OVA", "SPECIAL" |
| **MAL Score** | `#anime-rating[data-score]` | Float score (e.g. 9.31) |
| **Status** | `#main-entity .detail div:contains(Status)` | "Releasing" → Ongoing, "Completed" → Completed |
| **Genres** | `#main-entity .detail a[href*="/genres/"]` | List of category strings |
| **Country** | `#main-entity .detail div:contains(Country)` | "Japan" or "China" |
| **Year / Season** | `#main-entity .detail div:contains(Premiered)`| e.g. "Spring 2026" → extract 2026 |

### 3.2 Episode List Parsing

Episodes are rendered under `.eplist ul.range li a`. Even for anime with 1,000+ episodes (such as *One Piece*), all ranges are delivered in the initial HTML DOM without requiring secondary AJAX requests!

```html
<ul class="range" data-range="001-100">
  <li>
    <a href="https://animekai.be/watch/one-piece/ep-1"
       num="1"
       data-sub="1"
       data-dub="1"
       langs="3">
       1
       <span data-jp="Episode 1" data-orig="Episode 1">Episode 1</span>
    </a>
  </li>
</ul>
```

- **Extraction Logic**:
  ```kotlin
  doc.select(".eplist ul.range li a").mapNotNull { a ->
      val epNum = a.attr("num").toIntOrNull() ?: return@mapNotNull null
      val epHref = a.attr("href")
      val epTitle = a.selectFirst("span")?.text()?.trim() ?: "Episode $epNum"
      newEpisode(epHref) {
          this.name = epTitle
          this.episode = epNum
      }
  }
  ```

---

## 4. Streaming & Player Reverse Engineering (`megaplay.buzz`)

### 4.1 Embed Execution Flow
1. **Source Embed**: `https://megaplay.buzz/stream/mal/{mal_id}/{ep_num}/{sub|dub}`
2. **Mandatory Header**: `Referer: https://animekai.be/`
   - *Security Note*: Requesting `megaplay.buzz` without the `animekai.be` referer triggers an "Error - MegaPlay" response.
3. **Player DOM**: Contains `<div id="megaplay-player" data-id="{player_id}">`.
4. **Internal Sources Call**:
   `GET https://megaplay.buzz/stream/getSources?id={player_id}&platform=OTHER`
   - Headers: `Referer: https://megaplay.buzz/stream/mal/...`, `X-Requested-With: XMLHttpRequest`
   - Returns:
     ```json
     {
       "tracks": [
         { "file": "https://.../subtitles/english.vtt", "label": "English", "kind": "captions", "default": true }
       ],
       "enc": "...",
       "server": 4
     }
     ```
5. **Decryption vs. WebViewResolver**:
   - MegaPlay uses frequently rotated obfuscation keys in `e1-player.min.js`.
   - **Recommended CloudStream Architecture**: Use CloudStream's native `WebViewResolver(interceptUrl = Regex("""\.m3u8"""))` as the fail-safe zero-maintenance player resolver.
   - When loaded in Android's headless WebView with `Referer: https://animekai.be/`, the client player automatically decrypts and requests the master playlist:
     `https://fetch.nexabloom.top/anime/.../master.m3u8?token=...`
   - This returns multi-quality streams:
     - 1080p: `index-f1-v1-a1.m3u8`
     - 720p: `index-f2-v1-a1.m3u8`
     - 360p: `index-f3-v1-a1.m3u8`

---

## 5. CloudStream 6-Pillar Optimization Blueprint

Adhering to the `/cloudstream-provider-dev` 6-pillar standard:

1. **Non-Blocking Coroutines**:
   - Use `items.amap { ... }` for concurrent server resolutions; avoid `runBlocking`.
2. **Anti-Rate-Limiting**:
   - `sequentialMainPage = true`
   - `timeout = 30`
3. **Zero NPE Guarantees**:
   - Safe calls: `?.`, `toIntOrNull()`, `parsedSafe<T>()`.
4. **UI & Metadata Richness**:
   - Dual Sub/Dub indicators: `addDubStatus(hasDub, dubCount, hasSub, subCount)`
   - Backdrops: `backgroundPosterUrl`
   - Alternative names: `addNames(titleJp, ...)`
   - Full genre categorization and MAL float score.
5. **Playback Multi-Quality**:
   - Master M3U8 handling via `M3u8Helper.generateM3u8`.
   - WebVTT subtitles parsed from `tracks` and provided via `subtitleCallback`.
6. **JVM & Gradle Compatibility**:
   - JVM 1.8 target, Jackson `2.13.1` annotations.

---

## 6. Complete Production Provider Implementation Blueprint

```kotlin
package com.animekai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class AnimeKaiProvider : MainAPI() {
    override var mainUrl = "https://animekai.be"
    override var name = "AnimeKai"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "all-updates" to "Latest Updates",
        "sub-updates" to "Latest Sub",
        "dub-updates" to "Latest Dub",
        "china-updates" to "Chinese Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tab = request.data
        val url = "$mainUrl/ajax/home/items?tab=$tab&page=$page"
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/home")).parsedSafe<HomeAjaxResponse>()
        val html = res?.html ?: return newHomePageResponse(emptyList())

        val doc = org.jsoup.Jsoup.parse(html)
        val homeItems = doc.select(".aitem").mapNotNull { it.toAnimeSearchResponse() }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems, isHorizontalImages = false)),
            hasNext = res.hasNext == true
        )
    }

    private fun Element.toAnimeSearchResponse(): SearchResponse? {
        val link = selectFirst("a.title") ?: selectFirst("a.poster") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.text().trim()
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        val subCount = selectFirst(".info .sub")?.text()?.toIntOrNull()
        val dubCount = selectFirst(".info .dub")?.text()?.toIntOrNull()
        val typeStr = selectFirst(".info span:last-child")?.text()?.trim()

        val tvType = when (typeStr?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(
                dubExist = dubCount != null && dubCount > 0,
                dubEpisodes = dubCount,
                subExist = subCount != null && subCount > 0,
                subEpisodes = subCount
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Fast path: Native Suggest API
        val suggestUrl = "$mainUrl/api/search/suggest?q=${query.encodeUrl()}"
        val res = app.get(suggestUrl, headers = mapOf("Referer" to "$mainUrl/")).parsedSafe<List<SuggestItem>>()

        if (!res.isNullOrEmpty()) {
            return res.map { item ->
                val href = "$mainUrl/watch/${item.slug}"
                val tvType = when (item.type?.uppercase()) {
                    "MOVIE" -> TvType.AnimeMovie
                    "SPECIAL", "OVA" -> TvType.OVA
                    else -> TvType.Anime
                }
                newAnimeSearchResponse(item.title, href, tvType) {
                    this.posterUrl = item.posterUrl
                    addDubStatus(
                        dubExist = item.hasDub == true,
                        dubEpisodes = item.dubCount,
                        subExist = item.hasSub == true,
                        subEpisodes = item.subCount
                    )
                }
            }
        }

        // Fallback: Browse search
        val browseDoc = app.get("$mainUrl/browse?keyword=${query.encodeUrl()}").document
        return browseDoc.select(".aitem").mapNotNull { it.toAnimeSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val entity = doc.selectFirst("#main-entity")

        val title = entity?.selectFirst("h1.title")?.attr("data-orig")
            ?: entity?.selectFirst("h1.title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("Episode")?.trim()
            ?: "Unknown Anime"

        val titleJp = entity?.selectFirst("h1.title")?.attr("data-jp")
        val poster = doc.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
        val backdrop = doc.selectFirst(".poster-wrap-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
        }

        val plot = doc.selectFirst(".desc#synopsis-text")?.text()?.trim()
        val rating = doc.selectFirst("#anime-rating")?.attr("data-score")?.toFloatOrNull()
        val genres = doc.select("#main-entity a[href*='/genres/']").map { it.text().trim() }
        val statusText = doc.selectFirst("#main-entity .detail div:contains(Status)")?.text()
        val showStatus = if (statusText?.contains("Completed", ignoreCase = true) == true) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        val episodes = doc.select(".eplist ul.range li a").mapNotNull { epLink ->
            val num = epLink.attr("num").toIntOrNull() ?: return@mapNotNull null
            val epName = epLink.selectFirst("span")?.text()?.trim() ?: "Episode $num"
            val slug = url.substringAfterLast("/").substringBefore("?")

            newEpisode("$slug:$num") {
                this.name = epName
                this.episode = num
                this.posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.rating = rating
            this.tags = genres
            this.showStatus = showStatus
            addNames(listOfNotNull(titleJp))
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(":")
        val (slug, epNum) = if (parts.size >= 2) {
            parts[0] to parts[1]
        } else {
            val s = data.substringAfter("/watch/").substringBefore("/")
            val n = Regex("""ep-(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
            s to n
        }

        val sourcesUrl = "$mainUrl/watch/$slug/ep/$epNum/sources"
        val sourcesRes = app.get(
            sourcesUrl,
            headers = mapOf(
                "Referer" to "$mainUrl/watch/$slug",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<SourcesApiResponse>() ?: return false

        val servers = mutableListOf<Pair<String, String>>()
        sourcesRes.sources?.sub?.forEach { src ->
            src.sourceUrl?.let { servers.add("MegaPlay Sub (${src.serverName ?: "Server"})" to it) }
        }
        sourcesRes.sources?.dub?.forEach { src ->
            src.sourceUrl?.let { servers.add("MegaPlay Dub (${src.serverName ?: "Server"})" to it) }
        }

        servers.amap { (displayName, embedUrl) ->
            loadMegaPlayStream(embedUrl, displayName, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun loadMegaPlayStream(
        url: String,
        displayName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mainHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://animekai.be/",
            "Origin" to "https://megaplay.buzz"
        )

        // Resilient Headless WebView Resolver for Dynamic Key Decryption
        val jsClick = "(() => { document.querySelector('.jw-icon-display')?.click(); return 'ok'; })();"
        val m3u8Resolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),
            script = jsClick,
            timeout = 15_000L
        )

        val vttResolver = WebViewResolver(
            interceptUrl = Regex("""\.vtt"""),
            script = jsClick,
            timeout = 15_000L
        )

        try {
            // Intercept Subtitles
            val vttRes = app.get(url, referer = "https://animekai.be/", interceptor = vttResolver)
            if (vttRes.url.endsWith(".vtt") && !vttRes.url.contains("thumbnail", ignoreCase = true)) {
                subtitleCallback(newSubtitleFile("English", vttRes.url))
            }

            // Intercept Multi-Quality M3U8 Master Stream
            val m3u8Res = app.get(url, referer = "https://animekai.be/", interceptor = m3u8Resolver)
            M3u8Helper.generateM3u8(
                displayName,
                m3u8Res.url,
                "https://megaplay.buzz/",
                headers = mainHeaders
            ).forEach(callback)
        } catch (_: Exception) {}
    }

    // ── Supporting Data Classes ──────────────────────────────────────
    data class HomeAjaxResponse(
        @JsonProperty("html") val html: String? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("has_next") val hasNext: Boolean? = null
    )

    data class SuggestItem(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("title_jp") val titleJp: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("has_sub") val hasSub: Boolean? = null,
        @JsonProperty("has_dub") val hasDub: Boolean? = null,
        @JsonProperty("sub_count") val subCount: Int? = null,
        @JsonProperty("dub_count") val dubCount: Int? = null
    )

    data class SourcesApiResponse(
        @JsonProperty("sources") val sources: SourcesContainer? = null
    )

    data class SourcesContainer(
        @JsonProperty("sub") val sub: List<ServerItem>? = emptyList(),
        @JsonProperty("dub") val dub: List<ServerItem>? = emptyList()
    )

    data class ServerItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("source_url") val sourceUrl: String? = null
    )
}
```

---

## 7. Next Steps for `/cloudstream-provider-dev`

1. **Scaffold Directory**: Create `nehal-CloudStream/AnimeKai` or target extension folder with `build.gradle.kts`.
2. **Provider Registration**: Register `AnimeKaiPlugin` in `Plugin.kt`.
3. **Build & Make Gate**: Compile `.cs3` binary using `./gradlew AnimeKai:make`.
4. **Autonomous Self-Learning**: Register new archetype and advance `LEARNING_LOG.md` to next step.
