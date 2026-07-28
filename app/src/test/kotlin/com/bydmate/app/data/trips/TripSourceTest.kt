package com.bydmate.app.data.trips

import org.junit.Assert.assertEquals
import org.junit.Test

class TripSourceTest {
    @Test
    fun `constants match wire values used in DB`() {
        assertEquals("live", TripSource.LIVE)
        assertEquals("energydata", TripSource.ENERGYDATA)
        assertEquals("native_polling", TripSource.NATIVE_POLLING)
        assertEquals("diplus", TripSource.DIPLUS)
    }

    @Test
    fun `all returns full set in stable order`() {
        assertEquals(listOf("live", "energydata", "diplus", "native_polling"), TripSource.all)
    }
}
