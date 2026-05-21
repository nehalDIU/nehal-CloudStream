package com.nehal.ftpbd

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class FTPBDProvider : MainAPI() {
    override var mainUrl = "https://ftpbd.net"
    override var name = "FTPBD"
    override var lang = "bn"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "movie" to "Movies",
        "tv_shows" to "TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            "${mainUrl.trimEnd('/')}/${request.data}/"
        } else {
            "${mainUrl.trimEnd('/')}/${request.data}/page/$page/"
        }
        val doc = app.get(pageUrl).document
        val items = parseSearchItems(doc)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return parseSearchItems(doc)
    }

    private fun parseSearchItems(doc: Document): List<SearchResponse> {
        return doc.select(".jws-post-item").mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href*='/movie/'], a[href*='/tv_shows/']") ?: return null
        val href = link.attr("href").trim()
        if (href.isEmpty()) return null

        val title = selectFirst(".video_title a")?.text()?.trim()
            ?: selectFirst(".tv-shows-content .title a")?.text()?.trim()
            ?: link.text().trim()
        if (title.isEmpty()) return null

        val poster = selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: selectFirst("img")?.attr("src")

        return if (href.contains("/tv_shows/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        return if (url.contains("/tv_shows/")) {
            loadTvShow(url, doc)
        } else {
            loadMovie(url, doc)
        }
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1.jws-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Missing title")
        val year = doc.selectFirst(".jws-meta-info2 .video-years")?.text()?.trim()?.toIntOrNull()
        val duration = doc.selectFirst(".jws-meta-info2 .video-time")?.text()?.let {
            getDurationFromString(it)
        }
        val tags = doc.select(".jws-category a[rel=tag]")
            .mapNotNull { it.text().trim().ifEmpty { null } }
            .takeIf { it.isNotEmpty() }
        val plot = doc.selectFirst(".js-content")?.text()?.replace("Show More", "")?.trim()
        val poster = doc.selectFirst(".single-movies .post-media img")?.attr("src")
            ?: doc.selectFirst(".post-media img")?.attr("src")

        val dataUrl = extractVideoUrl(doc) ?: url

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.duration = duration
            this.plot = plot
            this.tags = tags
        }
    }

    private suspend fun loadTvShow(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1.jws-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Missing title")
        val year = doc.selectFirst(".video-years")?.text()?.trim()?.toIntOrNull()
        val season = doc.selectFirst(".seasions-numer")?.text()?.let {
            Regex("(\\d+)").find(it)?.value?.toIntOrNull()
        }
        val tags = doc.select(".jws-category a[rel=tag]")
            .mapNotNull { it.text().trim().ifEmpty { null } }
            .takeIf { it.isNotEmpty() }
        val plot = doc.selectFirst(".jws-description")?.text()?.replace("Show More", "")?.trim()
        val poster = doc.selectFirst(".single-tv_shows .post-media img")?.attr("src")
            ?: doc.selectFirst(".post-media img")?.attr("src")

        val episodesPage = doc.selectFirst("a.jws-view-episodes")?.attr("href")?.trim()
            ?.ifEmpty { null }
            ?: "${url.trimEnd('/')}/episodes"
        val episodesDoc = app.get(episodesPage).document
        val episodes = episodesDoc.select(".episodes-info").mapNotNull { element ->
            val episodeLink = element.selectFirst("h6 a")?.attr("href")?.trim() ?: return@mapNotNull null
            val episodeName = element.selectFirst("h6 a")?.text()?.trim()
            val episodeNum = element.selectFirst(".episodes-number")?.text()?.trim()?.toIntOrNull()

            newEpisode(episodeLink) {
                this.name = episodeName
                this.episode = episodeNum
                this.season = season
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = if (data.contains(".m3u8")) {
            data
        } else {
            val doc = app.get(data).document
            extractVideoUrl(doc)
        }

        if (link.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = link,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }

    private fun extractVideoUrl(doc: Document): String? {
        val source = doc.selectFirst(".videos_player source[type*=mpegURL], .videos_player source")
            ?.attr("src")?.trim()
        if (!source.isNullOrBlank()) return source
        return doc.selectFirst(".videos_player video")?.attr("src")?.trim()
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> mainUrl.trimEnd('/') + "/" + url
        }
    }
}
