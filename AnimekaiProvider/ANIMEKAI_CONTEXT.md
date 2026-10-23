# AnimeKai Provider — Full Site Architecture Context

> **Site:** https://animekai.beer  
> **Platform:** WordPress 7.1.3 + **Zetaflix theme** (v1.4.5)  
> **Recon date:** 2026-10-23  
> **Protection:** Cloudflare on subpages (anime/episode detail pages block bots). Home page and search pages load fine. Static-HTML scraping must use browser-like headers.

---

## 1. Site Architecture Overview

```
animekai.beer  (WordPress / Zetaflix theme)
│
├── /                         → Home page  (no CF block)
├── /anime/                   → Anime series listing (paginated)
├── /movies/                  → Movies listing
├── /episodes/                → Latest episodes listing
├── /trending/                → Trending page
├── /ratings/                 → Ratings page
├── /imdb/                    → Top IMDb page
├── /genre/{slug}/            → Genre filter pages
│
├── /anime/{slug}/            → TV Series detail page  ⚠️ CF-protected
├── /movies/{slug}/           → Movie detail page       ⚠️ CF-protected
├── /episodes/{slug}-{S}x{E}/ → Single episode page    ⚠️ CF-protected
│
├── /?s={query}&post_type=tvshows  → Search results (HTML, no CF)
│
└── WP REST API
    └── /wp-json/zetaplayer/v2/   → Player API (no CF, key endpoint)
```

---

## 2. URL Patterns & Slugs

| Content | URL Pattern | Example |
|---|---|---|
| Anime series | `/anime/{slug}/` | `/anime/naruto-shippuden/` |
| Movie | `/movies/{slug}/` | `/movies/that-time-i-got-reincarnated-as-a-slime-movie/` |
| Episode | `/episodes/{anime-slug}-{S}x{E}/` | `/episodes/a-returners-magic-should-be-special-2x3/` |
| Genre filter | `/genre/{genre-slug}/` | `/genre/action-adventure/` |
| Search | `/?s={query}&post_type=tvshows` | `/?s=naruto&post_type=tvshows` |
| Anime listing | `/anime/page/{N}/` | `/anime/page/2/` |
| Movies listing | `/movies/page/{N}/` | `/movies/page/2/` |

---

## 3. JavaScript Config (`ztAjax`) — injected on every page

```json
{
  "homeurl":        "https://animekai.beer",
  "url":            "/wp-admin/admin-ajax.php",
  "player_api":     "https://animekai.beer/wp-json/zetaplayer/v2/",
  "play_ajaxmd":    "1",
  "play_method":    "wp_json",
  "play_method_ep": "admin_ajax",
  "ajaxtvmenu":     "1",
  "autoplayer":     "1",
  "autotvep":       "1",
  "autoepvid":      "1",
  "sspstyle":       "horizontal"
}
```

**Key:** `play_method = "wp_json"` means the player fetches video via WP REST API (not admin-ajax).

---

## 4. Search

### Request
```
GET https://animekai.beer/?s={query}&post_type=tvshows
Headers:
  User-Agent: Mozilla/5.0 ...
  Referer: https://animekai.beer/
```

### Response HTML — Search Result Item Structure
```html
<div id="item-{postId}" class="display-item">
  <div class="item-box">
    <a href="https://animekai.beer/anime/{slug}/"
       data-url="{slug}"
       data-ptype="tvshows"
       title="{Title}">
    </a>
    <span data-itemid="{postId}" data-nonce="{nonce}" 
          data-itemtype="tvshows" data-itemname="{slug}"></span>
    <img post-id="{postId}"
         data-original="https://image.tmdb.org/t/p/w300_and_h450_face/{tmdb_id}.jpg"
         class="thumb mli-thumb"
         alt="{Title}" src="...">
    <div class="item-desc">
      <div class="item-desc-title"><h3>{Title}</h3></div>
      <div class="item-desc-hl">
        <span class="item-quality">S{N} EP{N}</span>
      </div>
    </div>
  </div>
</div>
```

