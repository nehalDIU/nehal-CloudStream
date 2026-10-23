package com.MovieBox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieBoxProviderPlugin : Plugin() {
    override fun load(context: Context) {
        MovieBoxProvider.context = context
        registerMainAPI(MovieBoxProvider())
        this.openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            activity?.let {
                SettingsFragment(this).show(it.supportFragmentManager, "MovieBoxSettings")
            }
        }
    }
}
