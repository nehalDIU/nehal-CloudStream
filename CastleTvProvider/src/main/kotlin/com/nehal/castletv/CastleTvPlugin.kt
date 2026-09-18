package com.nehal.castletv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CastleTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CastleTvProvider())
    }
}
