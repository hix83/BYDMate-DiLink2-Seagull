package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelegramReportBuilderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val strings = ReportStrings { id, args -> context.getString(id, *args) }

    @Test
    fun `last trip keeps detailed metrics and map in one report`() {
        val built = TelegramReportBuilder.build(
            header = "BYDMate — report",
            customText = "",
            fields = setOf(ReportField.TRIP, ReportField.LOCATION),
            inputs = ReportInputs(
                data = null,
                rangeKm = null,
                latitude = 55.751244,
                longitude = 37.618423,
                liveTrip = null,
                lastTrip = TripEntity(
                    startTs = 1_759_200_900_000L,
                    endTs = 1_759_202_280_000L,
                    distanceKm = 5.0,
                    kwhConsumed = 0.70,
                    kwhPer100km = 14.0,
                    socStart = 50,
                    socEnd = 48,
                    avgSpeedKmh = 20.8,
                    exteriorTemp = 12,
                ),
            ),
            lang = "ru",
            strings = strings,
            nowMs = 1_759_202_280_000L,
        )

        assertTrue(built.text.contains("⚡"))
        assertTrue(built.text.contains("0.70") || built.text.contains("0,70"))
        assertTrue(built.text.contains("📊"))
        assertTrue(built.text.contains("14.0") || built.text.contains("14,0"))
        assertTrue(built.text.contains("50%"))
        assertTrue(built.text.contains("48%"))
        assertTrue(built.text.contains("20.8") || built.text.contains("20,8"))
        assertTrue(built.text.contains("📍"))
        val point = TelegramReportBuilder.mapPoint(built.text)
        assertNotNull(point)
        assertEquals(55.751244, point!!.latitude, 0.000001)
        assertEquals(37.618423, point.longitude, 0.000001)
    }
}