### CSS Selectors for Search Parsing
```kotlin
val items     = doc.select("div.display-item")
val anchor    = item.selectFirst("a[data-ptype]")
val href      = fixUrl(anchor.attr("href"))          // anime detail URL
val title     = anchor.attr("title")
val postId    = item.id().removePrefix("item-")      // WP post ID
val poster    = item.selectFirst("img.mli-thumb")
                    ?.let { it.attr("data-original").ifEmpty { it.attr("src") } }
val epCount   = item.selectFirst("span.item-quality")?.text()  // "S2 EP12"
val ptype     = anchor.attr("data-ptype")  // "tvshows" or "movies"
```

---

## 5. Home Page Sections

### Section HTML Structure
```html
<div class="home-module grid stream episodes" data-module-id="featured-episodes">
  <div class="module-title">
    <span>Episodes</span>
    <a href="https://animekai.beer/episodes/">View All</a>
  </div>
  <div class="hz-module-content">
    <div id="item-{postId}" class="display-item">
      <div class="item-box">
        <a href="https://animekai.beer/episodes/{anime-slug}-{S}x{E}/"
           data-url="{anime-slug}-{S}x{E}"
           data-ptype="episodes"
           title="{Anime Title: SxE}">
        </a>
        <img data-original="https://image.tmdb.org/t/p/w300/{tmdb}.jpg"
             class="thumb mli-thumb">
        <div class="item-desc">
          <div class="item-desc-title xtras">
            <h3>{Anime Title}</h3>
            <span class="season-desc">S{N} EP{N}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</div>
```

### Home Sections (3 modules)
| `data-module-id` | Title heading | Content type |
|---|---|---|
| `featured-episodes` | Episodes | Latest individual episodes (`data-ptype="episodes"`) |
| `featured-seasons` | Seasons | Newly added seasons |
| `featured-movies` | Movies | Latest movies |

### Hero Slider (Featured)
```html
<div class="featured-slider fulls">
  <div id="main-slider" class="owl-carousel">
    <div class="item" id="slide-{postId}">
      <div class="featured-data" style="background-image: url({tmdb_backdrop})">
        <h1>{Title}</h1>
        <span class="f-imdb">IMDb: {score}</span>
        <span class="f-year">{year}</span>
        <span class="f-genre"><a href="/genre/{slug}/">{Genre}</a></span>
        <p class="f-desc">{description}</p>
        <a href="https://animekai.beer/anime/{slug}/">Watch</a>
      </div>
    </div>
  </div>
</div>
```

### Home Page URLs for `mainPageOf`
```kotlin
override val mainPage = mainPageOf(
    "$mainUrl/anime/"    to "Anime Series",
    "$mainUrl/movies/"   to "Movies",
    "$mainUrl/episodes/" to "Latest Episodes",
    "$mainUrl/trending/" to "Trending",
)
```

### Pagination
- Anime/Movie listings: `GET /anime/page/{N}/` or `/movies/page/{N}/`
- Items are in `.display-item` inside `.page-body`

---

## 6. Anime/TV Series Detail Page

### URL: `https://animekai.beer/anime/{slug}/`
> ⚠️ Cloudflare-protected. Must set browser-like headers.

### Key HTML Elements
```html
<!-- Post ID in data-id attributes -->
<div class="rating-stars starstruck-main" data-id="{postId}"></div>

<!-- Poster -->
<img class="thumb mli-thumb" 
     data-original="https://image.tmdb.org/t/p/w500/{tmdb}.jpg">

<!-- Description -->
<div class="details-desc"><p>{description}</p></div>

<!-- Genre tags -->
<span class="f-genre"><a href="/genre/{slug}/">{Genre}</a></span>

<!-- Year / Air date -->
<span class="ep-date">{date}</span>

<!-- IMDb score embedded in hero section -->
<span class="f-imdb">IMDb: {score}</span>

<!-- Season list — each season has data-sec (nonce), data-tvs (postId), data-snum -->
<ul class="seasons-list">
  <li class="ss-1">
    <a class="play-ss" data-sec="{nonce}" data-tvs="{postId}" data-snum="1">
      Season 1<span class="ep-count"></span>
    </a>
  </li>
  <!-- ... -->
</ul>

<!-- Episodes list (inline for Season 1, AJAX for others) -->
<ul id="season-listep-1" class="episodes-list">
  <li class="ep-1">
    <a class="play-ep"
       data-type="tvep"
       data-sec="{nonce}"          <!-- WP nonce — changes per page load -->
       data-pid="{epPostId}"       <!-- WP post ID of the episode post -->
       data-epid="{episodeNum}"    <!-- Episode number within season -->
       data-ssid="{seasonNum}">    <!-- Season number -->
      <span class="ep-num">{N}</span>
      <img src="https://image.tmdb.org/t/p/w500/{tmdb}.jpg">
      <span class="ep-title">{Episode Title}</span>
      <span class="ep-date">{Air Date}</span>
    </a>
  </li>
</ul>
```

