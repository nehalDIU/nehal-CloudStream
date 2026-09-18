package com.nehal.animedekho

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

open class OnepaceProvider : MainAPI() {
    override var mainUrl = "https://onepace.me"
    override var name = "OnePace"
    override val hasMainPage = true
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "/series/one-pace-english-sub/" to "One Pace English Sub",
        "/series/one-pace-english-dub/" to "One Pace English Dub",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val link = "$mainUrl${request.data}"
        val document = app.get(link).document
        val home = document.select("div.seasons.aa-crd > div.seasons-bx").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val hrefTitle = this.selectFirst("picture img")?.attr("alt") ?: ""
        val href = if (hrefTitle.contains("Dub", ignoreCase = true)) {
            "https://onepace.me/series/one-pace-english-dub"
        } else {
            "https://onepace.me/series/one-pace-english-sub"
        }
        val title = this.selectFirst("p")?.text() ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")
        val isDub = hrefTitle.contains("Dub", ignoreCase = true)

        return newAnimeSearchResponse(title, PaceMedia(href, posterUrl, title).toJson(), TvType.Anime, false) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = isDub, subExist = !isDub)
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("ul[data-results] li article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = parseJson<PaceMedia>(url)
        val document = app.get(media.url).document
        val arcInt = media.mediaType?.substringAfter("Arc ") ?: ""
        val element = document.selectFirst("div.seasons.aa-crd > div.seasons-bx:contains($arcInt)")
        val title = media.mediaType ?: "No Title"
        val poster = "https://images3.alphacoders.com/134/1342304.jpeg"
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        val year = (document.selectFirst("span.year")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:updated_time]")?.attr("content")?.substringBefore("-"))?.toIntOrNull()
        val lst = element?.select("ul.seasons-lst.anm-a li")

        return if (lst.isNullOrEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, PaceMedia(media.url, mediaType = "1").toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val episodes = lst.mapNotNull { it ->
                val name = it.selectFirst("h3.title")?.ownText() ?: "null"
                val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/OnePack.png"
                val seasonNumber = it.selectFirst("h3.title > span")?.text()?.substringAfter("S")?.substringBefore("-")?.toIntOrNull()
                newEpisode(PaceMedia(href, mediaType = "2").toJson()) {
                    this.name = name
                    this.posterUrl = epPoster
                    this.season = seasonNumber
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = try {
            parseJson<PaceMedia>(data)
        } catch (e: Throwable) {
            Log.e("OnePace", "Failed to parse PaceMedia: $e")
            return false
        }
        val body = app.get(media.url).document.selectFirst("body")?.attr("class") ?: return false
        val term = Regex("""(?:term|postid)-(\d+)""").find(body)?.groupValues?.getOrNull(1) ?: return false

        (0..4).toList().amap { i ->
            try {
                val link = app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}")
                    .document.selectFirst("iframe")?.attr("src")
                if (!link.isNullOrBlank()) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            } catch (e: Throwable) {
                Log.e("OnePace", "Failed to load link $i: $e")
            }
        }
        return true
    }

    data class PaceMedia(
        @JsonProperty("url") val url: String,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("mediaType") val mediaType: String? = null
    )
}
