package com.bydmate.app.data.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class VehiclePlatformDetectorTest {
    @Test
    fun `Android 9 selects DiLink 2`() {
        assertEquals(VehiclePlatform.DILINK2, VehiclePlatformDetector.detect(28))
    }

    @Test
    fun `Android 10 and newer preserve modern DiLink profile`() {
        assertEquals(VehiclePlatform.MODERN_DILINK, VehiclePlatformDetector.detect(29))
        assertEquals(VehiclePlatform.MODERN_DILINK, VehiclePlatformDetector.detect(32))
    }
}
