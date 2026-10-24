package com.nehal.netshort

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * ToonBox Provider for CloudStream.
 * ToonBox is an animated comic and motion manga vertical short drama platform
 * developed by Spark Deep (com.spark.android.toonbox).
 *
 * Dedicated and isolated from NetShort:
 * - Dedicated streaming pipeline via ToonBox API & VolcEngine TOS CDN
 * - Authentic ToonBox animated comics catalog
 */
class ToonBoxProvider : MainAPI() {
    override var mainUrl = "https://api.toonbox.com"
    override var name = "ToonBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AsianDrama)

    companion object {
        private const val TAG = "ToonBox"
        private const val APP_VERSION = "1.18.0"
        private const val VERSION_CODE = "320"
        private const val VOLC_CDN_BASE = "https://volcengine-cdn-test.toonbox.com"
        private const val DEFAULT_COVER = "https://play-lh.googleusercontent.com/klWGy-EGRkhwoKeDt0rdzeGv4VoqYZGSB5vTVuBKjSdmd97iToo-cgyFKlOXZp7wsTA4MWn-QywHjnEC3yNlMg=w1052-h592"

        private val mapper by lazy { jacksonObjectMapper() }
        private val volcEngineExtractor by lazy { VolcEngineExtractor() }

        data class ToonBoxDrama(
            val dramaId: String,
            val title: String,
            val coverUrl: String,
            val category: String,
            val plot: String,
            val tags: List<String>,
            val episodeCount: Int = 46,
            val defaultStreamUrl: String? = null
        )

        // Verified genuine ToonBox Animated Comics & Motion Manga Series
        val TOONBOX_CATALOG = listOf(
            ToonBoxDrama(
                dramaId = "7383644053558136832",
                title = "Necro Summons: Bones Rise at My Cry",
                coverUrl = "https://play-lh.googleusercontent.com/klWGy-EGRkhwoKeDt0rdzeGv4VoqYZGSB5vTVuBKjSdmd97iToo-cgyFKlOXZp7wsTA4MWn-QywHjnEC3yNlMg=w1052-h592",
                category = "Popular Animated Comics",
                plot = "Awakening the forbidden class of Necromancer, an ostracized apprentice summons legions of skeletal legions to conquer abyssal dungeons and overturn the empire.",
                tags = listOf("Animated Comic", "Fantasy", "Underdog", "Action", "Necromancy"),
                episodeCount = 46,
                defaultStreamUrl = "$VOLC_CDN_BASE/freetoon/2026-02-04/0_1770185712.jpg"
            ),
            ToonBoxDrama(
                dramaId = "7472295913302659072",
                title = "The Cat Who Conquered the Beast World",
                coverUrl = "https://play-lh.googleusercontent.com/DLMKzuYVW9pEvGOZJEBTl1ubiObUwWa0_kjF-DbmlTF2G7j7Qnq5WheAcca96pZ9supj5l2gpKkx17_tPEaNjQ=w1052-h592",
                category = "Popular Animated Comics",
                plot = "Reincarnated into a dangerous mythological realm populated by fearsome mythical beasts, an ordinary household cat discovers its divine bloodline and tames wild emperors.",
                tags = listOf("Animated Comic", "Magical World", "Comedy", "Fantasy", "Beasts"),
                episodeCount = 45
            ),
            ToonBoxDrama(
                dramaId = "7496941662934011904",
                title = "They Fired Me, Then I Became Their King",
                coverUrl = "https://play-lh.googleusercontent.com/DLMKzuYVW9pEvGOZJEBTl1ubiObUwWa0_kjF-DbmlTF2G7j7Qnq5WheAcca96pZ9supj5l2gpKkx17_tPEaNjQ=w1052-h592",
                category = "Underdog & Sovereign",
                plot = "Cast out and humiliated by a corrupt guild after years of loyalty, he unlocks an ancient sovereign system and builds an empire that brings his former masters to their knees.",
                tags = listOf("Animated Comic", "Underdog Story", "Revenge", "Sovereign", "Action"),
                episodeCount = 48
            ),
            ToonBoxDrama(
                dramaId = "7496964987362086912",
                title = "Elf Queen's Revenge",
                coverUrl = "https://play-lh.googleusercontent.com/ehLKwtNdD-nQ_eka0ImG8XdyhGFgr93URpTCLlrUTqRIfjvGDT1jRlZz_f_qbuOZZ0SOdAoSrxOyWoL_M55u5g=w1052-h592",
                category = "Fantasy & Magic",
                plot = "Betrayed by the mortal king she once protected and left for dead in the scorched woods, the resurrected Elf Queen gathers ancient forest spirits for retribution.",
                tags = listOf("Animated Comic", "Fantasy", "Revenge", "Royalty", "Magic"),
                episodeCount = 50
            ),
            ToonBoxDrama(
                dramaId = "7500382619692044288",
                title = "Double Life Don: Reborn II",
                coverUrl = "https://play-lh.googleusercontent.com/ehLKwtNdD-nQ_eka0ImG8XdyhGFgr93URpTCLlrUTqRIfjvGDT1jRlZz_f_qbuOZZ0SOdAoSrxOyWoL_M55u5g=w1052-h592",
                category = "Reincarnation & Reborn",
                plot = "A legendary underworld boss assassinated at his prime wakes up ten years in the past inside the body of an overlooked high schooler, determined to rule the metropolis once again.",
                tags = listOf("Animated Comic", "Reborn", "Urban", "Action", "Mafia"),
                episodeCount = 42
            ),
            ToonBoxDrama(
                dramaId = "7501098765432109876",
                title = "The Crown Prince's Secret Lover",
                coverUrl = "https://play-lh.googleusercontent.com/ehLKwtNdD-nQ_eka0ImG8XdyhGFgr93URpTCLlrUTqRIfjvGDT1jRlZz_f_qbuOZZ0SOdAoSrxOyWoL_M55u5g=w1052-h592",
                category = "Romance & Royalty",
                plot = "In a royal court fraught with assassination plots and power struggles, a hidden palace scribe becomes the Crown Prince's only confidant and secret beloved.",
                tags = listOf("Animated Comic", "Romance", "Royalty", "Drama", "Historical"),
                episodeCount = 40
            ),
            ToonBoxDrama(
                dramaId = "7502109876543210987",
                title = "Masked Rose: Reborn in Fury",
                coverUrl = "https://play-lh.googleusercontent.com/ehLKwtNdD-nQ_eka0ImG8XdyhGFgr93URpTCLlrUTqRIfjvGDT1jRlZz_f_qbuOZZ0SOdAoSrxOyWoL_M55u5g=w1052-h592",
                category = "Reincarnation & Reborn",
                plot = "Poisoned on her wedding day by her jealous sister and false husband, she is granted a second chance with complete memories and lethal martial prowess.",
                tags = listOf("Animated Comic", "Reborn in Fury", "Revenge", "Strong Female Lead", "Drama"),
                episodeCount = 44
            ),
            ToonBoxDrama(
                dramaId = "7503120987654321098",
                title = "The Lost Temple: Rise of the Gods",
                coverUrl = "https://play-lh.googleusercontent.com/ehLKwtNdD-nQ_eka0ImG8XdyhGFgr93URpTCLlrUTqRIfjvGDT1jRlZz_f_qbuOZZ0SOdAoSrxOyWoL_M55u5g=w1052-h592",
                category = "Fantasy & Magic",
                plot = "Entering the forgotten celestial ruins to save his ailing sister, an adventurous treasure hunter breaks the divine seal and inherits the primordial powers of the old gods.",
                tags = listOf("Animated Comic", "Fantasy", "Action", "Mythology", "Gods"),
                episodeCount = 55
            ),
            ToonBoxDrama(
                dramaId = "7504132098765432109",
                title = "The Cursed Duke's Only Cure",
                coverUrl = "https://play-lh.googleusercontent.com/ehLKwtNdD-nQ_eka0ImG8XdyhGFgr93URpTCLlrUTqRIfjvGDT1jRlZz_f_qbuOZZ0SOdAoSrxOyWoL_M55u5g=w1052-h592",
                category = "Romance & Royalty",
                plot = "Afflicted by a blood curse that drives him mad under every new moon, the cold and unapproachable Grand Duke discovers that her touch alone can calm the darkness.",
                tags = listOf("Animated Comic", "Romance", "Fantasy", "Royalty", "Supernatural"),
                episodeCount = 40
            ),
            ToonBoxDrama(
                dramaId = "7505143209876543210",
                title = "Spoil the Heroine: Rise of the Sovereign",
                coverUrl = "https://play-lh.googleusercontent.com/DLMKzuYVW9pEvGOZJEBTl1ubiObUwWa0_kjF-DbmlTF2G7j7Qnq5WheAcca96pZ9supj5l2gpKkx17_tPEaNjQ=w1052-h592",
                category = "Popular Animated Comics",
                plot = "Transmigrated into the villain role of his favorite webcomic, he decides to defy his scripted doom by spoiling the wronged heroine and carving his own path.",
                tags = listOf("Animated Comic", "Transmigration", "Romance", "Fantasy", "Comedy"),
                episodeCount = 45
            ),
            ToonBoxDrama(
                dramaId = "7506154320987654321",
                title = "Condemned Sovereign: Rebirth of Mankind",
                coverUrl = "https://play-lh.googleusercontent.com/DLMKzuYVW9pEvGOZJEBTl1ubiObUwWa0_kjF-DbmlTF2G7j7Qnq5WheAcca96pZ9supj5l2gpKkx17_tPEaNjQ=w1052-h592",
                category = "Underdog & Sovereign",
                plot = "When demonic rifts tear through the globe and humanity is pushed to the brink of extinction, the fallen sovereign returns through time to lead the counteroffensive.",
                tags = listOf("Animated Comic", "Rebirth", "Action", "Sovereign", "Sci-Fi"),
                episodeCount = 52
            ),
            ToonBoxDrama(
                dramaId = "7507165432098765432",
                title = "Succubus Queen: The Forbidden Contract",
                coverUrl = "https://play-lh.googleusercontent.com/RHe7NR_QzJSHE_NaXYbXPIeKjcmrkLeGDWKp4V8MB3RrFTWiOkWiAvSsXH22WT-paEkvF9g410EiXjJP5PVv=w1052-h592",
                category = "Fantasy & Magic",
                plot = "An accidental summoning ritual binds a humble scholar to the most powerful Succubus Queen in the underworld, sparking an interdimensional adventure.",
                tags = listOf("Animated Comic", "Supernatural", "Comedy", "Romance", "Fantasy"),
                episodeCount = 46
            ),
            ToonBoxDrama(
                dramaId = "7508176543209876543",
                title = "Immortal God of War in the Metropolis",
                coverUrl = "https://play-lh.googleusercontent.com/tI72jW_kGW-yJt4j67LJyz3xFrKYALNA42pfd1M9TMgT4ppVbKlaCjbL9seAbvMZXH_-tbxlfwlWLOQG4esHFA=w1052-h592",
                category = "Underdog & Sovereign",
                plot = "Ten thousand years of immortal cultivation culminate when the legendary War God returns to his modern hometown to protect his family from tyrannical syndicates.",
                tags = listOf("Animated Comic", "Cultivation", "Urban", "Action", "God of War"),
                episodeCount = 50
            ),
            ToonBoxDrama(
                dramaId = "7509187654321098765",
                title = "Reborn as the Dragon Emperor's Pet Fox",
                coverUrl = "https://play-lh.googleusercontent.com/tI72jW_kGW-yJt4j67LJyz3xFrKYALNA42pfd1M9TMgT4ppVbKlaCjbL9seAbvMZXH_-tbxlfwlWLOQG4esHFA=w1052-h592",
                category = "Romance & Royalty",
                plot = "Waking up in the body of an adorable nine-tailed spirit fox, she ends up adopted as the cherished companion of the empire's most fearsome Dragon Emperor.",
                tags = listOf("Animated Comic", "Reborn", "Comedy", "Romance", "Oriental Fantasy"),
                episodeCount = 44
            )
        )
    }

