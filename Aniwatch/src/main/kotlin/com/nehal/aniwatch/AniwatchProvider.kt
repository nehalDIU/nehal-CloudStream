package com.nehal.aniwatch

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class AniwatchProvider : MainAPI() {
    override var mainUrl = "https://aniwatch.co.at"
    override var name = "Aniwatch"
    override var lang = "en"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val megaPlayBase = "https://megaplay.buzz"

    override val mainPage = mainPageOf(
        "$mainUrl/anime/" to "Anime Archive",
        "$mainUrl/top-airing/" to "Top Airing",
        "$mainUrl/most-popular-anime/" to "Most Popular",
        "$mainUrl/most-favorite-anime/" to "Most Favorite"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = buildPagedUrl(request.data, page)
        val doc = app.get(url).document
        val results = parseSearchItems(doc)
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = buildSearchUrl(query, page)
        val doc = app.get(url).document
        return parseSearchItems(doc).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val episodeDoc = app.get(url).document
        val animeId = episodeDoc.selectFirst("#ani_detail")?.attr("data-anime-id")?.trim().orEmpty()

        val animeUrl = episodeDoc.selectFirst("#ani_detail .anisc-detail .film-name a")
            ?.attr("href")
            ?.trim()
            ?.ifBlank { null }

        val animeDoc = if (!animeUrl.isNullOrBlank()) {
            app.get(animeUrl).document
        } else {
            episodeDoc
        }

        val title = animeDoc.selectFirst("#ani_detail .film-name, #ani_detail .anisc-detail .film-name")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: episodeDoc.selectFirst("#ani_detail .film-name, #ani_detail .anisc-detail .film-name")
                ?.text()
                ?.trim()
                ?.ifBlank { null }
            ?: "Unknown Title"

        val poster = animeDoc.selectFirst("#ani_detail .film-poster img")
            ?.attr("data-src")
            ?.ifBlank { null }
            ?: animeDoc.selectFirst("#ani_detail .film-poster img")?.attr("src")

        val plot = animeDoc.selectFirst("#ani_detail .film-description .text, #ani_detail .film-description")
            ?.text()
            ?.trim()
            ?.ifBlank { null }

        val tags = animeDoc.select("#ani_detail a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val status = parseStatus(animeDoc)
        val episodes = if (animeId.isNotBlank()) fetchEpisodes(animeId) else emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = if (tags.isEmpty()) null else tags
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.substringBefore("|").trim()
        if (episodeId.isBlank()) return false

        val serverRes = app.get("$mainUrl/wp-json/hianime/v1/episode/servers/$episodeId")
            .parsedSafe<HtmlResponse>()
            ?: return false

        if (serverRes.status != true) return false

        var found = false
        serverRes.doc.select("div.server-item").forEach { item ->
            val hash = item.attr("data-hash")
            if (hash.isBlank()) return@forEach

            val serverUrl = base64Decode(hash)
            val type = item.attr("data-type").lowercase()
            val label = when (type) {
                "dub" -> "VidSrc [Dub]"
                "sub" -> "VidSrc [Sub]"
                else -> "VidSrc"
            }

            when {
                serverUrl.contains("my.1anime.site/index.php?action=play", ignoreCase = true) -> {
                    val direct = extractDirectVideo(serverUrl)
                    if (!direct.isNullOrBlank()) {
                        found = addDirectLink(direct, label, callback) || found
                    }
                }
                serverUrl.contains("/megaplay/", ignoreCase = true) ||
                    serverUrl.contains("megaplay.buzz", ignoreCase = true) -> {
                    if (extractMegaPlay(serverUrl, label, subtitleCallback, callback)) {
                        found = true
                    }
                }
                serverUrl.contains(".m3u8", ignoreCase = true) ||
                    serverUrl.contains(".mp4", ignoreCase = true) -> {
                    found = addDirectLink(serverUrl, label, callback) || found
                }
            }
        }

        return found
    }

    private fun parseSearchItems(doc: Document): List<AnimeSearchResponse> {
        val items = doc.select(".film_list .flw-item")
            .ifEmpty { doc.select(".flw-item") }

        return items.mapNotNull { item ->
            val link = item.selectFirst(".film-name a") ?: item.selectFirst("a.film-poster-ahref")
            val href = link?.attr("href")?.trim().orEmpty()
            if (href.isBlank()) return@mapNotNull null

            val title = link.text().trim().ifBlank { link.attr("title").trim() }
            if (title.isBlank()) return@mapNotNull null

            val img = item.selectFirst("img")
            val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }

            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    private suspend fun fetchEpisodes(animeId: String): List<Episode> {
        val res = app.get("$mainUrl/wp-json/hianime/v1/episode/list/$animeId")
            .parsedSafe<HtmlResponse>()
            ?: return emptyList()

        if (res.status != true) return emptyList()

        return res.doc.select("a.ssl-item.ep-item").mapNotNull { element ->
            val epId = element.attr("data-id").trim()
            if (epId.isBlank()) return@mapNotNull null

            val number = element.attr("data-number").toIntOrNull()
            val name = element.selectFirst(".ep-name")?.text()?.trim()
                ?: element.attr("title")?.trim().orEmpty()

            newEpisode(epId) {
                this.episode = number
                this.name = name.ifBlank { "Episode ${number ?: 0}" }
            }
        }
    }

    private fun parseStatus(doc: Document): ShowStatus? {
        val text = doc.select("#ani_detail").text()
        return when {
            text.contains("Finished Airing", ignoreCase = true) -> ShowStatus.Completed
            text.contains("Currently Airing", ignoreCase = true) -> ShowStatus.Ongoing
            text.contains("Airing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }
    }

    private suspend fun extractDirectVideo(url: String): String? {
        val doc = app.get(url).document
        val src = doc.selectFirst("video source")?.attr("src")
            ?.ifBlank { null }
            ?: doc.selectFirst("video")?.attr("src")?.ifBlank { null }

        return resolveUrl(url, src)
    }

    private fun addDirectLink(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (url.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(label, url, mainUrl).forEach(callback)
            true
        } else {
            callback(
                newExtractorLink(
                    source = "VidSrc",
                    name = label,
                    url = url,
                    type = ExtractorLinkType.VIDEO
                )
            )
            true
        }
    }

    private suspend fun extractMegaPlay(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = if (url.contains("1anime.site/megaplay", ignoreCase = true)) {
            "https://megaplay.buzz" + url.substringAfter("/megaplay")
        } else {
            url
        }

        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$megaPlayBase/"
        )

        val doc = app.get(embedUrl, headers = headers).document
        val id = doc.selectFirst("#megaplay-player")?.attr("data-id")?.trim().orEmpty()
        if (id.isBlank()) return false

        val apiUrl = "$megaPlayBase/stream/getSources?id=$id&id=$id"
        val response = app.get(apiUrl, headers).parsedSafe<MegaPlayResponse>() ?: return false
        val m3u8 = response.sources?.file ?: return false

        M3u8Helper.generateM3u8(label, m3u8, megaPlayBase, headers = mapOf("Referer" to "$megaPlayBase/"))
            .forEach(callback)

        response.tracks.forEach { track ->
            val file = track.file ?: return@forEach
            val kind = track.kind ?: ""
            if (kind.contains("caption", ignoreCase = true) || kind.contains("subtitle", ignoreCase = true)) {
                subtitleCallback(
                    newSubtitleFile(track.label ?: "Unknown", file) {
                        this.headers = mapOf("Referer" to "$megaPlayBase/")
                    }
                )
            }
        }

        return true
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        val clean = base.trimEnd('/')
        return if (page <= 1) {
            "$clean/"
        } else {
            "$clean/page/$page/"
        }
    }

    private fun buildSearchUrl(query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }
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

    private fun fixUrl(url: String): String = fixUrlNull(url) ?: url

    private fun resolveUrl(base: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            URI(base).resolve(url).toString()
        } catch (_: Exception) {
            url
        }
    }

    data class HtmlResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("html") val html: String? = null
    ) {
        val doc: Document = Jsoup.parse(html ?: "")
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track> = emptyList()
    )

    data class Sources(
        @JsonProperty("file") val file: String? = null
    )

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
