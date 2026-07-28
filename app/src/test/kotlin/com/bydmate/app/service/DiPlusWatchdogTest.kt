package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiPlusWatchdogTest {
    @Test
    fun `relaunches at threshold and respects cooldown`() {
        assertFalse(DiPlusWatchdog.shouldRelaunch(2, 3, 10_000, 0, 300_000))
        assertTrue(DiPlusWatchdog.shouldRelaunch(3, 3, 10_000, 0, 300_000))
        assertFalse(DiPlusWatchdog.shouldRelaunch(4, 3, 20_000, 10_000, 300_000))
        assertTrue(DiPlusWatchdog.shouldRelaunch(4, 3, 310_000, 10_000, 300_000))
    }
}
