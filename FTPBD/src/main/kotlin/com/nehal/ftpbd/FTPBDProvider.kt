package com.nehal.ftpbd

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

open class FTPBDProvider : MainAPI() {
    override var mainUrl = "https://server2.ftpbd.net"
    override var name = "FTPBD"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    private val host2 = "https://server2.ftpbd.net"
    private val host3 = "https://server3.ftpbd.net"
    private val host4 = "https://server4.ftpbd.net"
    private val host5 = "https://server5.ftpbd.net"

    private data class FtpCategory(
        val name: String,
        val fullUrl: String,
        val host: String,
        val path: String,
        val type: TvType,
        val isYearIndexed: Boolean
    )

    private val categories = listOf(
        FtpCategory("English Movies", "https://server2.ftpbd.net/FTP-2/English%20Movies/", host2, "/FTP-2/English%20Movies/", TvType.Movie, true),
        FtpCategory("English & Foreign TV Series", "https://server4.ftpbd.net/FTP-4/English-Foreign-TV-Series/", host4, "/FTP-4/English-Foreign-TV-Series/", TvType.TvSeries, false),
        FtpCategory("English Movies (Dual Audio)", "https://server2.ftpbd.net/FTP-2/English%20Movies/Dual-Audio/", host2, "/FTP-2/English%20Movies/Dual-Audio/", TvType.Movie, true),
        FtpCategory("Hindi Movies", "https://server3.ftpbd.net/FTP-3/Hindi%20Movies/", host3, "/FTP-3/Hindi%20Movies/", TvType.Movie, true),
        FtpCategory("Hindi TV Series", "https://server3.ftpbd.net/FTP-3/Hindi%20TV%20Series/", host3, "/FTP-3/Hindi%20TV%20Series/", TvType.TvSeries, false),
        FtpCategory("Anime & Cartoon TV Series", "https://server5.ftpbd.net/FTP-5/Anime--Cartoon-TV-Series/", host5, "/FTP-5/Anime--Cartoon-TV-Series/", TvType.Anime, false),
        FtpCategory("Animation Movies", "https://server5.ftpbd.net/FTP-5/Animation%20Movies/", host5, "/FTP-5/Animation%20Movies/", TvType.Anime, true),
        FtpCategory("South Indian Movies", "https://server3.ftpbd.net/FTP-3/South%20Indian%20Movies/", host3, "/FTP-3/South%20Indian%20Movies/", TvType.Movie, true),
        FtpCategory("Bangla Collection", "https://server3.ftpbd.net/FTP-3/Bangla%20Collection/BANGLA/", host3, "/FTP-3/Bangla%20Collection/BANGLA/", TvType.Movie, true),
        FtpCategory("Bangla Web Series", "https://server3.ftpbd.net/FTP-3/Bangla%20Collection/BANGLA/Web-Series/", host3, "/FTP-3/Bangla%20Collection/BANGLA/Web-Series/", TvType.TvSeries, false)
    )

    override val mainPage = mainPageOf(
        *categories.map { it.fullUrl to it.name }.toTypedArray()
    )

    // ==========================================
    // JSON & CACHING MODELS
    // ==========================================

    private val mapper = jacksonObjectMapper()

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

    private data class CacheEntry(
        val timestampMs: Long,
        val items: List<H5Item>
    )

    private val childrenCache = ConcurrentHashMap<String, CacheEntry>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val posterCache = ConcurrentHashMap<String, String?>()

    private fun getSemaphoreForHost(host: String): Semaphore {
        val cleanHost = try { URI(host).host ?: host } catch (_: Exception) { host }
        return hostSemaphores.computeIfAbsent(cleanHost) { Semaphore(6) }
    }

    private fun getTtlForPath(path: String): Long {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = Regex("(19|20)\\d{2}").findAll(path)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()

        if (years.isNotEmpty() && years.any { it < currentYear }) {
            return 24 * 60 * 60 * 1000L // 24 hours for archived past years
        }
        return 30 * 60 * 1000L // 30 minutes for active roots and current year
    }