### CSS Selectors
```kotlin
// Post ID
val postId = doc.selectFirst("[data-id]")?.attr("data-id") ?: ""

// Poster
val poster = doc.selectFirst("img.mli-thumb")
                ?.let { it.attr("data-original").ifEmpty { it.attr("src") } }

// Description  
val plot = doc.selectFirst("div.details-desc p")?.text()

// Genres
val genres = doc.select("span.f-genre a").map { it.text() }

// Title
val title = doc.selectFirst("h1")?.text()
         ?: doc.selectFirst(".f-title h1")?.text()

// Seasons data (nonce + postId always same across seasons)
val seasonLinks = doc.select("ul.seasons-list a.play-ss")
val postId  = seasonLinks.first()?.attr("data-tvs")
val nonce   = seasonLinks.first()?.attr("data-sec")
val seasons = seasonLinks.map { it.attr("data-snum").toInt() }

// Episodes (Season 1 always inline, other seasons via AJAX)
val epItems = doc.select("ul.episodes-list li a.play-ep")
// Each episode:
val epPostId = epItem.attr("data-pid")
val epNum    = epItem.attr("data-epid").toIntOrNull()
val ssNum    = epItem.attr("data-ssid").toIntOrNull()
val epNonce  = epItem.attr("data-sec")
val epTitle  = epItem.selectFirst("span.ep-title")?.text()
val epThumb  = epItem.selectFirst("img")?.attr("src")
```

---

## 7. AJAX Season Menu (fetching episodes for Season N ≥ 2)

### Request
```
POST https://animekai.beer/wp-admin/admin-ajax.php
Content-Type: application/x-www-form-urlencoded
Referer: https://animekai.beer/anime/{slug}/

action=zt_ajax_season_menu
tvshow={postId}
season={seasonNum}
nonce={nonce}           ← from data-sec on .play-ss anchor
```

### Response (JSON)
```json
{
  "episodes": "<ul id=\"season-listep-2\" class=\"episodes-list\">...</ul>"
}
```
The `episodes` field contains HTML identical to Season 1's inline list.

---

## 8. WP REST API — Player Endpoint (THE KEY)

### Step 1: Get available servers for an episode/movie

```
GET https://animekai.beer/wp-json/zetaplayer/v2/{epPostId}/{type}/default
Headers:
  Referer: https://animekai.beer/anime/{slug}/
```

**`{type}`** values:
- `tvep` — TV episode
- `tvshows` — full TV show (movies)
- `movies` — movie post

**Example:**
```
GET /wp-json/zetaplayer/v2/9490/tvep/default
```

**Response:**
```json
{
  "pid": "9490",
  "status": 1,
  "msg": "",
  "error": "",
  "mode": "ajax_mode",
  "type": "tvep",
  "embed": [
    { "num": 1, "type": "ztshcode", "title": "Server", "name": "DUB: 1" },
    { "num": 2, "type": "ztshcode", "title": "Server", "name": "DUB: 2" },
    { "num": 3, "type": "ztshcode", "title": "Server", "name": "SUB: 1" },
    { "num": 4, "type": "ztshcode", "title": "Server", "name": "SUB: 2" }
  ],
  "default": ""
}
```

