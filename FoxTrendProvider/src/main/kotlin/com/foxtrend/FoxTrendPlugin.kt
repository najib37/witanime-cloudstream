package com.foxtrend

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FoxTrendPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FoxTrendProvider())
    }
}
