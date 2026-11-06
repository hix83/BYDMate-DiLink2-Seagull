package com.bydmate.app.helper.dtc

import org.junit.Assert.*
import org.junit.Test

class CanFdDiagnosticCacheTest {
    private val profile = mapOf("ro.hardware" to "mt6765", "ro.product.device" to "k65v1_64_bsp",
        "ro.car.protocol" to "CANFD", "sys.tcp_client_ver" to "mtk_2.1h.202312130_canfd")

    @Test fun `only the inspected exact profile enables the cached reader`() {
        assertTrue(CanFdDiagnosticCache.supports { profile[it] })
        for (key in profile.keys) {
            assertFalse(key, CanFdDiagnosticCache.supports { if (it == key) null else profile[it] })
        }
        assertFalse(CanFdDiagnosticCache.supports { if (it == "sys.tcp_client_ver") "new unknown firmware" else profile[it] })
        assertFalse(CanFdDiagnosticCache.supports { null })
    }

    @Test fun `unsupported firmware or missing P never invokes native read`() {
        var calls = 0
        val read = { calls++; 0 to byteArrayOf() }
        assertEquals(CacheReadStatus.UNSUPPORTED_FIRMWARE, CanFdDiagnosticCache.read(false, 1, read).status)
        for (gear in listOf(null, 0, 2, 3, 4)) assertEquals(CacheReadStatus.NOT_PARK, CanFdDiagnosticCache.read(true, gear, read).status)
        assertEquals(0, calls)
    }

    @Test fun `empty cached response is no data not a clean scan`() {
        assertEquals(CacheReadStatus.EMPTY, CanFdDiagnosticCache.read(true, 1) { 0 to byteArrayOf() }.status)
    }
    @Test fun `nonempty data remains explicitly undecoded and is copied`() {
        val bytes = byteArrayOf(0x59, 2, 0)
        val result = CanFdDiagnosticCache.read(true, 1) { 0 to bytes }
        assertEquals(CacheReadStatus.UNDECODED, result.status)
        bytes[0] = 0
        assertEquals(0x59.toByte(), result.bytes[0])
    }
    @Test fun `native failures missing response sentinels and oversized data are unavailable`() {
        val replies = listOf(null, -1 to byteArrayOf(), 0 to null, 0 to ByteArray(4097),
            0 to byteArrayOf(0x80.toByte(), 0, 3, 0xE8.toByte()),
            0 to byteArrayOf(0xE8.toByte(), 3, 0, 0x80.toByte()))
        for (reply in replies) assertEquals(CacheReadStatus.UNAVAILABLE, CanFdDiagnosticCache.read(true, 1) { reply }.status)
        assertEquals(CacheReadStatus.UNAVAILABLE, CanFdDiagnosticCache.read(true, 1) { error("offline") }.status)
    }
    @Test fun `inspected native buffer read uses 13 not the legacy int-array transaction 9`() {
        assertEquals(13, CanFdDiagnosticCache.GET_BUFFER_TRANSACTION)
        assertEquals(1032, CanFdDiagnosticCache.DEVICE)
        assertEquals(815, CanFdDiagnosticCache.FAULT_CACHE_FID)
    }
}
