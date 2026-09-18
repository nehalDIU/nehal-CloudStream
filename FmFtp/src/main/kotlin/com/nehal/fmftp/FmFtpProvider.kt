package com.nehal.fmftp

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

open class FmFtpProvider : MainAPI() {
    override var name = "FM FTP"
    override var mainUrl = "https://server2.ftpbd.net"
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
    override var sequentialMainPageDelay = 120L

    private val host2 = "https://server2.ftpbd.net"
    private val host3 = "https://server3.ftpbd.net"
    private val host4 = "https://server4.ftpbd.net"
    private val host5 = "https://server5.ftpbd.net"
    private val host7 = "https://server7.ftpbd.net"

    private val directoryCache = ConcurrentHashMap<String, Pair<Long, List<DirectoryEntry>>>()
    private val cacheTtlMs = 30 * 60 * 1000L // 30 minutes memory cache
    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()

    private fun getSemaphoreForHost(url: String): Semaphore {
        val host = try { URI(url).host ?: url } catch (_: Exception) { url }
        return hostSemaphores.computeIfAbsent(host) { Semaphore(4) }
    }

    private data class FtpCategory(
        val name: String,
        val path: String,
        val host: String,
        val type: TvType
    )

    private val categories = listOf(
        // Server 2: Movies & 4K Collections
        FtpCategory("English Movies (4K)", "/FTP-2/English%20Movies/English-Movies-4K/", host2, TvType.Movie),
        FtpCategory("English Movies", "/FTP-2/English%20Movies/", host2, TvType.Movie),
        FtpCategory("English Movies (Dual Audio)", "/FTP-2/English%20Movies/Dual-Audio/", host2, TvType.Movie),
        FtpCategory("IMDb Top 250", "/FTP-2/English%20Movies/IMDB-TOP-250/", host2, TvType.Movie),
        FtpCategory("Movie Series Collection", "/FTP-2/English%20Movies/Movie-Series-Collection/", host2, TvType.Movie),
        FtpCategory("3D Movies", "/FTP-2/3D%20Movies/", host2, TvType.Movie),

        // Server 3: Hindi, South Indian, Foreign & Bangla Collections
        FtpCategory("Hindi Movies", "/FTP-3/Hindi%20Movies/", host3, TvType.Movie),
        FtpCategory("South Indian Movies", "/FTP-3/South%20Indian%20Movies/", host3, TvType.Movie),
        FtpCategory("Foreign Language Movies", "/FTP-3/Foreign%20Language%20Movies/", host3, TvType.Movie),
        FtpCategory("Bangla Movies (Kolkata & BD)", "/FTP-3/Bangla%20Collection/BANGLA/Kolkata-Bangla-Movies/", host3, TvType.Movie),
        FtpCategory("Bangla Web Series", "/FTP-3/Bangla%20Collection/BANGLA/Web-Series/", host3, TvType.TvSeries),
        FtpCategory("Hindi TV Series", "/FTP-3/Hindi%20TV%20Series/", host3, TvType.TvSeries),
        FtpCategory("South Indian TV Series", "/FTP-3/South%20Indian%20TV%20Serias/", host3, TvType.TvSeries),

        // Server 4: English & Foreign TV Series
        FtpCategory("English & Foreign TV Series", "/FTP-4/English-Foreign-TV-Series/", host4, TvType.TvSeries),

        // Server 5: Animation, Anime & Documentaries
        FtpCategory("Animation Movies", "/FTP-5/Animation%20Movies/", host5, TvType.Anime),
        FtpCategory("Anime & Cartoon TV Series", "/FTP-5/Anime--Cartoon-TV-Series/", host5, TvType.Anime),
        FtpCategory("Documentaries", "/FTP-5/Documentary/", host5, TvType.Movie),

        // Server 7: Sports, Wrestling & TV Shows
        FtpCategory("WWE Wrestling", "/FTP-7/WWE%20Wrestling/", host7, TvType.TvSeries),
        FtpCategory("AEW Wrestling", "/FTP-7/All%20Elite%20Wrestling%20%28AEW%29/", host7, TvType.TvSeries),
        FtpCategory("UFC", "/FTP-7/Ultimate%20Fighting%20Championship%20%28UFC%29/", host7, TvType.TvSeries),
        FtpCategory("Awards & TV Shows", "/FTP-7/Awards--TV-Shows/", host7, TvType.TvSeries)
    )

    override val mainPage = mainPageOf(
        *categories.map { cat -> cat.path to cat.name }.toTypedArray()
    )

