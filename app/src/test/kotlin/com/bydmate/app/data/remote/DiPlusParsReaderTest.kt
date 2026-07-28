package com.bydmate.app.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiPlusParsReaderTest {
    private val reader = DiPlusParsReader(OkHttpClient())

    @Test
    fun `parses DiPlus snapshot and normalizes scaled fields`() {
        val data = reader.parse(
            "SOC:73|Speed:42|Mileage:12345|Power:-8.5|" +
                "Voltage12V:12600|MaxCellV:3.42|MinCellV:3.39|" +
                "MaxBatTemp:31|MinBatTemp:27|Gear:4|PowerState:2"
        )!!

        assertEquals(73, data.soc)
        assertEquals(42, data.speed)
        assertEquals(1234.5, data.mileage!!, 0.001)
        assertEquals(12.6, data.voltage12v!!, 0.001)
        assertEquals(4, data.gear)
        assertEquals(2, data.powerState)
    }

    @Test
    fun `rejects an empty or sentinel-only DiPlus snapshot`() {
        assertNull(reader.parse(""))
        assertNull(reader.parse("SOC:-1|Mileage:-1|Voltage12V:0"))
    }
}