    // ==========================================
    // NETWORK & H5AI SCRAPER ENGINE
    // ==========================================

    private suspend fun fetchDirectChildren(host: String, rawPath: String): List<H5Item> {
        val path = normalizePath(rawPath)
        val cacheKey = "${host.trimEnd('/')}|$path"
        val nowMs = System.currentTimeMillis()
        val ttl = getTtlForPath(path)

        childrenCache[cacheKey]?.let { entry ->
            if (nowMs - entry.timestampMs <= ttl && entry.items.isNotEmpty()) {
                return entry.items
            }
        }

        val keyLock = keyLocks.computeIfAbsent(cacheKey) { Mutex() }
        return keyLock.withLock {
            childrenCache[cacheKey]?.let { entry ->
                if (System.currentTimeMillis() - entry.timestampMs <= ttl && entry.items.isNotEmpty()) {
                    return@withLock entry.items
                }
            }

            // 1. High-speed h5ai POST JSON API
            val jsonItems = fetchH5aiJson(host, path)
            if (!jsonItems.isNullOrEmpty()) {
                childrenCache[cacheKey] = CacheEntry(System.currentTimeMillis(), jsonItems)
                return@withLock jsonItems
            }

            // 2. HTML Fallback Parser
            val htmlItems = fetchHtmlFallback(host, path)
            if (htmlItems.isNotEmpty()) {
                childrenCache[cacheKey] = CacheEntry(System.currentTimeMillis(), htmlItems)
                return@withLock htmlItems
            }

            // Stale fallback in case of transient network error
            childrenCache[cacheKey]?.items ?: emptyList()
        }
    }

