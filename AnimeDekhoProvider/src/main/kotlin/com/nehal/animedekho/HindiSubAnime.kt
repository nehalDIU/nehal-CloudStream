package com.nehal.animedekho

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class HindiSubAnime : AnimeDekhoProvider() {
    override var mainUrl = "https://hindisubanime.co"
    override var name = "HindiSubAnime"
    override val hasMainPage = true
    override var lang = "hi"

    override val mainPage = mainPageOf(
        "/category/shounen/" to "Shounen",
        "/category/action/" to "Action",
        "/category/fantasy/" to "Fantasy",
        "/series/" to "Series",
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = try {
            parseJson<Media>(data)
        } catch (e: Throwable) {
            Log.e("HindiSubAnime", "Failed to parse media JSON: $e")
            return false
        }
        val body = app.get(media.url).document.selectFirst("body")?.attr("class") ?: return false
        val term = Regex("""(?:term|postid)-(\d+)""").find(body)?.groupValues?.getOrNull(1)
            ?: return false

        (0..4).toList().amap { i ->
            try {
                val link = app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}")
                    .document.selectFirst("iframe")?.attr("src")
                if (!link.isNullOrBlank()) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            } catch (e: Throwable) {
                Log.e("HindiSubAnime", "Failed to load link $i: $e")
            }
        }
        return true
    }
}
