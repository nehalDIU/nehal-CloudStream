package com.nehal.showtimebd

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

open class ShowTimeBDProvider : MainAPI() {
    override var mainUrl = "http://showtimebd.com"
    override var name = "ShowTime BD"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override var lang = "bn"

    override val mainPage = mainPageOf(
        "movie/NewMovie" to "Trending Now",
        "movie/category_movie/1" to "Hollywood Movies",
        "movie/category_movie/3" to "Bollywood Movies",
        "movie/category_movie/6" to "South Indian Movies",
        "movie/category_movie/5" to "Animation Movies",
        "movie/category_movie/9" to "TV Series & TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(
            "$mainUrl/${request.data}",
            params = mapOf("page" to page.toString())
        ).document

        val items = doc.select("div.single_movie").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/movie/search",
            params = mapOf("search" to query)
        ).document
        return doc.select("div.single_movie").mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = selectFirst("figcaption a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href").trim()
        if (title.isEmpty() || href.isEmpty()) return null

        val posterUrl = selectFirst("div.photo_grid img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(".single_page h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Missing title")
        val downloadUrl = doc.selectFirst(".single_page a:contains(Click For Download)")?.attr("href")
            ?: throw ErrorLoadingException("Missing download link")

        var year: Int? = null
        var tags: List<String>? = null

        doc.select(".single_page li").map { it.text().trim() }.forEach { item ->
            val parts = item.split(":", limit = 2)
            if (parts.size < 2) return@forEach
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when {
                key.contains("genre") -> {
                    tags = value.split(",").mapNotNull { tag ->
                        tag.trim().takeIf { it.isNotEmpty() }
                    }
                }
                key.contains("realese") || key.contains("release") -> {
                    year = Regex("(19|20)\\d{2}").find(value)?.value?.toIntOrNull()
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, downloadUrl) {
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                data,
                this.name,
                url = data
            )
        )
        return true
    }
}
