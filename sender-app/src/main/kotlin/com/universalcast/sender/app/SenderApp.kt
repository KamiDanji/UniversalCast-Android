package com.universalcast.sender.app

import android.app.Application
import com.universalcast.updater.UpdateConfig
import com.universalcast.updater.UpdateManager

class SenderApp : Application() {
    lateinit var runtime: SenderRuntime
        private set
    lateinit var updates: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = SenderRuntime(this)
        updates = UpdateManager(
            this,
            UpdateConfig("KamiDanji/UniversalCast-Android", "universalcast-sender", BuildConfig.VERSION_NAME, enabled = !BuildConfig.DEBUG),
        )
    }
}
