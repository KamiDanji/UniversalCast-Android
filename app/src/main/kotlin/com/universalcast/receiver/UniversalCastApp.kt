package com.universalcast.receiver

import android.app.Application
import com.universalcast.updater.UpdateConfig
import com.universalcast.updater.UpdateManager

class UniversalCastApp : Application() {
    lateinit var runtime: ReceiverRuntime
        private set
    lateinit var updates: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = ReceiverRuntime(this)
        updates = UpdateManager(
            this,
            UpdateConfig("KamiDanji/UniversalCast-Android", "universalcast-receiver", BuildConfig.VERSION_NAME, enabled = !BuildConfig.DEBUG),
        )
    }
}
