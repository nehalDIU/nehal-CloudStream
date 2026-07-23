package com.nehal.dhakaflixbdix

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

open class DhakaFlixBDIXProvider : MainAPI() {
    override var name = "DhakaFlix (BDIX)"
    override var mainUrl = "http://172.16.50.12"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    private val host12 = "http://172.16.50.12"
    private val host14 = "http://172.16.50.14"
    private val host7  = "http://172.16.50.7"

    private val directoryCache = ConcurrentHashMap<String, Pair<Long, List<DirectoryEntry>>>()
    private val cacheTtlMs = 30 * 60 * 1000L // 30 minutes memory cache

    private data class BDIXCategory(
        val name: String,
        val path: String,
        val host: String,
        val type: TvType
    )

    private val categories = listOf(
        // Server 14 Categories
        BDIXCategory("English Movies (1080p)", "/DHAKA-FLIX-14/English%20Movies%20%281080p%29/", host14, TvType.Movie),
        BDIXCategory("Hindi Movies", "/DHAKA-FLIX-14/Hindi%20Movies/", host14, TvType.Movie),
        BDIXCategory("South Indian Movies", "/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/", host14, TvType.Movie),
        BDIXCategory("Animation Movies", "/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/", host14, TvType.Movie),
        BDIXCategory("IMDb Top 250", "/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/", host14, TvType.Movie),
        BDIXCategory("Korean TV & Web Series", "/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/", host14, TvType.TvSeries),
        
        // Server 12 Categories
        BDIXCategory("TV & Web Series", "/DHAKA-FLIX-12/TV-WEB-Series/", host12, TvType.TvSeries),

        // Server 7 Categories
        BDIXCategory("English Movies (720p)", "/DHAKA-FLIX-7/English%20Movies/", host7, TvType.Movie),
        BDIXCategory("3D Movies", "/DHAKA-FLIX-7/3D%20Movies/", host7, TvType.Movie),
        BDIXCategory("Foreign Language Movies", "/DHAKA-FLIX-7/Foreign%20Language%20Movies/", host7, TvType.Movie),
        BDIXCategory("Kolkata Bangla Movies", "/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/", host7, TvType.Movie)
    )

    override val mainPage = mainPageOf(
        *categories.map { cat -> cat.path to cat.name }.toTypedArray()
    )

    private fun getServerTagFromUrl(url: String): String {
        return when {
            url.contains("172.16.50.12") || url.contains("DHAKA-FLIX-12") -> "DF-12"
            url.contains("172.16.50.14") || url.contains("DHAKA-FLIX-14") -> "DF-14"
            url.contains("172.16.50.7")  || url.contains("DHAKA-FLIX-7")  -> "DF-7"
            else -> "BDIX"
        }
    }

    private fun cleanTitle(rawPathOrHref: String, serverTag: String? = null): String {
        val segment = rawPathOrHref.trimEnd('/').substringAfterLast('/')
        var name = try {
            URLDecoder.decode(segment, StandardCharsets.UTF_8.name()).trim()
        } catch (_: Exception) {
            segment.trim()
        }

        // Remove media/subtitle extensions if present
        name = Regex("""\.(mp4|mkv|avi|m4v|webm|flv|srt|sub|txt)$""", RegexOption.IGNORE_CASE).replace(name, "")

        // Replace separator dots/underscores with spaces if dots are used as word separators
        if (name.contains(".") && !name.contains(" ")) {
            name = name.replace(".", " ")
        }
        name = name.replace("_", " ").trim()

        // Clean up redundant spaces
        return name.replace(Regex("""\s+"""), " ")
    }

    private fun fixUrl(path: String, host: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$host$cleanPath"
    }

    private fun getOptimizedPosterUrl(folderUrl: String): String? {
        val cleanUrl = folderUrl.trimEnd('/')
        if (cleanUrl.isBlank() || isMediaFile(cleanUrl)) return null
        val lower = cleanUrl.lowercase()
        return when {
            lower.contains("imdb") -> "$cleanUrl/a11.jpg"
            lower.contains("3d") -> "$cleanUrl/MV.jpg"
            else -> "$cleanUrl/a_AL_.jpg"
        }
    }

