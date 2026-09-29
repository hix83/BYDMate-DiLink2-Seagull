package com.bydmate.app.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramReportManagerTest {
    @Test
    fun `power off report is split into readable blocks`() {
        val report = TelegramReportManager.buildPowerOffReport(
            data = diParsData(soc = 64, mileage = 23_456.7, exteriorTemp = 12, insideTemp = 19),
            rangeKm = 312.0,
            tripKm = 14.2,
            tripKwh = 3.14,
            startedAtMs = 1_000_000L,
            location = null,
            lastTrip = null,
            nowMs = 3_100_000L,
        )

        assertTrue(report.contains("<b>BYDMate: машина выключена"))
        assertTrue(report.contains("🔋 Заряд <b>64%</b>, запас <b>312 км</b>"))
        assertTrue(report.contains("🧭 Пробег 23456 км"))
        assertTrue(report.contains("🚗 <b>Поездка завершена</b>"))
        assertTrue(report.contains("14.2 км за 35 мин"))
        assertTrue(report.contains("22.1 кВт·ч/100 км"))
    }
}
