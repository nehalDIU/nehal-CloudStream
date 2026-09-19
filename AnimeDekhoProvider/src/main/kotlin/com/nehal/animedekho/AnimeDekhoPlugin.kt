package com.nehal.animedekho

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeDekhoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeDekhoProvider())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Rubystm())
        registerExtractorAPI(Vidmolynet())
        registerExtractorAPI(VidmolyBiz())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(Techinmind())
        registerExtractorAPI(Cdnwish())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(FileMoonNL())
        registerExtractorAPI(Animezia())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(vidcloudupns())
        registerExtractorAPI(Animedekhoco())
        registerExtractorAPI(AnimedekhoPixel())
        registerExtractorAPI(Blakiteapi())
        registerExtractorAPI(ascdn21())
        registerExtractorAPI(Abyass())
        registerExtractorAPI(AWSStream())
    }
}

