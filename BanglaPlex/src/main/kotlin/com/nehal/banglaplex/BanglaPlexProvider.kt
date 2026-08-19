package com.nehal.banglaplex

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class BanglaPlexProvider : MainAPI() {
    override var name = "BanglaPlex"
    override var mainUrl = "https://banglaplex.lat"
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "latest" to "Latest Releases",
        "genre/bengali-movies" to "Bengali Movies",
        "genre/bengali-web-series" to "Bengali Web Series",
        "genre/bollywood-movies" to "Bollywood Movies",
        "genre/bollywood-series" to "Bollywood Series",
        "genre/hollywood-movies" to "Hollywood Movies",
        "genre/hollywood-web-series" to "Hollywood Web Series",
        "genre/south-indian-movies" to "South Indian Movies",
        "genre/korean-movies" to "Korean Movies",
        "genre/korean-web-series" to "Korean Web Series",
        "genre/dual-audio-movies" to "Dual Audio Movies",
        "genre/dual-audio-series" to "Dual Audio Series",
        "genre/action" to "Action",
        "genre/comedy" to "Comedy",
        "genre/horror" to "Horror",
        "genre/thriller" to "Thriller"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val targetUrl = when {
            request.data == "latest" -> {
                if (page == 1) "$mainUrl/" else "$mainUrl/genre/bengali-movies/${(page - 1) * 24}.html"
            }
            request.data.startsWith("genre/") -> {
                if (page == 1) "$mainUrl/${request.data}.html" else "$mainUrl/${request.data}/${(page - 1) * 24}.html"
            }
            else -> return null
        }

        val doc = try {
            app.get(targetUrl, referer = mainUrl).document
        } catch (e: Exception) {
            Log.e("BanglaPlex", "Failed to get main page: ${e.message}")
            return null
        }

        val items = parseCards(doc, request.data)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val doc = try {
            app.get(url, referer = mainUrl).document
        } catch (e: Exception) {
            Log.e("BanglaPlex", "Failed to search: ${e.message}")
            return emptyList()
        }
        return parseCards(doc, "search")
    }

    private fun parseCards(doc: Document, context: String = ""): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        doc.select(".col-md-2, .col-sm-3, .col-xs-6, .movie-opt, .movie-container, .latest-movie").forEach { container ->
            val linkEl = container.selectFirst("a[href*=\"/watch/\"]") ?: return@forEach
            val href = linkEl.attr("href").trim()
            val cleanUrl = fixUrl(href)
            if (!cleanUrl.contains("/watch/") || !seen.add(cleanUrl)) return@forEach

            val titleEl = container.selectFirst(".movie-title h3 a, .movie-title a, .popup")
            var title = titleEl?.attr("title")?.takeIf { it.isNotBlank() }
                ?: titleEl?.text()?.trim()
                ?: linkEl.text().trim()

            if (title.isBlank()) {
                title = cleanUrl.substringAfterLast("/").substringBefore(".html").replace("-", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

            // Extract Poster
            val imgContainer = container.selectFirst(".latest-movie-img-container, .movie-img")
            val style = imgContainer?.attr("style") ?: ""
            val bgPoster = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            val imgTag = container.selectFirst("img")
            val poster = fixUrlNull(bgPoster ?: imgTag?.attr("src") ?: imgTag?.attr("data-src"))

            // Extract Year
            val yearText = container.selectFirst(".video_year_movie .label-year, .label-year")?.text()?.trim()
            val year = yearText?.toIntOrNull() ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

            // Extract Quality
            val qualityText = container.selectFirst(".video_quality_movie .label-primary, .label-primary")?.text()?.trim()
            val quality = getSearchQuality(qualityText)

            // Extract Rating / Score
            val ratingText = container.selectFirst(".imdb-rating .label-imdb, .label-imdb")?.text()?.trim()
            val score = ratingText?.replace(Regex("(?i)imdb"), "")?.trim()

            val isTv = context.contains("series", ignoreCase = true) ||
                    cleanUrl.contains("series", ignoreCase = true) ||
                    title.contains("season", ignoreCase = true) ||
                    title.contains("s0", ignoreCase = true)

            if (isTv) {
                items.add(
                    newTvSeriesSearchResponse(title, cleanUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        this.quality = quality
                        if (!score.isNullOrBlank()) this.score = Score.from10(score)
                    }
                )
            } else {
                items.add(
                    newMovieSearchResponse(title, cleanUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        this.quality = quality
                        if (!score.isNullOrBlank()) this.score = Score.from10(score)
                    }
                )
            }
        }

        return items
    }

    private fun getSearchQuality(str: String?): SearchQuality? {
        if (str.isNullOrBlank()) return null
        val lower = str.lowercase()
        return when {
            lower.contains("4k") || lower.contains("uhd") -> SearchQuality.UHD
            lower.contains("hd") || lower.contains("1080p") || lower.contains("720p") || lower.contains("web-dl") || lower.contains("hdrip") -> SearchQuality.HD
            lower.contains("cam") || lower.contains("hdtc") || lower.contains("predvd") -> SearchQuality.Cam
            else -> SearchQuality.HD
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = fixUrl(url)
        val doc = app.get(cleanUrl, referer = mainUrl).document

        // Clean title extraction
        val rawTitle = doc.selectFirst("h1.movie-title, h1, .movie-details h1")?.text()?.trim()
            ?: doc.title().trim()

        val cleanTitle = rawTitle
            .substringBefore(" |")
            .substringBefore(" –")
            .substringBefore(" -")
            .trim()

        val posterStyle = doc.selectFirst(".latest-movie-img-container")?.attr("style") ?: ""
        val bgPoster = Regex("""url\(['"]?(.*?)['"]?\)""").find(posterStyle)?.groupValues?.get(1)
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: bgPoster
                ?: doc.selectFirst(".poster-container img, img.img-responsive")?.attr("src")
        )

        val plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?: doc.selectFirst("p.text-slate-100, .movie-details p, .synopsis, .description")?.text()?.trim()

        // Extract release year accurately
        val releaseText = doc.select("p").firstOrNull { it.text().contains("Release:", ignoreCase = true) }?.text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(releaseText ?: "")?.value?.toIntOrNull()
            ?: doc.selectFirst(".video_year_movie, .label-year, .badge")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(doc.title())?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()

        // Real genre extraction (exclude navbar header!)
        val detailsSection = doc.selectFirst(".movie-details, #main-content")
        val tags = if (detailsSection != null) {
            detailsSection.select("p").firstOrNull { it.text().contains("Genre:", ignoreCase = true) }
                ?.select("a[href*=\"/genre/\"]")?.map { it.text().trim() }
                ?: detailsSection.select("a[href*=\"/genre/\"]").map { it.text().trim() }
        } else {
            doc.select("p:contains(Genre) a[href*=\"/genre/\"]").map { it.text().trim() }
        }.filter { it.isNotBlank() && !it.equals("Home", true) }.distinct()

        val rating = doc.selectFirst(".imdb-rating .label-imdb, .label-imdb")?.text()?.replace(Regex("(?i)imdb"), "")?.trim()
            ?: doc.select("p").firstOrNull { it.text().contains("Rating:", ignoreCase = true) }?.text()?.replace(Regex("(?i)Rating:"), "")?.trim()

        // Extract actors
        val actors = doc.select("p:contains(Actor) a[href*=\"/star/\"]").map { it.text().trim() }
            .ifEmpty { doc.select("a[href*=\"/star/\"], .stars a").map { it.text().trim() } }
            .filter { it.isNotBlank() }
            .distinct()

        // Distinguish TV Series vs Movie
        val seasonButtons = doc.select("a[href*=\"?key=\"]")
        val pasteLinks = doc.select("a[href*=\"pasteurl.net/view/\"]")

        val hasSeriesGenre = tags.any { it.contains("series", ignoreCase = true) }
        val hasSeasonButton = seasonButtons.any { 
            val text = it.text().lowercase()
            text.contains("season") || text.contains("web series") || text.contains("series")
        }
        val isFullMovieButton = seasonButtons.any { it.text().contains("full movie", ignoreCase = true) }

        val isSeries = !isFullMovieButton && (
                hasSeriesGenre ||
                hasSeasonButton ||
                pasteLinks.any { it.text().contains("s0", ignoreCase = true) || it.text().contains("season", ignoreCase = true) } ||
                rawTitle.contains("s0", ignoreCase = true) ||
                rawTitle.contains("season", ignoreCase = true)
        )

        if (isSeries) {
            val episodes = mutableListOf<Episode>()

            if (seasonButtons.isNotEmpty()) {
                val parsedSeasons = seasonButtons.mapIndexed { index, link ->
                    val epUrl = fixUrl(link.attr("href"))
                    val linkText = link.text().trim()
                    val sNum = Regex("""(?i)s(?:eason)?\s*0?(\d+)""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)
                    val epNum = Regex("""(?i)ep(?:isode)?\s*0?(\d+)""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1

                    val epName = if (linkText.equals("web series", ignoreCase = true) || linkText.isBlank()) {
                        "Season $sNum Complete"
                    } else {
                        linkText
                    }

                    Triple(sNum, epNum, Pair(epName, epUrl))
                }.sortedWith(compareBy({ it.first }, { it.second }))

                parsedSeasons.forEach { (sNum, epNum, data) ->
                    val (epName, epUrl) = data
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            } else if (pasteLinks.isNotEmpty()) {
                pasteLinks.forEachIndexed { index, link ->
                    val linkText = link.text().trim()
                    val sNum = Regex("""(?i)s(?:eason)?\s*0?(\d+)""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)
                    val epName = linkText.replace(Regex("^[-\\s]+"), "").ifBlank { "Season $sNum Complete" }

                    episodes.add(
                        newEpisode(cleanUrl) {
                            this.name = epName
                            this.season = sNum
                            this.episode = 1
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                episodes.add(
                    newEpisode(cleanUrl) {
                        this.name = "Season 1 Complete"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }

            return newTvSeriesLoadResponse(cleanTitle, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
                if (!rating.isNullOrBlank()) this.score = Score.from10(rating)
            }
        }

        // It is a single Movie!
        return newMovieLoadResponse(cleanTitle, cleanUrl, TvType.Movie, cleanUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addActors(actors)
            if (!rating.isNullOrBlank()) this.score = Score.from10(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = fixUrl(data)

        if (cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".m3u8") || cleanUrl.endsWith(".mkv")) {
            val type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(
                    "Direct Stream",
                    "BanglaPlex Direct",
                    cleanUrl,
                    type
                )
            )
            return true
        }

        val doc = try {
            app.get(cleanUrl, referer = mainUrl).document
        } catch (e: Exception) {
            Log.e("BanglaPlex", "Failed to fetch watch page: ${e.message}")
            return false
        }

        // 1. Process Embedded Players (e.g. plextream.work)
        doc.select("iframe[src]").forEach { iframe ->
            val iframeSrc = fixUrl(iframe.attr("src"))
            if (iframeSrc.contains("plextream.work") || iframeSrc.contains("embed.php")) {
                try {
                    val plextreamDoc = app.get(iframeSrc, referer = cleanUrl).document
                    val plextreamHtml = plextreamDoc.html()

                    // Extract server buttons
                    Regex("""changeServer\(['"]([^'"]+)['"]""").findAll(plextreamHtml).forEach { m ->
                        val serverUrl = m.groupValues[1]
                        if (serverUrl.isNotBlank() && serverUrl.startsWith("http")) {
                            loadExtractor(serverUrl, iframeSrc, subtitleCallback, callback)
                        }
                    }

                    // Extract inner iframe if present
                    val innerSrc = plextreamDoc.selectFirst("#videoFrame")?.attr("src")
                    if (!innerSrc.isNullOrBlank() && innerSrc.startsWith("http")) {
                        loadExtractor(innerSrc, iframeSrc, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e("BanglaPlex", "Plextream error: ${e.message}")
                }
            } else if (iframeSrc.startsWith("http")) {
                loadExtractor(iframeSrc, cleanUrl, subtitleCallback, callback)
            }
        }

        // 2. Process PasteURL Links
        doc.select("a[href*=\"pasteurl.net/view/\"]").forEach { pasteA ->
            val pasteUrl = fixUrl(pasteA.attr("href"))
            unlockAndExtractPasteUrl(pasteUrl, cleanUrl, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun unlockAndExtractPasteUrl(
        pasteUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val getRes = app.get(pasteUrl, referer = refererUrl)
            val cookies = getRes.cookies
            val getDoc = getRes.document

            val csrfInput = getDoc.selectFirst("input[name^=\"_csrf_token_\"]") ?: return
            val csrfName = csrfInput.attr("name")
            val csrfVal = csrfInput.attr("value")

            val postRes = app.post(
                pasteUrl,
                data = mapOf(csrfName to csrfVal),
                referer = pasteUrl,
                cookies = cookies,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
            )

            val postDoc = postRes.document

            // Extract all unlocked URLs from <a> tags and raw text
            val unlockedUrls = mutableSetOf<String>()

            postDoc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (isValidHostLink(href)) {
                    unlockedUrls.add(href)
                }
            }

            Regex("""https?://[^\s<>"']+""").findAll(postDoc.body().text()).forEach { match ->
                val link = match.value.trim()
                if (isValidHostLink(link)) {
                    unlockedUrls.add(link)
                }
            }

            unlockedUrls.forEach { targetUrl ->
                when {
                    targetUrl.contains("hubcloud") || targetUrl.contains("hgcloud") || targetUrl.contains("hglink") || targetUrl.contains("sportverse") -> {
                        HubCloud().getUrl(targetUrl, pasteUrl, subtitleCallback, callback)
                    }
                    targetUrl.contains("gdflix") || targetUrl.contains("gdlink") -> {
                        GDFlix().getUrl(targetUrl, pasteUrl, subtitleCallback, callback)
                    }
                    targetUrl.contains("streamtape") -> {
                        StreamTapeCustom().getUrl(targetUrl, pasteUrl, subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(targetUrl, pasteUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BanglaPlex", "Error unlocking pasteurl $pasteUrl: ${e.message}")
        }
    }

    private fun isValidHostLink(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val lower = url.lowercase()
        return !lower.contains("pasteurl.net") &&
                !lower.contains("cloudflare.com") &&
                !lower.contains("fontawesome") &&
                !lower.contains("bootstrap") &&
                !lower.endsWith(".css") &&
                !lower.endsWith(".js") &&
                !lower.endsWith(".png") &&
                !lower.endsWith(".jpg")
    }
}