    private fun getServerTagFromUrl(url: String): String {
        return when {
            url.contains("server2.ftpbd.net") || url.contains("/FTP-2/") -> "FTP-2"
            url.contains("server3.ftpbd.net") || url.contains("/FTP-3/") -> "FTP-3"
            url.contains("server4.ftpbd.net") || url.contains("/FTP-4/") -> "FTP-4"
            url.contains("server5.ftpbd.net") || url.contains("/FTP-5/") -> "FTP-5"
            url.contains("server7.ftpbd.net") || url.contains("/FTP-7/") -> "FTP-7"
            else -> "FTPBD"
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
        name = Regex("""\.(mp4|mkv|avi|m4v|webm|flv|ts|m3u8|srt|sub|vtt|txt)$""", RegexOption.IGNORE_CASE).replace(name, "")

        // Remove re-encoded suffix tags often present on ftpbd
        name = Regex("""_reencoded$""", RegexOption.IGNORE_CASE).replace(name, "")

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

    private fun isMediaFile(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".flv") ||
                lower.endsWith(".ts") || lower.endsWith(".mov") || lower.endsWith(".wmv")
    }

    private fun isImageFile(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif")
    }

    private fun isSubtitleFile(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".sub")
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

    private fun formatCardName(entryName: String, cat: FtpCategory, entryHref: String): String {
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
            combined.contains("bangla") || combined.contains("bengali") -> "Bangla"
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
        cat: FtpCategory,
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
        if (extractedYear != null && extractedYear in 1900..2035) {
            response.year = extractedYear
        }

        // 3. Set SearchQuality enum for top-right badge
        response.quality = extractQualityEnum(combinedText)

        // 4. Set Dub/Sub status for badge
        val isDualOrMulti = combinedText.contains("dual audio") || combinedText.contains("dual-audio") ||
                combinedText.contains("multi audio") || combinedText.contains("multi-audio") ||
                combinedText.contains("dual") || combinedText.contains("multi")
        val isDubbed = isDualOrMulti || combinedText.contains("dubbed") || combinedText.contains("hindi") ||
                combinedText.contains("south indian") || combinedText.contains("bangla")
        val isSubbed = combinedText.contains("sub") || combinedText.contains("esub") || combinedText.contains("msub")

        if (isDubbed || isSubbed) {
            response.addDubStatus(dubExist = isDubbed, subExist = isSubbed)
        } else {
            response.addDubStatus(dubExist = true, subExist = false)
        }
    }

    private fun isContainerFolder(name: String): Boolean {
        val clean = name.trim().lowercase()
        // Year container folder pattern: (2025), (2025) 1080p, 2025, (1995) 1080p & Before, 2020-&-Before
        if (Regex("""^\(?\d{4}\)?(\s*(1080p|720p|&|and|before|-|\b).*)*$""", RegexOption.IGNORE_CASE).matches(clean)) return true
        // Group pattern like "0 - 9", "A - L", "0 — 9", "A — L", "1991--2000"
        if (Regex("""\b(\d+\s*[—–-]+\s*\d+|[a-z]\s*[—–-]+\s*[a-z])\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return true
        if (clean == "hindi dubbed" || clean == "english dubbed" || clean == "foreign language movies" ||
            clean == "4k-film-series" || clean == "dual-audio" || clean == "temporary hindi & south indian movie upload"
        ) return true
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
            return freshEntries
        }
        return directoryCache[url]?.second ?: emptyList()
    }

    private suspend fun fetchDirectoryListing(url: String): List<DirectoryEntry> {
        return try {
            val safeUrl = url.replace(" ", "%20")
            val responseHtml = getSemaphoreForHost(safeUrl).withPermit {
                app.get(safeUrl, timeout = 25, cacheTime = 60).text
            }
            val doc = Jsoup.parse(responseHtml)
            val entries = mutableListOf<DirectoryEntry>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

            val origin = if (safeUrl.contains("://")) {
                val scheme = safeUrl.substringBefore("://")
                val rest = safeUrl.substringAfter("://")
                val host = rest.substringBefore("/")
                "$scheme://$host"
            } else ""

            val trs = doc.select("tr")
            if (trs.isNotEmpty()) {
                trs.forEach { tr ->
                    val a = tr.selectFirst("a[href]") ?: return@forEach
                    val href = a.attr("href")
                    if (href.isBlank() || href == ".." || href == "." || (href.startsWith("/") && href.length == 1)) return@forEach
                    if (href.contains("_h5ai") || href.contains("larsjung.de") || href.contains("browsehappy.com")) return@forEach

                    val cleanedName = cleanTitle(href)
                    if (cleanedName.isBlank()) return@forEach

                    val isDir = href.endsWith("/")
                    val fullUrl = if (href.startsWith("http://") || href.startsWith("https://")) {
                        href.replace(" ", "%20")
                    } else if (href.startsWith("/")) {
                        (origin + href).replace(" ", "%20")
                    } else {
                        val baseFolder = if (safeUrl.endsWith("/")) safeUrl else safeUrl.substringBeforeLast('/') + "/"
                        (baseFolder + href).replace(" ", "%20")
                    }

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
                    if (href.isBlank() || href == ".." || href == "." || (href.startsWith("/") && href.length == 1)) return@forEach
                    if (href.contains("_h5ai") || href.contains("larsjung.de") || href.contains("browsehappy.com")) return@forEach

                    val cleanedName = cleanTitle(href)
                    if (cleanedName.isBlank()) return@forEach

                    val isDir = href.endsWith("/")
                    val fullUrl = if (href.startsWith("http://") || href.startsWith("https://")) {
                        href.replace(" ", "%20")
                    } else if (href.startsWith("/")) {
                        (origin + href).replace(" ", "%20")
                    } else {
                        val baseFolder = if (safeUrl.endsWith("/")) safeUrl else safeUrl.substringBeforeLast('/') + "/"
                        (baseFolder + href).replace(" ", "%20")
                    }

                    entries.add(DirectoryEntry(cleanedName, href, fullUrl, isDir, 0L))
                }
            }

            entries
        } catch (e: Exception) {
            println("[FmFtp] Error fetching directory $url: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchCategoryEntries(cat: FtpCategory, maxContainers: Int = 2): List<DirectoryEntry> {
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

        val expandedMovies = coroutineScope {
            sortedContainers.take(maxContainers).map { container ->
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

        val rootUrl = fixUrl(cat.path, cat.host)
        val topEntries = fetchDirectoryListingCached(rootUrl)

        val directMovies = topEntries.filter { !isContainerFolder(it.name) }
        val containers = topEntries.filter { isContainerFolder(it.name) && it.isDirectory }

        val items: List<DirectoryEntry>
        val hasNext: Boolean

        if (containers.isNotEmpty()) {
            val sortedContainers = containers.sortedByDescending { container ->
                Regex("""\d{4}""").find(container.name)?.value?.toIntOrNull() ?: 0
            }

            if (page <= 1) {
                val firstContainer = sortedContainers.firstOrNull()
                val containerItems = firstContainer?.let { container ->
                    fetchDirectoryListingCached(container.fullUrl).filter { it.isDirectory && !isContainerFolder(it.name) }
                } ?: emptyList()
                items = (directMovies + containerItems).distinctBy { it.fullUrl }.sortedByDescending { it.modifiedTimeMs }
                hasNext = sortedContainers.size > 1
            } else {
                val containerIndex = page - 1
                val targetContainer = sortedContainers.getOrNull(containerIndex)
                items = targetContainer?.let { container ->
                    fetchDirectoryListingCached(container.fullUrl).filter { it.isDirectory && !isContainerFolder(it.name) }.sortedByDescending { it.modifiedTimeMs }
                } ?: emptyList()
                hasNext = containerIndex + 1 < sortedContainers.size
            }
        } else {
            val pageSize = 30
            val startIndex = (page - 1) * pageSize
            val sortedDirect = directMovies.sortedByDescending { it.modifiedTimeMs }
            items = sortedDirect.drop(startIndex).take(pageSize)
            hasNext = (startIndex + pageSize) < sortedDirect.size
        }

        val searchResponses = items.map { entry ->
            val formattedName = formatCardName(entry.name, cat, entry.href)
            val itemUrl = entry.fullUrl
            val posterUrl = if (entry.isDirectory) {
                // If it's a directory, try to find a cached poster or null
                null
            } else null

            newAnimeSearchResponse(formattedName, itemUrl, cat.type) {
                populateItemMetadata(this, entry, cat, posterUrl)
            }
        }

        return newHomePageResponse(request.name, searchResponses, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank() || cleanQuery.length < 2) return emptyList()

        return coroutineScope {
            val tasks = categories.map { cat ->
                async {
                    try {
                        val entries = fetchCategoryEntries(cat, maxContainers = 2)
                        entries.filter { it.name.lowercase().contains(cleanQuery) }.map { entry ->
                            val formattedName = formatCardName(entry.name, cat, entry.href)
                            newAnimeSearchResponse(formattedName, entry.fullUrl, cat.type) {
                                populateItemMetadata(this, entry, cat, null)
                            }
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            tasks.awaitAll().flatten()
        }
    }

    private fun extractTags(text: String): List<String> {
        val lower = text.lowercase()
        val tags = mutableListOf<String>()
        if (lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd")) tags.add("4K UHD")
        if (lower.contains("1080p") || lower.contains("fhd")) tags.add("1080p FHD")
        if (lower.contains("720p") || lower.contains("hd")) tags.add("720p HD")
        if (lower.contains("3d")) tags.add("3D")
        if (lower.contains("hindi")) tags.add("Hindi")
        if (lower.contains("bangla") || lower.contains("kolkata") || lower.contains("bengali")) tags.add("Bangla")
        if (lower.contains("korean")) tags.add("Korean")
        if (lower.contains("animation") || lower.contains("anime")) tags.add("Animation")
        if (lower.contains("imdb") || lower.contains("top-250") || lower.contains("top 250")) tags.add("IMDb Top 250")
        if (lower.contains("dual") || lower.contains("multi")) tags.add("Dual Audio")
        if (lower.contains("dubbed")) tags.add("Dubbed")
        if (lower.contains("sub") || lower.contains("esub")) tags.add("Subtitled")
        if (lower.contains("wwe") || lower.contains("wrestling") || lower.contains("aew") || lower.contains("ufc")) tags.add("Sports")
        return tags.distinct()
    }

    private fun extractSeasonNumber(dirName: String): Int? {
        val clean = dirName.lowercase()
        return Regex("""(?:season|s)\s*[-_]?\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b0*(\d+)\b""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(fileName: String): Int? {
        val clean = fileName.lowercase()
        return Regex("""(?:episode|ep|e)\s*[-_]?\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(?:e|ep)0*(\d+)\b""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val serverTag = getServerTagFromUrl(url)
        val decodedTitle = cleanTitle(url, serverTag)
        val isVideoFile = isMediaFile(url)
        val extractedYear = extractYear(url)
        val tags = extractTags(url)

        if (isVideoFile) {
            val parentFolder = url.substringBeforeLast('/') + "/"
            val folderEntries = fetchDirectoryListingCached(parentFolder)
            val posterUrl = pickPosterFromEntries(folderEntries)
            return newMovieLoadResponse(decodedTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = posterUrl
                this.tags = tags
                this.plot = "Server: $serverTag (FM FTP / FTPBD)"
                if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
            }
        }

        val entries = fetchDirectoryListingCached(url)
        val posterUrl = pickPosterFromEntries(entries)
        val videoFiles = entries.filter { isMediaFile(it.fullUrl) }
        val subDirs = entries.filter { it.isDirectory }

        if (subDirs.isNotEmpty()) {
            // Treat as TV Series with Seasons / Sub-folders
            val episodes = mutableListOf<Episode>()
            var episodeNum = 1

            for (dir in subDirs) {
                val seasonEntries = fetchDirectoryListingCached(dir.fullUrl)
                val seasonVideos = seasonEntries.filter { isMediaFile(it.fullUrl) }
                val detectedSeason = extractSeasonNumber(dir.name)

                for (vid in seasonVideos) {
                    val epNum = extractEpisodeNumber(vid.name) ?: episodeNum++
                    episodes.add(
                        newEpisode(vid.fullUrl) {
                            this.name = vid.name
                            this.episode = epNum
                            this.season = detectedSeason
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                // Check direct video files in root folder
                for (vid in videoFiles) {
                    val epNum = extractEpisodeNumber(vid.name) ?: episodeNum++
                    episodes.add(
                        newEpisode(vid.fullUrl) {
                            this.name = vid.name
                            this.episode = epNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(decodedTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = posterUrl
                this.tags = tags
                this.plot = "Server: $serverTag (FM FTP / FTPBD)"
                if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
            }
        }

        if (videoFiles.size > 1) {
            // Multiple video files directly in folder (e.g. episodic series / multi-part)
            var epCounter = 1
            val episodes = videoFiles.map { vid ->
                val epNum = extractEpisodeNumber(vid.name) ?: epCounter++
                newEpisode(vid.fullUrl) {
                    this.name = vid.name
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(decodedTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = posterUrl
                this.tags = tags
                this.plot = "Server: $serverTag (FM FTP / FTPBD)"
                if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
            }
        }

        // Single movie folder
        val targetVideoUrl = videoFiles.firstOrNull()?.fullUrl ?: url
        return newMovieLoadResponse(decodedTitle, url, TvType.Movie, targetVideoUrl) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.tags = tags
            this.plot = "Server: $serverTag (FM FTP / FTPBD)"
            if (extractedYear != null && extractedYear in 1900..2035) this.year = extractedYear
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = if (isMediaFile(data)) {
            data
        } else {
            val entries = fetchDirectoryListingCached(data)
            entries.firstOrNull { isMediaFile(it.fullUrl) }?.fullUrl ?: data
        }

        // Find potential subtitles in the same folder
        try {
            val folderUrl = videoUrl.substringBeforeLast('/') + "/"
            val folderEntries = fetchDirectoryListingCached(folderUrl)
            folderEntries.filter { isSubtitleFile(it.fullUrl) }.forEach { subEntry ->
                subtitleCallback(
                    SubtitleFile(
                        lang = subEntry.name,
                        url = subEntry.fullUrl
                    )
                )
            }
        } catch (_: Exception) {}

        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(videoUrl)
            }
        )

        return true
    }
}
