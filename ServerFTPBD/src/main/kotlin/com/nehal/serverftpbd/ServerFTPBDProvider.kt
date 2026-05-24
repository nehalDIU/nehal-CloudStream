package com.nehal.serverftpbd

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
import java.util.LinkedHashMap
import com.lagradost.nicehttp.Requests
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

open class ServerFTPBDProvider : MainAPI() {
    override var name = "ServerFTPBD"
    override var mainUrl = "https://server3.ftpbd.net/"
    override var lang = "bn"

    private val customApp by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
        Requests(client)
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    private data class Category(
        val key: String,
        val path: String,
        val name: String,
        val type: TvType,
        val host: String,
        val maxDepth: Int
    )

    private val categories = listOf(
        Category("movie:english", "/FTP-2/English%20Movies/", "English Movies", TvType.Movie, "https://server2.ftpbd.net", 2),
        Category("movie:english-dub", "/FTP-2/English%20Movies/Dual-Audio/", "English Dub (Dual-Audio)", TvType.Movie, "https://server2.ftpbd.net", 2),
        Category("movie:hindi", "/FTP-3/Hindi%20Movies/", "Hindi Movies", TvType.Movie, "https://server3.ftpbd.net", 2),
        Category("movie:bangla", "/FTP-3/Bangla%20Collection/BANGLA/", "Bangla Movies", TvType.Movie, "https://server3.ftpbd.net", 3),
        Category("tv:english", "/FTP-4/English-Foreign-TV-Series/", "English & Foreign TV Series", TvType.TvSeries, "https://server4.ftpbd.net", 2),
        Category("tv:hindi", "/FTP-3/Hindi%20TV%20Series/", "Hindi TV Series", TvType.TvSeries, "https://server3.ftpbd.net", 2),
        Category("tv:anime", "/FTP-5/Anime--Cartoon-TV-Series/", "Anime & Cartoon TV Series", TvType.Anime, "https://server5.ftpbd.net", 2)
    )

    override val mainPage = mainPageOf(
        "movie:english" to "English Movies",
        "movie:english-dub" to "English Dub (Dual-Audio)",
        "movie:hindi" to "Hindi Movies",
        "movie:bangla" to "Bangla Movies",
        "tv:english" to "English & Foreign TV Series",
        "tv:hindi" to "Hindi TV Series",
        "tv:anime" to "Anime & Cartoon TV Series"
    )

