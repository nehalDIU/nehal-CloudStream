package com.nehal.server2ftpbd

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

open class Server2FTPBDProvider : MainAPI() {
    override var name = "Server2FTPBD"
    override var mainUrl = "https://server2.ftpbd.net"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    private val mapper = jacksonObjectMapper()
    private val posterCache = mutableMapOf<String, String?>()
    private val childrenCache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)
    private val childrenCacheTtlMs = 15 * 60 * 1000L // 15 minutes TTL
    private val posterFileNames = listOf("a_AL_.jpg", "a11.jpg")

    private val fetchMutex = Mutex()

    private val unsafeClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private val client: Requests by lazy {
        Requests(unsafeClient)
    }

    private data class Category(
        val key: String,
        val path: String,
        val name: String,
        val type: TvType,
        val host: String,
        val hasYears: Boolean = false
    )

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

    private val categories = listOf(
        Category("eng-movies", "/FTP-2/English%20Movies/", "English Movies", TvType.Movie, "https://server2.ftpbd.net", hasYears = true),
        Category("dual-audio", "/FTP-2/English%20Movies/Dual-Audio/", "English Movies [Dual Audio]", TvType.Movie, "https://server2.ftpbd.net", hasYears = true),
        Category("4k-movies", "/FTP-2/English%20Movies/English-Movies-4K/", "English Movies 4K", TvType.Movie, "https://server2.ftpbd.net", hasYears = true),
        Category("3d-movies", "/FTP-2/3D%20Movies/", "3D Movies", TvType.Movie, "https://server2.ftpbd.net", hasYears = true),
        Category("imdb-250", "/FTP-2/English%20Movies/IMDB-TOP-250/", "IMDb Top 250", TvType.Movie, "https://server2.ftpbd.net", hasYears = false),
        Category("eng-series-coll", "/FTP-2/English%20Movies/Movie-Series-Collection/", "Movie Series Collection", TvType.TvSeries, "https://server2.ftpbd.net", hasYears = false),
        Category("foreign-movies", "/FTP-3/Foreign%20Language%20Movies/", "Foreign Movies", TvType.Movie, "https://server3.ftpbd.net", hasYears = true),
        Category("hindi-movies", "/FTP-3/Hindi%20Movies/", "Hindi Movies", TvType.Movie, "https://server3.ftpbd.net", hasYears = true),
        Category("south-movies", "/FTP-3/South%20Indian%20Movies/", "South Indian Movies", TvType.Movie, "https://server3.ftpbd.net", hasYears = true),
        Category("bangla-movies", "/FTP-3/Bangla%20Collection/BANGLA/Kolkata-Bangla-Movies/", "Bangla Movies", TvType.Movie, "https://server3.ftpbd.net", hasYears = true),
        Category("animation-movies", "/FTP-5/Animation%20Movies/", "Animation Movies", TvType.Movie, "https://server5.ftpbd.net", hasYears = true),
        Category("documentary", "/FTP-5/Documentary/", "Documentary", TvType.Movie, "https://server5.ftpbd.net", hasYears = false),
        
        Category("hindi-series", "/FTP-3/Hindi%20TV%20Series/", "Hindi TV Series", TvType.TvSeries, "https://server3.ftpbd.net", hasYears = false),
        Category("bangla-series", "/FTP-3/Bangla%20Collection/BANGLA/Web-Series/", "Bengali TV Series", TvType.TvSeries, "https://server3.ftpbd.net", hasYears = false),
        Category("eng-series", "/FTP-4/English-Foreign-TV-Series/", "English & Foreign TV Series", TvType.TvSeries, "https://server4.ftpbd.net", hasYears = false),
        Category("anime", "/FTP-5/Anime--Cartoon-TV-Series/", "Anime & Cartoon TV Series", TvType.Anime, "https://server5.ftpbd.net", hasYears = false),
        Category("wwe", "/FTP-7/WWE%20Wrestling/", "WWE Wrestling", TvType.TvSeries, "https://server7.ftpbd.net", hasYears = false),
        Category("aew", "/FTP-7/All%20Elite%20Wrestling%20%28AEW%29/", "All Elite Wrestling (AEW)", TvType.TvSeries, "https://server7.ftpbd.net", hasYears = false),
        Category("awards", "/FTP-7/Awards--TV-Shows/", "Awards & TV Shows", TvType.TvSeries, "https://server7.ftpbd.net", hasYears = false)
    )

    override val mainPage = mainPageOf(
        *categories.map { it.key to it.name }.toTypedArray()
    )

    private fun buildSearchResponse(title: String, url: String, type: TvType, posterUrl: String? = null): SearchResponse {
        return if (type == TvType.TvSeries || type == TvType.Anime) {
            newTvSeriesSearchResponse(title, url, type) {
                if (!posterUrl.isNullOrBlank()) this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, url, type) {
                if (!posterUrl.isNullOrBlank()) this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = categories.firstOrNull { it.key == request.data } ?: return newHomePageResponse(request.name, emptyList())
        val items = ArrayList<SearchResponse>()
        val pageSize = 40

        if (category.hasYears) {
            val yearFolders = getYearFolders(category)
            if (yearFolders.isNotEmpty()) {
                val folderIndex = page - 1
                if (folderIndex < yearFolders.size) {
                    val targetFolder = yearFolders[folderIndex]
                    val children = fetchDirectChildren(category.host, targetFolder.href)
                        .filter { it.isFolder }
                        .sortedByDescending { it.time ?: 0L }
                    
                    children.forEach { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.isNotEmpty()) {
                            val url = absoluteUrl(category.host, item.href)
                            val posterUrl = guessPosterUrl(category.host, item.href)
                            items.add(buildSearchResponse(title, url, category.type, posterUrl))
                        }
                    }
                }
            }
        } else {
            val children = fetchDirectChildren(category.host, category.path)
                .filter { it.isFolder }
                .sortedByDescending { it.time ?: 0L }
            
            val paged = children.drop((page - 1) * pageSize).take(pageSize)
            paged.forEach { item ->
                val title = cleanName(decodeNameFromHref(item.href))
                if (title.isNotEmpty()) {
                    val url = absoluteUrl(category.host, item.href)
                    val posterUrl = guessPosterUrl(category.host, item.href)
                    items.add(buildSearchResponse(title, url, category.type, posterUrl))
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val normalized = query.trim()
        if (normalized.isEmpty()) return@coroutineScope emptyList()

        val queryLower = normalized.lowercase()
        val queryYear = extractYear(normalized)
        val results = ArrayList<SearchResponse>()
        val maxResults = 80

        if (queryYear != null) {
            val yearDeferreds = categories.filter { it.hasYears }.map { category ->
                async {
                    val yearFolders = getYearFolders(category)
                    val matchingFolder = yearFolders.firstOrNull { 
                        extractYearFromFolder(decodeNameFromHref(it.href)) == queryYear 
                    }
                    if (matchingFolder != null) {
                        val children = fetchDirectChildren(category.host, matchingFolder.href)
                            .filter { it.isFolder }
                        children.mapNotNull { item ->
                            val title = cleanName(decodeNameFromHref(item.href))
                            if (title.lowercase().contains(queryLower)) {
                                val url = absoluteUrl(category.host, item.href)
                                val poster = guessPosterUrl(category.host, item.href)
                                buildSearchResponse(title, url, category.type, poster)
                            } else null
                        }
                    } else emptyList()
                }
            }
            
            val nonYearDeferreds = categories.filter { !it.hasYears }.map { category ->
                async {
                    val children = fetchDirectChildren(category.host, category.path)
                        .filter { it.isFolder }
                    children.mapNotNull { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.lowercase().contains(queryLower)) {
                            val url = absoluteUrl(category.host, item.href)
                            val poster = guessPosterUrl(category.host, item.href)
                            buildSearchResponse(title, url, category.type, poster)
                        } else null
                    }
                }
            }

            results.addAll(yearDeferreds.awaitAll().flatten())
            results.addAll(nonYearDeferreds.awaitAll().flatten())
        } else {
            val yearCategoryDeferreds = categories.filter { it.hasYears }.map { category ->
                async {
                    val yearFolders = getYearFolders(category).take(4) // Top 4 most recent years
                    val folderDeferreds = yearFolders.map { folder ->
                        async {
                            fetchDirectChildren(category.host, folder.href)
                                .filter { it.isFolder }
                                .mapNotNull { item ->
                                    val title = cleanName(decodeNameFromHref(item.href))
                                    if (title.lowercase().contains(queryLower)) {
                                        val url = absoluteUrl(category.host, item.href)
                                        val poster = guessPosterUrl(category.host, item.href)
                                        buildSearchResponse(title, url, category.type, poster)
                                    } else null
                                }
                        }
                    }
                    folderDeferreds.awaitAll().flatten()
                }
            }

            val nonYearCategoryDeferreds = categories.filter { !it.hasYears }.map { category ->
                async {
                    val children = fetchDirectChildren(category.host, category.path)
                        .filter { it.isFolder }
                    children.mapNotNull { item ->
                        val title = cleanName(decodeNameFromHref(item.href))
                        if (title.lowercase().contains(queryLower)) {
                            val url = absoluteUrl(category.host, item.href)
                            val poster = guessPosterUrl(category.host, item.href)
                            buildSearchResponse(title, url, category.type, poster)
                        } else null
                    }
                }
            }

            results.addAll(yearCategoryDeferreds.awaitAll().flatten())
            results.addAll(nonYearCategoryDeferreds.awaitAll().flatten())
        }

        results.distinctBy { it.url }.take(maxResults)
    }

    override suspend fun load(url: String): LoadResponse {
        val type = categoryTypeForUrl(url) ?: if (url.contains("/TV-") || url.contains("Series")) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries || type == TvType.Anime) {
            loadTvSeries(url, type)
        } else {
            loadMovie(url)
        }
    }

    private fun categoryTypeForUrl(url: String): TvType? {
        val path = pathFromUrl(url)
        return categories.firstOrNull { 
            path.startsWith(normalizePath(it.path)) 
        }?.type
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isVideo = looksLikeVideoUrl(data)
        val host = hostForUrl(data)
        
        val links = if (isVideo) {
            listOf(data)
        } else {
            val path = pathFromUrl(data)
            fetchDirectChildren(host, path)
                .filter { isVideoFile(it.href) }
                .map { absoluteUrl(host, it.href) }
        }

        if (links.isEmpty()) return false

        // Fetch subtitles
        val dirPath = if (isVideo) {
            data.substringBeforeLast('/') + "/"
        } else {
            pathFromUrl(data)
        }

        runCatching {
            val subtitleExtensions = setOf("srt", "vtt")
            val children = fetchDirectChildren(host, dirPath)
            
            children.filter { 
                val ext = it.href.substringAfterLast('.', "").lowercase()
                ext in subtitleExtensions
            }.forEach { subItem ->
                val subUrl = absoluteUrl(host, subItem.href)
                val subName = cleanFileName(decodeNameFromHref(subItem.href))
                
                if (isVideo) {
                    val epName = data.substringAfterLast('/')
                    val epNum = extractEpisodeNumber(epName)
                    val subNum = extractEpisodeNumber(subName)
                    if (epNum != null && subNum != null && epNum != subNum) {
                        return@forEach
                    }
                }
                
                val lang = when {
                    subName.contains("bangla", ignoreCase = true) || subName.contains("bn", ignoreCase = true) -> "Bengali"
                    else -> "English"
                }
                subtitleCallback.invoke(
                    newSubtitleFile(lang, subUrl)
                )
            }
        }

        links.forEach { link ->
            val label = cleanFileName(decodeNameFromUrl(link))
            if (link.lowercase().contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(this.name, label, url = link, type = ExtractorLinkType.M3U8)
                )
            } else {
                callback.invoke(
                    newExtractorLink(this.name, label, url = link)
                )
            }
        }

        return true
    }

    private suspend fun getYearFolders(category: Category): List<H5Item> {
        val children = fetchDirectChildren(category.host, category.path)
        return children.filter { item ->
            item.isFolder && isYearFolder(decodeNameFromHref(item.href))
        }.sortedByDescending { 
            extractYearFromFolder(decodeNameFromHref(it.href)) ?: 0
        }
    }

    private fun isYearFolder(folderName: String): Boolean {
        val name = decodeComponent(folderName).trim('/')
        return extractYearFromFolder(name) != null
    }

    private fun extractYearFromFolder(folderName: String): Int? {
        val cleaned = folderName.replace("%28", "(").replace("%29", ")")
        val match = Regex("(19|20)\\d{2}").find(cleaned)
        return match?.value?.toIntOrNull()
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
                client.post(
                    "${host.trimEnd('/')}$path?",
                    data = mapOf(
                        "action" to "get",
                        "items[href]" to path,
                        "items[what]" to "1"
                    )
                )
            } catch (e: Exception) {
                null
            }

            val items = response?.let { resp ->
                runCatching {
                    mapper.readValue<H5ItemsResponse>(resp.text).items
                }.getOrElse { emptyList() }
            } ?: emptyList()

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
        return normalized
    }

    private fun hostForUrl(url: String): String {
        val uri = try { java.net.URI(url) } catch (e: Exception) { null }
        val hostName = uri?.host
        val scheme = uri?.scheme ?: "https"
        return if (!hostName.isNullOrBlank()) {
            "$scheme://$hostName"
        } else {
            categories.firstOrNull { url.startsWith(it.host) }?.host ?: mainUrl
        }
    }

    private fun pathFromUrl(url: String): String {
        val uri = try { java.net.URI(url) } catch (e: Exception) { null }
        val rawPath = uri?.rawPath ?: "/"
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
        val rawPath = try { java.net.URI(url).rawPath } catch (e: Exception) { null } ?: url
        val name = rawPath.trimEnd('/').substringAfterLast('/')
        return decodeComponent(name)
    }

    private fun decodeComponent(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            value
        }
    }

    private fun cleanName(name: String): String {
        return name.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanFileName(name: String): String {
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        return base.replace('.', ' ').replace('_', ' ').trim()
    }

    private fun extractYear(text: String): Int? {
        return Regex("(19|20)\\d{2}").find(text)?.value?.toIntOrNull()
    }

    private fun isSeasonFolder(href: String): Boolean {
        val name = decodeNameFromHref(href)
        return name.lowercase().startsWith("season") || name.lowercase().contains(Regex("s\\d+"))
    }

    private fun extractSeasonNumber(name: String): Int? {
        return Regex("season\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("s(\\d+)", RegexOption.IGNORE_CASE)
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

    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "m3u8", "mpd"
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp"
    )

    private fun isVideoFile(href: String): Boolean {
        val ext = href.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    private fun isImageFile(href: String): Boolean {
        val ext = href.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        return isVideoFile(url)
    }
}
