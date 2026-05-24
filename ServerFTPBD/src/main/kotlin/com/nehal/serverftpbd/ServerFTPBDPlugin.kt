package com.nehal.serverftpbd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ServerFTPBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ServerFTPBDProvider())
    }
}
