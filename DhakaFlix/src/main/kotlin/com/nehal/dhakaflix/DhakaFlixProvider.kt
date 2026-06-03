package com.nehal.dhakaflix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Deferred

open class DhakaFlixProvider : MainAPI() {
    override var name = "DhakaFlix"
    override var mainUrl = "http://172.16.50.12"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    private val tvHost = "http://172.16.50.12"
    private val movieHost = "http://172.16.50.14"
    private val kolkataHost = "http://172.16.50.7"
    private val animeHost = "http://172.16.50.9"

    private val tvRootPath = "/DHAKA-FLIX-12/TV-WEB-Series/"
    private val movieRootPath = "/DHAKA-FLIX-14/English%20Movies%20%281080p%29/"

    private data class Category(
        val key: String,
        val path: String,
        val name: String,
        val type: TvType,
        val host: String
    )

    private data class ListingItem(
        val title: String,
        val url: String,
        val type: TvType,
        val host: String,
        val folderHref: String
    )

    private val tvGroups = mapOf(
        "tv:0-9" to "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%98%85%20%200%20%20%E2%80%94%20%209/",
        "tv:a-l" to "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/",
        "tv:m-r" to "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A6%20%20M%20%20%E2%80%94%20%20R/",
        "tv:s-z" to "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A6%20%20S%20%20%E2%80%94%20%20Z/"
    )

    private val animeGroups = mapOf(
        "anime:0-9" to "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%98%85%20%200%20%20%E2%80%94%20%209/",
        "anime:a-f" to "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20F/",
        "anime:g-m" to "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A5%20%20G%20%20%E2%80%94%20%20M/",
        "anime:n-s" to "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A6%20%20N%20%20%E2%80%94%20%20S/",
        "anime:t-z" to "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A6%20%20T%20%20%E2%80%94%20%20Z/"
    )

    private val movieCategories = listOf(
        Category("movie:latest", movieRootPath, "English Movies 1080p - Latest", TvType.Movie, movieHost),
        Category("movie:english-7", "/DHAKA-FLIX-7/English%20Movies/", "English Movies", TvType.Movie, kolkataHost),
        Category("movie:hindi", "/DHAKA-FLIX-14/Hindi%20Movies/", "Hindi Movies", TvType.Movie, movieHost),
        Category("movie:south-dubbed", "/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/", "South-Movie Hindi Dubbed", TvType.Movie, movieHost),
        Category("movie:kolkata-bangla", "/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/", "Kolkata Bangla Movies", TvType.Movie, kolkataHost),
        Category("movie:animation-1080p", "/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/", "Animation Movies - 1080p", TvType.Movie, movieHost),
        Category("movie:imdb-top250", "/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/", "IMDb Top-250 Movies", TvType.Movie, movieHost),
        Category("movie:korean-tv", "/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/", "KOREAN TV & WEB Series", TvType.TvSeries, movieHost),
        Category("movie:anime", "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/", "Anime & Cartoon TV Series", TvType.Anime, animeHost)
    )

    private val movieCategoryMap = movieCategories.associateBy { it.key }

    override val mainPage = mainPageOf(
        "tv:all" to "TV Series 0-9 & A-Z",
        "movie:latest" to "English Movies 1080p - Latest",
        "movie:english-7" to "English Movies",
        "movie:hindi" to "Hindi Movies",
        "movie:south-dubbed" to "South-Movie Hindi Dubbed",
        "movie:kolkata-bangla" to "Kolkata Bangla Movies",
        "movie:animation-1080p" to "Animation Movies - 1080p",
        "movie:imdb-top250" to "IMDb Top-250 Movies",
        "movie:korean-tv" to "KOREAN TV & WEB Series",
        "movie:anime" to "Anime & Cartoon TV Series"
    )

