package com.nehal.netshort

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newTvSeriesSearchResponse

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxListResponse(
    val code: Int? = null,
    val message: String? = null,
    val msg: String? = null,
    @JsonProperty("is_encrypt") val isEncrypt: Boolean? = null,
    val data: ToonBoxPage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxPage(
    val list: List<ToonBoxDramaItem>? = null,
    @JsonProperty("total_count") val totalCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxDramaItem(
    @JsonProperty("drama_id") val dramaId: String? = null,
    @JsonProperty("drama_name") val dramaName: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
    @JsonProperty("video_count") val videoCount: Int? = null,
    @JsonProperty("tag_list") val tagList: List<String>? = null,
    @JsonProperty("is_free_video") val isFreeVideo: Boolean? = null,
    val description: String? = null,
    val intro: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxDramaInfoResponse(
    val code: Int? = null,
    val message: String? = null,
    val msg: String? = null,
    val data: ToonBoxDramaDetail? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxDramaDetail(
    @JsonProperty("drama_id") val dramaId: String? = null,
    @JsonProperty("drama_name") val dramaName: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    val intro: String? = null,
    @JsonProperty("tag_list") val tagList: List<String>? = null,
    @JsonProperty("video_count") val videoCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxVideoListResponse(
    val code: Int? = null,
    val message: String? = null,
    val msg: String? = null,
    val data: ToonBoxVideoPage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxVideoPage(
    @JsonProperty("video_list") val videoList: List<ToonBoxVideoItem>? = null,
    val list: List<ToonBoxVideoItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxVideoItem(
    @JsonProperty("video_id") val videoId: String? = null,
    val episode: Int? = null,
    @JsonProperty("sub_title") val subTitle: String? = null,
    @JsonProperty("is_free_video") val isFreeVideo: Boolean? = null,
    @JsonProperty("coin_cost") val coinCost: Int? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxPlayResponse(
    val code: Int? = null,
    val message: String? = null,
    val msg: String? = null,
    val data: ToonBoxPlayInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxPlayInfo(
    @JsonProperty("video_url") val videoUrl: String? = null,
    @JsonProperty("video_url_expire_time") val videoUrlExpireTime: Long? = null,
    @JsonProperty("subtitle_list") val subtitleList: List<ToonBoxSubtitleItem>? = null,
    @JsonProperty("sub_title") val subTitle: String? = null,
    @JsonProperty("sdk_vid") val sdkVid: String? = null,
    @JsonProperty("play_voucher") val playVoucher: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonBoxSubtitleItem(
    val language: String? = null,
    val url: String? = null,
    @JsonProperty("subtitle_id") val subtitleId: String? = null,
)

data class ToonBoxEpisodeLinkData(
    val videoId: String,
    val dramaId: String,
    val episodeNo: Int? = null,
    val title: String? = null
)