The `embed` array lists available servers. `num` is the server index. `name` tells dub/sub.

---

### Step 2: Get the embed URL for a specific server

```
GET https://animekai.beer/wp-json/zetaplayer/v2/{epPostId}/{type}/{serverNum}
Headers:
  Referer: https://animekai.beer/anime/{slug}/
```

**Example:**
```
GET /wp-json/zetaplayer/v2/9490/tvep/3
```

**Response:**
```json
{
  "pid":       "9490",
  "status":    1,
  "type":      "tvep",
  "embed_url": "<iframe src='https://example.com/embed/...' ...></iframe>",
  "play_url":  "https://example.com/embed/...",
  "msg":       ""
}
```

The `embed_url` can be:
- Raw HTML iframe embed → extract `src` attribute
- Or a direct URL string when `type == "mp4"` or `type == "trailer"`

The JS logic confirms:
```javascript
if (t.type == "trailer" || t.type == "mp4") {
    // use embed_url directly as iframe src
} else {
    // embed_url is already full HTML to inject
}
```

---

## 9. Movie Detail Page

Similar to anime, but:
- URL: `/movies/{slug}/`
- Player type: `movies` (not `tvep`)
- No episode/season list
- `data-type="mv"` on play button

---

## 10. Episode URL Page (Direct Episode Page)

URL: `https://animekai.beer/episodes/{anime-slug}-{S}x{E}/`

The episode page has:
- `data-id="{epPostId}"` on `.rating-stars`
- `data-type="ep"` on `#splash-play` button
- Player is loaded via same WP JSON API using episode's `data-pid`
- Backdrop: TMDB image in `.splash-bg img`

---

## 11. Known Embed Hosts (from `embed_url`)

Based on Zetaflix theme behavior + AnimeKai's history:
- **Filemoon / FileMoon** — `filemoon.sx`, `moon.sx` → `.m3u8`
- **StreamTape** — `streamtape.com` → `.mp4`
- **Vidcloud / NativeHLS** — CloudStream has built-in extractor
- **MyCloud** — `mcloud.to` → HLS
- **Gogostream / gogo** — Direct HLS
- **DoodStream** — `dood.watch` → `.m3u8`
- **ztshcode** — Site's own obfuscated embed shortcode that resolves to one of the above

The `type` field in player API response will be one of:
- `"ztshcode"` → HTML embed block (contains `<iframe>`)
- `"mp4"` → Direct MP4 url in `embed_url`  
- `"trailer"` → YouTube/trailer link
- `"hls"` / `"m3u8"` → Direct HLS manifest

---

## 12. Headers Required

```kotlin
val defaultHeaders = mapOf(
    "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
    "Referer"         to "https://animekai.beer/",
    "Accept-Language" to "en-US,en;q=0.9",
    "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
)

val jsonHeaders = mapOf(
    "User-Agent"   to "Mozilla/5.0 ...",
    "Referer"      to "https://animekai.beer/",
    "Accept"       to "application/json",
    "X-Requested-With" to "XMLHttpRequest"
)
```

---

## 13. Full Data Flow for CloudStream Provider

```
getMainPage(page, request)
  → GET /anime/page/{page}/  OR  /movies/page/{page}/
  → parse .display-item elements
  → return newAnimeSearchResponse / newMovieSearchResponse

search(query)
  → GET /?s={query}&post_type=tvshows
  → parse .display-item elements
  → return list of SearchResponse

load(url)     [url = anime detail page URL]
  → GET {url}    (with CF-bypass headers)
  → parse:
      title, poster, plot, genres, year from HTML
      postId from [data-id]
      nonce  from .play-ss[data-sec]
      seasons from .seasons-list li a.play-ss
      episodes for season 1: .episodes-list li a.play-ep (inline)
      episodes for seasons 2+: POST admin-ajax "zt_ajax_season_menu"
  → build EpisodeData = "{epPostId},{type},{animeDetailUrl}"
  → return newAnimeLoadResponse(title, url, ...) { episodes = [...] }

loadLinks(data, ...)
  → parse data → epPostId, type, refererUrl
  → GET /wp-json/zetaplayer/v2/{epPostId}/{type}/default
      → get embed array with server numbers and dub/sub labels
  → for each server in parallel (amap):
      GET /wp-json/zetaplayer/v2/{epPostId}/{type}/{serverNum}
      → get embed_url
      → if embed_url is iframe HTML: extract src attribute
      → call loadExtractor(src, referer, subtitleCallback, callback)
```

