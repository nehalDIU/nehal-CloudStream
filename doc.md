### Implement a Basic Content Provider Extension in Kotlin

Source: https://context7.com/recloudstream/cloudstream/llms.txt

This example demonstrates how to create a functional content provider extension by implementing the MainAPI abstract class. It includes defining provider metadata, homepage sections, and methods for fetching content, searching, loading details, and extracting links.

```kotlin
class ExampleProvider : MainAPI() {
    override var name = "Example Provider"
    override var mainUrl = "https://example.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Define homepage sections
    override val mainPage = listOf(
        MainPageData("Latest Movies", "/movies/latest"),
        MainPageData("TV Series", "/series/latest"),
        MainPageData("Trending", "/trending", horizontalImages = true)
    )

    // Fetch homepage content with pagination
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl${request.data}?page=$page").document
        val items = document.select(".media-item").map {
            newMovieSearchResponse(
                name = it.selectFirst("h3")?.text() ?: "",
                url = it.selectFirst("a")?.attr("href") ?: ""
            ) {
                posterUrl = it.selectFirst("img")?.attr("src")
                year = it.selectFirst(".year")?.text()?.toIntOrNull()
            }
        }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // Search for content
    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select(".search-result").map {
            newTvSeriesSearchResponse(
                name = it.selectFirst("h3")?.text() ?: "",
                url = it.selectFirst("a")?.attr("href") ?: ""
            ) {
                posterUrl = it.selectFirst("img")?.attr("src")
                addQuality("HD")
            }
        }
    }

    // Load media details page
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        val plot = document.selectFirst(".description")?.text()
        val posterUrl = document.selectFirst(".poster img")?.attr("src")

        // For movies
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = document.selectFirst(".play-button")?.attr("data-url") ?: ""
        ) {
            this.plot = plot
            this.posterUrl = posterUrl
            addScore("8.5", maxValue = 10)
            addDuration("2h 15min")
            tags = listOf("Action", "Drama")
            addTrailer("https://youtube.com/watch?v=example")
        }
    }

    // Extract playable video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Add subtitle
        subtitleCallback(
            newSubtitleFile("English", "https://example.com/subs/en.srt")
        )

        // Add video link
        callback(
            newExtractorLink(
                source = name,
                name = "HD Server",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.P1080.value
                referer = mainUrl
            }
        )
        return true
    }
}
```

--------------------------------

### CloudStream Utility Functions

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Provides examples of various utility functions including getting Unix timestamps, fixing URLs, extracting IMDb IDs, Base64 encoding/decoding, string manipulation (capitalization, title fixing), and parsing duration strings.

```kotlin
// Utility functions
fun utilityExamples() {
    // Unix timestamps
    val unixSeconds = APIHolder.unixTime
    val unixMillis = APIHolder.unixTimeMS

    // URL fixing
    val api = object : MainAPI() {
        override var name = "Test"
        override var mainUrl = "https://example.com"
    }

    val fixed = api.fixUrl("/path/to/resource") // Returns "https://example.com/path/to/resource"
    val fixedProtocol = api.fixUrl("//cdn.example.com/image.jpg") // Returns "https://cdn.example.com/image.jpg"

    // IMDb ID extraction
    val imdbId = imdbUrlToId("https://www.imdb.com/title/tt1234567/") // Returns "tt1234567"

    // Base64 encoding/decoding
    val encoded = base64Encode("Hello".toByteArray())
    val decoded = base64Decode(encoded)

    // String utilities
    val capitalized = capitalizeString("hello world") // "Hello world"
    val titleFixed = fixTitle("THE MOVIE TITLE") // "The Movie Title"

    // Duration parsing
    val minutes = getDurationFromString("2h 30min") // Returns 150
    val minutes2 = getDurationFromString("1 hr 45 min") // Returns 105
}
```

--------------------------------

### CloudStream Plugin Entry Point

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Implement the Plugin class to create a CloudStream extension. Register content providers, extractors, and custom video click actions. Ensure a manifest.json file is present in the plugin root.

