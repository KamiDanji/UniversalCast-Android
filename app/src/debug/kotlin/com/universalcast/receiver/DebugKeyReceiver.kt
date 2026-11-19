package com.universalcast.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.universalcast.playback.RemoteKey

/** Debug builds only (src/debug): feeds a [RemoteKey] into the same dispatcher a paired phone uses. */
class DebugKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = runCatching { RemoteKey.valueOf(intent.getStringExtra("key").orEmpty()) }.getOrNull() ?: return
        (context.applicationContext as UniversalCastApp).runtime.remoteDispatcher.dispatch(key)
    }
}
