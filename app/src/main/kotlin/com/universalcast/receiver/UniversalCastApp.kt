package com.universalcast.receiver

import android.app.Application

class UniversalCastApp : Application() {
    lateinit var runtime: ReceiverRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = ReceiverRuntime(this)
    }
}