```kotlin
// Plugin entry point - manifest.json required in plugin root
// manifest.json: { "name": "MyPlugin", "pluginClassName": "com.example.MyPlugin", "version": 1 }

class MyPlugin : Plugin() {

    override fun load(context: Context) {
        // Register content providers
        registerMainAPI(ExampleProvider())
        registerMainAPI(AnotherProvider())

        // Register custom video extractors
        registerExtractorAPI(CustomExtractor())

        // Register video click action (custom player integration)
        registerVideoClickAction(object : VideoClickAction() {
            override val name = "Open in External Player"

            override suspend fun shouldShow(
                videoData: VideoClickActionData
            ): Boolean = true

            override suspend fun onClick(
                videoData: VideoClickActionData
            ) {
                // Handle custom action
            }
        })

        // Access plugin resources if requiresResources = true in manifest
        val drawable = resources?.getDrawable(R.drawable.icon)

        // Add settings button
        openSettings = { ctx ->
            Toast.makeText(ctx, "Settings clicked", Toast.LENGTH_SHORT).show()
        }
    }

    override fun beforeUnload() {
        // Cleanup when plugin is unloaded
    }
}
```

--------------------------------

### Create TvSeriesLoadResponse with Episodes

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Constructs a TvSeriesLoadResponse including a list of episodes, show status, and next airing information. Use this for TV series content.

```kotlin
suspend fun MainAPI.loadTvSeriesExample(url: String): TvSeriesLoadResponse {
    val episodes = listOf(
        newEpisode("https://stream.example.com/s01e01") {
            name = "Pilot"
            season = 1
            episode = 1
            posterUrl = "https://example.com/ep1.jpg"
            description = "The beginning of the story..."
            addDate("2024-01-15")
            runTime = 45 * 60 // seconds
        },
        newEpisode("https://stream.example.com/s01e02") {
            name = "Episode 2"
            season = 1
            episode = 2
        }
    )

    return newTvSeriesLoadResponse(
        name = "Example Series",
        url = url,
        type = TvType.TvSeries,
        episodes = episodes
    ) {
        showStatus = ShowStatus.Ongoing
        nextAiring = NextAiring(
            episode = 3,
            unixTime = System.currentTimeMillis() / 1000 + 604800, // 1 week
            season = 1
        )
        addSeasonNames(listOf(
            SeasonData(season = 1, name = "The Beginning", displaySeason = 1)
        ))
    }
}
```

--------------------------------

### Integrate with AniList/MAL Trackers

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Shows how to find tracker information (like MAL ID, AniList ID, cover images) for a media title. Specify titles, types, and optionally the year for more accurate results. `lessAccurate` can be set to true to broaden the search.

```kotlin
// Tracker integration (AniList/MAL lookup)
suspend fun trackerExample() {
    val tracker = APIHolder.getTracker(
        titles = listOf("Attack on Titan", "Shingeki no Kyojin"),
        types = setOf(TrackerType.TV, TrackerType.ONA),
        year = 2023,
        lessAccurate = false
    )

    tracker?.let {
        println("MAL ID: ${it.malId}")
        println("AniList ID: ${it.aniId}")
        println("Cover: ${it.image}")
        println("Banner: ${it.cover}")
    }
}
```

--------------------------------

### Create AnimeLoadResponse with Dub/Sub Episodes

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Constructs an AnimeLoadResponse, specifying English and Japanese names, and organizing episodes by dub and sub status. Includes MAL and AniList IDs for synchronization.

