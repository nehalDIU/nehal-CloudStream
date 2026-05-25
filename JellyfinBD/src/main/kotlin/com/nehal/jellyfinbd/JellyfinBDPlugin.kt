package com.nehal.jellyfinbd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JellyfinBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JellyfinBDProvider())
    }
}
