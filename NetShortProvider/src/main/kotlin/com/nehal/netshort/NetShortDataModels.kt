package com.nehal.netshort

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetShortApiResponse<T>(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("msg") val msg: String? = null,
    @JsonProperty("data") val data: T? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DramaRecommendData(
    @JsonProperty("dataList") val dataList: List<DramaItem>? = null,
    @JsonProperty("hasMore") val hasMore: Boolean? = null,
    @JsonProperty("total") val total: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DramaSearchData(
    @JsonProperty("records") val records: List<DramaItem>? = null,
    @JsonProperty("dataList") val dataList: List<DramaItem>? = null,
    @JsonProperty("total") val total: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DramaItem(
    @JsonProperty("shortPlayId") val shortPlayId: String? = null,
    @JsonProperty("shortPlayName") val shortPlayName: String? = null,
    @JsonProperty("shortPlayCover") val shortPlayCover: String? = null,
    @JsonProperty("shotIntroduce") val shotIntroduce: String? = null,
    @JsonProperty("totalEpisode") val totalEpisode: Int? = null,
    @JsonProperty("isFinish") val isFinish: Int? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("classificationList") val classificationList: List<Classification>? = null,
    @JsonProperty("actorList") val actorList: List<ActorInfo>? = null,
    @JsonProperty("shortPlayLabels") val shortPlayLabels: String? = null,
    @JsonProperty("sdkVid") val sdkVid: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Classification(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ActorInfo(
    @JsonProperty("actorId") val actorId: String? = null,
    @JsonProperty("actorName") val actorName: String? = null,
    @JsonProperty("actorAvatar") val actorAvatar: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DramaDetail(
    @JsonProperty("shortPlayId") val shortPlayId: String? = null,
    @JsonProperty("shortPlayName") val shortPlayName: String? = null,
    @JsonProperty("shortPlayCover") val shortPlayCover: String? = null,
    @JsonProperty("shotIntroduce") val shotIntroduce: String? = null,
    @JsonProperty("totalEpisode") val totalEpisode: Int? = null,
    @JsonProperty("isFinish") val isFinish: Int? = null,
    @JsonProperty("publishTime") val publishTime: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("payPoint") val payPoint: Int? = null,
    @JsonProperty("actorList") val actorList: List<ActorInfo>? = null,
    @JsonProperty("classificationList") val classificationList: List<Classification>? = null,
    @JsonProperty("shortPlayEpisodeInfos") val shortPlayEpisodeInfos: List<EpisodeItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeItem(
    @JsonProperty("episodeId") val episodeId: String? = null,
    @JsonProperty("episodeNo") val episodeNo: Int? = null,
    @JsonProperty("episodeCover") val episodeCover: String? = null,
    @JsonProperty("videoName") val videoName: String? = null,
    @JsonProperty("isLock") val isLock: Boolean? = null,
    @JsonProperty("isVip") val isVip: Boolean? = null,
    @JsonProperty("isAd") val isAd: Boolean? = null,
    @JsonProperty("episodeType") val episodeType: Int? = null,
    @JsonProperty("playVoucher") val playVoucher: String? = null,
    @JsonProperty("sdkVid") val sdkVid: String? = null,
    @JsonProperty("playToken") val playToken: String? = null,
    @JsonProperty("playClarity") val playClarity: String? = null,
    @JsonProperty("subtitleList") val subtitleList: List<SubtitleItem>? = null,
    @JsonProperty("episodeClarityList") val episodeClarityList: List<EpisodeClarityItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeClarityItem(
    @JsonProperty("playClarity") val playClarity: String? = null,
    @JsonProperty("playVoucher") val playVoucher: String? = null,
    @JsonProperty("playType") val playType: String? = null,
    @JsonProperty("expireTime") val expireTime: Long? = null,
    @JsonProperty("playUrl") val playUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleItem(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("subtitleUrl") val subtitleUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeDetailData(
    @JsonProperty("episodeId") val episodeId: String? = null,
    @JsonProperty("episodeNo") val episodeNo: Int? = null,
    @JsonProperty("sdkVid") val sdkVid: String? = null,
    @JsonProperty("playVoucher") val playVoucher: String? = null,
    @JsonProperty("playToken") val playToken: String? = null,
    @JsonProperty("playUrl") val playUrl: String? = null,
    @JsonProperty("episodeClarityList") val episodeClarityList: List<EpisodeClarityItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeStreamLinkData(
    @JsonProperty("shortPlayId") val shortPlayId: String,
    @JsonProperty("episodeId") val episodeId: String,
    @JsonProperty("episodeNo") val episodeNo: Int,
    @JsonProperty("isLock") val isLock: Boolean = false,
    @JsonProperty("sdkVid") val sdkVid: String? = null,
    @JsonProperty("playVoucher") val playVoucher: String? = null,
    @JsonProperty("episodeType") val episodeType: Int? = null
)
