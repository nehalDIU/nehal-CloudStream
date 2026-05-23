package com.aniwatch

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniwatchPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Aniwatch())
        registerExtractorAPI(VidSrc())
    }
}
