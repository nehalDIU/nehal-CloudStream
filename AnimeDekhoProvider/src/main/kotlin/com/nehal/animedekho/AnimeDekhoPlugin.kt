package com.nehal.animedekho

import android.content.Context
import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Krakenfiles
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeDekhoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeDekhoProvider())
        registerMainAPI(HindiSubAnime())
        registerMainAPI(OnepaceProvider())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Vidmolynet())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(Techinmind())
        registerExtractorAPI(Cdnwish())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FileMoonNL())
        registerExtractorAPI(Krakenfiles())
        registerExtractorAPI(Voe())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(Animezia())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(vidcloudupns())
        registerExtractorAPI(Animedekhoco())
        registerExtractorAPI(Blakiteapi())
        registerExtractorAPI(ascdn21())
        registerExtractorAPI(Abyass())
    }
}
