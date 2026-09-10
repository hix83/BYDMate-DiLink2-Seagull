package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRestoreLegacyTest {

    private class Prefs : AdbRestorePreferences {
        override fun isEnabled() = true
        override fun setEnabled(enabled: Boolean) = Unit
        override fun lastWriteNetwork(): String? = null
        override fun lastWriteAtMs() = 0L
        override fun recordWrite(network: String?, atMs: Long) = Unit
    }

    private class LegacySystem(
        var configured: Boolean = true,
        var writeAccepted: Boolean = true,
        var restoreOnConnectCall: Int = 2,
    ) : AdbRestoreSystem {
        var writes = 0
        var connectCalls = 0
        var uiPulses = 0
        override fun hasWriteSecureSettings() = true
        override fun usesLegacyAdbRestore() = true
        override fun isLegacyWirelessAdbConfigured() = configured
        override fun writeLegacyAdbEnabled(value: Int): Boolean {
            if (value == 1) writes++
            return writeAccepted
        }
        override fun requestLegacyWirelessAdbUiPulse(): Boolean {
            uiPulses++
            return true
        }
        override fun wifiNetwork(): String? = error("legacy path must not inspect Wi-Fi")
        override fun readAdbWifiEnabled() = 0
        override fun writeAdbWifiEnabled(value: Int) = false
        override fun tlsPortFromProperty(): Int? = error("legacy path must not inspect TLS")
        override suspend fun discoverTlsPort(timeoutMs: Long): Int? = error("legacy path must not use mDNS")
        override suspend fun restartTcpip(port: Int): String? = error("legacy path must not use STLS")
        override suspend fun classicConnect(): Boolean = ++connectCalls >= restoreOnConnectCall
        override suspend fun ensureHelperRunning() = true
        override suspend fun sleep(ms: Long) = Unit
        override fun nowMs() = 42L
    }

    private fun TestScope.manager(system: LegacySystem) =
        AdbRestoreManager(Prefs(), system, this)

    @Test
    fun `android 9 reasserts adb enabled and restores classic port`() = runTest {
        val system = LegacySystem()
        val manager = manager(system)

        manager.attemptIfNeeded("test")

        assertEquals(1, system.writes)
        assertEquals(0, system.uiPulses)
        assertTrue(manager.state.value is AdbRestoreState.Restored)
    }

    @Test
    fun `android 9 pulses stock BYD switch when adb enabled alone is ignored`() = runTest {
        val system = LegacySystem(restoreOnConnectCall = 6)
        val manager = manager(system)

        manager.attemptIfNeeded("test")

        assertEquals(1, system.writes)
        assertEquals(1, system.uiPulses)
        assertTrue(manager.state.value is AdbRestoreState.Restored)
    }

    @Test
    fun `android 9 explains when stock wireless adb was never enabled`() = runTest {
        val system = LegacySystem(configured = false)
        val manager = manager(system)

        manager.attemptIfNeeded("test")

        assertEquals(0, system.writes)
        assertEquals(
            AdbRestoreState.Failed("enable Wireless ADB once in BYD Development Tools"),
            manager.state.value,
        )
    }
}
