package com.bydmate.app.data.telegram

import com.bydmate.app.data.local.entity.ActionDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramReportActionTest {
    @Test
    fun `new payload round trips fields and text`() {
        val action = ActionDef("", "Telegram", kind = TELEGRAM_REPORT_KIND)
            .withTelegramReport(setOf(ReportField.LOCATION, ReportField.SOC), "Готово")

        assertEquals(setOf(ReportField.LOCATION, ReportField.SOC), action.reportFields())
        assertEquals("Готово", action.reportText())
    }

    @Test
    fun `rule name is added only to telegram action`() {
        val report = ActionDef("", "Telegram", kind = TELEGRAM_REPORT_KIND)
            .withTelegramReport(ReportField.DEFAULT, "")
            .withReportRuleName("Машина выключена")
        val vehicle = ActionDef("车窗关闭", "Окна").withReportRuleName("ignored")

        assertEquals("Машина выключена", report.reportRuleName())
        assertNull(vehicle.reportRuleName())
        assertEquals("车窗关闭", vehicle.command)
    }

    @Test
    fun `missing fields use safe defaults`() {
        val action = ActionDef("", "Telegram", kind = TELEGRAM_REPORT_KIND, payload = "{}")
        assertEquals(ReportField.DEFAULT, action.reportFields())
    }
}
