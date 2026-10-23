package com.MovieBox

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import org.json.JSONObject
import kotlin.math.max
import kotlin.random.Random

internal fun cleanTitle(s: String): String {
    return s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
}

internal fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

suspend fun identifyID(
    title: String,
    year: Int?,
    mediaType: String,
    imdbRatingValue: Double? = null
): Pair<Int?, String?> {
    val tmdbApiKey = "1865f43a0549ca50d341dd9ab8b29f49"
    val normTitle = cleanTitle(title)
    val searchUrl = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=${java.net.URLEncoder.encode(normTitle, "UTF-8")}&random=${Random.nextInt()}"
    
    val searchResp = runCatching { app.get(searchUrl).text }.getOrNull() ?: return Pair(null, null)
    val results = JSONObject(searchResp).optJSONArray("results") ?: return Pair(null, null)

    var bestId: Int? = null
    var bestScore = 0.0
    var bestIsTv = (mediaType == "tv")

    for (i in 0 until results.length()) {
        val o = results.optJSONObject(i) ?: continue
        val type = o.optString("media_type")
        if (type != "movie" && type != "tv") continue

        val candidateId = o.optInt("id", -1)
        if (candidateId == -1) continue

        val titles = listOf(
            o.optString("title"),
            o.optString("name"),
            o.optString("original_title"),
            o.optString("original_name")
        ).filter { it.isNotBlank() }

        val candDate = if (type == "tv") o.optString("first_air_date", "") else o.optString("release_date", "")
        val candYear = candDate.take(4).toIntOrNull()
        val candRating = o.optDouble("vote_average", Double.NaN)

        var score = 0.0
        var titleScore = 0.0
        for (t in titles) {
            val candClean = cleanTitle(t)
            if (tokenEquals(candClean, normTitle)) {
                titleScore = 50.0
                break
            }
            if (candClean.contains(normTitle) || normTitle.contains(candClean)) {
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
            bestIsTv = (type == "tv")
        }
    }

    if (bestId == null || bestScore < 40.0) return Pair(null, null)

    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=$tmdbApiKey&append_to_response=external_ids&random=${Random.nextInt()}"
    val detailText = runCatching { app.get(detailUrl).text }.getOrNull() ?: return Pair(bestId, null)
    val detailJson = JSONObject(detailText)
    val imdbId = detailJson.optJSONObject("external_ids")?.optString("imdb_id")

    return Pair(bestId, imdbId)
}

suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
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
    return null
}
