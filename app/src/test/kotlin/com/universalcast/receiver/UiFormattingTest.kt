package com.universalcast.receiver

import com.universalcast.receiver.ui.formatTime
import org.junit.Test
import kotlin.test.assertEquals

class UiFormattingTest {
    @Test fun formatsMinutesAndHours() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("1:01:01", formatTime(3_661_000))
        assertEquals("0:00", formatTime(-5))
    }
}
