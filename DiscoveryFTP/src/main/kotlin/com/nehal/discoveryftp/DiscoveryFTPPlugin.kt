package com.nehal.discoveryftp

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiscoveryFTPPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiscoveryFTPProvider())
    }
}