```kotlin
suspend fun MainAPI.loadAnimeExample(url: String): AnimeLoadResponse {
    return newAnimeLoadResponse(
        name = "Example Anime",
        url = url,
        type = TvType.Anime
    ) {
        engName = "Example Anime"
        japName = "Example Anime JP"

        // Add dubbed episodes
        addEpisodes(DubStatus.Dubbed, listOf(
            newEpisode("https://dub.example.com/ep1") { episode = 1 },
            newEpisode("https://dub.example.com/ep2") { episode = 2 }
        ))

        // Add subbed episodes
        addEpisodes(DubStatus.Subbed, listOf(
            newEpisode("https://sub.example.com/ep1") { episode = 1 },
            newEpisode("https://sub.example.com/ep2") { episode = 2 },
            newEpisode("https://sub.example.com/ep3") { episode = 3 }
        ))

        // Add MAL/AniList sync
        addMalId(12345)
        addAniListId(67890)
    }
}
```

--------------------------------

### Create MovieLoadResponse with Metadata

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Constructs a MovieLoadResponse with detailed metadata including poster URLs, year, plot, tags, score, duration, content rating, actors, trailers, sync IDs, and recommendations. Use this when loading information for a movie.

```kotlin
suspend fun MainAPI.loadMovieExample(url: String): MovieLoadResponse {
    return newMovieLoadResponse(
        name = "Example Movie",
        url = url,
        type = TvType.Movie,
        dataUrl = "https://stream.example.com/video.m3u8"
    ) {
        posterUrl = "https://example.com/poster.jpg"
        backgroundPosterUrl = "https://example.com/background.jpg"
        year = 2024
        plot = "An exciting movie about..."
        tags = listOf("Action", "Thriller", "Sci-Fi")

        // Add score (rating)
        addScore(Score.from10(8.5))
        // Or: addScore("8.5", maxValue = 10)

        addDuration("2h 15min")
        contentRating = "PG-13"

        // Add actors with roles
        addActors(listOf(
            ActorData(
                actor = Actor("John Doe", "https://example.com/actor.jpg"),
                role = ActorRole.Main,
                roleString = "Main Character"
            )
        ))

        // Add trailers
        addTrailer("https://youtube.com/watch?v=trailer123")

        // Add sync service IDs
        addImdbId("tt1234567")
        addTMDbId("12345")

        // Add recommendations
        recommendations = listOf(
            newMovieSearchResponse("Similar Movie", "/similar/1") {
                posterUrl = "https://example.com/similar.jpg"
            }
        )
    }
}
```

--------------------------------

### Custom Video Extractor Implementation

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Create a custom extractor by extending ExtractorApi to handle video extraction from specific hosting sites. Implement getUrl to parse video links and subtitles from a given URL.

```kotlin
// Custom video extractor implementation
class CustomExtractor : ExtractorApi() {
    override val name = "CustomHost"
    override val mainUrl = "https://customhost.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        // Extract video sources
        val videoUrl = document.selectFirst("source")?.attr("src")
            ?: return

        // Parse quality from page
        val quality = document.selectFirst(".quality")?.text() 
            ?.let { getQualityFromName(it) } 
            ?: Qualities.Unknown.value

        // Return extracted link
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl
            ) {
                this.quality = quality
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )

        // Extract subtitles if available
        document.select("track[kind=captions]").forEach { track ->
            subtitleCallback(
                newSubtitleFile(
                    lang = track.attr("label"),
                    url = fixUrl(track.attr("src"))
                )
            )
        }
    }
}
```

--------------------------------

### Manage CloudStream Providers

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Demonstrates how to retrieve providers by name or URL, access the collection of all registered providers, and initialize them. Use `synchronized` block when accessing `allProviders` for thread safety.

```kotlin
// Provider management
fun providerManagement() {
    // Get provider by name
    val provider = APIHolder.getApiFromNameNull("ExampleProvider")

    // Get provider by URL
    val providerByUrl = APIHolder.getApiFromUrl("https://example.com/movie/123")

    // Access all registered providers
    synchronized(APIHolder.allProviders) {
        APIHolder.allProviders.forEach { api ->
            println("Provider: ${api.name} - ${api.mainUrl}")
        }
    }

    // Initialize all providers
    APIHolder.initAll()
}
```

--------------------------------

### Plugin List Structure

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Defines the JSON structure for a list of plugins within a repository. Each plugin entry includes metadata such as name, version, download URL, authors, and supported TV types.

