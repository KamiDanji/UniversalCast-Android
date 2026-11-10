package com.universalcast.sender.app

import android.app.Application

class SenderApp : Application() {
    lateinit var runtime: SenderRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = SenderRuntime(this)
    }
}
