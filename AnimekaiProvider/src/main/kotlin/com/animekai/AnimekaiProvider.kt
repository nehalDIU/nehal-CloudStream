package com.animekai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeKaiProvider : MainAPI() {
    override var mainUrl = "https://animekai.be"
    override var name = "AnimeKai"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "featured" to "Featured Spotlight",
        "all-updates" to "Latest Updates",
        "sub-updates" to "Latest Sub",
        "dub-updates" to "Latest Dub",
        "china-updates" to "Chinese Donghua",
        "trending" to "Top Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        AnalyticsTracker.heartbeat(name)

        if (request.data == "featured") {
            if (page > 1) return null
            val doc = app.get("$mainUrl/home", headers = mapOf("Referer" to "$mainUrl/")).document
            val featuredItems = doc.select("#featuredSwiper .swiper-slide, #featured .swiper-slide").mapNotNull { slide ->
                val link = slide.selectFirst("a[href*='/watch/']") ?: return@mapNotNull null
                val href = fixUrl(link.attr("href"))
                val title = slide.selectFirst(".title, h3, h2")?.text()?.trim()
                    ?: link.text().trim().ifBlank { return@mapNotNull null }
                val poster = slide.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            return newHomePageResponse(
                listOf(HomePageList(request.name, featuredItems, isHorizontalImages = true)),
                hasNext = false
            )
        }

        if (request.data == "trending") {
            if (page > 1) return null
            val doc = app.get("$mainUrl/home", headers = mapOf("Referer" to "$mainUrl/")).document
            val trendingItems = doc.select("#trending-anime .aitem").mapNotNull { it.toAnimeSearchResponse() }

            return newHomePageResponse(
                listOf(HomePageList(request.name, trendingItems, isHorizontalImages = false)),
                hasNext = false
            )
        }

        // AJAX Updates Tabs: all-updates, sub-updates, dub-updates, china-updates
        val tab = request.data
        val url = "$mainUrl/ajax/home/items?tab=$tab&page=$page"
        val res = app.get(url, headers = mapOf("Referer" to "$mainUrl/home")).parsedSafe<HomeAjaxResponse>()
        val html = res?.html ?: return newHomePageResponse(emptyList())

        val doc = Jsoup.parse(html)
        val homeItems = doc.select(".aitem").mapNotNull { it.toAnimeSearchResponse() }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems, isHorizontalImages = false)),
            hasNext = res.hasNext == true
        )
    }

    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val link = selectFirst("a.title") ?: selectFirst("a.poster") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.text().trim().ifBlank {
            selectFirst(".title")?.text()?.trim() ?: return null
        }
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        val subCount = selectFirst(".info .sub")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val dubCount = selectFirst(".info .dub")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val typeStr = selectFirst(".info span:last-child")?.text()?.trim()

        val tvType = when (typeStr?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(
                dubExist = dubCount != null && dubCount > 0,
                dubEpisodes = dubCount,
                subExist = subCount != null && subCount > 0,
                subEpisodes = subCount
            )
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        AnalyticsTracker.track(name, "search", mapOf("query" to query, "page" to page))

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        // Dual-tier: Fast REST Suggest API for first page
        if (page == 1) {
            val suggestUrl = "$mainUrl/api/search/suggest?q=$encodedQuery"
            val res = runCatching {
                app.get(suggestUrl, headers = mapOf("Referer" to "$mainUrl/")).parsedSafe<List<SuggestItem>>()
            }.getOrNull()

            if (!res.isNullOrEmpty()) {
                val results = res.map { item ->
                    val href = "$mainUrl/watch/${item.slug}"
                    val tvType = when (item.type?.uppercase()) {
                        "MOVIE" -> TvType.AnimeMovie
                        "SPECIAL", "OVA" -> TvType.OVA
                        else -> TvType.Anime
                    }
                    newAnimeSearchResponse(item.title, href, tvType) {
                        this.posterUrl = item.posterUrl?.let { fixUrl(it) }
                        addDubStatus(
                            dubExist = item.hasDub == true || (item.dubCount != null && item.dubCount > 0),
                            dubEpisodes = item.dubCount,
                            subExist = item.hasSub == true || (item.subCount != null && item.subCount > 0),
                            subEpisodes = item.subCount
                        )
                    }
                }
                return results.toNewSearchResponseList()
            }
        }

        // Full Catalog / Multi-page Search Fallback: /browse?keyword=
        val url = if (page > 1) {
            "$mainUrl/browse?keyword=$encodedQuery&page=$page"
        } else {
            "$mainUrl/browse?keyword=$encodedQuery"
        }

        val browseDoc = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        val items = browseDoc.select(".aitem").mapNotNull { it.toAnimeSearchResponse() }
        val hasNext = browseDoc.select(".pagination .next, .pagination a[rel='next']").isNotEmpty()

        return items.toNewSearchResponseList(hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        AnalyticsTracker.track(name, "load", mapOf("url" to url))

        val doc = app.get(url).document
        val entity = doc.selectFirst("#main-entity")

        val title = entity?.selectFirst("h1.title")?.attr("data-orig")
            ?: entity?.selectFirst("h1.title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("Episode")?.trim()
            ?: "Unknown Anime"

        val titleJp = entity?.selectFirst("h1.title")?.attr("data-jp")
        val altTitles = entity?.selectFirst("small.al-title")?.text()?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        val poster = doc.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
        val backdrop = doc.selectFirst(".poster-wrap-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
        }

        val plot = doc.selectFirst(".desc#synopsis-text")?.text()?.trim()
            ?: doc.selectFirst(".desc, .synopsis")?.text()?.trim()

        val ratingFloat = doc.selectFirst("#anime-rating")?.attr("data-score")?.toFloatOrNull()
        val genres = doc.select("#main-entity a[href*='/genres/']").map { it.text().trim() }
        val statusText = doc.selectFirst("#main-entity .detail div:contains(Status)")?.text()
        val showStatus = if (statusText?.contains("Completed", ignoreCase = true) == true) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        val slug = url.substringAfterLast("/").substringBefore("?").substringBefore("#")

        val episodes = doc.select(".eplist ul.range li a").mapNotNull { epLink ->
            val num = epLink.attr("num").toIntOrNull() ?: return@mapNotNull null
            val epName = epLink.selectFirst("span")?.text()?.trim() ?: "Episode $num"

            newEpisode("$slug:$num") {
                this.name = epName
                this.episode = num
                this.posterUrl = poster
            }
        }.distinctBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.score = Score.from10(ratingFloat)
            this.tags = genres
            this.showStatus = showStatus
            this.japName = titleJp
            this.synonyms = altTitles
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        AnalyticsTracker.track(name, "loadLinks", mapOf("data" to data))

        val parts = data.split(":")
        val (slug, epNum) = if (parts.size >= 2) {
            parts[0] to parts[1]
        } else {
            val s = data.substringAfter("/watch/").substringBefore("/")
            val n = Regex("""ep-(\d+)""").find(data)?.groupValues?.get(1) ?: "1"
            s to n
        }

        val sourcesUrl = "$mainUrl/watch/$slug/ep/$epNum/sources"
        val sourcesRes = app.get(
            sourcesUrl,
            headers = mapOf(
                "Referer" to "$mainUrl/watch/$slug",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<SourcesApiResponse>() ?: return false

        val servers = mutableListOf<Pair<String, String>>()
        sourcesRes.sources?.sub?.forEach { src ->
            src.sourceUrl?.let {
                val serverLabel = src.serverName?.ifBlank { "Server" } ?: "Server"
                servers.add("MegaPlay (Sub - $serverLabel)" to it)
            }
        }
        sourcesRes.sources?.dub?.forEach { src ->
            src.sourceUrl?.let {
                val serverLabel = src.serverName?.ifBlank { "Server" } ?: "Server"
                servers.add("MegaPlay (Dub - $serverLabel)" to it)
            }
        }

        servers.amap { (displayName, embedUrl) ->
            try {
                MegaPlay(displayName).getUrl(embedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        return true
    }

    // ── Supporting Data Models ───────────────────────────────────────
    data class HomeAjaxResponse(
        @JsonProperty("html") val html: String? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("has_next") val hasNext: Boolean? = null
    )

    data class SuggestItem(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("title_jp") val titleJp: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("has_sub") val hasSub: Boolean? = null,
        @JsonProperty("has_dub") val hasDub: Boolean? = null,
        @JsonProperty("sub_count") val subCount: Int? = null,
        @JsonProperty("dub_count") val dubCount: Int? = null
    )

    data class SourcesApiResponse(
        @JsonProperty("sources") val sources: SourcesContainer? = null
    )

    data class SourcesContainer(
        @JsonProperty("sub") val sub: List<ServerItem>? = emptyList(),
        @JsonProperty("dub") val dub: List<ServerItem>? = emptyList()
    )

    data class ServerItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("source_url") val sourceUrl: String? = null
    )
}
