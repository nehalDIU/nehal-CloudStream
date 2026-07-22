package com.nehal.dhakaflixbdix

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
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
        BDIXCategory("South Indian Movies", "/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/", host14, TvType.Movie),
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

    private fun decodeName(raw: String): String {
        return try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
                .replace("/", "")
                .trim()
        } catch (_: Exception) {
            raw.replace("/", "").trim()
        }
    }

    private fun fixUrl(path: String, host: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$host$cleanPath"
    }

    private data class DirectoryEntry(
        val name: String,
        val href: String,
        val fullUrl: String,
        val isDirectory: Boolean
    )

    private suspend fun fetchDirectoryListing(url: String): List<DirectoryEntry> {
        return try {
            val responseHtml = app.get(url, timeout = 10).text
            val doc = Jsoup.parse(responseHtml)
            val baseUri = URI(url)
            val entries = mutableListOf<DirectoryEntry>()

            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.isBlank() || href == ".." || href == "." || href.startsWith("/") && href.length == 1) return@forEach
                if (href.contains("_h5ai") || href.contains("larsjung.de") || href.contains("browsehappy.com")) return@forEach

                val decodedName = decodeName(href)
                if (decodedName.isBlank()) return@forEach

                val isDir = href.endsWith("/")
                val fullUrl = baseUri.resolve(href).toString()
                entries.add(DirectoryEntry(decodedName, href, fullUrl, isDir))
            }
            entries
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = categories.find { it.path == request.data }
            ?: categories.first()

        val fullUrl = fixUrl(cat.path, cat.host)
        val entries = fetchDirectoryListing(fullUrl)

        val items = entries.map { entry ->
            val name = entry.name
            val itemUrl = entry.fullUrl
            if (cat.type == TvType.TvSeries) {
                newTvSeriesSearchResponse(name, itemUrl, TvType.TvSeries) {
                    this.posterUrl = null
                }
            } else {
                newMovieSearchResponse(name, itemUrl, TvType.Movie) {
                    this.posterUrl = null
                }
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
                    val catUrl = fixUrl(cat.path, cat.host)
                    val entries = fetchDirectoryListing(catUrl)
                    entries.filter { it.name.lowercase().contains(cleanQuery) }.map { entry ->
                        if (cat.type == TvType.TvSeries) {
                            newTvSeriesSearchResponse(entry.name, entry.fullUrl, TvType.TvSeries)
                        } else {
                            newMovieSearchResponse(entry.name, entry.fullUrl, TvType.Movie)
                        }
                    }
                }
            }
            tasks.awaitAll().flatten()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val decodedTitle = decodeName(url.substringAfterLast('/'))
        val isVideoFile = isMediaFile(url)

        if (isVideoFile) {
            return newMovieLoadResponse(decodedTitle, url, TvType.Movie, url)
        }

        val entries = fetchDirectoryListing(url)
        val videoFiles = entries.filter { isMediaFile(it.fullUrl) }
        val subDirs = entries.filter { it.isDirectory }

        if (subDirs.isNotEmpty()) {
            // Treat as TV Series with episodes
            val episodes = mutableListOf<Episode>()
            var episodeNum = 1

            for (dir in subDirs) {
                val seasonEntries = fetchDirectoryListing(dir.fullUrl)
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

            return newTvSeriesLoadResponse(decodedTitle, url, TvType.TvSeries, episodes)
        }

        // Direct movie folder with media files inside
        val mainVideoUrl = videoFiles.firstOrNull()?.fullUrl ?: url
        return newMovieLoadResponse(decodedTitle, url, TvType.Movie, mainVideoUrl)
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

        val entries = fetchDirectoryListing(data)
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