    private fun pickPosterFromEntries(entries: List<DirectoryEntry>): String? {
        val imageFiles = entries.filter { isImageFile(it.fullUrl) }
        val preferredPoster = imageFiles.firstOrNull { it.name.contains("a_AL_", true) }
            ?: imageFiles.firstOrNull { it.name.contains("a_VL_", true) }
            ?: imageFiles.firstOrNull { it.name.contains("a_v1", true) || it.name.contains("a._v1", true) }
            ?: imageFiles.firstOrNull { it.name.contains("a11", true) }
            ?: imageFiles.firstOrNull { it.name.contains("a22", true) }
            ?: imageFiles.firstOrNull { it.name.contains("a06", true) }
            ?: imageFiles.firstOrNull { it.name.contains("a2323", true) }
            ?: imageFiles.firstOrNull { it.name.contains("mv", true) }
            ?: imageFiles.firstOrNull { it.name.contains("pha", true) }
            ?: imageFiles.firstOrNull { it.name.contains("poster", true) }
            ?: imageFiles.firstOrNull { it.name.contains("cover", true) }
            ?: imageFiles.firstOrNull { it.name.contains("folder", true) }
            ?: imageFiles.firstOrNull()

        return preferredPoster?.fullUrl
    }

    private fun extractYear(titleOrUrl: String): Int? {
        val decoded = try { URLDecoder.decode(titleOrUrl, StandardCharsets.UTF_8.name()) } catch (_: Exception) { titleOrUrl }
        val match = Regex("""\(?(19\d{2}|20\d{2})\)?""").find(decoded)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractQualityEnum(titleOrUrl: String): SearchQuality {
        val lower = titleOrUrl.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd") -> SearchQuality.FourK
            lower.contains("1080p") || lower.contains("fhd") || lower.contains("720p") || lower.contains("hd") -> SearchQuality.HD
            lower.contains("480p") || lower.contains("360p") || lower.contains("sd") -> SearchQuality.SD
            else -> SearchQuality.HD
        }
    }

    private fun formatCardName(entryName: String, cat: BDIXCategory, entryHref: String): String {
        val decodedTitle = try { URLDecoder.decode(entryName, StandardCharsets.UTF_8.name()) } catch (_: Exception) { entryName }
        val decodedHref = try { URLDecoder.decode(entryHref, StandardCharsets.UTF_8.name()) } catch (_: Exception) { entryHref }
        val combined = "${cat.name} ${cat.path} $decodedTitle $decodedHref".lowercase()

        val qTag = when {
            combined.contains("3d") -> "3D"
            combined.contains("2160p") || combined.contains("4k") || combined.contains("uhd") -> "4K"
            combined.contains("1080p") || combined.contains("1080") -> "1080p"
            combined.contains("720p") || combined.contains("720") -> "720p"
            else -> null
        }

        val audioTag = when {
            combined.contains("dual") || combined.contains("multi") -> "Dual Audio"
            combined.contains("hindi") -> "Hindi"
            combined.contains("bangla") -> "Bangla"
            else -> null
        }

        val clean = decodedTitle.trim()
        val tags = mutableListOf<String>()
        if (qTag != null && !clean.lowercase().contains(qTag.lowercase())) {
            tags.add(qTag)
        }
        if (audioTag != null && !clean.lowercase().contains(audioTag.lowercase())) {
            tags.add(audioTag)
        }

        return if (tags.isNotEmpty()) {
            "$clean [${tags.joinToString(", ")}]"
        } else {
            clean
        }
    }

    private fun populateItemMetadata(
        response: AnimeSearchResponse,
        entry: DirectoryEntry,
        cat: BDIXCategory,
        posterUrl: String?
    ) {
        val decodedTitle = try { URLDecoder.decode(entry.name, StandardCharsets.UTF_8.name()) } catch (_: Exception) { entry.name }
        val decodedHref = try { URLDecoder.decode(entry.href, StandardCharsets.UTF_8.name()) } catch (_: Exception) { entry.href }
        
        val itemText = "$decodedTitle $decodedHref".lowercase()
        val catText = "${cat.name} ${cat.path}".lowercase()
        val combinedText = "$catText $itemText"

        // 1. Set poster
        response.posterUrl = posterUrl

        // 2. Set release year badge
        val extractedYear = extractYear(decodedTitle)
        if (extractedYear != null && extractedYear in 1900..2030) {
            response.year = extractedYear
        }

        // 3. Set SearchQuality enum (HD, FourK, SD) for top-right poster pill
        response.quality = extractQualityEnum(combinedText)

        // 4. Set Dub/Sub status for bottom floating poster badge (DUB, SUB, DUB | SUB)
        val isDualOrMulti = combinedText.contains("dual audio") || combinedText.contains("dual-audio") || combinedText.contains("multi audio") || combinedText.contains("multi-audio") || combinedText.contains("dual") || combinedText.contains("multi")
        val isDubbed = isDualOrMulti || combinedText.contains("dubbed") || combinedText.contains("hindi") || combinedText.contains("south indian") || combinedText.contains("bangla")
        val isSubbed = combinedText.contains("sub") || combinedText.contains("esub") || combinedText.contains("msub")

        if (isDubbed || isSubbed) {
            response.addDubStatus(dubExist = isDubbed, subExist = isSubbed)
        } else {
            response.addDubStatus(dubExist = true, subExist = false)
        }
    }

