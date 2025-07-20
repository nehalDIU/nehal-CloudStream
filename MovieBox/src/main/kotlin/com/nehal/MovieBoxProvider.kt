package com.nehal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api.inmoviebox.com"
    override var name = "MovieBox"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "tv-shows" to "TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data}?page=$page").document
        val homeResponse = doc.select("div.movie-item") // This selector needs to be updated based on actual API response
        val home = homeResponse.mapNotNull { post ->
            toSearchResult(post)
        }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toSearchResult(post: Element): SearchResponse? {
        val name = post.selectFirst("h3")?.text() ?: return null
        val url = post.selectFirst("a")?.attr("href") ?: return null
        val image = post.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(name, fixUrl(url), TvType.Movie) {
            this.posterUrl = fixUrlNull(image)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select("div.search-result").mapNotNull { post ->
            toSearchResult(post)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst("img.poster")?.attr("src")
        val plot = doc.selectFirst(".description")?.text()
        val year = doc.selectFirst(".year")?.text()?.toIntOrNull()
        val tags = doc.select(".genre a").map { it.text() }
        
        // Check if it's a TV series or movie based on episodes
        val episodes = doc.select(".episode-list .episode")
        
        return if (episodes.isNotEmpty()) {
            val episodeList = episodes.mapNotNull { ep ->
                val epName = ep.selectFirst(".episode-title")?.text() ?: return@mapNotNull null
                val epUrl = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epNum = ep.selectFirst(".episode-number")?.text()?.toIntOrNull()
                
                newEpisode(fixUrl(epUrl)) {
                    this.name = epName
                    this.episode = epNum
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Extract video links - this needs to be customized based on the actual API structure
        doc.select(".video-source").forEach { source ->
            val videoUrl = source.attr("data-src") ?: source.attr("src")
            if (videoUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        videoUrl,
                        name,
                        url = videoUrl
                    )
                )
            }
        }
        
        return true
    }
}