    private val mapper = jacksonObjectMapper()
    private val posterCache = mutableMapOf<String, String?>()
    private val childrenCache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)
    private val childrenCacheTtlMs = 15 * 60 * 1000L // 15 minutes TTL for stable H5ai directories
    private val posterFileNames = listOf("a_AL_.jpg", "a_VL_.jpg", "a11.jpg")

    private val fetchMutex = Mutex()

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
                addQuality("HD")
            }
        } else {
            newMovieSearchResponse(title, url, type) {
                if (!posterUrl.isNullOrBlank()) this.posterUrl = posterUrl
                addQuality("HD")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val category = categories.firstOrNull { it.key == request.data }
            ?: return@coroutineScope newHomePageResponse(request.name, emptyList())

        val pageSize = 40
        val targetCount = page * pageSize
        val collectedItems = mutableListOf<H5Item>()

        // 1. Fetch group folders under the category root
        val groupFolders = fetchDirectChildren(category.host, category.path)
            .filter { it.isFolder }

        // 2. Classify and sort the group folders
        val specialtyFolders = mutableListOf<H5Item>()
        val yearFolders = mutableListOf<Pair<Int, H5Item>>()
        val otherFolders = mutableListOf<H5Item>()

        groupFolders.forEach { item ->
            val name = decodeNameFromHref(item.href)
            val year = extractYear(name)
            when {
                name.contains("4k", ignoreCase = true) || 
                name.contains("top", ignoreCase = true) || 
                name.contains("series", ignoreCase = true) || 
                name.contains("collection", ignoreCase = true) -> specialtyFolders.add(item)
                
                year != null -> yearFolders.add(year to item)
                else -> otherFolders.add(item)
            }
        }

        // Sort year folders descending (latest first)
        val sortedYears = yearFolders.sortedByDescending { it.first }.map { it.second }
        val orderedGroupFolders = specialtyFolders + sortedYears + otherFolders

        // 3. Progressively traverse group folders in parallel until we have enough items
        // We limit the number of folders scanned to prevent massive concurrency timeouts
        val maxFoldersToScan = 6 + (page - 1) * 3
        val foldersToScan = orderedGroupFolders.take(maxFoldersToScan)

        val traversedResults = foldersToScan.map { groupFolder ->
            async {
                val subMedia = traverseForMediaFolders(category.host, groupFolder.href, 2, category.maxDepth)
                subMedia.sortedByDescending { it.time ?: 0L }
            }
        }.awaitAll()

        for (sortedSub in traversedResults) {
            collectedItems.addAll(sortedSub)
        }

        // If we still need items, we also check the root itself for direct media folders
        if (collectedItems.size < targetCount) {
            val directMedia = groupFolders.filter { !isGroupFolder(decodeNameFromHref(it.href)) }
            collectedItems.addAll(directMedia.sortedByDescending { it.time ?: 0L })
        }

        val distinctMedia = collectedItems.distinctBy { it.href }
        val paged = distinctMedia.drop((page - 1) * pageSize).take(pageSize)

        val responses = paged.map { item ->
            val title = cleanName(decodeNameFromHref(item.href))
            val url = absoluteUrl(category.host, item.href)
            val posterUrl = guessPosterUrl(category.host, item.href)
            buildSearchResponse(title, url, category.type, posterUrl)
        }

        newHomePageResponse(request.name, responses, hasNext = distinctMedia.size > targetCount)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val normalized = query.trim()
        if (normalized.isEmpty()) return@coroutineScope emptyList()

        val queryLower = normalized.lowercase()
        val queryYear = extractYear(normalized)
        
        // Search all categories in parallel
        val deferreds = categories.map { category ->
            async {
                val mediaFolders = traverseForMediaFolders(category.host, category.path, 1, category.maxDepth, queryYear)
                mediaFolders.mapNotNull { item ->
                    val title = cleanName(decodeNameFromHref(item.href))
                    if (title.lowercase().contains(queryLower)) {
                        val url = absoluteUrl(category.host, item.href)
                        val posterUrl = guessPosterUrl(category.host, item.href)
                        buildSearchResponse(title, url, category.type, posterUrl)
                    } else null
                }
            }
        }
        
        deferreds.awaitAll().flatten().distinctBy { it.url }
    }

    private suspend fun traverseForMediaFolders(
        host: String,
        path: String,
        currentDepth: Int,
        maxDepth: Int,
        queryYear: Int? = null
    ): List<H5Item> = coroutineScope {
        val children = fetchDirectChildren(host, path)
        val mediaFolders = mutableListOf<H5Item>()
        
        val hasVideoFiles = children.any { !it.isFolder && isVideoFile(it.href) }
        if (hasVideoFiles) {
            return@coroutineScope listOf(H5Item(href = path, size = null))
        }
        
        val folderChildren = children.filter { it.isFolder }
        
        // Filter year folders based on queryYear or threshold (2023) to optimize speed
        val filteredFolders = folderChildren.filter { item ->
            val folderName = decodeNameFromHref(item.href)
            if (isGroupFolder(folderName)) {
                val folderYear = extractYear(folderName)
                if (folderYear != null) {
                    if (queryYear != null) {
                        folderYear == queryYear
                    } else {
                        folderYear >= 2023
                    }
                } else {
                    true
                }
            } else {
                true
            }
        }

        val deferreds = filteredFolders.map { item ->
            async {
                val folderName = decodeNameFromHref(item.href)
                if (currentDepth < maxDepth && isGroupFolder(folderName)) {
                    traverseForMediaFolders(host, item.href, currentDepth + 1, maxDepth, queryYear)
                } else {
                    listOf(item)
                }
            }
        }
        deferreds.awaitAll().forEach { mediaFolders.addAll(it) }
        mediaFolders
    }

    private fun isGroupFolder(name: String): Boolean {
        val clean = name.trim().lowercase()
        if (clean.contains("before") || clean.contains("collection") || clean.contains("series")) return true
        if (clean == "dual-audio" || clean == "english-movies-4k" || clean == "imdb-top-250" || clean == "hindi-4k-movies" || clean == "kolkata-bangla-movies" || clean == "bangla") return true
        if (Regex(".*\\b\\d{4}\\b.*").matches(clean)) return true
        return false
    }

    override suspend fun load(url: String): LoadResponse {
        val type = categoryTypeForUrl(url)
        return if (type == TvType.TvSeries || type == TvType.Anime) {
            loadTvSeries(url, type)
        } else {
            loadMovie(url)
        }
    }

    private fun categoryTypeForUrl(url: String): TvType {
        val path = pathFromUrl(url)
        val category = categories.firstOrNull { path.startsWith(it.path) }
        return category?.type ?: when {
            url.contains("TV-Series", ignoreCase = true) || url.contains("TV%20Series", ignoreCase = true) -> TvType.TvSeries
            url.contains("Anime", ignoreCase = true) -> TvType.Anime
            else -> TvType.Movie
        }
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

    private suspend fun fetchDirectChildren(host: String, href: String): List<H5Item> {
        val path = normalizePath(href)
        val cacheKey = host.trimEnd('/') + "|" + path
        
        val nowMs = System.currentTimeMillis()
        childrenCache[cacheKey]?.let { entry ->
            if (nowMs - entry.timestampMs <= childrenCacheTtlMs) {
                return entry.items
            }
        }
        
        return fetchMutex.withLock {
            childrenCache[cacheKey]?.let { entry ->
                if (System.currentTimeMillis() - entry.timestampMs <= childrenCacheTtlMs) {
                    return@withLock entry.items
                }
            }
            
            val response = try {
                customApp.post(
                    "${host.trimEnd('/')}$path?",
                    data = mapOf(
                        "action" to "get",
                        "items[href]" to path,
                        "items[what]" to "1"
                    )
                )
            } catch (e: Exception) {
                if (host.startsWith("https://")) {
                    val fallbackHost = host.replace("https://", "http://")
                    try {
                        customApp.post(
                            "${fallbackHost.trimEnd('/')}$path?",
                            data = mapOf(
                                "action" to "get",
                                "items[href]" to path,
                                "items[what]" to "1"
                            )
                        )
                    } catch (e2: Exception) {
                        null
                    }
                } else {
                    null
                }
            }

            val items = if (response != null) {
                runCatching {
                    mapper.readValue<H5ItemsResponse>(response.text).items
                }.getOrElse { emptyList() }
            } else {
                emptyList()
            }
            val direct = directChildren(items, path)

            childrenCache[cacheKey] = CacheEntry(System.currentTimeMillis(), direct)
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
        return normalized.replace("//", "/")
    }

    private fun hostForUrl(url: String): String {
        val uri = try { URI(url) } catch (e: Exception) { null }
        val host = uri?.host
        val scheme = uri?.scheme ?: "https"
        return if (!host.isNullOrBlank()) {
            "$scheme://$host"
        } else {
            val matched = categories.firstOrNull { url.startsWith(it.host) }
            matched?.host ?: mainUrl.trimEnd('/')
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

    private fun looksLikeVideoUrl(url: String): Boolean {
        return hasExtension(url, videoExtensions)
    }

    private fun hasExtension(url: String, extensions: Set<String>): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return extensions.any { ext -> clean.endsWith(".$ext") }
    }
}