    private val mapper = jacksonObjectMapper()
    private val posterCache = mutableMapOf<String, String?>()
    private val childrenCache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)
    private val childrenCacheTtlMs = 15 * 60 * 1000L // 15 minutes TTL for stable H5ai directories
    private val childrenCacheMaxSize = 1000 // Large cache size to hold all traversed folders
    private val posterFileNames = listOf("a_AL_.jpg", "a11.jpg")

    // Concurrent request de-duplication variables
    private val cacheMutex = Mutex()
    private val keyLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private data class CacheEntry(
        val timestampMs: Long,
        val items: List<H5Item>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class H5ItemsResponse(val items: List<H5Item> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class H5Item(
        val href: String = "",
        val time: Long? = null,
        val size: Long? = null
    ) {
        val isFolder: Boolean
            get() = size == null && href.endsWith("/")
    }

    private fun buildSearchResponse(title: String, url: String, type: TvType, posterUrl: String? = null): SearchResponse {
        return if (type == TvType.TvSeries || type == TvType.Anime) {
            newTvSeriesSearchResponse(title, url, type) {
                if (!posterUrl.isNullOrBlank()) this.posterUrl = posterUrl
                addQuality("Dual Audio")
            }
        } else {
            newMovieSearchResponse(title, url, type) {
                if (!posterUrl.isNullOrBlank()) this.posterUrl = posterUrl
                addQuality("Dual Audio")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageSize = 40
        val items = when {
            request.data == "tv:all" -> {
                coroutineScope {
                    tvGroups.values
                        .map { groupPath -> async { fetchDirectChildren(tvHost, groupPath) } }
                        .awaitAll()
                        .flatten()
                        .filter { it.isFolder }
                        .distinctBy { it.href }
                        .sortedByDescending { it.time ?: 0L }
                        .mapNotNull { item ->
                            val title = cleanName(decodeNameFromHref(item.href))
                            if (title.isEmpty()) return@mapNotNull null
                            val url = absoluteUrl(tvHost, item.href)
                            ListingItem(title, url, TvType.TvSeries, tvHost, item.href)
                        }
                }
            }
            request.data.startsWith("tv:") -> {
                val groupPath = tvGroups[request.data] ?: tvRootPath
                fetchDirectChildren(tvHost, groupPath)
                    .filter { it.isFolder }
                    .sortedByDescending { it.time ?: 0L }
                    .mapNotNull { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.isEmpty()) return@mapNotNull null
                        val url = absoluteUrl(tvHost, item.href)
                        ListingItem(title, url, TvType.TvSeries, tvHost, item.href)
                    }
            }
            request.data.startsWith("movie:") -> {
                val category = movieCategoryMap[request.data]
                val categoryPath = category?.path ?: movieRootPath
                val host = category?.host ?: movieHost
                val children = when (request.data) {
                    "movie:latest" -> {
                        val latestYearPath = findLatestMovieYearPath()
                        if (latestYearPath != null) {
                            fetchDirectChildren(movieHost, latestYearPath)
                        } else {
                            fetchYearIndexedMovieFolders(movieHost, movieRootPath, 2023, 2026)
                        }
                    }
                    "movie:english-7" -> fetchYearIndexedMovieFolders(kolkataHost, categoryPath, 2023, 2026)
                    "movie:hindi" -> fetchYearIndexedMovieFolders(movieHost, categoryPath, 2023, 2026)
                    "movie:south-dubbed" -> fetchYearIndexedMovieFolders(movieHost, categoryPath, 2023, 2026)
                    "movie:kolkata-bangla" -> fetchYearIndexedMovieFolders(kolkataHost, categoryPath, 2021, 2026)
                    "movie:anime" -> coroutineScope {
                        animeGroups.values
                            .map { groupPath -> async { fetchDirectChildren(animeHost, groupPath) } }
                            .awaitAll()
                            .flatten()
                    }
                    else -> fetchDirectChildren(host, categoryPath)
                }
                children
                    .filter { it.isFolder }
                    .sortedByDescending { it.time ?: 0L }
                    .mapNotNull { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.isEmpty()) return@mapNotNull null
                        val url = absoluteUrl(host, item.href)
                        val type = category?.type ?: TvType.Movie
                        ListingItem(title, url, type, host, item.href)
                    }
            }
            else -> emptyList()
        }

        val paged = items.drop((page - 1) * pageSize).take(pageSize)
        val responses = ArrayList<SearchResponse>(paged.size)
        for (item in paged) {
            val posterUrl = guessPosterUrl(item.host, item.folderHref)
            responses.add(buildSearchResponse(item.title, item.url, item.type, posterUrl))
        }
        return newHomePageResponse(request.name, responses)
    }

    private fun getTvGroupForQuery(query: String): String? {
        val firstChar = query.trim().firstOrNull()?.lowercaseChar() ?: return null
        return when (firstChar) {
            in '0'..'9' -> tvGroups["tv:0-9"]
            in 'a'..'l' -> tvGroups["tv:a-l"]
            in 'm'..'r' -> tvGroups["tv:m-r"]
            in 's'..'z' -> tvGroups["tv:s-z"]
            else -> null
        }
    }

    private fun getAnimeGroupForQuery(query: String): String? {
        val firstChar = query.trim().firstOrNull()?.lowercaseChar() ?: return null
        return when (firstChar) {
            in '0'..'9' -> animeGroups["anime:0-9"]
            in 'a'..'f' -> animeGroups["anime:a-f"]
            in 'g'..'m' -> animeGroups["anime:g-m"]
            in 'n'..'s' -> animeGroups["anime:n-s"]
            in 't'..'z' -> animeGroups["anime:t-z"]
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val normalized = query.trim()
        if (normalized.isEmpty()) return@coroutineScope emptyList()

        val queryLower = normalized.lowercase()
        val queryYear = extractYear(normalized)
        val maxResults = 60
        val results = ArrayList<SearchResponse>()

        // Helper to perform TV searching on specified groups
        suspend fun searchTvGroups(groupsToSearch: Collection<String>): List<SearchResponse> {
            val tvDeferreds = groupsToSearch.map { groupPath ->
                async {
                    fetchDirectChildren(tvHost, groupPath)
                        .filter { it.isFolder }
                        .mapNotNull { item ->
                            val title = cleanName(decodeNameFromHref(item.href))
                            if (title.lowercase().contains(queryLower)) {
                                val posterUrl = guessPosterUrl(tvHost, item.href)
                                buildSearchResponse(title, absoluteUrl(tvHost, item.href), TvType.TvSeries, posterUrl)
                            } else null
                        }
                }
            }
            return tvDeferreds.awaitAll().flatten()
        }

        // Helper to search movie categories under specified year constraint
        suspend fun searchMovieCategories(minYear: Int?, maxYear: Int?): List<SearchResponse> {
            val categoriesToSearch = movieCategories.filter { it.key != "movie:latest" && it.key != "movie:anime" }
            val movieDeferreds = categoriesToSearch.map { category ->
                async {
                    val items = when (category.key) {
                        "movie:english-7" -> fetchYearIndexedMovieFolders(category.host, category.path, minYear, maxYear, queryYear)
                        "movie:hindi" -> fetchYearIndexedMovieFolders(category.host, category.path, minYear, maxYear, queryYear)
                        "movie:south-dubbed" -> fetchYearIndexedMovieFolders(category.host, category.path, minYear, maxYear, queryYear)
                        "movie:kolkata-bangla" -> fetchYearIndexedMovieFolders(category.host, category.path, minYear, maxYear, queryYear)
                        else -> fetchDirectChildren(category.host, category.path)
                    }.filter { it.isFolder }

                    items.mapNotNull { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.lowercase().contains(queryLower)) {
                            val url = absoluteUrl(category.host, item.href)
                            val posterUrl = guessPosterUrl(category.host, item.href)
                            buildSearchResponse(title, url, category.type, posterUrl)
                        } else null
                    }
                }
            }
            return movieDeferreds.awaitAll().flatten()
        }

        // Helper to search general English movies under specified year constraint
        suspend fun searchEnglishMovies(minYear: Int?, maxYear: Int?): List<SearchResponse> {
            val yearFolders = fetchDirectChildren(movieHost, movieRootPath)
                .filter { it.isFolder }
                .filter { item ->
                    val year = extractYear(decodeNameFromHref(item.href)) ?: return@filter false
                    if (queryYear != null) {
                        year == queryYear
                    } else {
                        (minYear == null || year >= minYear) && (maxYear == null || year <= maxYear)
                    }
                }

            val englishDeferreds = yearFolders.map { yearFolder ->
                async {
                    val movieItems = fetchDirectChildren(movieHost, yearFolder.href)
                        .filter { it.isFolder }

                    movieItems.mapNotNull { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.lowercase().contains(queryLower)) {
                            val posterUrl = guessPosterUrl(movieHost, item.href)
                            buildSearchResponse(title, absoluteUrl(movieHost, item.href), TvType.Movie, posterUrl)
                        } else null
                    }
                }
            }
            return englishDeferreds.awaitAll().flatten()
        }

        // ==========================================
        // PHASE 1: TARGETED & RECENT SEARCH (PARALLEL)
        // ==========================================
        // Helper to perform Anime searching on specified groups
        suspend fun searchAnimeGroups(groupsToSearch: Collection<String>): List<SearchResponse> {
            val animeDeferreds = groupsToSearch.map { groupPath ->
                async {
                    fetchDirectChildren(animeHost, groupPath)
                        .filter { it.isFolder }
                        .mapNotNull { item ->
                            val title = cleanName(decodeNameFromHref(item.href))
                            if (title.lowercase().contains(queryLower)) {
                                val posterUrl = guessPosterUrl(animeHost, item.href)
                                buildSearchResponse(title, absoluteUrl(animeHost, item.href), TvType.Anime, posterUrl)
                            } else null
                        }
                }
            }
            return animeDeferreds.awaitAll().flatten()
        }

        // Determine alphabetical TV group matching the query
        val targetTvGroup = getTvGroupForQuery(normalized)
        val phase1TvGroups = if (targetTvGroup != null) listOf(targetTvGroup) else tvGroups.values

        // Determine alphabetical Anime group matching the query
        val targetAnimeGroup = getAnimeGroupForQuery(normalized)
        val phase1AnimeGroups = if (targetAnimeGroup != null) listOf(targetAnimeGroup) else animeGroups.values

        // If queryYear is provided, we skip recency tier and target that year directly
        val recentMinYear = if (queryYear != null) null else 2018

        val phase1TvDeferred = async { searchTvGroups(phase1TvGroups) }
        val phase1AnimeDeferred = async { searchAnimeGroups(phase1AnimeGroups) }
        val phase1MoviesDeferred = async { searchMovieCategories(recentMinYear, 2026) }
        val phase1EnglishDeferred = async { searchEnglishMovies(recentMinYear, 2026) }

        results.addAll(phase1TvDeferred.await())
        results.addAll(phase1AnimeDeferred.await())
        results.addAll(phase1MoviesDeferred.await())
        results.addAll(phase1EnglishDeferred.await())

        // ==========================================
        // PHASE 2: FALLBACK (OLDER YEARS & OTHER GROUPS)
        // ==========================================
        // Only run Phase 2 if we got less than 5 results and no specific year was targeted
        if (results.size < 5 && queryYear == null) {
            val remainingTvGroups = tvGroups.values.filter { it !in phase1TvGroups }
            val phase2TvDeferred = if (remainingTvGroups.isNotEmpty()) async { searchTvGroups(remainingTvGroups) } else null

            val remainingAnimeGroups = animeGroups.values.filter { it !in phase1AnimeGroups }
            val phase2AnimeDeferred = if (remainingAnimeGroups.isNotEmpty()) async { searchAnimeGroups(remainingAnimeGroups) } else null
            
            // Search older years (pre-2018)
            val phase2MoviesDeferred = async { searchMovieCategories(1995, 2017) }
            val phase2EnglishDeferred = async { searchEnglishMovies(1995, 2017) }

            phase2TvDeferred?.await()?.let { results.addAll(it) }
            phase2AnimeDeferred?.await()?.let { results.addAll(it) }
            results.addAll(phase2MoviesDeferred.await())
            results.addAll(phase2EnglishDeferred.await())
        }

        results.distinctBy { it.url }.take(maxResults)
    }

    private suspend fun fetchYearIndexedMovieFolders(
        host: String,
        rootPath: String,
        minYear: Int? = null,
        maxYear: Int? = null,
        queryYear: Int? = null
    ): List<H5Item> = coroutineScope {
        val yearFolders = fetchDirectChildren(host, rootPath)
            .filter { it.isFolder }
            .filter { item ->
                val year = extractYear(decodeNameFromHref(item.href)) ?: return@filter false
                if (queryYear != null) {
                    year == queryYear
                } else {
                    (minYear == null || year >= minYear) && (maxYear == null || year <= maxYear)
                }
            }

        val deferreds = yearFolders.map { yearFolder ->
            async {
                fetchDirectChildren(host, yearFolder.href).filter { it.isFolder }
            }
        }
        deferreds.awaitAll().flatten().distinctBy { it.href }
    }

    override suspend fun load(url: String): LoadResponse {
        val type = categoryTypeForUrl(url) ?: if (url.contains("/TV-WEB-Series/")) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries || type == TvType.Anime) {
            loadTvSeries(url, type)
        } else {
            loadMovie(url)
        }
    }

    private fun categoryTypeForUrl(url: String): TvType? {
        val normalizedUrlPath = pathFromUrl(url)
        return movieCategories.firstOrNull { normalizedUrlPath.startsWith(normalizePath(it.path)) }?.type
    }

    private suspend fun loadTvSeries(url: String, type: TvType): LoadResponse {
        val host = hostForUrl(url)
        val seriesPath = pathFromUrl(url)
        val title = cleanName(decodeNameFromUrl(url))
        if (title.isEmpty()) throw ErrorLoadingException("Missing title")

        val seriesItems = fetchDirectChildren(host, seriesPath)
        val posterUrl = pickPoster(host, seriesItems)
        val year = extractYear(title)

        val seasonFolders = seriesItems.filter { it.isFolder && isSeasonFolder(it.href) }
        val episodes = ArrayList<Episode>()

        if (seasonFolders.isNotEmpty()) {
            val sortedSeasons = seasonFolders.sortedBy {
                extractSeasonNumber(decodeNameFromHref(it.href)) ?: Int.MAX_VALUE
            }

            for (season in sortedSeasons) {
                val seasonPath = normalizePath(season.href)
                val seasonNumber = extractSeasonNumber(decodeNameFromHref(season.href))
                val seasonItems = fetchDirectChildren(host, seasonPath)
                    .filter { isVideoFile(it.href) }

                seasonItems.sortedBy { it.href }.forEachIndexed { index, item ->
                    val fileName = decodeNameFromHref(item.href)
                    val episodeNumber = extractEpisodeNumber(fileName) ?: (index + 1)
                    val derivedSeason = extractSeasonFromFileName(fileName) ?: seasonNumber

                    episodes.add(
                        newEpisode(absoluteUrl(host, item.href)) {
                            this.name = cleanFileName(fileName)
                            this.episode = episodeNumber
                            this.season = derivedSeason ?: seasonNumber
                        }
                    )
                }
            }
        } else {
            val seasonItems = seriesItems.filter { isVideoFile(it.href) }
            seasonItems.sortedBy { it.href }.forEachIndexed { index, item ->
                val fileName = decodeNameFromHref(item.href)
                val episodeNumber = extractEpisodeNumber(fileName) ?: (index + 1)
                val derivedSeason = extractSeasonFromFileName(fileName)

                episodes.add(
                    newEpisode(absoluteUrl(host, item.href)) {
                        this.name = cleanFileName(fileName)
                        this.episode = episodeNumber
                        this.season = derivedSeason
                    }
                )
            }
        }

        rememberPoster(host, seriesPath, posterUrl)
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val host = hostForUrl(url)
        val moviePath = pathFromUrl(url)
        val title = cleanName(decodeNameFromUrl(url))
        if (title.isEmpty()) throw ErrorLoadingException("Missing title")

        val items = fetchDirectChildren(host, moviePath)
        val posterUrl = pickPoster(host, items)
        val year = extractYear(title)

        rememberPoster(host, moviePath, posterUrl)
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = if (looksLikeVideoUrl(data)) {
            listOf(data)
        } else {
            val host = hostForUrl(data)
            val path = pathFromUrl(data)
            fetchDirectChildren(host, path)
                .filter { isVideoFile(it.href) }
                .map { absoluteUrl(host, it.href) }
        }

        if (links.isEmpty()) return false

        links.forEach { link ->
            if (link.lowercase().contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(this.name, this.name, url = link, type = ExtractorLinkType.M3U8)
                )
            } else {
                callback.invoke(
                    newExtractorLink(this.name, this.name, url = link)
                )
            }
        }

        return true
    }

    private suspend fun findLatestMovieYearPath(): String? {
        val items = fetchDirectChildren(movieHost, movieRootPath)
            .filter { it.isFolder }

        val yearItems = items.mapNotNull { item ->
            val name = decodeNameFromHref(item.href)
            val year = extractYear(name) ?: return@mapNotNull null
            year to item.href
        }

        val latest = yearItems.maxByOrNull { it.first } ?: return null
        return latest.second
    }

    private suspend fun fetchDirectChildren(host: String, href: String): List<H5Item> {
        val path = normalizePath(href)
        val cacheKey = host.trimEnd('/') + "|" + path
        
        // 1. Fast path: Check memory cache under cache lock
        val nowMs = System.currentTimeMillis()
        cacheMutex.withLock {
            childrenCache[cacheKey]?.let { entry ->
                if (nowMs - entry.timestampMs <= childrenCacheTtlMs) {
                    return entry.items
                }
            }
        }
        
        // 2. Slow path (lock per key): Fetch and cache
        val keyLock = keyLocks.computeIfAbsent(cacheKey) { Mutex() }
        return keyLock.withLock {
            // Re-check cache inside key lock
            cacheMutex.withLock {
                childrenCache[cacheKey]?.let { entry ->
                    if (System.currentTimeMillis() - entry.timestampMs <= childrenCacheTtlMs) {
                        return@withLock entry.items
                    }
                }
            }
            
            val response = app.post(
                "$host$path?",
                data = mapOf(
                    "action" to "get",
                    "items[href]" to path,
                    "items[what]" to "1"
                )
            )

            val items = runCatching {
                mapper.readValue<H5ItemsResponse>(response.text).items
            }.getOrElse { emptyList() }
            val direct = directChildren(items, path)

            // Update cache under cache lock
            cacheMutex.withLock {
                childrenCache[cacheKey] = CacheEntry(System.currentTimeMillis(), direct)
                
                // Keep LinkedHashMap size bounded
                if (childrenCache.size > childrenCacheMaxSize) {
                    val iterator = childrenCache.keys.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            
            // Periodically clean up keyLocks to prevent memory leak
            if (keyLocks.size > 500) {
                keyLocks.clear()
            }
            
            direct
        }
    }

    private fun directChildren(items: List<H5Item>, parentHref: String): List<H5Item> {
        val parent = normalizePath(parentHref)
        return items.mapNotNull { item ->
            if (!item.href.startsWith(parent) || item.href == parent) return@mapNotNull null
            val relative = item.href.removePrefix(parent)
            val clean = relative.trim('/')
            if (clean.isEmpty() || clean.contains('/')) return@mapNotNull null
            item
        }.distinctBy { it.href }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        var normalized = if (path.startsWith("/")) path else "/$path"
        if (!normalized.endsWith("/")) normalized += "/"
        return normalized
    }

    private fun hostForUrl(url: String): String {
        val uri = try { URI(url) } catch (e: Exception) { null }
        val host = uri?.host
        val scheme = uri?.scheme ?: "http"
        return if (!host.isNullOrBlank()) {
            "$scheme://$host"
        } else if (url.startsWith(movieHost)) {
            movieHost
        } else if (url.startsWith(kolkataHost)) {
            kolkataHost
        } else if (url.startsWith(animeHost)) {
            animeHost
        } else {
            tvHost
        }
    }

    private fun pathFromUrl(url: String): String {
        val uri = URI(url)
        val rawPath = uri.rawPath ?: "/"
        return normalizePath(rawPath)
    }

    private fun absoluteUrl(host: String, href: String): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") -> host.trimEnd('/') + href
            else -> host.trimEnd('/') + "/" + href
        }
    }

    private fun decodeNameFromHref(href: String): String {
        val name = href.trimEnd('/').substringAfterLast('/')
        return decodeComponent(name)
    }

    private fun decodeNameFromUrl(url: String): String {
        val rawPath = URI(url).rawPath ?: url
        val name = rawPath.trimEnd('/').substringAfterLast('/')
        return decodeComponent(name)
    }

    private fun decodeComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun cleanName(name: String): String {
        return name.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanFileName(name: String): String {
        val base = name.substringBeforeLast('.')
        return base.replace('.', ' ').replace('_', ' ').trim()
    }

    private fun extractYear(text: String): Int? {
        return Regex("(19|20)\\d{2}").find(text)?.value?.toIntOrNull()
    }

    private fun isSeasonFolder(href: String): Boolean {
        val name = decodeNameFromHref(href)
        return name.lowercase().startsWith("season")
    }

    private fun extractSeasonNumber(name: String): Int? {
        return Regex("season\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSeasonFromFileName(name: String): Int? {
        return Regex("s(\\d{1,2})e\\d{1,3}", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(name: String): Int? {
        return Regex("s\\d{1,2}e(\\d{1,3})", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("e(\\d{1,3})", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("episode\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun pickPoster(host: String, items: List<H5Item>): String? {
        val posterItem = items.firstOrNull {
            isImageFile(it.href) && it.href.contains("a_VL_", ignoreCase = true)
        } ?: items.firstOrNull {
            isImageFile(it.href) && it.href.contains("a_AL_", ignoreCase = true)
        } ?: items.firstOrNull {
            isImageFile(it.href) && it.href.contains("a11", ignoreCase = true)
        } ?: items.firstOrNull {
            isImageFile(it.href)
        }

        return posterItem?.let { absoluteUrl(host, it.href) }
    }

    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "m3u8", "mpd"
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp"
    )

    private fun isVideoFile(href: String): Boolean {
        return hasExtension(href, videoExtensions)
    }

    private fun isImageFile(href: String): Boolean {
        return hasExtension(href, imageExtensions)
    }

    private fun guessPosterUrl(host: String, folderHref: String): String {
        val folderPath = if (folderHref.startsWith("http://") || folderHref.startsWith("https://")) {
            pathFromUrl(folderHref)
        } else {
            normalizePath(folderHref)
        }
        val cacheKey = posterCacheKey(host, folderPath)
        posterCache[cacheKey]?.let { cachedUrl ->
            if (!cachedUrl.isNullOrBlank()) return cachedUrl
        }

        val nowMs = System.currentTimeMillis()
        childrenCache[cacheKey]?.let { entry ->
            if (nowMs - entry.timestampMs <= childrenCacheTtlMs) {
                val posterItem = entry.items.firstOrNull { item ->
                    val href = item.href.lowercase()
                    posterFileNames.any { name -> href.endsWith("/" + name.lowercase()) || href.endsWith(name.lowercase()) }
                }
                if (posterItem != null) {
                    return absoluteUrl(host, posterItem.href)
                }
            }
        }

        val fallbackFile = posterFileNames.firstOrNull() ?: "a_AL_.jpg"
        return absoluteUrl(host, folderPath + fallbackFile)
    }

    private fun posterCacheKey(host: String, folderPath: String): String {
        return host.trimEnd('/') + "|" + normalizePath(folderPath)
    }

    private fun rememberPoster(host: String, folderPath: String, posterUrl: String?) {
        if (posterUrl.isNullOrBlank()) return
        val cacheKey = posterCacheKey(host, folderPath)
        posterCache[cacheKey] = posterUrl
    }

    private suspend fun resolvePoster(host: String, folderHref: String): String? {
        val folderPath = if (folderHref.startsWith("http://") || folderHref.startsWith("https://")) {
            pathFromUrl(folderHref)
        } else {
            folderHref
        }
        val cacheKey = posterCacheKey(host, folderPath)
        if (posterCache.containsKey(cacheKey)) {
            return posterCache[cacheKey]
        }

        val items = fetchDirectChildren(host, folderPath)
        val posterUrl = pickPoster(host, items)
        posterCache[cacheKey] = posterUrl
        return posterUrl
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        return hasExtension(url, videoExtensions)
    }

    private fun hasExtension(url: String, extensions: Set<String>): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return extensions.any { ext -> clean.endsWith(".$ext") }
    }
}
