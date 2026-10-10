package com.cncverse

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import java.security.SecureRandom
import kotlin.random.Random

class MovieBoxProviderIN : MainAPI() {
    companion object {
        var context: Any? = null
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBoxIN"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    @Volatile private var cachedGuestToken: String? = null
    @Volatile private var tokenLastFetchMs: Long = 0L

    private var cachedToken: String?
        get() = cachedGuestToken
        set(value) {
            cachedGuestToken = value
            tokenLastFetchMs = if (value == null) 0L else System.currentTimeMillis()
        }

    private fun extractAndCacheToken(responseHeaders: okhttp3.Headers?) {
        val xUserHeader = responseHeaders?.get("x-user") ?: return
        if (xUserHeader.isNotBlank()) {
            try {
                val mapper = jacksonObjectMapper()
                val xUserJson = mapper.readTree(xUserHeader)
                val token = xUserJson["token"]?.asText()
                if (!token.isNullOrBlank()) {
                    cachedGuestToken = token
                    tokenLastFetchMs = System.currentTimeMillis()
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedGuestToken != null && (now - tokenLastFetchMs < 3600000L)) {
            return cachedGuestToken
        }
        return try {
            val tokenUrl = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
            val xClientToken = generateXClientToken(now)
            val xTrSignature = generateXTrSignature(
                method = "GET",
                accept = "application/json",
                contentType = "application/json",
                url = tokenUrl,
                hardcodedTimestamp = now
            )
            val headers = mapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"SM-S918B","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0"
            )
            val res = app.get(tokenUrl, headers = headers)
            val xUserHeader = res.headers["x-user"]
            if (!xUserHeader.isNullOrBlank()) {
                val mapper = jacksonObjectMapper()
                val token = mapper.readTree(xUserHeader).get("token")?.asText()
                if (!token.isNullOrBlank()) {
                    cachedGuestToken = token
                    tokenLastFetchMs = now
                    return token
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getOrFetchToken(): String? = fetchAnonymousToken()

    private suspend fun fetchDubDetailPaths(detailPath: String, mapper: com.fasterxml.jackson.databind.ObjectMapper): Map<String, String> {
        return try {
            val url = "https://movie-box.co/wefeed-h5api-bff/detail?detailPath=$detailPath"
            val headers = mapOf(
                "Accept" to "application/json",
                "Origin" to "https://movie-box.co",
                "Referer" to "https://mzfi.me/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
                "x-client-info" to """{"timezone":"Asia/Calcutta"}"""
            )
            val res = app.get(url, headers = headers)
            val root = mapper.readTree(res.body.string())
            val dubsNode = root.get("data")?.get("subject")?.get("dubs")
            val map = mutableMapOf<String, String>()
            if (dubsNode != null && dubsNode.isArray) {
                for (item in dubsNode) {
                    val sId = item.get("subjectId")?.asText() ?: continue
                    val dPath = item.get("detailPath")?.asText()
                    if (!dPath.isNullOrBlank()) {
                        map[sId] = dPath
                    }
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")

        private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    val deviceId = generateDeviceId()

    data class BrandModel(val brand: String, val model: String)

    private val brandModels = mapOf(
        "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
        "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
        "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
        "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
        "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
    )

    fun randomBrandModel(): BrandModel {
        val brand = brandModels.keys.random()
        val model = brandModels[brand]!!.random()
        return BrandModel(brand, model)
    }
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val uri = try { java.net.URI(url) } catch (_: Exception) { null }
        val path = uri?.path ?: ""
        val rawQuery = uri?.rawQuery ?: ""
        val query = if (rawQuery.isNotBlank()) {
            rawQuery.split("&").sorted().joinToString("&")
        } else ""

        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

     override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        AnalyticsTracker.heartbeat(name)
        showTelegramPopup()
        // Show star popup on first visit (shared across all CNCVerse plugins)
        val token = getOrFetchToken()
        val url = "$mainUrl/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version="

        // Generate required security headers.
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)

        val bm = randomBrandModel()
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"${bm.brand.lowercase()}","model":"${bm.model}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2" // Optional, if needed for specific API behavior
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }

        val response = app.get(url, headers = headers)
        extractAndCacheToken(response.headers)
        val responseBody = response.body?.string() ?: ""

        // Helper function to parse a 'subject' JSON object into your app's data model.
        fun parseSubject(subjectJson: JsonNode?): SearchResponse? {
            subjectJson ?: return null // Return null if the subject object is missing
            val subjectId = subjectJson["subjectId"]?.asText() ?: return null
            val title = subjectJson["title"]?.asText() ?: return null
            val coverUrl = subjectJson["cover"]?.get("url")?.asText()
            val subjectType = when (subjectJson["subjectType"]?.asInt()) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie // Default to Movie
            }
            return newMovieSearchResponse(title, subjectId, subjectType) {
                this.posterUrl = coverUrl
                this.score = Score.from10(subjectJson["imdbRatingValue"]?.asText())
            }
        }

        // Use Jackson to parse the new, multi-section API response structure.
        val homePageLists = try {
            val mapper = jacksonObjectMapper()
            val root = mapper.readTree(responseBody)
            val sections = root["data"]?.get("items") ?: return newHomePageResponse(emptyList())

            // Iterate through each section (e.g., Banners, Trending Now, etc.)
            sections.mapNotNull { section ->
                val title = section["title"]?.asText()?.let {
                    if (it.equals("banner", ignoreCase = true)) "🔥Top Picks" else it
                } ?: return@mapNotNull null
                val type = section["type"]?.asText()

                // Extract the list of media items based on the section type.
                val mediaList = when (type) {
                    "BANNER" -> section["banner"]?.get("banners")
                        ?.mapNotNull { bannerItem -> parseSubject(bannerItem["subject"]) }
                    "SUBJECTS_MOVIE" -> section["subjects"]
                        ?.mapNotNull { subjectItem -> parseSubject(subjectItem) }
                    "CUSTOM" -> section["customData"]?.get("items")
                        ?.mapNotNull { customItem -> parseSubject(customItem["subject"]) }
                    else -> null
                }

                // Only create a HomePageList if the section contains valid media items.
                if (mediaList.isNullOrEmpty()) {
                    null
                } else {
                    HomePageList(title, mediaList)
                }
            }
        } catch (e: Exception) {
            // In case of a parsing error, return an empty list.
            e.printStackTrace()
            emptyList()
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        AnalyticsTracker.track(name, "search", mapOf("query" to query))
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        AnalyticsTracker.track(name, "search", mapOf("query" to query, "page" to page))
        var token = getOrFetchToken()
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val mapper = jacksonObjectMapper()
        val payloadMap = mapOf(
            "page" to page,
            "perPage" to 20,
            "keyword" to query,
            "tabId" to "All"
        )
        val jsonBody = mapper.writeValueAsString(payloadMap)

        suspend fun doSearchRequest(authToken: String?): String? {
            val contentType = "application/json; charset=utf-8"
            val bm = randomBrandModel()
            val ts = System.currentTimeMillis()
            val xClientToken = generateXClientToken(ts)
            val xTrSignature = generateXTrSignature("POST", "application/json", contentType, url, jsonBody, hardcodedTimestamp = ts)
            val headers = mutableMapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to "application/json",
                "content-type" to contentType,
                "connection" to "keep-alive",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"${bm.brand.lowercase()}","model":"${bm.model}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0"
            )
            if (!authToken.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $authToken"
            }
            val requestBody = jsonBody.toRequestBody(contentType.toMediaType())
            return try {
                val response = app.post(
                    url,
                    headers = headers,
                    requestBody = requestBody
                )
                extractAndCacheToken(response.headers)
                response.text
            } catch (_: Exception) {
                null
            }
        }

        var responseBody = doSearchRequest(token)

        // If unauthorized or missing token, clear cache, fetch fresh token and retry once
        if (responseBody == null || responseBody.contains("UNAUTHORIZED") || responseBody.contains("miss token") || responseBody.contains("Signature invalid")) {
            cachedToken = null
            token = getOrFetchToken()
            responseBody = doSearchRequest(token)
        }

        if (responseBody.isNullOrBlank()) return newSearchResponseList(emptyList())

        val searchList = mutableListOf<SearchResponse>()
        try {
            val root = mapper.readTree(responseBody)
            val results = root.get("data")?.get("results") ?: return newSearchResponseList(emptyList())
            for (result in results) {
                val subjects = result["subjects"]
                if (subjects != null && subjects.isArray) {
                    for (subject in subjects) {
                        val item = parseSearchSubject(subject)
                        if (item != null) searchList.add(item)
                    }
                } else {
                    val subject = if (result.has("subject")) result["subject"] else result
                    val item = parseSearchSubject(subject)
                    if (item != null) searchList.add(item)
                }
            }
        } catch (_: Exception) {}

        return searchList.toNewSearchResponseList()
    }

    private fun parseSearchSubject(subject: JsonNode): SearchResponse? {
        val title = subject["title"]?.asText() ?: return null
        val id = subject["subjectId"]?.asText() ?: return null
        val coverImg = subject["cover"]?.get("url")?.asText() ?: subject["stills"]?.get("url")?.asText()
        val subjectType = subject["subjectType"]?.asInt() ?: 1
        val type = when (subjectType) {
            1 -> TvType.Movie
            2 -> TvType.TvSeries
            7 -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newMovieSearchResponse(
            name = title,
            url = id,
            type = type
        ) {
            this.posterUrl = coverImg
            this.score = Score.from10(subject["imdbRatingValue"]?.asText())
        }
    }

    override suspend fun load(url: String): LoadResponse {
        

        val id = Regex("""subjectId=([^&]+)""")
            .find(url)
            ?.groupValues?.get(1)
            ?: url.substringAfterLast('/')

        val token = getOrFetchToken()
        val finalUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", finalUrl)

        val bm = randomBrandModel()
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; ${bm.model}; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"${bm.brand.lowercase()}","model":"${bm.model}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }

        val response = app.get(finalUrl, headers = headers)
        extractAndCacheToken(response.headers)
        if (response.code != 200) {
            throw ErrorLoadingException("Failed to load data: ${response.body.string()}")
        }

        val body = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(body)
        val data = root["data"] ?: throw ErrorLoadingException("No data")

        val title = data["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title found")
        AnalyticsTracker.track(name, "view", mapOf("title" to title, "url" to url))
        val description = data["description"]?.asText()
        val releaseDate = data["releaseDate"]?.asText()
        val duration = data["duration"]?.asText()
        val genre = data["genre"]?.asText()
        val imdbRating = data["imdbRatingValue"]?.asText()?.toDoubleOrNull()?.times(10)?.toInt()
        val year = releaseDate?.substring(0, 4)?.toIntOrNull()

        val coverUrl = data["cover"]?.get("url")?.asText()
        val backgroundUrl = data["cover"]?.get("url")?.asText()

        val subjectType = data["subjectType"]?.asInt() ?: 1

        val actors = data["staffList"]
            ?.mapNotNull { staff ->
                val staffType = staff["staffType"]?.asInt()
                if (staffType == 1) {
                    val name = staff["name"]?.asText() ?: return@mapNotNull null
                    val character = staff["character"]?.asText()
                    val avatarUrl = staff["avatarUrl"]?.asText()
                    ActorData(
                        Actor(name, avatarUrl),
                        roleString = character
                    )
                } else null
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()


        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()

        val durationMinutes = duration?.let { dur ->
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val m = regex.find(dur)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toIntOrNull() ?: 0
                h * 60 + min
            } else dur.replace("m", "").toIntOrNull()
        }

        val type = when (subjectType) {
            1 -> TvType.Movie
            2 -> TvType.TvSeries
            7 -> TvType.TvSeries
            else -> TvType.Movie
        }

        val (tmdbId, imdbId) = identifyID(
            title = title.substringBefore("(").substringBefore("["),
            year = releaseDate?.take(4)?.toIntOrNull(),
            imdbRatingValue = imdbRating?.toDouble(),
        )

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbId,
            appLangCode = "en"
        )

        val meta = if (!imdbId.isNullOrBlank()) fetchMetaData(imdbId, type) else null
        val metaVideos = meta?.get("videos")?.toList() ?: emptyList()

        val Poster = meta?.get("poster")?.asText() ?: coverUrl
        val Background = meta?.get("background")?.asText() ?: backgroundUrl
        val Description = meta?.get("overview")?.asText() ?: description
        val IMDBRating = meta?.get("imdbRating")?.asText()

        if (type == TvType.TvSeries) {
            val allSubjectIds = mutableListOf<String>()
            allSubjectIds.add(id)
            data["dubs"]?.forEach {
                val sid = it["subjectId"]?.asText()
                if (!sid.isNullOrBlank() && sid !in allSubjectIds) {
                    allSubjectIds.add(sid)
                }
            }

            val episodeMap = mutableMapOf<Int, MutableSet<Int>>() // season -> episodes

            for (subjectId in allSubjectIds) {
                val seasonUrl = "$mainUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                val seasonSig = generateXTrSignature("GET", "application/json", "application/json", seasonUrl)

                val seasonHeaders = headers.toMutableMap().apply {
                    put("x-tr-signature", seasonSig)
                }

                val seasonResponse = app.get(seasonUrl, headers = seasonHeaders)
                if (seasonResponse.code != 200) continue

                val seasonRoot = mapper.readTree(seasonResponse.body.string())
                val seasons = seasonRoot["data"]?.get("seasons")

                if (seasons == null || !seasons.isArray || seasons.size() == 0) {
                    continue
                }

                seasons.forEach { season ->
                    val seasonNumber = season["se"]?.asInt() ?: 1
                    val maxEp = season["maxEp"]?.asInt() ?: 1

                    val epSet = episodeMap.getOrPut(seasonNumber) { mutableSetOf() }

                    for (ep in 1..maxEp) {
                        epSet.add(ep)
                    }
                }
            }

            val episodes = mutableListOf<Episode>()

            episodeMap.forEach { (seasonNumber, epSet) ->
                epSet.sorted().forEach { episodeNumber ->

                    val epMeta = metaVideos.firstOrNull {
                        it["season"]?.asInt() == seasonNumber &&
                                it["episode"]?.asInt() == episodeNumber
                    }

                    val epName = epMeta?.get("name")?.asText()
                        ?: epMeta?.get("title")?.asText()?.takeIf { it.isNotBlank() }
                        ?: "S${seasonNumber}E${episodeNumber}"

                    val epDesc = epMeta?.get("overview")?.asText()
                        ?: epMeta?.get("description")?.asText()
                        ?: "Season $seasonNumber Episode $episodeNumber"

                    val epThumb = epMeta?.get("thumbnail")?.asText()?.takeIf { it.isNotBlank() }
                        ?: coverUrl

                    val runtime = epMeta?.get("runtime")?.asText()
                        ?.filter { it.isDigit() }
                        ?.toIntOrNull()

                    val aired = epMeta?.get("released")?.asText()
                        ?.takeIf { it.isNotBlank() } ?: ""

                    episodes.add(
                        newEpisode("$id|$seasonNumber|$episodeNumber") {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = epThumb
                            this.description = epDesc
                            this.runTime = runtime
                            addDate(aired)
                        }
                    )
                }
            }

            // fallback
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$id|1|1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = coverUrl ?: Poster
                this.backgroundPosterUrl = Background ?: backgroundUrl ?: Poster
                try { this.logoUrl = logoUrl } catch(_: Throwable) {}
                this.plot = Description ?: description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = Score.from10(IMDBRating) ?: imdbRating?.let { Score.from10(it) }
                this.duration = durationMinutes
                addImdbId(imdbId)
                addTMDbId(tmdbId.toString())
            }
        }

        return newMovieLoadResponse(title, finalUrl, type, id) {
            this.posterUrl = coverUrl ?: Poster
            this.backgroundPosterUrl = Background ?: backgroundUrl
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.plot = Description ?: description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = Score.from10(IMDBRating) ?:imdbRating?.let { Score.from10(it) }
            this.duration = durationMinutes
            addImdbId(imdbId)
            addTMDbId(tmdbId.toString())
        }
    }


    private suspend fun emitStream(
        subjectId: String,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
        subDomain: String,
        subPath: String,
        langLabel: String,
        stream: JsonNode,
        streamType: String,
        tokenRef: kotlin.jvm.internal.Ref.ObjectRef<String>,
        mapper: com.fasterxml.jackson.databind.ObjectMapper,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val streamUrl = stream.get("url")?.asText()?.takeIf { it.isNotBlank() } ?: return
        val streamId = stream.get("id")?.asText() ?: "$subjectId|$season|$episode"
        val resolutions = stream.get("resolutions")?.asText() ?: ""
        val quality = getHighestQuality(resolutions)
        val signCookieRaw = stream.get("signCookie")?.asText()?.takeIf { it.isNotEmpty() }
        val signHeaderKey = stream.get("signHeaderKey")?.asText()?.takeIf { it.isNotBlank() } ?: "X-MB-Token"

        val linkType = when {
            streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            streamType.equals("HLS", ignoreCase = true) || streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            streamType.equals("MP4", ignoreCase = true) || streamUrl.contains(".mp4", ignoreCase = true) || streamUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }

        val linkName = when (linkType) {
            ExtractorLinkType.VIDEO -> "$name ($langLabel) MP4"
            ExtractorLinkType.DASH -> "$name ($langLabel) DASH"
            else -> "$name ($langLabel)"
        }

        val extLink = newExtractorLink(
            source = "$name $langLabel",
            name = linkName,
            url = streamUrl,
            type = linkType
        ) {
            if (linkType == ExtractorLinkType.VIDEO) {
                this.headers = mapOf(
                    "Referer" to "https://movie-box.co/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
                )
            } else {
                val h = mutableMapOf(
                    "Origin" to subDomain,
                    "Referer" to "$subDomain/movies/$subPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
                )
                if (signCookieRaw != null) {
                    h[signHeaderKey] = signCookieRaw
                }
                this.headers = h
            }
            if (quality != null) {
                this.quality = quality
            }
        }
        callback.invoke(extLink)

        // Captions extraction
        try {
            val captionUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
            val captionHeaders = mapOf(
                "Authorization" to "Bearer ${tokenRef.element}",
                "user-agent" to "com.community.mbox.in/50020126 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)",
                "Accept" to "application/json",
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"4.0.02.0831.03","version_code":50020126,"os":"android","os_version":"14","install_ch":"official","device_id":"$deviceId","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "X-Client-Status" to "0",
                "Content-Type" to "application/json",
                "X-Client-Token" to generateXClientToken(),
                "x-tr-signature" to generateXTrSignature("GET", "", "", captionUrl)
            )
            val capRes = app.get(captionUrl, headers = captionHeaders)
            val capRoot = mapper.readTree(capRes.body.string())
            val capData = capRoot.get("data")
            val captions = capData?.get("extCaptions") ?: capData?.get("captions")
            if (captions != null && captions.isArray) {
                for (cap in captions) {
                    val capUrl = cap.get("url")?.asText() ?: continue
                    val capLang = cap.get("language")?.asText()
                        ?: cap.get("lanName")?.asText()
                        ?: cap.get("lan")?.asText()
                        ?: "Unknown"
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = "$capLang ($langLabel)",
                            url = capUrl
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        AnalyticsTracker.track(name, "play", mapOf("data" to data))

        try {
            val parts = if (data.contains("|")) data.split("|") else data.split(",")
            val originalSubjectId = when {
                parts[0].contains("get?subjectId") -> {
                    Regex("""subjectId=([^&]+)""")
                        .find(parts[0])
                        ?.groupValues?.get(1)
                        ?: parts[0].substringAfterLast('/')
                }
                parts[0].contains("/") -> {
                    Regex("""(?:/|^)(\d+)(?:/|$)""").find(parts[0])?.groupValues?.get(1)
                        ?: parts[0].substringAfterLast('/')
                }
                else -> parts[0]
            }

            val season = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
            val episode = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0

            val tokenRef = kotlin.jvm.internal.Ref.ObjectRef<String>()
            tokenRef.element = fetchAnonymousToken() ?: ""

            val subjectUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
            val subjectHeaders = mutableMapOf(
                "user-agent" to "com.community.mbox.in/50020126 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to generateXClientToken(),
                "x-tr-signature" to generateXTrSignature("GET", "application/json", "application/json", subjectUrl),
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"4.0.02.0831.03","version_code":50020126,"os":"android","os_version":"14","install_ch":"official","device_id":"$deviceId","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                "x-client-status" to "0"
            )
            if (tokenRef.element.isNotBlank()) {
                subjectHeaders["Authorization"] = "Bearer ${tokenRef.element}"
            }

            var subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
            if (subjectResponse.code == 441 || subjectResponse.code == 401) {
                val freshToken = fetchAnonymousToken(forceRefresh = true)
                if (!freshToken.isNullOrBlank()) {
                    tokenRef.element = freshToken
                    subjectHeaders["Authorization"] = "Bearer $freshToken"
                    subjectHeaders["x-tr-signature"] = generateXTrSignature("GET", "application/json", "application/json", subjectUrl)
                    subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
                }
            }

            val mapper = jacksonObjectMapper()
            val languages = mutableListOf<Pair<String, String>>()
            var originalLangName = "Original"
            val subDomainRef = kotlin.jvm.internal.Ref.ObjectRef<String>()
            val subPathRef = kotlin.jvm.internal.Ref.ObjectRef<String>()

            if (subjectResponse.code == 200) {
                val subjectRoot = mapper.readTree(subjectResponse.body.string())
                val subjectData = subjectRoot.get("data")
                val detailUrl = subjectData?.get("detailUrl")?.asText()
                if (!detailUrl.isNullOrBlank()) {
                    runCatching {
                        val uri = java.net.URI(detailUrl)
                        subDomainRef.element = "${uri.scheme}://${uri.host}"
                        subPathRef.element = detailUrl.trimEnd('/').substringAfterLast('/')
                    }
                }
                val dubs = subjectData?.get("dubs")
                if (dubs != null && dubs.isArray) {
                    for (dub in dubs) {
                        val dubSubjectId = dub.get("subjectId")?.asText()
                        val lanName = dub.get("lanName")?.asText()
                        if (dubSubjectId != null && lanName != null) {
                            if (dubSubjectId == originalSubjectId) {
                                originalLangName = lanName
                            } else {
                                languages.add(Pair(dubSubjectId, lanName))
                            }
                        }
                    }
                }
            }

            val xUserHeader = subjectResponse.headers["x-user"]
            if (!xUserHeader.isNullOrBlank()) {
                runCatching {
                    val t = mapper.readTree(xUserHeader).get("token")?.asText()
                    if (!t.isNullOrBlank()) {
                        tokenRef.element = t
                    }
                }
            }

            languages.add(0, Pair(originalSubjectId, originalLangName))

            val detailSubPath = subPathRef.element
            val dubDetailMap = if (!detailSubPath.isNullOrBlank() && !subDomainRef.element.isNullOrBlank()) {
                fetchDubDetailPaths(detailSubPath, mapper)
            } else {
                emptyMap()
            }

            for ((subId, rawLan) in languages) {
                val langLabel = rawLan.replace("dub", "Audio")
                val subDomain = subDomainRef.element ?: continue
                val currentSubPath = if (subId == originalSubjectId) {
                    subPathRef.element ?: continue
                } else {
                    dubDetailMap[subId] ?: continue
                }

                val playUrl = "$subDomain/wefeed-h5api-bff/subject/play?subjectId=$subId&se=$season&ep=$episode&detailPath=$currentSubPath&streamSignType=1&supportCodecs[hevc]=1&supportCodecs[h264]=1"
                val playHeaders = mutableMapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36",
                    "Referer" to "$subDomain/movies/$currentSubPath?id=$subId&type=/movie/detail&detailSe=&detailEp=&lang=en",
                    "Accept" to "application/json",
                    "x-client-info" to """{"timezone":"Asia/Calcutta"}""",
                    "x-request-lang" to "en",
                    "x-vip-restrict" to "0",
                    "x-no-high-risk-restrict" to "0",
                    "x-source" to ""
                )
                if (tokenRef.element.isNotBlank()) {
                    playHeaders["Authorization"] = "Bearer ${tokenRef.element}"
                }

                val playResponse = app.get(playUrl, headers = playHeaders)
                if (playResponse.code == 200) {
                    val playRoot = mapper.readTree(playResponse.body.string())
                    val playData = playRoot.get("data") ?: continue

                    // 1. DASH streams
                    val dashList = playData.get("dash")
                    if (dashList != null && dashList.isArray) {
                        for (streamNode in dashList) {
                            emitStream(
                                subjectId = subId,
                                season = season,
                                episode = episode,
                                callback = callback,
                                subDomain = subDomain,
                                subPath = currentSubPath,
                                langLabel = langLabel,
                                stream = streamNode,
                                streamType = "DASH",
                                tokenRef = tokenRef,
                                mapper = mapper,
                                subtitleCallback = subtitleCallback
                            )
                        }
                    }

                    // 2. HLS streams
                    val hlsList = playData.get("hls")
                    if (hlsList != null && hlsList.isArray) {
                        for (streamNode in hlsList) {
                            emitStream(
                                subjectId = subId,
                                season = season,
                                episode = episode,
                                callback = callback,
                                subDomain = subDomain,
                                subPath = currentSubPath,
                                langLabel = langLabel,
                                stream = streamNode,
                                streamType = "HLS",
                                tokenRef = tokenRef,
                                mapper = mapper,
                                subtitleCallback = subtitleCallback
                            )
                        }
                    }

                    // 3. MP4 / regular streams
                    val streamsList = playData.get("streams")
                    if (streamsList != null && streamsList.isArray) {
                        for (streamNode in streamsList) {
                            val format = streamNode.get("format")?.asText() ?: "MP4"
                            emitStream(
                                subjectId = subId,
                                season = season,
                                episode = episode,
                                callback = callback,
                                subDomain = subDomain,
                                subPath = currentSubPath,
                                langLabel = langLabel,
                                stream = streamNode,
                                streamType = format,
                                tokenRef = tokenRef,
                                mapper = mapper,
                                subtitleCallback = subtitleCallback
                            )
                        }
                    }
                }
            }

            return true

        } catch (_: Exception) {
            return false
        }
    }


    private fun showTelegramPopup() {
        // Multiplatform / Desktop stub
    }

    private fun openInExternalBrowser(url: String) {
        val now = System.currentTimeMillis()
        if (now - lastBrowserOpenMs < BROWSER_DEBOUNCE_MS) return
        lastBrowserOpenMs = now
        try {
            val desktopClass = Class.forName("java.awt.Desktop")
            val isSupportedMethod = desktopClass.getMethod("isDesktopSupported")
            val isSupported = isSupportedMethod.invoke(null) as? Boolean ?: false
            if (isSupported) {
                val getDesktopMethod = desktopClass.getMethod("getDesktop")
                val desktop = getDesktopMethod.invoke(null)
                val browseMethod = desktopClass.getMethod("browse", java.net.URI::class.java)
                browseMethod.invoke(desktop, java.net.URI(url))
            }
        } catch (_: Throwable) {}
    }
}

fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )

    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) {
            return mappedValue
        }
    }
    return null
}


private fun cleanTitle(s: String): String {
    return s.lowercase()
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
}
private suspend fun identifyID(
    title: String,
    year: Int?,
    imdbRatingValue: Double?
): Pair<Int?, String?> {
    val normTitle = normalize(title)
    val res = searchAndPick(normTitle, year, imdbRatingValue)
    if (res.first != null) return res

    return Pair(null, null)
}

private suspend fun searchAndPick(
    normTitle: String,
    year: Int?,
    imdbRatingValue: Double?,
): Pair<Int?, String?> {

    suspend fun doSearch(endpoint: String, extraParams: String = ""): org.json.JSONArray? {
        val url = buildString {
            append("https://api.themoviedb.org/3/").append(endpoint)
            append("?api_key=").append("1865f43a0549ca50d341dd9ab8b29f49")
            append(extraParams)
            append("&include_adult=false&page=1")
            append("&random=").append(Random.nextInt())
        }
        val text = app.get(url).text
        return JSONObject(text).optJSONArray("results")
    }

    val multiResults = doSearch("search/multi", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    val searchQueues: List<Pair<String, org.json.JSONArray?>> = listOf(
        "multi" to multiResults,
        "tv" to doSearch("search/tv", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&first_air_date_year=$year" else "")),
        "movie" to doSearch("search/movie", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    )

    var bestId: Int? = null
    var bestScore = -1.0
    var bestIsTv = false

    for ((sourceType, results) in searchQueues) {
        if (results == null) continue
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)

            val mediaType = when (sourceType) {
                "multi" -> o.optString("media_type", "")
                "tv" -> "tv"
                else -> "movie"
            }

            val candidateId = o.optInt("id", -1)
            if (candidateId == -1) continue

            val titles = listOf(
                o.optString("title"),
                o.optString("name"),
                o.optString("original_title"),
                o.optString("original_name")
            ).filter { it.isNotBlank() }

            val candDate = when (mediaType) {
                "tv" -> o.optString("first_air_date", "")
                else -> o.optString("release_date", "")
            }
            val candYear = candDate.take(4).toIntOrNull()
            val candRating = o.optDouble("vote_average", Double.NaN)

            // scoring
            var score = 0.0
            val normClean = cleanTitle(normTitle)

            var titleScore = 0.0
            for (t in titles) {
                val candClean = cleanTitle(t)

                if (tokenEquals(candClean, normClean)) {
                    titleScore = 50.0
                    break
                }

                if (candClean.contains(normClean) || normClean.contains(candClean)) {
                    titleScore = maxOf(titleScore, 20.0)
                }
            }
            score += titleScore


            if (candYear != null && year != null && candYear == year) score += 35.0

            if (imdbRatingValue != null && !candRating.isNaN()) {
                val diff = kotlin.math.abs(candRating - imdbRatingValue)
                if (diff <= 0.5) score += 10.0 else if (diff <= 1.0) score += 5.0
            }

            if (o.has("popularity")) score += (o.optDouble("popularity", 0.0) / 100.0).coerceAtMost(5.0)

            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
                bestIsTv = (mediaType == "tv")
            }
        }
    }

    if (bestId == null || bestScore < 40.0) return Pair(null, null)

    // fetch details for external_ids
    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=external_ids&random=${Random.nextInt()}"
    val detailText = app.get(detailUrl).text
    val detailJson = JSONObject(detailText)
    val imdbId = detailJson.optJSONObject("external_ids")?.optString("imdb_id")

    return Pair(bestId, imdbId)
}

private fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

private fun normalize(s: String): String {
    val t = s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
    return t
}

private suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null

    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"

    return try {
        val resp = app.get(url).text
        mapper.readTree(resp)["meta"]
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey&random=${Random.nextInt()}"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey&random=${Random.nextInt()}"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    // Language match
    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    // Highest voted fallback
    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    // No language match & no voted logos
    return null


}
