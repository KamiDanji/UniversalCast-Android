package com.universalcast.receiver.policy

import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus

/** Broadcast actions after which the receiver may start itself. */
object BootActions {
    const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    const val MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    val ALL = setOf(BOOT_COMPLETED, QUICKBOOT_POWERON, MY_PACKAGE_REPLACED)
}

/** Whether the boot receiver should start the receiver service for broadcast [action]. */
fun shouldAutostart(action: String?, settingEnabled: Boolean): Boolean =
    settingEnabled && action != null && action in BootActions.ALL

/** Decision of [AutoLaunchPolicy]. */
sealed interface AutoLaunchDecision {
    object Launch : AutoLaunchDecision { override fun toString() = "Launch" }
    data class Skip(val reason: Reason) : AutoLaunchDecision

    enum class Reason {
        /** "Open automatically when a phone casts" is switched off. */
        SETTING_OFF,
        /** The player is already on screen; nothing to do. */
        ALREADY_VISIBLE,
        /** A launch happened moments ago (e.g. a quick reload on the phone). */
        DEBOUNCED,
        /** Android 10+ blocks background activity starts without "Display over other apps". */
        NO_OVERLAY_PERMISSION,
    }
}

/**
 * Should the service bring the receiver to the front because a phone started casting?
 *
 * Background activity starts are restricted since Android 10 (API 29); the documented exemption that fits a TV
 * receiver is the user-granted SYSTEM_ALERT_WINDOW ("Display over other apps") permission. Below API 29 there is
 * no restriction.
 */
object AutoLaunchPolicy {
    const val DEBOUNCE_MS = 2_000L
    const val BACKGROUND_START_RESTRICTED_FROM_SDK = 29

    fun decide(
        activityResumed: Boolean,
        canDrawOverlays: Boolean,
        sdkInt: Int,
        settingEnabled: Boolean,
        lastLaunchAtMs: Long?,
        nowMs: Long,
    ): AutoLaunchDecision {
        if (!settingEnabled) return AutoLaunchDecision.Skip(AutoLaunchDecision.Reason.SETTING_OFF)
        if (activityResumed) return AutoLaunchDecision.Skip(AutoLaunchDecision.Reason.ALREADY_VISIBLE)
        if (lastLaunchAtMs != null && nowMs - lastLaunchAtMs in 0 until DEBOUNCE_MS) {
            return AutoLaunchDecision.Skip(AutoLaunchDecision.Reason.DEBOUNCED)
        }
        if (sdkInt >= BACKGROUND_START_RESTRICTED_FROM_SDK && !canDrawOverlays) {
            return AutoLaunchDecision.Skip(AutoLaunchDecision.Reason.NO_OVERLAY_PERMISSION)
        }
        return AutoLaunchDecision.Launch
    }
}

/**
 * Detects "a phone just started casting": the transition from IDLE (or nothing) to any non-IDLE state that has a
 * media URI. A different URI loaded over a running playback also counts (the TV may have been sent to the background
 * meanwhile). Further state changes of the same playback (buffering, pause, seek…) are not new casts. Thread-safe.
 */
class CastStartDetector {
    private var wasActive = false
    private var lastUri: String? = null

    @Synchronized
    fun onSnapshot(s: PlaybackSnapshot): Boolean {
        val active = s.status != PlaybackStatus.IDLE && s.mediaUri != null
        val started = active && (!wasActive || s.mediaUri != lastUri)
        wasActive = active
        lastUri = if (active) s.mediaUri else null
        return started
    }
}
