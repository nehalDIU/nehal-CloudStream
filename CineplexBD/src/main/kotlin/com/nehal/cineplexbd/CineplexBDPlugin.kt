package com.nehal.cineplexbd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineplexBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineplexBDProvider())
    }
}