---

## 14. Cloudflare Strategy

AnimeKai uses **Cloudflare Bot Management** on detail pages. Strategies:
1. **Session cookie reuse** — use `app.get()` with a valid `cf_clearance` cookie (requires initial browser visit)
2. **`sequentialMainPage = true`** — avoid rate-limiting on home page
3. **Delay between requests** — add `cacheTime = 60` 
4. **CloudStream CloudflareKiller** — CloudStream has built-in CF bypass helper:
   ```kotlin
   val cfHeaders = getCookiesFromAWebView(url)
   // or use: app.get(url, interceptor = cloudflareClearance)
   ```
5. **Use WP REST API directly** — The `/wp-json/` endpoint does **NOT** have Cloudflare protection, so the player API calls always work. Only the HTML detail pages need CF bypass.

---

## 15. Data Class Definitions

```kotlin
// Passed as episode data string (JSON-encoded for robustness)
data class EpData(
    val pid: String,      // WP episode post ID
    val type: String,     // "tvep" for series, "movies" for film
    val refUrl: String    // Detail page URL (for Referer header)
)

// Player API server list response
data class PlayerServersResponse(
    val pid: String,
    val status: Int,
    val type: String,
    val embed: List<EmbedServer>,
    val default: String
)
data class EmbedServer(
    val num: Int,
    val type: String,    // "ztshcode" / "mp4" / "hls"
    val title: String,
    val name: String     // "DUB: 1" / "SUB: 2" etc
)

// Player API embed response
data class PlayerEmbedResponse(
    val pid: String,
    val status: Int,
    val type: String,
    @JsonProperty("embed_url") val embedUrl: String,
    @JsonProperty("play_url")  val playUrl: String,
    val msg: String
)
```

---

## 16. Season/Episode AJAX Action Summary

| Action | Method | Endpoint | Key Params |
|---|---|---|---|
| Season episode list | POST | `/wp-admin/admin-ajax.php` | `action=zt_ajax_season_menu`, `tvshow={postId}`, `season={N}`, `nonce={sec}` |
| View counter | POST | `/wp-admin/admin-ajax.php` | `action=zetaflix_viewcounter`, `post_id={id}` |
| Player servers | GET | `/wp-json/zetaplayer/v2/{pid}/{type}/default` | — |
| Player embed | GET | `/wp-json/zetaplayer/v2/{pid}/{type}/{serverNum}` | — |

---

## 17. Caveats & Gotchas

1. **Nonces expire** — `data-sec` values are WordPress nonces (10-character hex). They're valid for ~24 hours per session. For server-side scraping you need fresh nonces from the page HTML.
2. **Season 1 episodes are always inline** in the HTML. Seasons ≥ 2 require the admin-ajax call.
3. **TMDB images** — All poster/backdrop images come from TMDB (`image.tmdb.org`). Use `data-original` attribute, not `src` (may be a low-res placeholder).
4. **WP Post ID** is the primary key for ALL player API calls — not the URL slug.
5. **DUB/SUB detection** — The server `name` field tells you: "DUB: 1" → dubbed, "SUB: 1" → subbed.
6. **Cloudflare on detail pages** — Use `sequentialMainPage = true` and proper headers. Consider fetching detail pages with CloudStream's WebView interceptor.
7. **`post_type` param for search** — Must include `&post_type=tvshows` else WordPress returns blog posts.
8. **No pagination in embed array** — All servers returned in one call (usually 2–4 servers).
9. **`embed_url` vs `play_url`** — `embed_url` is what gets injected into the page. `play_url` may be the raw iframe `src`. Prefer checking `play_url` first, then parsing `embed_url` for an iframe's `src`.
