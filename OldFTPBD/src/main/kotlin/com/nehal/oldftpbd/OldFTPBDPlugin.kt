package com.nehal.oldftpbd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OldFTPBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OldFTPBDProvider())
    }
}
