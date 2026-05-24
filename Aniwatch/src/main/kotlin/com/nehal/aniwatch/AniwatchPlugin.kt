package com.nehal.aniwatch

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniwatchPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniwatchProvider())
    }
}
