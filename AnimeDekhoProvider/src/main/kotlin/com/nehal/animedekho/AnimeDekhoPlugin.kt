package com.nehal.animedekho

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeDekhoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeDekhoProvider())

        registerExtractorAPI(Zephyrflick())
        registerExtractorAPI(Ravok())
        registerExtractorAPI(AWSStream())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(UpnsPlayer())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(GDMirrorbotFHD())
        registerExtractorAPI(FilesForever())
        registerExtractorAPI(EmTurboVid())
        registerExtractorAPI(VidMolyNet())
        registerExtractorAPI(Blakite())
        registerExtractorAPI(WorldMirror())
        registerExtractorAPI(Rpmshare())
        registerExtractorAPI(StreamPro())
        registerExtractorAPI(Streamhg())
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(Byse())
        registerExtractorAPI(XerverMirror())
        registerExtractorAPI(VidSrcGet())
        registerExtractorAPI(NeoCDN())
    }
}
