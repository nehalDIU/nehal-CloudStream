package com.nehal.movielinkbd

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class MovieLinkBDProvider : MainAPI() {
    override var name = "MovieLinkBD"
    override var mainUrl = "https://y4fbhj.movielinkbd.li"
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "latest" to "Latest Releases",
        "type/movies" to "All Movies",
        "type/series" to "All Web Series",
        "anime" to "Anime Zone",
        "ongoing" to "Ongoing Series",
        "drama" to "K/J/C Drama",
        "language/bangla" to "Bangla Movies",
        "language/bangla-dubbed" to "Bangla Dubbed",
        "language/hindi" to "Hindi Movies",
        "language/hindi-dubbed" to "Hindi Dubbed",
        "southIndian" to "South Indian",
        "language/dual-audio" to "Dual Audio",
        "language/english" to "English Movies",
        "language/korean" to "Korean Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (request.data == "latest") {
            if (page == 1) mainUrl else "$mainUrl/page/$page"
        } else {
            if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        }

        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            return null
        }

        val items = doc.select("a[href*=\"/movie/\"], a[href*=\"/series/\"], a[href*=\"/anime/\"], a[href*=\"/drama/\"], a[href*=\"/download18plus/\"]")
            .mapNotNull { el -> el.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/search?q=$query"
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            return null
        }

        return doc.select("a[href*=\"/movie/\"], a[href*=\"/series/\"], a[href*=\"/anime/\"], a[href*=\"/drama/\"], a[href*=\"/download18plus/\"]")
            .mapNotNull { el -> el.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = fixUrl(url)
        val doc = try {
            app.get(fullUrl).document
        } catch (e: Exception) {
            return null
        }

        val rawTitle = doc.selectFirst("h1, h2, .entry-title")?.text()?.trim()
            ?: doc.title().substringBefore("•").substringBefore("|").trim()
        val title = rawTitle.replace(Regex("(?i)\\b(HDTC|WEB-DL|BluRay|HDRip|720p|1080p|480p|Best Quality Print|Completed|Hindi|Bangla|Dual Audio)\\b"), "").trim()

        val poster = fixUrlNull(
            doc.selectFirst("img[title*=\"Poster\"], img[alt*=\"MovieLinkBD\"], .entry-content img, .post-content img")?.let {
                it.attr("data-src").takeIf { src -> src.isNotBlank() } ?: it.attr("src")
            } ?: doc.select("img").firstOrNull { !it.attr("src").contains("logo", true) && !it.attr("src").contains("movielinkbd.webp", true) }?.attr("src")
        )

        val textContent = doc.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(textContent)?.value?.toIntOrNull()

        val ratingText = doc.select("*").firstOrNull { it.text().contains("IMDb:", ignoreCase = true) }?.text()
        val rating = ratingText?.let { Regex("(?i)IMDb:\\s*([0-9.]+)/10").find(it)?.groupValues?.get(1) }

        val plot = doc.select("p, .storyline, .description")
            .map { it.text().trim() }
            .firstOrNull { it.length > 20 && !it.contains("বিজ্ঞাপন", ignoreCase = true) && !it.contains("Download", ignoreCase = true) }

        val isSeries = fullUrl.contains("/series/") || fullUrl.contains("/drama/") || fullUrl.contains("/ongoing") || title.contains("Season", ignoreCase = true) || title.contains("S0", ignoreCase = true)

        val fileLinks = doc.select("a[href*=\"/file/\"]").map { el ->
            val label = el.text().trim().takeIf { it.isNotBlank() } ?: "Download Stream"
            val fileUrl = fixUrl(el.attr("href"))
            label to fileUrl
        }.distinctBy { it.second }

        return if (isSeries) {
            val episodes = fileLinks.mapIndexed { idx, (label, link) ->
                val epNum = Regex("(?i)Ep(?:isode)?\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val seasonNum = Regex("(?i)S(?:eason)?\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(link) {
                    this.name = label
                    this.episode = epNum
                    this.season = seasonNum
                }
            }

            newTvSeriesLoadResponse(title, fullUrl, TvType.TvSeries, episodes.ifEmpty {
                listOf(newEpisode(fullUrl) { this.name = title })
            }) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                if (rating != null) this.score = Score.from10(rating)
            }
        } else {
            val dataUrl = fileLinks.firstOrNull()?.second ?: fullUrl
            newMovieLoadResponse(title, fullUrl, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = fixUrl(data)
        var foundAny = false

        val filePagesToVisit = mutableListOf<String>()

        if (fullUrl.contains("/file/")) {
            filePagesToVisit.add(fullUrl)
        } else {
            try {
                val doc = app.get(fullUrl).document
                val links = doc.select("a[href*=\"/file/\"]").map { fixUrl(it.attr("href")) }
                filePagesToVisit.addAll(links)
            } catch (_: Exception) {}
        }

        for (fileUrl in filePagesToVisit.distinct()) {
            try {
                val doc = app.get(fileUrl).document
                val streamLinks = doc.select("a[href]").map { fixUrl(it.attr("href")) }
                    .filter { link ->
                        link.contains("instantcloud.org") ||
                        link.contains("xcloud.asia") ||
                        link.contains(".mp4") ||
                        link.contains(".mkv") ||
                        link.contains(".m3u8")
                    }

                for (link in streamLinks) {
                    if (link.contains("instantcloud.org") || link.contains("xcloud.asia")) {
                        val hostName = if (link.contains("xcloud")) "XCloud" else "InstantCloud"
                        callback.invoke(
                            newExtractorLink(
                                name = "$hostName (Use WebView)",
                                source = this.name,
                                url = link,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                        foundAny = true
                    }

                    if (link.contains(".mp4") || link.contains(".mkv") || link.contains(".m3u8")) {
                        val isM3u8 = link.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                name = if (link.contains("xcloud")) "XCloud Stream" else "Cloud Stream",
                                source = this.name,
                                url = link,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        foundAny = true
                    }
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        val fullHref = fixUrl(href)

        var title = this.selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank() || title.equals("poster", ignoreCase = true)) {
            title = this.text().trim().lines().firstOrNull { it.isNotBlank() }
        }
        if (title.isNullOrBlank()) return null

        var posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")
        )
        if (posterUrl?.contains("mlbd_load.svg") == true) {
            posterUrl = null
        }

        val year = Regex("\\b(19|20)\\d{2}\\b").find(this.text())?.value?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        val isTv = fullHref.contains("/series/") || fullHref.contains("/drama/") || fullHref.contains("/ongoing")

        return if (isTv) {
            newTvSeriesSearchResponse(title, fullHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fullHref, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> mainUrl.trimEnd('/') + "/" + url
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }
}
