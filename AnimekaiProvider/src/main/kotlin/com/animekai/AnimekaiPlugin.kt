package com.animekai

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeKaiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeKaiProvider())
        registerExtractorAPI(MegaPlay())
    }
}