```json
[
    {
        "name": "Example Provider",
        "internalName": "ExampleProvider",
        "url": "https://example.com/plugins/example.cs3",
        "version": 1,
        "apiVersion": 1,
        "status": 1,
        "authors": ["Developer Name"],
        "description": "Example content provider",
        "repositoryUrl": "https://github.com/user/repo",
        "language": "en",
        "tvTypes": ["Movie", "TvSeries"],
        "iconUrl": "https://example.com/icon.png",
        "fileSize": 12345,
        "fileHash": "sha256-abc123..."
    }
]
```

--------------------------------

### Repository Manifest Structure

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Defines the JSON structure for a repository manifest, including repository details and a list of plugin manifest URLs. This file serves as the entry point for discovering available plugins.

```json
{
    "name": "My Extension Repository",
    "description": "Custom extensions for CloudStream",
    "manifestVersion": 1,
    "iconUrl": "https://example.com/icon.png",
    "pluginLists": [
        "https://example.com/plugins.json"
    ]
}
```

--------------------------------

### Create Various Search Response Types

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Use builder functions like newMovieSearchResponse, newTvSeriesSearchResponse, etc., to create specialized search response objects. These functions automatically handle URL fixing and metadata. Configure details like poster URL, year, quality, and episode information within the builder's lambda.

```kotlin
fun MainAPI.createSearchResponses(): List<SearchResponse> {
    return listOf(
        // Movie search result
        newMovieSearchResponse(
            name = "Action Movie",
            url = "/movie/123",  // URL is automatically fixed with mainUrl
            type = TvType.Movie
        ) {
            posterUrl = "https://example.com/poster.jpg"
            year = 2024
            addQuality("4K")
            addPoster("https://example.com/poster.jpg", headers = mapOf("Referer" to mainUrl))
        },

        // TV Series search result
        newTvSeriesSearchResponse(
            name = "Drama Series",
            url = "/series/456",
            type = TvType.TvSeries
        ) {
            episodes = 24
            year = 2023
            quality = SearchQuality.HD
        },

        // Anime search result with dub status
        newAnimeSearchResponse(
            name = "Anime Title",
            url = "/anime/789"
        ) {
            addDubStatus(DubStatus.Dubbed, episodes = 12)
            addDubStatus(DubStatus.Subbed, episodes = 24)
            // Or: addDubStatus(dubExist = true, subExist = true, dubEpisodes = 12, subEpisodes = 24)
            year = 2024
        },

        // Live stream result
        newLiveSearchResponse(
            name = "Live Channel",
            url = "/live/channel1",
            type = TvType.Live
        ) {
            lang = "en"
        },

        // Torrent result
        newTorrentSearchResponse(
            name = "Movie Torrent",
            url = "/torrent/abc"
        ) {
            quality = SearchQuality.FourK
        }
    )
}
```

--------------------------------

### Create Extractor Links for Various Stream Types

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Use this function to create and register extractor links for different video stream types, including standard video files, HLS, DASH, and DRM-protected content. Configure quality, referer, and headers as needed.

