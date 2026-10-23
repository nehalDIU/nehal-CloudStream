package com.MovieBox

import com.fasterxml.jackson.annotation.JsonProperty

data class SportClip(
    @JsonProperty("title") val title: String,
    @JsonProperty("path") val path: String,
    @JsonProperty("coverUrl") val coverUrl: String? = null,
    @JsonProperty("duration") val duration: Long? = null
)

data class SportPlaySource(
    @JsonProperty("title") val title: String,
    @JsonProperty("path") val path: String
)

data class SportVideoData(
    @JsonProperty("title") val title: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("poster") val poster: String? = null
)

data class SportMatchData(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("playPath") val playPath: String? = null,
    @JsonProperty("playSource") val playSource: List<SportPlaySource>? = null,
    @JsonProperty("highlights") val highlights: List<SportClip>? = null,
    @JsonProperty("replay") val replay: List<SportClip>? = null
)
