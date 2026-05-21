package com.nehal.ftpbd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FTPBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FTPBDProvider())
    }
}