    private suspend fun fetchH5aiJson(host: String, path: String): List<H5Item>? {
        val url = "$host$path?"
        val postData = mapOf(
            "action" to "get",
            "items[href]" to decodeComponent(path),
            "items[what]" to "1"
        )
        return try {
            val responseText = getSemaphoreForHost(host).withPermit {
                app.post(url, data = postData, timeout = 25).text
            }
            if (responseText.isNotBlank() && responseText.startsWith("{")) {
                val parsed = mapper.readValue<H5ItemsResponse>(responseText).items
                filterDirectChildren(parsed, path)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchHtmlFallback(host: String, path: String): List<H5Item> {
        return try {
            val url = "$host$path"
            val html = getSemaphoreForHost(host).withPermit {
                app.get(url, timeout = 25).text
            }
            val doc = Jsoup.parse(html)
            val items = mutableListOf<H5Item>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

            doc.select("tr").forEach { tr ->
                val a = tr.selectFirst("a[href]") ?: return@forEach
                val href = a.attr("href").trim()
                if (href.isBlank() || href == "." || href == ".." || href == "/") return@forEach
                if (href.contains("_h5ai") || href.contains("larsjung.de") || href.contains("browsehappy.com")) return@forEach

                var modifiedMs: Long? = null
                tr.select("td.fb-d, td").forEach { td ->
                    val txt = td.text().trim()
                    if (txt.length >= 16 && txt[4] == '-' && txt[7] == '-') {
                        try {
                            modifiedMs = dateFormat.parse(txt.substring(0, 16))?.time
                        } catch (_: Exception) {}
                    }
                }

                val fullHref = if (href.startsWith("/")) href else "$path$href"
                val normalizedHref = if (href.endsWith("/") && !fullHref.endsWith("/")) "$fullHref/" else fullHref
                items.add(H5Item(normalizedHref, modifiedMs, if (normalizedHref.endsWith("/")) null else 0L))
            }
            filterDirectChildren(items, path)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun filterDirectChildren(items: List<H5Item>, parentPath: String): List<H5Item> {
        val parent = normalizePath(parentPath)
        val decodedParent = decodeComponent(parent)

        return items.filter { item ->
            val h = item.href
            val dh = decodeComponent(h)
            val matches = (h.startsWith(parent) && h != parent) || (dh.startsWith(decodedParent) && dh != decodedParent)
            if (!matches) return@filter false

            val relative = if (h.startsWith(parent)) h.removePrefix(parent) else dh.removePrefix(decodedParent)
            val clean = relative.trim('/')
            clean.isNotEmpty() && !clean.contains('/')
        }.distinctBy { it.href }
    }

    // ==========================================
    // CATALOG BROWSING & PAGINATION
    // ==========================================

    private suspend fun fetchYearIndexedMovies(
        host: String,
        categoryPath: String,
        page: Int,
        pageSize: Int
    ): Pair<List<H5Item>, Boolean> = coroutineScope {
        val actualRoot = if (categoryPath.contains("Bangla%20Collection/BANGLA/") || categoryPath.contains("Bangla Collection/BANGLA/")) {
            val banglaChildren = fetchDirectChildren(host, categoryPath)
            banglaChildren.firstOrNull { it.href.contains("Kolkata-Bangla-Movies", true) }?.href ?: categoryPath
        } else {
            categoryPath
        }

        val direct = fetchDirectChildren(host, actualRoot)
        val folders = direct.filter { it.isFolder }
        val (yearFolders, otherFolders) = folders.partition { extractYear(decodeName(it.href)) != null }
        val sortedYears = yearFolders.sortedByDescending { extractYear(decodeName(it.href)) ?: 0 }
        val allContainers = sortedYears + otherFolders

        if (allContainers.isEmpty()) {
            val directItems = direct.filter { it.isFolder }
            val paged = directItems.drop((page - 1) * pageSize).take(pageSize)
            return@coroutineScope Pair(paged, directItems.size > page * pageSize)
        }

        val targetCount = page * pageSize
        val accumulated = ArrayList<H5Item>()
        var containerIndex = 0

        // Stream year chunks lazily (recent years first, e.g. 2026, then 2025...)
        for (container in allContainers) {
            containerIndex++
            val children = fetchDirectChildren(host, container.href).filter { it.isFolder }
            accumulated.addAll(children)
            if (accumulated.size >= targetCount) {
                break
            }
        }

        val paged = accumulated.drop((page - 1) * pageSize).take(pageSize)
        val hasNext = accumulated.size > page * pageSize || containerIndex < allContainers.size
        Pair(paged, hasNext)
    }

    private suspend fun fetchDirectSeries(
        host: String,
        path: String,
        page: Int,
        pageSize: Int
    ): Pair<List<H5Item>, Boolean> {
        val items = fetchDirectChildren(host, path).filter { it.isFolder }
        val sorted = items.sortedByDescending { it.time ?: 0L }
        val paged = sorted.drop((page - 1) * pageSize).take(pageSize)
        val hasNext = sorted.size > page * pageSize
        return Pair(paged, hasNext)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageSize = 30
        val cat = categories.find { it.fullUrl == request.data || it.path == request.data }
            ?: categories.first()

        val (items, hasNext) = if (cat.isYearIndexed) {
            fetchYearIndexedMovies(cat.host, cat.path, page, pageSize)
        } else {
            fetchDirectSeries(cat.host, cat.path, page, pageSize)
        }

        val responses = items.mapNotNull { item ->
            val rawName = decodeName(item.href)
            val title = cleanTitle(rawName)
            if (title.isBlank()) return@mapNotNull null

            val fullItemUrl = absoluteUrl(cat.host, item.href)
            val posterUrl = guessPosterUrl(cat.host, item.href)
            val formattedTitle = formatCardTitle(title, cat.name, item.href)

            buildSearchResponse(formattedTitle, fullItemUrl, cat.type, posterUrl, title, item.href, cat.name)
        }

        return newHomePageResponse(request.name, responses, hasNext = hasNext)
    }

    // ==========================================
    // SEARCH IMPLEMENTATION
    // ==========================================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val rawQuery = query.trim()
        if (rawQuery.length < 2) return@coroutineScope emptyList()

        val queryYear = extractYear(rawQuery)
        val queryClean = if (queryYear != null) rawQuery.replace(queryYear.toString(), "").trim() else rawQuery
        val queryLower = queryClean.lowercase()
        val maxResults = 50

        val tasks = categories.map { cat ->
            async {
                try {
                    if (cat.isYearIndexed) {
                        val actualRoot = if (cat.path.contains("Bangla%20Collection/BANGLA/")) {
                            val banglaChildren = fetchDirectChildren(cat.host, cat.path)
                            banglaChildren.firstOrNull { it.href.contains("Kolkata-Bangla-Movies", true) }?.href ?: cat.path
                        } else cat.path

                        val yearFolders = fetchDirectChildren(cat.host, actualRoot).filter { it.isFolder }
                        val targetYears = if (queryYear != null) {
                            yearFolders.filter { extractYear(decodeName(it.href)) == queryYear }
                        } else {
                            yearFolders.sortedByDescending { extractYear(decodeName(it.href)) ?: 0 }.take(3)
                        }

                        val movieDeferreds = targetYears.map { yf ->
                            async { fetchDirectChildren(cat.host, yf.href).filter { it.isFolder } }
                        }
                        val items = movieDeferreds.awaitAll().flatten()
                        items.filter { item ->
                            val name = cleanTitle(decodeName(item.href)).lowercase()
                            (queryLower.isBlank() || name.contains(queryLower)) &&
                                    (queryYear == null || name.contains(queryYear.toString()) || item.href.contains(queryYear.toString()))
                        }.map { item ->
                            val title = cleanTitle(decodeName(item.href))
                            val fullUrl = absoluteUrl(cat.host, item.href)
                            val posterUrl = guessPosterUrl(cat.host, item.href)
                            buildSearchResponse(title, fullUrl, cat.type, posterUrl, title, item.href, cat.name)
                        }
                    } else {
                        val items = fetchDirectChildren(cat.host, cat.path).filter { it.isFolder }
                        items.filter { item ->
                            val name = cleanTitle(decodeName(item.href)).lowercase()
                            (queryLower.isBlank() || name.contains(queryLower)) &&
                                    (queryYear == null || name.contains(queryYear.toString()) || item.href.contains(queryYear.toString()))
                        }.take(20).map { item ->
                            val title = cleanTitle(decodeName(item.href))
                            val fullUrl = absoluteUrl(cat.host, item.href)
                            val posterUrl = guessPosterUrl(cat.host, item.href)
                            buildSearchResponse(title, fullUrl, cat.type, posterUrl, title, item.href, cat.name)
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        tasks.awaitAll().flatten().distinctBy { it.url }.take(maxResults)
    }

    // ==========================================
    // DETAIL PAGE (LOAD) & STREAM RESOLVER
    // ==========================================

    override suspend fun load(url: String): LoadResponse {
        val host = hostForUrl(url)
        val serverTag = getServerTagFromUrl(url)
        val decodedTitle = cleanTitle(decodeName(url))
        val extractedYear = extractYear(url)
        val tags = extractTags(url)

        if (isVideoFile(url)) {
            val parentFolder = url.substringBeforeLast('/') + "/"
            val folderItems = fetchDirectChildren(host, pathFromUrl(parentFolder))
            val posterUrl = pickPoster(host, folderItems)
            rememberPoster(host, pathFromUrl(parentFolder), posterUrl)

            return newMovieLoadResponse(decodedTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = posterUrl
                this.tags = tags
                this.plot = "Server: $serverTag (FTPBD)"
                if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
            }
        }

        val folderPath = pathFromUrl(url)
        val entries = fetchDirectChildren(host, folderPath)
        val posterUrl = pickPoster(host, entries)
        rememberPoster(host, folderPath, posterUrl)

        val subDirs = entries.filter { it.isFolder }
        val videoFiles = entries.filter { isVideoFile(it.href) }

        if (subDirs.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            var episodeCounter = 1

            for (dir in subDirs) {
                val seasonItems = fetchDirectChildren(host, dir.href)
                val seasonVideos = seasonItems.filter { isVideoFile(it.href) }
                val detectedSeason = extractSeasonNumber(decodeName(dir.href))

                for (vid in seasonVideos) {
                    val fileName = cleanTitle(decodeName(vid.href))
                    val epNum = extractEpisodeNumber(fileName) ?: episodeCounter++
                    episodes.add(
                        newEpisode(absoluteUrl(host, vid.href)) {
                            this.name = fileName
                            this.episode = epNum
                            this.season = detectedSeason
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                for (vid in videoFiles) {
                    val fileName = cleanTitle(decodeName(vid.href))
                    val epNum = extractEpisodeNumber(fileName) ?: episodeCounter++
                    episodes.add(
                        newEpisode(absoluteUrl(host, vid.href)) {
                            this.name = fileName
                            this.episode = epNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(decodedTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = posterUrl
                this.tags = tags
                this.plot = "Server: $serverTag (FTPBD)"
                if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
            }
        }

        if (videoFiles.size > 1) {
            var epCounter = 1
            val episodes = videoFiles.map { vid ->
                val fileName = cleanTitle(decodeName(vid.href))
                val epNum = extractEpisodeNumber(fileName) ?: epCounter++
                newEpisode(absoluteUrl(host, vid.href)) {
                    this.name = fileName
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(decodedTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = posterUrl
                this.tags = tags
                this.plot = "Server: $serverTag (FTPBD)"
                if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
            }
        }

        val targetVideoUrl = videoFiles.firstOrNull()?.let { absoluteUrl(host, it.href) } ?: url
        return newMovieLoadResponse(decodedTitle, url, TvType.Movie, targetVideoUrl) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.tags = tags
            this.plot = "Server: $serverTag (FTPBD)"
            if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = hostForUrl(data)
        val videoUrl = if (isVideoFile(data)) {
            data
        } else {
            val entries = fetchDirectChildren(host, pathFromUrl(data))
            entries.firstOrNull { isVideoFile(it.href) }?.let { absoluteUrl(host, it.href) } ?: data
        }

        // Subtitle extraction (checks parent folder and any Subs/ subfolder)
        try {
            val parentFolder = videoUrl.substringBeforeLast('/') + "/"
            val parentPath = pathFromUrl(parentFolder)
            val folderItems = fetchDirectChildren(host, parentPath)

            folderItems.filter { isSubtitleFile(it.href) }.forEach { sub ->
                val subUrl = absoluteUrl(host, sub.href)
                subtitleCallback(SubtitleFile(cleanTitle(decodeName(sub.href)), subUrl))
            }

            val subsDir = folderItems.firstOrNull { it.isFolder && it.href.contains("subs", true) }
            if (subsDir != null) {
                val innerSubs = fetchDirectChildren(host, subsDir.href)
                innerSubs.filter { isSubtitleFile(it.href) }.forEach { sub ->
                    val subUrl = absoluteUrl(host, sub.href)
                    subtitleCallback(SubtitleFile(cleanTitle(decodeName(sub.href)), subUrl))
                }
            }
        } catch (_: Exception) {}

        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(videoUrl)
            }
        )

        return true
    }

    // ==========================================
    // METADATA, TITLE & POSTER UTILITIES
    // ==========================================

    private fun buildSearchResponse(
        displayTitle: String,
        url: String,
        type: TvType,
        posterUrl: String?,
        cleanName: String,
        href: String,
        catName: String
    ): SearchResponse {
        val combinedText = "$catName $displayTitle $cleanName $href".lowercase()
        val year = extractYear(cleanName)
        val quality = extractQualityEnum(combinedText)

        val isDualOrMulti = combinedText.contains("dual audio") || combinedText.contains("dual-audio") ||
                combinedText.contains("multi audio") || combinedText.contains("multi-audio") ||
                combinedText.contains("dual") || combinedText.contains("multi")
        val isDubbed = isDualOrMulti || combinedText.contains("dubbed") || combinedText.contains("hindi") ||
                combinedText.contains("south indian") || combinedText.contains("bangla")
        val isSubbed = combinedText.contains("sub") || combinedText.contains("esub") || combinedText.contains("msub")

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(displayTitle, url, type) {
                this.posterUrl = posterUrl
                if (year != null && year in 1900..2035) this.year = year
                this.quality = quality
                if (isDubbed || isSubbed) {
                    addDubStatus(dubExist = isDubbed, subExist = isSubbed)
                }
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(displayTitle, url, type) {
                this.posterUrl = posterUrl
                if (year != null && year in 1900..2035) this.year = year
                this.quality = quality
            }
            else -> newMovieSearchResponse(displayTitle, url, type) {
                this.posterUrl = posterUrl
                if (year != null && year in 1900..2035) this.year = year
                this.quality = quality
            }
        }
    }

    private fun formatCardTitle(cleanTitle: String, categoryName: String, entryHref: String): String {
        val combined = "$categoryName $cleanTitle $entryHref".lowercase()
        val tags = mutableListOf<String>()

        when {
            combined.contains("2160p") || combined.contains("4k") || combined.contains("uhd") -> tags.add("4K")
            combined.contains("1080p") || combined.contains("1080") -> tags.add("1080p")
            combined.contains("720p") || combined.contains("720") -> tags.add("720p")
        }

        when {
            combined.contains("dual") || combined.contains("multi") -> tags.add("Dual Audio")
            combined.contains("hindi") -> tags.add("Hindi")
            combined.contains("bangla") || combined.contains("kolkata") -> tags.add("Bangla")
        }

        val filteredTags = tags.filter { !cleanTitle.contains(it, ignoreCase = true) }
        return if (filteredTags.isNotEmpty()) {
            "$cleanTitle [${filteredTags.joinToString(", ")}]"
        } else {
            cleanTitle
        }
    }

    private fun cleanTitle(rawPathOrHref: String): String {
        val segment = rawPathOrHref.trimEnd('/').substringAfterLast('/')
        var name = decodeComponent(segment)

        name = Regex("""\.(mp4|mkv|avi|m4v|webm|flv|ts|m3u8|srt|sub|vtt|txt)$""", RegexOption.IGNORE_CASE).replace(name, "")
        name = Regex("""_reencoded$""", RegexOption.IGNORE_CASE).replace(name, "")

        if (name.contains(".") && !name.contains(" ")) {
            name = name.replace(".", " ")
        }
        name = name.replace("_", " ").trim()
        return name.replace(Regex("""\s+"""), " ")
    }

    private fun extractTags(text: String): List<String> {
        val lower = text.lowercase()
        val tags = mutableListOf<String>()
        if (lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")) tags.add("4K UHD")
        if (lower.contains("1080p") || lower.contains("fhd")) tags.add("1080p FHD")
        if (lower.contains("720p") || lower.contains("hd")) tags.add("720p HD")
        if (lower.contains("3d")) tags.add("3D")
        if (lower.contains("hindi")) tags.add("Hindi")
        if (lower.contains("bangla") || lower.contains("kolkata")) tags.add("Bangla")
        if (lower.contains("dual") || lower.contains("multi")) tags.add("Dual Audio")
        if (lower.contains("dubbed")) tags.add("Dubbed")
        if (lower.contains("sub") || lower.contains("esub")) tags.add("Subtitled")
        if (lower.contains("anime") || lower.contains("animation")) tags.add("Animation")
        return tags.distinct()
    }

    private fun extractYear(text: String): Int? {
        val decoded = decodeComponent(text)
        return Regex("""\(?(19\d{2}|20\d{2})\)?""").find(decoded)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractQualityEnum(text: String): SearchQuality {
        val lower = text.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd") -> SearchQuality.FourK
            lower.contains("1080p") || lower.contains("fhd") || lower.contains("720p") || lower.contains("hd") -> SearchQuality.HD
            lower.contains("480p") || lower.contains("360p") || lower.contains("sd") -> SearchQuality.SD
            else -> SearchQuality.HD
        }
    }

    private fun extractSeasonNumber(name: String): Int? {
        val clean = name.lowercase()
        return Regex("""(?:season|s)\s*[-_]?\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b0*(\d+)\b""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(name: String): Int? {
        val clean = name.lowercase()
        return Regex("""s\d{1,2}e(\d{1,3})""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?:episode|ep|e)\s*[-_]?\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(?:e|ep)0*(\d+)\b""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""-\s*0*(\d+)\s*-""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun pickPoster(host: String, items: List<H5Item>): String? {
        val imageItems = items.filter { isImageFile(it.href) }
        val preferred = imageItems.firstOrNull { it.href.contains("a_AL_", true) }
            ?: imageItems.firstOrNull { it.href.contains("a_VL_", true) }
            ?: imageItems.firstOrNull { it.href.contains("a11", true) }
            ?: imageItems.firstOrNull { it.href.contains("a22", true) }
            ?: imageItems.firstOrNull { it.href.contains("poster", true) }
            ?: imageItems.firstOrNull { it.href.contains("cover", true) }
            ?: imageItems.firstOrNull { it.href.contains("folder", true) }
            ?: imageItems.firstOrNull()

        return preferred?.let { absoluteUrl(host, it.href) }
    }

    private fun guessPosterUrl(host: String, folderHref: String): String? {
        val folderPath = pathFromUrl(folderHref)
        val cacheKey = "${host.trimEnd('/')}|$folderPath"
        posterCache[cacheKey]?.let { return it }

        childrenCache[cacheKey]?.let { entry ->
            val poster = pickPoster(host, entry.items)
            if (poster != null) {
                posterCache[cacheKey] = poster
                return poster
            }
        }
        return null
    }

    private fun rememberPoster(host: String, folderPath: String, posterUrl: String?) {
        if (posterUrl.isNullOrBlank()) return
        val cacheKey = "${host.trimEnd('/')}|${normalizePath(folderPath)}"
        posterCache[cacheKey] = posterUrl
    }

    private fun getServerTagFromUrl(url: String): String {
        return when {
            url.contains("server2.ftpbd.net") || url.contains("/FTP-2/") -> "FTP-2"
            url.contains("server3.ftpbd.net") || url.contains("/FTP-3/") -> "FTP-3"
            url.contains("server4.ftpbd.net") || url.contains("/FTP-4/") -> "FTP-4"
            url.contains("server5.ftpbd.net") || url.contains("/FTP-5/") -> "FTP-5"
            else -> "FTPBD"
        }
    }

    private fun hostForUrl(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host
            val scheme = uri.scheme ?: "https"
            if (!host.isNullOrBlank()) "$scheme://$host" else host2
        } catch (_: Exception) {
            when {
                url.contains("server3.ftpbd.net") || url.contains("/FTP-3/") -> host3
                url.contains("server4.ftpbd.net") || url.contains("/FTP-4/") -> host4
                url.contains("server5.ftpbd.net") || url.contains("/FTP-5/") -> host5
                else -> host2
            }
        }
    }

    private fun pathFromUrl(url: String): String {
        return try {
            val rawPath = URI(url).rawPath
            if (!rawPath.isNullOrBlank()) normalizePath(rawPath) else normalizePath(url)
        } catch (_: Exception) {
            val clean = url.substringAfter("://").substringAfter('/')
            normalizePath("/$clean")
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        var normalized = if (path.startsWith("/")) path else "/$path"
        if (!normalized.endsWith("/")) normalized += "/"
        return normalized
    }

    private fun absoluteUrl(host: String, href: String): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") -> host.trimEnd('/') + href
            else -> host.trimEnd('/') + "/" + href
        }
    }

    private fun decodeName(rawPathOrHref: String): String {
        val segment = rawPathOrHref.trimEnd('/').substringAfterLast('/')
        return decodeComponent(segment)
    }

    private fun decodeComponent(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    private fun isVideoFile(urlOrHref: String): Boolean {
        val lower = urlOrHref.substringBefore('?').lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".flv") ||
                lower.endsWith(".ts") || lower.endsWith(".mov") || lower.endsWith(".m3u8")
    }

    private fun isImageFile(urlOrHref: String): Boolean {
        val lower = urlOrHref.substringBefore('?').lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp")
    }

    private fun isSubtitleFile(urlOrHref: String): Boolean {
        val lower = urlOrHref.substringBefore('?').lowercase()
        return lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".sub")
    }
}