```kotlin
suspend fun createExtractorLinks(callback: (ExtractorLink) -> Unit) {

    // Standard video link
    callback(newExtractorLink(
        source = "ProviderName",
        name = "720p Server",
        url = "https://cdn.example.com/video.mp4"
    ) {
        quality = Qualities.P720.value
        referer = "https://example.com"
        headers = mapOf("Authorization" to "Bearer token123")
    })

    // HLS/M3U8 stream
    callback(newExtractorLink(
        source = "ProviderName",
        name = "HD Stream",
        url = "https://stream.example.com/playlist.m3u8",
        type = ExtractorLinkType.M3U8
    ) {
        quality = Qualities.P1080.value
    })

    // DASH stream
    callback(newExtractorLink(
        source = "ProviderName",
        name = "4K DASH",
        url = "https://stream.example.com/manifest.mpd",
        type = ExtractorLinkType.DASH
    ) {
        quality = Qualities.P2160.value
    })

    // DRM protected content (Widevine)
    callback(newDrmExtractorLink(
        source = "ProviderName",
        name = "DRM Stream",
        url = "https://drm.example.com/stream.mpd",
        type = ExtractorLinkType.DASH,
        uuid = WIDEVINE_UUID
    ) {
        licenseUrl = "https://license.example.com/widevine"
        keyRequestParameters = hashMapOf("token" to "abc123")
    })

    // ClearKey DRM
    callback(newDrmExtractorLink(
        source = "ProviderName",
        name = "ClearKey Stream",
        url = "https://example.com/encrypted.m3u8",
        type = ExtractorLinkType.M3U8,
        uuid = CLEARKEY_UUID
    ) {
        kid = "base64EncodedKid=="
        key = "base64EncodedKey=="
        kty = "oct"
    })

    // With separate audio tracks
    callback(newExtractorLink(
        source = "ProviderName",
        name = "Multi-Audio",
        url = "https://example.com/video.mp4"
    ) {
        audioTracks = listOf(
            newAudioFile("https://example.com/audio_en.m4a") {
                headers = mapOf("Referer" to "https://example.com")
            },
            newAudioFile("https://example.com/audio_jp.m4a")
        )
    })
}
```

```kotlin
val qualities = listOf(
    Qualities.P144.value,    // 144
    Qualities.P240.value,    // 240
    Qualities.P360.value,    // 360
    Qualities.P480.value,    // 480
    Qualities.P720.value,    // 720
    Qualities.P1080.value,   // 1080
    Qualities.P1440.value,   // 1440
    Qualities.P2160.value,   // 2160 (4K)
    Qualities.Unknown.value  // 400
)
```

```kotlin
val parsed = getQualityFromName("1080p") // Returns 1080
val parsed4k = getQualityFromName("4K")  // Returns 2160
```

--------------------------------

### Retrieve reCAPTCHA v3 Tokens

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Demonstrates how to obtain a CAPTCHA token for reCAPTCHA v3. Requires the URL of the page, the site key, and the referer URL.

```kotlin
// CAPTCHA token retrieval (reCAPTCHA v3)
suspend fun captchaExample() {
    val token = APIHolder.getCaptchaToken(
        url = "https://example.com",
        key = "recaptcha_site_key_here",
        referer = "https://example.com"
    )
    println("Captcha token: $token")
}
```

--------------------------------

### Auto-Detect Extractor Usage

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Utilize the loadExtractor helper function to automatically detect and use the appropriate extractor for a given URL. This simplifies the process of extracting video links and subtitles.

```kotlin
// Using the loadExtractor helper to auto-detect extractor
suspend fun extractFromUnknownHost(url: String) {
    loadExtractor(
        url = url,
        referer = "https://source-site.com",
        subtitleCallback = { subtitle ->
            println("Found subtitle: ${subtitle.lang}")
        },
        callback = { link ->
            println("Found video: ${link.url} (${link.quality}p)")
        }
    )
}
```

--------------------------------

### MainAPI - Content Provider Base Class

Source: https://context7.com/recloudstream/cloudstream/llms.txt

The MainAPI abstract class is the foundation for all content provider extensions in CloudStream. It defines methods for fetching content, searching, loading details, and extracting playable links.

```APIDOC
## MainAPI - Content Provider Base Class

### Description
The `MainAPI` abstract class serves as the foundation for all content providers (extensions) in CloudStream. It defines the structure for fetching media information, searching content, loading media details, and extracting playable links. Every extension must implement this class to integrate with CloudStream.

### Class Structure
```kotlin
abstract class MainAPI {
    abstract var name: String
    abstract var mainUrl: String
    abstract var lang: String
    abstract val hasMainPage: Boolean
    abstract val supportedTypes: Set<TvType>
    open val mainPage: List<MainPageData> = emptyList()

