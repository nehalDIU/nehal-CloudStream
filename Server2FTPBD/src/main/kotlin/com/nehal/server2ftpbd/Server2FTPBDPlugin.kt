package com.nehal.server2ftpbd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Server2FTPBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Server2FTPBDProvider())
    }
}
