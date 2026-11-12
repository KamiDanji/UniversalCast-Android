package com.universalcast.receiver

import android.view.KeyEvent
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.receiver.policy.AutoLaunchDecision
import com.universalcast.receiver.policy.AutoLaunchDecision.Reason
import com.universalcast.receiver.policy.AutoLaunchPolicy
import com.universalcast.receiver.policy.CastStartDetector
import com.universalcast.receiver.policy.shouldAutostart
import com.universalcast.receiver.ui.OverlayPermission
import com.universalcast.receiver.ui.PlayerKeyPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoliciesTest {
    // ---- shouldAutostart ------------------------------------------------------------------------------

    @Test fun autostartOnBootActionsWhenEnabled() {
        for (a in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON", "android.intent.action.MY_PACKAGE_REPLACED")) {
            assertTrue(shouldAutostart(a, settingEnabled = true), a)
            assertFalse(shouldAutostart(a, settingEnabled = false), "setting off: $a")
        }
    }

    @Test fun noAutostartForOtherOrMissingActions() {
        assertFalse(shouldAutostart(null, true))
        assertFalse(shouldAutostart("", true))
        assertFalse(shouldAutostart("android.intent.action.PACKAGE_REPLACED", true))
        assertFalse(shouldAutostart("android.intent.action.boot_completed", true), "actions are case-sensitive")
    }

    // ---- AutoLaunchPolicy ------------------------------------------------------------------------------

    private fun decide(
        resumed: Boolean = false, overlay: Boolean = true, sdk: Int = 34, enabled: Boolean = true, last: Long? = null, now: Long = 100_000,
    ) = AutoLaunchPolicy.decide(resumed, overlay, sdk, enabled, last, now)

    @Test fun launchesWhenBackgroundedWithPermission() {
        assertEquals(AutoLaunchDecision.Launch, decide())
        assertEquals(AutoLaunchDecision.Launch, decide(sdk = 35))
    }

    @Test fun skipReasonsInPriorityOrder() {
        assertEquals(AutoLaunchDecision.Skip(Reason.SETTING_OFF), decide(enabled = false, resumed = true, overlay = false))
        assertEquals(AutoLaunchDecision.Skip(Reason.ALREADY_VISIBLE), decide(resumed = true, overlay = false))
        assertEquals(AutoLaunchDecision.Skip(Reason.DEBOUNCED), decide(last = 99_000, overlay = false))
        assertEquals(AutoLaunchDecision.Skip(Reason.NO_OVERLAY_PERMISSION), decide(overlay = false))
    }

    @Test fun overlayPermissionOnlyMattersFromAndroid10() {
        assertEquals(AutoLaunchDecision.Launch, decide(overlay = false, sdk = 28))
        assertEquals(AutoLaunchDecision.Launch, decide(overlay = false, sdk = 23))
        assertEquals(AutoLaunchDecision.Skip(Reason.NO_OVERLAY_PERMISSION), decide(overlay = false, sdk = 29))
    }

    @Test fun debounceWindowIsTwoSeconds() {
        assertEquals(AutoLaunchDecision.Skip(Reason.DEBOUNCED), decide(last = 100_000, now = 100_000))
        assertEquals(AutoLaunchDecision.Skip(Reason.DEBOUNCED), decide(last = 98_001, now = 100_000))
        assertEquals(AutoLaunchDecision.Launch, decide(last = 98_000, now = 100_000))
        assertEquals(AutoLaunchDecision.Launch, decide(last = 200_000, now = 100_000), "clock went backwards (reboot): not debounced")
    }

    // ---- CastStartDetector -----------------------------------------------------------------------------

    private fun snap(status: PlaybackStatus, uri: String? = "https://h/a.mp4") =
        PlaybackSnapshot(status = status, mediaUri = if (status == PlaybackStatus.IDLE) null else uri)

    @Test fun detectsIdleToActiveOnceAndNewMediaOverRunningPlayback() {
        val d = CastStartDetector()
        assertFalse(d.onSnapshot(snap(PlaybackStatus.IDLE)))
        assertTrue(d.onSnapshot(snap(PlaybackStatus.LOADING)))
        assertFalse(d.onSnapshot(snap(PlaybackStatus.BUFFERING)))
        assertFalse(d.onSnapshot(snap(PlaybackStatus.PLAYING)))
        assertFalse(d.onSnapshot(snap(PlaybackStatus.PAUSED)))
        assertTrue(d.onSnapshot(snap(PlaybackStatus.LOADING, "https://h/b.m3u8")), "a new cast over a running one")
        assertFalse(d.onSnapshot(snap(PlaybackStatus.IDLE)))
        assertTrue(d.onSnapshot(snap(PlaybackStatus.PLAYING, "https://h/b.m3u8")), "same URI again after a stop is a new cast")
        assertFalse(d.onSnapshot(PlaybackSnapshot(status = PlaybackStatus.ERROR, mediaUri = null)), "no URI: nothing to show")
    }

    // ---- remote keys and misc --------------------------------------------------------------------------

    @Test fun mediaKeysToggleSilently() {
        assertEquals(PlayerKeyPolicy.Action.PAUSE, PlayerKeyPolicy.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, isPlaying = true))
        assertEquals(PlayerKeyPolicy.Action.PLAY, PlayerKeyPolicy.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, isPlaying = false))
        assertEquals(PlayerKeyPolicy.Action.NONE, PlayerKeyPolicy.action(KeyEvent.KEYCODE_MEDIA_PLAY, isPlaying = true))
        assertEquals(PlayerKeyPolicy.Action.PLAY, PlayerKeyPolicy.action(KeyEvent.KEYCODE_MEDIA_PLAY, isPlaying = false))
        assertEquals(PlayerKeyPolicy.Action.PAUSE, PlayerKeyPolicy.action(KeyEvent.KEYCODE_MEDIA_PAUSE, isPlaying = true))
        assertEquals(PlayerKeyPolicy.Action.NONE, PlayerKeyPolicy.action(KeyEvent.KEYCODE_DPAD_CENTER, isPlaying = true))
        assertEquals(PlayerKeyPolicy.Action.NONE, PlayerKeyPolicy.action(KeyEvent.KEYCODE_DPAD_LEFT, isPlaying = true))
    }

    @Test fun adbFallbackNamesThePackage() {
        assertEquals("adb shell appops set com.universalcast.receiver.debug SYSTEM_ALERT_WINDOW allow", OverlayPermission.adbCommand("com.universalcast.receiver.debug"))
    }
}