    // Methods to be implemented by extensions
    abstract suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse?
    abstract suspend fun search(query: String): List<SearchResponse>?
    abstract suspend fun load(url: String): LoadResponse?
    abstract suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean

    // Helper methods and properties provided by the framework
    val app: AppUtils
    fun newMovieSearchResponse(...): SearchResponse
    fun newTvSeriesSearchResponse(...): SearchResponse
    fun newHomePageResponse(...): HomePageResponse
    fun newMovieLoadResponse(...): LoadResponse
    fun newSubtitleFile(...): SubtitleFile
    fun newExtractorLink(...): ExtractorLink
}
```

### Example Provider Implementation
This example demonstrates how to implement the `MainAPI` class to create a functional content provider.

```kotlin
// Creating a basic content provider extension
class ExampleProvider : MainAPI() {
    override var name = "Example Provider"
    override var mainUrl = "https://example.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Define homepage sections
    override val mainPage = listOf(
        MainPageData("Latest Movies", "/movies/latest"),
        MainPageData("TV Series", "/series/latest"),
        MainPageData("Trending", "/trending", horizontalImages = true)
    )

    // Fetch homepage content with pagination
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl${request.data}?page=$page").document
        val items = document.select(".media-item").map {
            newMovieSearchResponse(
                name = it.selectFirst("h3")?.text() ?: "",
                url = it.selectFirst("a")?.attr("href") ?: ""
            ) {
                posterUrl = it.selectFirst("img")?.attr("src")
                year = it.selectFirst(".year")?.text()?.toIntOrNull()
            }
        }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // Search for content
    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select(".search-result").map {
            newTvSeriesSearchResponse(
                name = it.selectFirst("h3")?.text() ?: "",
                url = it.selectFirst("a")?.attr("href") ?: ""
            ) {
                posterUrl = it.selectFirst("img")?.attr("src")
                addQuality("HD")
            }
        }
    }

    // Load media details page
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        val plot = document.selectFirst(".description")?.text()
        val posterUrl = document.selectFirst(".poster img")?.attr("src")

        // For movies
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = document.selectFirst(".play-button")?.attr("data-url") ?: ""
        ) {
            this.plot = plot
            this.posterUrl = posterUrl
            addScore("8.5", maxValue = 10)
            addDuration("2h 15min")
            tags = listOf("Action", "Drama")
            addTrailer("https://youtube.com/watch?v=example")
        }
    }

    // Extract playable video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Add subtitle
        subtitleCallback(
            newSubtitleFile("English", "https://example.com/subs/en.srt")
        )

        // Add video link
        callback(
            newExtractorLink(
                source = name,
                name = "HD Server",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.P1080.value
                referer = mainUrl
            }
        )
        return true
    }
}
```
```

--------------------------------

### Repository Manager Operations

Source: https://context7.com/recloudstream/cloudstream/llms.txt

Perform programmatic operations on plugin repositories using the RepositoryManager. This includes parsing repository URLs, fetching repository and plugin data, adding repositories to the user's list, and retrieving all configured repositories.

```kotlin
suspend fun repositoryOperations() {
    // Parse and validate repository URL
    val repoUrl = RepositoryManager.parseRepoUrl("cloudstreamrepo://example.com/repo.json")

    // Fetch repository info
    val repository = RepositoryManager.parseRepository(repoUrl!!)
    println("Repository: ${repository?.name}")

    // Get all plugins from repository
    val plugins = RepositoryManager.getRepoPlugins(repoUrl)
    plugins?.forEach { (repoUrl, plugin) ->
        println("Plugin: ${plugin.name} v${plugin.version}")
    }

    // Add repository to user's list
    RepositoryManager.addRepository(
        RepositoryData(
            url = repoUrl,
            name = repository?.name ?: "Unknown"
        )
    )

    // Get all configured repositories
    val allRepos = RepositoryManager.getRepositories()
}
```

=== COMPLETE CONTENT === This response contains all available snippets from this library. No additional content exists. Do not make further requests.