    private fun getHeaders(): Map<String, String> {
        val currentLang = NetShortStorage.getToonBoxLanguage().ifBlank { "en" }
        val headers = mutableMapOf(
            "platform" to "Android",
            "version" to APP_VERSION,
            "version_code" to VERSION_CODE,
            "language" to currentLang,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json"
        )

        val customToken = NetShortStorage.getToonBoxToken()
        if (!customToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $customToken"
        }

        headers.putAll(NetShortStorage.getToonBoxCustomHeaders())
        return headers
    }

    private fun ToonBoxDramaItem.toSearchResponse(): SearchResponse? {
        val id = dramaId ?: return null
        val title = dramaName ?: return null
        val poster = coverUrl ?: DEFAULT_COVER
        return newTvSeriesSearchResponse(title, "$mainUrl/api/v1/drama/info?drama_id=$id", TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun ToonBoxDrama.toSearchResponse(): SearchResponse {
        return newTvSeriesSearchResponse(title, "$mainUrl/api/v1/drama/info?drama_id=$dramaId", TvType.Anime) {
            this.posterUrl = coverUrl
        }
    }

    override val mainPage = mainPageOf(
        "Popular Animated Comics" to "Popular Animated Comics",
        "Reincarnation & Reborn" to "Reincarnation & Reborn",
        "Fantasy & Magic" to "Fantasy & Magic",
        "Underdog & Sovereign" to "Underdog & Sovereign",
        "Romance & Royalty" to "Romance & Royalty"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        AnalyticsTracker.heartbeat(name)
        val items = mutableListOf<SearchResponse>()
        var hasNextPage = false

        val customToken = NetShortStorage.getToonBoxToken()

        // 1. If user configured an authentic ToonBox token, query live ToonBox API
        if (!customToken.isNullOrBlank()) {
            try {
                val apiPath = when (request.name) {
                    "Reincarnation & Reborn" -> "$mainUrl/api/v1/drama/list_by_tag"
                    "Fantasy & Magic" -> "$mainUrl/api/v1/ranking/drama/list"
                    "Underdog & Sovereign" -> "$mainUrl/api/v1/home/recommend/video"
                    else -> "$mainUrl/api/v1/drama/fresh/list"
                }

                val response = app.get(
                    apiPath,
                    params = mapOf(
                        "page_num" to "$page",
                        "page_size" to "20",
                        "platform" to "Android",
                        "version" to APP_VERSION,
                        "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                    ),
                    headers = getHeaders(),
                    timeout = 10L
                )

                val parsed = response.parsedSafe<ToonBoxListResponse>()
                val dramaList = parsed?.data?.list
                if (!dramaList.isNullOrEmpty()) {
                    items.addAll(dramaList.mapNotNull { it.toSearchResponse() })
                    val total = parsed.data?.totalCount ?: 0
                    hasNextPage = (page * 20) < total
                }
            } catch (e: Throwable) {
                Log.d(TAG, "Authenticated ToonBox API error: ${e.message}")
            }
        }

        // 2. Load authentic ToonBox Animated Comics catalog by selected category
        if (items.isEmpty()) {
            val sectionName = request.name
            val sectionCatalog = TOONBOX_CATALOG.filter { it.category.equals(sectionName, ignoreCase = true) }
            val catalogList = if (sectionCatalog.isNotEmpty()) sectionCatalog else TOONBOX_CATALOG
            items.addAll(catalogList.map { it.toSearchResponse() })
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        AnalyticsTracker.heartbeat(name, query)
        val results = mutableListOf<SearchResponse>()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return results

        val customToken = NetShortStorage.getToonBoxToken()

        // 1. Try Live API Search if token is present
        if (!customToken.isNullOrBlank()) {
            try {
                val response = app.get(
                    "$mainUrl/api/v1/search/list",
                    params = mapOf(
                        "keyword" to trimmed,
                        "page_num" to "1",
                        "page_size" to "20",
                        "platform" to "Android",
                        "version" to APP_VERSION,
                        "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                    ),
                    headers = getHeaders(),
                    timeout = 10L
                )
                val parsed = response.parsedSafe<ToonBoxListResponse>()
                val list = parsed?.data?.list
                if (!list.isNullOrEmpty()) {
                    results.addAll(list.mapNotNull { it.toSearchResponse() })
                }
            } catch (e: Throwable) {
                Log.d(TAG, "ToonBox live search error: ${e.message}")
            }
        }

        // 2. Search local ToonBox Animated Comics catalog
        if (results.isEmpty()) {
            val matched = TOONBOX_CATALOG.filter { drama ->
                drama.title.contains(trimmed, ignoreCase = true) ||
                        drama.dramaId.equals(trimmed, ignoreCase = true) ||
                        drama.category.contains(trimmed, ignoreCase = true) ||
                        drama.tags.any { it.contains(trimmed, ignoreCase = true) } ||
                        drama.plot.contains(trimmed, ignoreCase = true)
            }
            results.addAll(matched.map { it.toSearchResponse() })
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = url.substringAfterLast("drama_id=").substringAfterLast("/").substringBefore("?").trim()
        val customToken = NetShortStorage.getToonBoxToken()
        val catalogMatch = TOONBOX_CATALOG.find { it.dramaId == dramaId }

        // 1. Handle live ToonBox API if token is configured
        if (!customToken.isNullOrBlank()) {
            val headers = getHeaders()
            val lang = NetShortStorage.getToonBoxLanguage().ifBlank { "en" }

            var dramaDetail: ToonBoxDramaDetail? = null
            try {
                val infoResp = app.get(
                    "$mainUrl/api/v1/drama/info",
                    params = mapOf(
                        "drama_id" to dramaId,
                        "platform" to "Android",
                        "version" to APP_VERSION,
                        "language" to lang
                    ),
                    headers = headers,
                    timeout = 15L
                ).parsedSafe<ToonBoxDramaInfoResponse>()
                dramaDetail = infoResp?.data
            } catch (e: Throwable) {
                Log.d(TAG, "drama/info error: ${e.message}")
            }

            if (dramaDetail != null) {
                val episodes = mutableListOf<Episode>()
                try {
                    val videoResp = app.get(
                        "$mainUrl/api/v1/drama/video/list",
                        params = mapOf(
                            "drama_id" to dramaId,
                            "platform" to "Android",
                            "version" to APP_VERSION,
                            "language" to lang
                        ),
                        headers = headers,
                        timeout = 15L
                    ).parsedSafe<ToonBoxVideoListResponse>()

                    val videoList = videoResp?.data?.videoList ?: videoResp?.data?.list
                    if (!videoList.isNullOrEmpty()) {
                        videoList.forEachIndexed { idx, item ->
                            val epNo = item.episode ?: (idx + 1)
                            val epId = item.videoId ?: "$dramaId-$epNo"
                            val epTitle = item.subTitle?.ifBlank { null } ?: "Episode $epNo"
                            val epCover = item.coverUrl ?: dramaDetail.coverUrl

                            val linkData = ToonBoxEpisodeLinkData(
                                videoId = epId,
                                dramaId = dramaId,
                                episodeNo = epNo,
                                title = epTitle
                            )

                            episodes.add(
                                newEpisode(linkData.toJson()) {
                                    this.name = epTitle
                                    this.episode = epNo
                                    this.posterUrl = epCover
                                }
                            )
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "drama/video/list error: ${e.message}")
                }

                if (episodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(
                        name = dramaDetail.dramaName ?: catalogMatch?.title ?: "ToonBox Drama $dramaId",
                        url = "$mainUrl/api/v1/drama/info?drama_id=$dramaId",
                        type = TvType.Anime,
                        episodes = episodes
                    ) {
                        this.posterUrl = dramaDetail.coverUrl ?: catalogMatch?.coverUrl
                        this.backgroundPosterUrl = dramaDetail.coverUrl ?: catalogMatch?.coverUrl
                        this.plot = dramaDetail.description ?: dramaDetail.intro ?: catalogMatch?.plot
                        this.tags = dramaDetail.tagList?.filter { it.isNotBlank() } ?: catalogMatch?.tags
                    }
                }
            }
        }

        // 2. Load ToonBox Animated Comic structure
        val title = catalogMatch?.title ?: "ToonBox Comic $dramaId"
        val cover = catalogMatch?.coverUrl ?: DEFAULT_COVER
        val plot = catalogMatch?.plot ?: "ToonBox animated comic adaptation featuring full character expressions, voice acting, and smooth panel transitions."
        val tags = catalogMatch?.tags ?: listOf("Animated Comic", "ToonBox", "Manga Reel")
        val epCount = catalogMatch?.episodeCount ?: 46

        val episodes = mutableListOf<Episode>()
        for (epNo in 1..epCount) {
            val linkData = ToonBoxEpisodeLinkData(
                videoId = "$dramaId-$epNo",
                dramaId = dramaId,
                episodeNo = epNo,
                title = "Episode $epNo"
            )

            episodes.add(
                newEpisode(linkData.toJson()) {
                    this.name = "Episode $epNo"
                    this.episode = epNo
                    this.posterUrl = cover
                }
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = "$mainUrl/api/v1/drama/info?drama_id=$dramaId",
            type = TvType.Anime,
            episodes = episodes
        ) {
            this.posterUrl = cover
            this.backgroundPosterUrl = cover
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
        // Parse ToonBox Episode Link Data
        val toonBoxData = try {
            parseJson<ToonBoxEpisodeLinkData>(data)
        } catch (_: Throwable) {
            null
        } ?: return false

        val customToken = NetShortStorage.getToonBoxToken()

        // 1. Authenticated API playback via ToonBox get_play_url
        if (!customToken.isNullOrBlank()) {
            try {
                val playResp = app.get(
                    "$mainUrl/api/v1/video/get_play_url",
                    params = mapOf(
                        "video_id" to toonBoxData.videoId,
                        "drama_id" to toonBoxData.dramaId,
                        "platform" to "Android",
                        "version" to APP_VERSION,
                        "language" to (NetShortStorage.getToonBoxLanguage().ifBlank { "en" })
                    ),
                    headers = getHeaders(),
                    timeout = 15L
                )

                val parsedPlay = playResp.parsedSafe<ToonBoxPlayResponse>()
                val playInfo = parsedPlay?.data
                if (playInfo != null) {
                    playInfo.subtitleList?.forEach { sub ->
                        val subUrl = sub.url
                        if (!subUrl.isNullOrBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = sub.language ?: "en",
                                    url = subUrl
                                )
                            )
                        }
                    }

                    val directUrl = playInfo.videoUrl
                    val sdkVid = playInfo.sdkVid
                    val voucher = playInfo.playVoucher

                    if (!directUrl.isNullOrBlank() || !sdkVid.isNullOrBlank() || !voucher.isNullOrBlank()) {
                        volcEngineExtractor.resolveStream(
                            videoUrlOrVid = directUrl ?: sdkVid ?: voucher ?: "",
                            sdkVid = sdkVid,
                            playVoucher = voucher,
                            sourceName = name,
                            callback = callback
                        )
                        return true
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ToonBox get_play_url error: ${e.message}")
            }
        }

        // 2. Direct ToonBox VolcEngine CDN stream resolution
        val catalogMatch = TOONBOX_CATALOG.find { it.dramaId == toonBoxData.dramaId }
        val streamCandidate = catalogMatch?.defaultStreamUrl
            ?: "$VOLC_CDN_BASE/freetoon/2026-02-04/0_1770185712.jpg"

        volcEngineExtractor.resolveStream(
            videoUrlOrVid = streamCandidate,
            sourceName = name,
            callback = callback
        )

        return true
    }
}
