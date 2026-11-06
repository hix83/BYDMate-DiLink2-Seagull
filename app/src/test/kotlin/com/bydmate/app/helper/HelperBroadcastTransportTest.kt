package com.bydmate.app.helper

import android.content.Intent
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class HelperBroadcastTransportTest {
    @Test fun `descriptor query bypasses token parcel parsing but ordinary commands do not`() {
        assertTrue(isHelperDescriptorRequest(android.os.IBinder.INTERFACE_TRANSACTION))
        assertFalse(isHelperDescriptorRequest(HelperBinderProtocol.TX_PING))
        assertFalse(isHelperDescriptorRequest(HelperBinderProtocol.TX_DTC_ERASE_START))
        assertFalse(isHelperDescriptorRequest(HelperBinderProtocol.TX_REGISTER_CLIENT))
    }
    @Test fun `fallback requires both a system context and an authentication token`() {
        assertEquals(RegistrationFallback.EXIT_NO_CONTEXT, decideRegistrationFallback(false, true))
        assertEquals(RegistrationFallback.EXIT_NO_CONTEXT, decideRegistrationFallback(false, false))
        assertEquals(RegistrationFallback.EXIT_NO_TOKEN, decideRegistrationFallback(true, false))
        assertEquals(RegistrationFallback.BROADCAST, decideRegistrationFallback(true, true))
    }
    @Test fun `only initial delivery can wake a stopped app`() {
        assertEquals(Intent.FLAG_INCLUDE_STOPPED_PACKAGES, binderIntentFlags(true))
        assertEquals(0, binderIntentFlags(false))
    }
    @Test fun `reannouncement stops once the app registers`() {
        assertTrue(shouldReannounce(HelperBinderHolder.TRANSPORT_BROADCAST, false))
        assertFalse(shouldReannounce(HelperBinderHolder.TRANSPORT_BROADCAST, true))
        assertFalse(shouldReannounce("servicemanager", false))
    }
    @Test fun `reflection error names the real registration failure`() {
        val text = describeBootstrapFailure(InvocationTargetException(SecurityException("addService denied")))
        assertTrue(text.contains("SecurityException"))
        assertTrue(text.contains("addService denied"))
    }
    @Test fun `spawn without a token cannot use broadcast transport`() {
        assertNull(spawnTokenFrom(arrayOf("10088")))
        assertNull(spawnTokenFrom(arrayOf("10088", " ")))
        assertEquals("abc", spawnTokenFrom(arrayOf("10088", "abc")))
    }
}
