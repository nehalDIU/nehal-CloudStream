package com.animekai

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class AnimekaiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimekaiProvider())
    }
}
