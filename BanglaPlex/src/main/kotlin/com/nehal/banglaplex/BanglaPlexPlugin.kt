package com.nehal.banglaplex

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BanglaPlexPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BanglaPlexProvider())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
    }
}
