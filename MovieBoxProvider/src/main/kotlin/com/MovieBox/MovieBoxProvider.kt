package com.MovieBox

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieBoxProvider : MainAPI() {

    companion object {
        var context: Context? = null

        val HOST_POOL = listOf(
            "https://api3.aoneroom.com",
            "https://apig.inmoviebox.com",
            "https://api7.aoneroom.com",
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com"
        )

        const val SPORT_API = "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff"
        const val SPORT_URL = "https://www.sportslive.wine/"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
        const val PREF_TOKEN_KEY = "moviebox_bearer_token_v3"

        @Volatile
        var bearerToken: String? = null
        @Volatile
        var tokenTimestamp: Long = 0L
    }

    override var name = "MovieBox"
    override var mainUrl = HOST_POOL.first()
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val mapper = jacksonObjectMapper()
    private val random = SecureRandom()

    val deviceId: String by lazy { generateDeviceId() }

    private val secretKeyDefaultBytes: ByteArray by lazy {
        base64DecodeArray(base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="))
    }
    private val secretKeyAltBytes: ByteArray by lazy {
        base64DecodeArray(base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="))
    }

    private val modernUserAgent = "com.community.oneroom/50020132 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
    private val modernClientInfo: String by lazy {
        """{"package_name":"com.community.oneroom","version_name":"4.0.03.0930.03","version_code":50020132,"os":"android","os_version":"14","device_id":"$deviceId","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}"""
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateXClientToken(timestamp: Long = System.currentTimeMillis()): String {
        val tsStr = timestamp.toString()
        val hash = md5(tsStr.reversed().toByteArray(Charsets.UTF_8))
        return "$tsStr,$hash"
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path ?: ""
        val rawQuery = uri?.rawQuery ?: ""
        val query = if (rawQuery.isNotBlank()) {
            rawQuery.split("&")
                .map {
                    val p = it.split("=")
                    p[0] to (p.getOrNull(1) ?: "")
                }
                .sortedBy { it.first }
                .joinToString("&") { "${it.first}=${it.second}" }
        } else ""

        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secretBytes = if (useAltKey) secretKeyAltBytes else secretKeyDefaultBytes
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signed = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$timestamp|2|${base64Encode(signed)}"
    }

    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && bearerToken != null && (now - tokenTimestamp < 3600000L)) {
            return bearerToken
        }

        return try {
            val tokenUrl = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1&restrictKid=0"
            val xClientToken = generateXClientToken(now)
            val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", tokenUrl, timestamp = now)
            val headers = mapOf(
                "user-agent" to modernUserAgent,
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to modernClientInfo,
                "x-client-status" to "0"
            )
            val res = app.get(tokenUrl, headers = headers)
            val xUserHeader = res.headers["x-user"]
            if (!xUserHeader.isNullOrBlank()) {
                val token = mapper.readTree(xUserHeader).get("token")?.asText()
                if (!token.isNullOrBlank()) {
                    bearerToken = token
                    tokenTimestamp = now
                    persistToken(token)
                    return token
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun persistToken(token: String) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_TOKEN_KEY, token).apply()
    }

    private fun loadPersistedToken() {
        if (bearerToken != null) return
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val token = prefs.getString(PREF_TOKEN_KEY, null)
        if (!token.isNullOrBlank()) {
            bearerToken = token
            tokenTimestamp = System.currentTimeMillis()
        }
    }

    private suspend fun buildAuthHeaders(
        method: String,
        accept: String = "application/json",
        contentType: String = "application/json",
        url: String,
        body: String? = null,
        useAltKey: Boolean = false
    ): Map<String, String> {
        loadPersistedToken()
        val token = fetchAnonymousToken()
        val now = System.currentTimeMillis()
        val xClientToken = generateXClientToken(now)
        val xTrSignature = generateXTrSignature(method, accept, contentType, url, body, useAltKey, timestamp = now)

        val headers = mutableMapOf(
            "user-agent" to modernUserAgent,
            "accept" to accept,
            "content-type" to contentType,
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to modernClientInfo,
            "x-client-status" to "0"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private fun getActiveHost(): String {
        val ctx = context ?: return mainUrl
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getString("moviebox_host", mainUrl) ?: mainUrl
    }

    private fun setActiveHost(host: String) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("moviebox_host", host).apply()
    }

    // ===========================
    // SEARCH
    // ===========================

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()

        // Check for Sports queries
        if (q.contains("football") || q.contains("soccer") || q.contains("live") || q.contains("match") || q.contains("sports")) {
            val sportResults = searchSports(query)
            if (sportResults.isNotEmpty()) return sportResults
        }

        val host = getActiveHost()
        val searchUrl = "$host/wefeed-mobile-bff/subject-api/search/v2"
        val bodyObj = mapOf(
            "page" to 1,
            "perPage" to 20,
            "keyword" to query,
            "restrictKid" to 0
        )
        val body = mapper.writeValueAsString(bodyObj)
        val ct = "application/json; charset=utf-8"
        val headers = buildAuthHeaders("POST", contentType = ct, url = searchUrl, body = body)

        return try {
            val mediaType = ct.toMediaType()
            val res = app.post(searchUrl, headers = headers, requestBody = body.toRequestBody(mediaType))
            val json = mapper.readTree(res.body.string())
            val results = json.get("data")?.get("results") ?: return emptyList()

            val list = mutableListOf<SearchResponse>()
            val seenUrls = mutableSetOf<String>()

            for (sub in results) {
                val subjects = sub.get("subjects") ?: continue
                if (!subjects.isArray) continue
                for (item in subjects) {
                    val id = item.get("subjectId")?.asText() ?: continue
                    val title = item.get("title")?.asText() ?: continue
                    val poster = item.get("cover")?.get("url")?.asText()
                    val type = item.get("subjectType")?.asInt(1) ?: 1
                    val rating = item.get("imdbRatingValue")?.asText() ?: item.get("score")?.asText()
                    val detailUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$id"

                    if (!seenUrls.add(detailUrl)) continue

                    if (type == 2) {
                        list.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.score = Score.from10(rating)
                        })
                    } else {
                        list.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                            this.posterUrl = poster
                            this.score = Score.from10(rating)
                        })
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun searchSports(query: String): List<SearchResponse> {
        val url = "$SPORT_API/live/match-list-v5"
        val headers = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "application/json",
            "Origin" to SPORT_URL,
            "Referer" to SPORT_URL,
            "X-Device-Info" to "{}"
        )
        return try {
            val res = app.get(url, headers = headers)
            val root = mapper.readTree(res.body.string())
            val list = root.get("data")?.get("list") ?: return emptyList()
            val results = mutableListOf<SearchResponse>()
            val q = query.lowercase()

            for (m in list) {
                val id = m.get("id")?.asText() ?: continue
                val team1 = m.get("team1")?.get("name")?.asText() ?: ""
                val team2 = m.get("team2")?.get("name")?.asText() ?: ""
                val league = m.get("league")?.get("name")?.asText() ?: ""
                val title = if (team1.isNotBlank() && team2.isNotBlank()) "$team1 vs $team2" else m.get("title")?.asText() ?: "Match"
                val poster = m.get("team1")?.get("avatar")?.asText() ?: m.get("cover")?.asText()

                if (title.lowercase().contains(q) || league.lowercase().contains(q)) {
                    results.add(newLiveSearchResponse("⚽ $title ($league)", "/sport_match:$id", TvType.Live) {
                        this.posterUrl = poster
                    })
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ===========================
    // MAIN PAGE
    // ===========================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val host = getActiveHost()
        val ctx = context
        val prefs = ctx?.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val savedOrderStr = prefs?.getString(SectionManagerFragment.PREF_ORDER_KEY, null)
        val savedHiddenStr = prefs?.getString(SectionManagerFragment.PREF_HIDDEN_KEY, null)

        val activeSections = if (!savedOrderStr.isNullOrBlank()) {
            val map = SectionManagerFragment.defaultSections.toMap()
            val list = savedOrderStr.split(",").filter { it.isNotBlank() }
            val loaded = list.mapNotNull { key -> map[key]?.let { key to it } }.toMutableList()
            for (def in SectionManagerFragment.defaultSections) {
                if (loaded.none { it.first == def.first }) loaded.add(def)
            }
            loaded
        } else {
            SectionManagerFragment.defaultSections.toMutableList()
        }

        val hidden = savedHiddenStr?.split(",")?.toSet() ?: emptySet()
        val visibleSections = activeSections.filter { !hidden.contains(it.first) }

        val rows = visibleSections.amap { (key, title) ->
            try {
                val items = loadSectionItems(host, key)
                HomePageList(title, items, isHorizontalImages = false)
            } catch (_: Exception) {
                HomePageList(title, emptyList(), isHorizontalImages = false)
            }
        }.filter { it.list.isNotEmpty() }

        return newHomePageResponse(rows)
    }

    private suspend fun loadSectionItems(host: String, key: String): List<SearchResponse> {
        if (key == "live|matches") {
            return loadLiveSportsMatches()
        }
        if (key == "live|highlights") {
            return loadLiveSportsHighlights()
        }

        if (key.startsWith("r|")) {
            val parts = key.split("|")
            val tabId = parts.getOrNull(1) ?: "0"
            val categoryType = parts.getOrNull(2) ?: ""
            val url = "$host/wefeed-mobile-bff/tab/ranking-list?tabId=$tabId&categoryType=$categoryType&page=1&perPage=20&restrictKid=0"
            val headers = buildAuthHeaders("GET", url = url)
            val res = app.get(url, headers = headers)
            return parseSubjectList(host, res.body.string())
        }

        // Filtered list format: "channelId|subjectType;k=v;k=v" or "channelId|classify"
        // Example: "1|1", "1|2", "2|2;country=Japan;genre=Animation"
        val parts = key.split(";")
        val headerParts = parts[0].split("|")
        val channelId = headerParts.getOrNull(0) ?: "1"

        val paramMap = mutableMapOf<String, String>()
        for (i in 1 until parts.size) {
            val pair = parts[i].split("=")
            if (pair.size == 2) {
                paramMap[pair[0].trim()] = pair[1].trim()
            }
        }

        val classify = paramMap["classify"] ?: if (headerParts.size > 1 && headerParts[1].toIntOrNull() == null) headerParts[1] else "All"
        val country = paramMap["country"] ?: "All"
        val year = paramMap["year"] ?: "All"
        val genre = paramMap["genre"] ?: "All"
        val sort = paramMap["sort"] ?: "ForYou"

        val url = "$host/wefeed-mobile-bff/subject-api/list"
        val bodyObj = mapOf(
            "page" to 1,
            "perPage" to 20,
            "channelId" to channelId,
            "classify" to classify,
            "country" to country,
            "year" to year,
            "genre" to genre,
            "sort" to sort,
            "restrictKid" to 0
        )
        val bodyStr = mapper.writeValueAsString(bodyObj)
        val ct = "application/json; charset=utf-8"
        val headers = buildAuthHeaders("POST", contentType = ct, url = url, body = bodyStr)
        val mediaType = ct.toMediaType()
        val res = app.post(url, headers = headers, requestBody = bodyStr.toRequestBody(mediaType))
        return parseSubjectList(host, res.body.string())
    }

    private fun parseSubjectList(host: String, jsonStr: String): List<SearchResponse> {
        return try {
            val root = mapper.readTree(jsonStr)
            val data = root.get("data") ?: return emptyList()
            val listNode = data.get("subjects") ?: data.get("items") ?: data.get("list") ?: return emptyList()
            val list = mutableListOf<SearchResponse>()
            val seenUrls = mutableSetOf<String>()

            for (item in listNode) {
                val id = item.get("subjectId")?.asText() ?: continue
                val title = item.get("title")?.asText() ?: continue
                val poster = item.get("cover")?.get("url")?.asText()
                val type = item.get("subjectType")?.asInt(1) ?: 1
                val rating = item.get("imdbRatingValue")?.asText() ?: item.get("score")?.asText()
                val detailUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$id"

                if (!seenUrls.add(detailUrl)) continue

                if (type == 2) {
                    list.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.score = Score.from10(rating)
                    })
                } else {
                    list.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.score = Score.from10(rating)
                    })
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun loadLiveSportsMatches(): List<SearchResponse> {
        val url = "$SPORT_API/live/match-list-v5"
        val headers = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "application/json",
            "Origin" to SPORT_URL,
            "Referer" to SPORT_URL,
            "X-Device-Info" to "{}"
        )
        return try {
            val res = app.get(url, headers = headers)
            val root = mapper.readTree(res.body.string())
            val list = root.get("data")?.get("list") ?: return emptyList()
            val results = mutableListOf<SearchResponse>()

            for (m in list) {
                val id = m.get("id")?.asText() ?: continue
                val team1 = m.get("team1")?.get("name")?.asText() ?: ""
                val team2 = m.get("team2")?.get("name")?.asText() ?: ""
                val league = m.get("league")?.get("name")?.asText() ?: ""
                val status = m.get("status")?.asText() ?: ""
                val title = if (team1.isNotBlank() && team2.isNotBlank()) "$team1 vs $team2" else m.get("title")?.asText() ?: "Match"
                val poster = m.get("team1")?.get("avatar")?.asText() ?: m.get("cover")?.asText()

                val displayTitle = if (status.isNotBlank()) "⚽ $title ($status)" else "⚽ $title"
                results.add(newLiveSearchResponse(displayTitle, "/sport_match:$id", TvType.Live) {
                    this.posterUrl = poster
                })
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun loadLiveSportsHighlights(): List<SearchResponse> {
        val url = "$SPORT_API/live/match-list-v5"
        val headers = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "application/json",
            "Origin" to SPORT_URL,
            "Referer" to SPORT_URL,
            "X-Device-Info" to "{}"
        )
        return try {
            val res = app.get(url, headers = headers)
            val root = mapper.readTree(res.body.string())
            val list = root.get("data")?.get("list") ?: return emptyList()
            val results = mutableListOf<SearchResponse>()

            for (m in list) {
                val id = m.get("id")?.asText() ?: continue
                val status = m.get("status")?.asText() ?: ""
                // Filter for matches that have ended or have replay
                if (!status.equals("FT", ignoreCase = true) && !status.equals("MatchEnded", ignoreCase = true)) continue

                val team1 = m.get("team1")?.get("name")?.asText() ?: ""
                val team2 = m.get("team2")?.get("name")?.asText() ?: ""
                val league = m.get("league")?.get("name")?.asText() ?: ""
                val title = if (team1.isNotBlank() && team2.isNotBlank()) "$team1 vs $team2" else m.get("title")?.asText() ?: "Highlights"
                val poster = m.get("team1")?.get("avatar")?.asText() ?: m.get("cover")?.asText()

                results.add(newLiveSearchResponse("🎬 $title (Highlights)", "/sport_match:$id", TvType.Live) {
                    this.posterUrl = poster
                })
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ===========================
    // LOAD
    // ===========================

    override suspend fun load(url: String): LoadResponse {
        val host = getActiveHost()

        if (url.startsWith("/sport_match:") || url.contains("sport_match:")) {
            val id = url.substringAfter("sport_match:")
            return loadSportMatch(id)
        }

        val subjectId = if (url.contains("subjectId=")) {
            url.substringAfter("subjectId=").substringBefore("&")
        } else {
            url.substringAfterLast("/")
        }

        val detailUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
        val headers = buildAuthHeaders("GET", url = detailUrl)
        val res = app.get(detailUrl, headers = headers)
        val root = mapper.readTree(res.body.string())
        val subject = root.get("data")?.get("subject") ?: root.get("data") ?: throw ErrorLoadingException("No data")

        val title = subject.get("title")?.asText() ?: "Untitled"
        val poster = subject.get("cover")?.get("url")?.asText()
        val plot = subject.get("description")?.asText()
        val year = subject.get("year")?.asInt() ?: subject.get("releaseDate")?.asText()?.take(4)?.toIntOrNull()
        val subjectType = subject.get("subjectType")?.asInt(1) ?: 1
        val rating = subject.get("score")?.asDouble() ?: subject.get("imdbRatingValue")?.asDouble()

        val isTv = subjectType == 2

        val (tmdbId, imdbId) = identifyID(title, year, if (isTv) "tv" else "movie")
        val metaData = fetchMetaData(imdbId, if (isTv) TvType.TvSeries else TvType.Movie)
        val backdrop = metaData?.get("background")?.asText()

        if (isTv) {
            val seasonUrl = "$host/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
            val seasonHeaders = buildAuthHeaders("GET", url = seasonUrl)
            val seasonRes = app.get(seasonUrl, headers = seasonHeaders)
            val seasonRoot = mapper.readTree(seasonRes.body.string())
            val seasons = seasonRoot.get("data")?.get("seasons")

            val episodes = mutableListOf<Episode>()
            if (seasons != null && seasons.isArray) {
                for (s in seasons) {
                    val sNum = s.get("seasonNumber")?.asInt(1) ?: 1
                    val eps = s.get("episodes") ?: continue
                    for (ep in eps) {
                        val epNum = ep.get("episodeNumber")?.asInt(1) ?: 1
                        val epTitle = ep.get("title")?.asText() ?: "Episode $epNum"
                        val epPoster = ep.get("cover")?.get("url")?.asText()
                        val epData = "$host/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&seasonNumber=$sNum&episodeNumber=$epNum"

                        episodes.add(newEpisode(epData) {
                            this.name = epTitle
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating?.toString())
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
            }
        } else {
            val playData = "$host/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId"
            return newMovieLoadResponse(title, url, TvType.Movie, playData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating?.toString())
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
            }
        }
    }

    private suspend fun loadSportMatch(id: String): LoadResponse {
        val url = "$SPORT_API/live/match-detail?id=$id"
        val headers = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "application/json",
            "Origin" to SPORT_URL,
            "Referer" to SPORT_URL,
            "X-Device-Info" to "{}"
        )
        val res = app.get(url, headers = headers)
        val root = mapper.readTree(res.body.string())
        val data = root.get("data") ?: throw ErrorLoadingException("Failed to load match")

        val match = mapper.readValue<SportMatchData>(data.toString())
        val episodes = mutableListOf<Episode>()

        match.playPath?.let { path ->
            episodes.add(newEpisode("/sport_video:$path") {
                this.name = "Live Stream (Main)"
                this.season = 1
                this.episode = 1
            })
        }

        match.playSource?.forEachIndexed { idx, src ->
            episodes.add(newEpisode("/sport_video:${src.path}") {
                this.name = src.title.ifBlank { "Channel ${idx + 1}" }
                this.season = 1
                this.episode = idx + 2
            })
        }

        match.highlights?.forEachIndexed { idx, clip ->
            episodes.add(newEpisode("/sport_video:${clip.path}") {
                this.name = clip.title.ifBlank { "Highlight ${idx + 1}" }
                this.season = 2
                this.episode = idx + 1
                this.posterUrl = clip.coverUrl
            })
        }

        return newTvSeriesLoadResponse(match.title, "/sport_match:$id", TvType.Live, episodes) {
            this.posterUrl = match.poster
            this.plot = "${match.league ?: "Sports"} - ${match.status ?: "Live"}"
        }
    }

    // ===========================
    // LOAD LINKS
    // ===========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("/sport_video:") || data.contains("sport_video:")) {
            val playPath = data.substringAfter("sport_video:")
            if (playPath.contains("88player.top/m3u8.html")) {
                val targetUrl = playPath.substringAfter("url=").substringBefore("&")
                val decoded = runCatching { java.net.URLDecoder.decode(targetUrl, "UTF-8") }.getOrNull() ?: targetUrl
                callback(newExtractorLink(name, "MovieBox Live", decoded, ExtractorLinkType.M3U8) {
                    this.referer = SPORT_URL
                    this.quality = Qualities.Unknown.value
                })
                return true
            }
            callback(newExtractorLink(name, "Live Channel", playPath, if (playPath.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                this.referer = SPORT_URL
                this.quality = Qualities.Unknown.value
            })
            return true
        }

        val playInfoUrl = data
        val headers = buildAuthHeaders("GET", url = playInfoUrl)
        val res = app.get(playInfoUrl, headers = headers)
        val root = mapper.readTree(res.body.string())
        val dataNode = root.get("data") ?: return false

        // Load subtitles
        val subjectId = data.substringAfter("subjectId=").substringBefore("&")
        loadCaptions(subjectId, subtitleCallback)

        // Extract Streams
        val streams = dataNode.get("streams") ?: dataNode.get("list")
        if (streams != null && streams.isArray) {
            for (st in streams) {
                val url = st.get("url")?.asText() ?: continue
                if (isDecoyStream(url)) continue

                val resolution = st.get("resolution")?.asInt() ?: 0
                val quality = when {
                    resolution >= 1080 -> Qualities.P1080.value
                    resolution >= 720 -> Qualities.P720.value
                    resolution >= 480 -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                val type = if (url.contains(".mpd") || url.contains("/index.mpd")) {
                    ExtractorLinkType.DASH
                } else if (url.contains(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    INFER_TYPE
                }

                callback(newExtractorLink(name, "$name $resolution p", url, type) {
                    this.quality = quality
                    this.referer = getActiveHost()
                })
            }
        }

        return true
    }

    private suspend fun loadCaptions(subjectId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val host = getActiveHost()
        val capUrl = "$host/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId"
        val headers = buildAuthHeaders("GET", url = capUrl)
        try {
            val res = app.get(capUrl, headers = headers)
            val root = mapper.readTree(res.body.string())
            val captions = root.get("data")?.get("captions") ?: return
            if (!captions.isArray) return

            for (c in captions) {
                val subUrl = c.get("url")?.asText() ?: continue
                val lang = c.get("lanName")?.asText() ?: c.get("language")?.asText() ?: "Unknown"
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
        } catch (_: Exception) {}
    }

    private fun isDecoyStream(url: String): Boolean {
        return url.contains("decoy") || url.contains("sample") || url.contains("blank.mp4")
    }
}
