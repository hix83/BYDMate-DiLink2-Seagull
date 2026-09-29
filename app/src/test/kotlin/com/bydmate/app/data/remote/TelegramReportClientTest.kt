package com.bydmate.app.data.remote

import com.bydmate.app.data.local.entity.TripEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramReportClientTest {
    @Test
    fun `report contains last trip values`() {
        val text = TelegramReportClient.buildLastTripReport(
            TripEntity(
                startTs = 1_700_000_000_000,
                distanceKm = 12.34,
                kwhConsumed = 2.5,
                kwhPer100km = 20.25,
                socStart = 80,
                socEnd = 74,
                avgSpeedKmh = 42.1,
            )
        )

        assertTrue(text.contains("12.3 км"))
        assertTrue(text.contains("2.50 кВт·ч"))
        assertTrue(text.contains("80% → 74%"))
    }

    @Test
    fun `empty history still produces useful connection report`() {
        assertTrue(TelegramReportClient.buildLastTripReport(null).contains("Подключение к боту работает"))
    }
}