    private fun isContainerFolder(name: String): Boolean {
        val clean = name.trim().lowercase()
        // Year container folder pattern: (2025), (2025) 1080p, 2025, (1995) 1080p & Before
        if (Regex("""^\(?\d{4}\)?(\s*(1080p|720p|&|and|before).*)*$""", RegexOption.IGNORE_CASE).matches(clean)) return true
        // Group pattern like "0 - 9", "A - L", "0 — 9", "A — L"
        if (Regex("""\b(\d\s*[—–-]\s*\d|[a-z]\s*[—–-]\s*[a-z])\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return true
        if (clean == "hindi dubbed" || clean == "english dubbed" || clean == "foreign language movies") return true
        return false
    }

    private data class DirectoryEntry(
        val name: String,
        val href: String,
        val fullUrl: String,
        val isDirectory: Boolean,
        val modifiedTimeMs: Long = 0L
    )

    private suspend fun fetchDirectoryListingCached(url: String): List<DirectoryEntry> {
        val now = System.currentTimeMillis()
        directoryCache[url]?.let { (timestamp, cachedEntries) ->
            if (now - timestamp < cacheTtlMs && cachedEntries.isNotEmpty()) {
                return cachedEntries
            }
        }
        val freshEntries = fetchDirectoryListing(url)
        if (freshEntries.isNotEmpty()) {
            directoryCache[url] = Pair(now, freshEntries)
        }
        return freshEntries
    }

    private suspend fun fetchDirectoryListing(url: String): List<DirectoryEntry> {
        return try {
            val responseHtml = app.get(url, timeout = 10).text
            val doc = Jsoup.parse(responseHtml)
            val baseUri = URI(url)
            val entries = mutableListOf<DirectoryEntry>()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)

            val trs = doc.select("tr")
            if (trs.isNotEmpty()) {
                trs.forEach { tr ->
                    val a = tr.selectFirst("a[href]") ?: return@forEach
                    val href = a.attr("href")
                    if (href.isBlank() || href == ".." || href == "." || href.startsWith("/") && href.length == 1) return@forEach
                    if (href.contains("_h5ai") || href.contains("larsjung.de") || href.contains("browsehappy.com")) return@forEach

                    val cleanedName = cleanTitle(href)
                    if (cleanedName.isBlank()) return@forEach

                    val isDir = href.endsWith("/")
                    val fullUrl = baseUri.resolve(href).toString()

                    var modifiedMs = 0L
                    tr.select("td").forEach { td ->
                        val txt = td.text().trim()
                        if (txt.length == 16 && txt[4] == '-' && txt[7] == '-') {
                            try {
                                modifiedMs = dateFormat.parse(txt)?.time ?: 0L
                            } catch (_: Exception) {}
                        }
                    }

                    entries.add(DirectoryEntry(cleanedName, href, fullUrl, isDir, modifiedMs))
                }
            }

            if (entries.isEmpty()) {
                doc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.isBlank() || href == ".." || href == "." || href.startsWith("/") && href.length == 1) return@forEach
                    if (href.contains("_h5ai") || href.contains("larsjung.de") || href.contains("browsehappy.com")) return@forEach

                    val cleanedName = cleanTitle(href)
                    if (cleanedName.isBlank()) return@forEach

                    val isDir = href.endsWith("/")
                    val fullUrl = baseUri.resolve(href).toString()
                    entries.add(DirectoryEntry(cleanedName, href, fullUrl, isDir, 0L))
                }
            }

            entries
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchCategoryEntries(cat: BDIXCategory): List<DirectoryEntry> {
        val rootUrl = fixUrl(cat.path, cat.host)
        val topEntries = fetchDirectoryListingCached(rootUrl)

        val directMovies = topEntries.filter { !isContainerFolder(it.name) }
        val containers = topEntries.filter { isContainerFolder(it.name) && it.isDirectory }

        if (containers.isEmpty()) {
            return directMovies.sortedByDescending { it.modifiedTimeMs }
        }

        // Expand container folders (recent years first, e.g. 2026, 2025, 2024, 2023...)
        val sortedContainers = containers.sortedByDescending { container ->
            Regex("""\d{4}""").find(container.name)?.value?.toIntOrNull() ?: 0
        }

        // Fetch top 5 most recent year containers in parallel with caching to maximize performance
        val expandedMovies = coroutineScope {
            sortedContainers.take(5).map { container ->
                async {
                    fetchDirectoryListingCached(container.fullUrl).filter { it.isDirectory && !isContainerFolder(it.name) }
                }
            }.awaitAll().flatten()
        }

        val allMovies = (expandedMovies + directMovies).distinctBy { it.fullUrl }
        return allMovies.sortedByDescending { it.modifiedTimeMs }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = categories.find { it.path == request.data }
            ?: categories.first()

        val entries = fetchCategoryEntries(cat)

        val items = entries.map { entry ->
            val formattedName = formatCardName(entry.name, cat, entry.href)
            val itemUrl = entry.fullUrl
            val posterUrl = if (entry.isDirectory) getOptimizedPosterUrl(itemUrl) else null

            newAnimeSearchResponse(formattedName, itemUrl, cat.type) {
                populateItemMetadata(this, entry, cat, posterUrl)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return emptyList()

        return coroutineScope {
            val tasks = categories.map { cat ->
                async {
                    val entries = fetchCategoryEntries(cat)
                    entries.filter { it.name.lowercase().contains(cleanQuery) }.map { entry ->
                        val formattedName = formatCardName(entry.name, cat, entry.href)
                        val posterUrl = if (entry.isDirectory) getOptimizedPosterUrl(entry.fullUrl) else null
                        newAnimeSearchResponse(formattedName, entry.fullUrl, cat.type) {
                            populateItemMetadata(this, entry, cat, posterUrl)
                        }
                    }
                }
            }
            tasks.awaitAll().flatten()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val serverTag = getServerTagFromUrl(url)
        val decodedTitle = cleanTitle(url, serverTag)
        val isVideoFile = isMediaFile(url)
        val extractedYear = extractYear(url)

        if (isVideoFile) {
            return newMovieLoadResponse(decodedTitle, url, TvType.Movie, url) {
                if (extractedYear != null && extractedYear in 1900..2030) this.year = extractedYear
            }
        }

        val entries = fetchDirectoryListingCached(url)
        val posterUrl = pickPosterFromEntries(entries) ?: getOptimizedPosterUrl(url)
        val videoFiles = entries.filter { isMediaFile(it.fullUrl) }
        val subDirs = entries.filter { it.isDirectory }

        if (subDirs.isNotEmpty()) {
            // Treat as TV Series with episodes
            val episodes = mutableListOf<Episode>()
            var episodeNum = 1

            for (dir in subDirs) {
                val seasonEntries = fetchDirectoryListingCached(dir.fullUrl)
                val seasonVideos = seasonEntries.filter { isMediaFile(it.fullUrl) }

                for (vid in seasonVideos) {
                    episodes.add(
                        newEpisode(vid.fullUrl) {
                            this.name = vid.name
                            this.episode = episodeNum++
                            this.season = extractSeasonNumber(dir.name)
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                // Check direct video files in root folder
                for (vid in videoFiles) {
                    episodes.add(
                        newEpisode(vid.fullUrl) {
                            this.name = vid.name
                            this.episode = episodeNum++
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(decodedTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                if (extractedYear != null && extractedYear in 1900..2030) this.year = extractedYear
            }
        }

        // Direct movie folder with media files inside
        val mainVideoUrl = videoFiles.firstOrNull()?.fullUrl ?: url
        return newMovieLoadResponse(decodedTitle, url, TvType.Movie, mainVideoUrl) {
            this.posterUrl = posterUrl
            if (extractedYear != null && extractedYear in 1900..2030) this.year = extractedYear
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMediaFile(data)) {
            val quality = getQualityFromName(data)
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    data,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
            return true
        }

        val entries = fetchDirectoryListingCached(data)
        val mediaFiles = entries.filter { isMediaFile(it.fullUrl) }

        for (file in mediaFiles) {
            val quality = getQualityFromName(file.name)
            callback.invoke(
                newExtractorLink(
                    this.name,
                    file.name,
                    file.fullUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        return mediaFiles.isNotEmpty()
    }

    private fun isMediaFile(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
               lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".flv")
    }

    private fun isImageFile(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("2160p", true) || name.contains("4k", true) -> 2160
            name.contains("1080p", true) -> 1080
            name.contains("720p", true) -> 720
            name.contains("480p", true) -> 480
            name.contains("360p", true) -> 360
            else -> 1080
        }
    }

    private fun extractSeasonNumber(dirName: String): Int {
        val match = Regex("""(?:season|s)\s*(\d+)""", RegexOption.IGNORE_CASE).find(dirName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }
}
