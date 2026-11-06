package com.bydmate.app.data.dtc

import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TgBackupConfig
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CanFdDiagnosticReaderTest {
    @Test fun `empty cache is reported honestly and never invokes scan or erase`() = runTest {
        val helper = mockk<HelperClient>()
        val settings = mockk<SettingsRepository>()
        val sink = mockk<TelegramBackupSink>()
        coEvery { helper.readCanFdDiagnosticCache() } returns CanFdCacheRead(CacheReadStatus.EMPTY)
        coEvery { settings.getTgBackupConfig() } returns TgBackupConfig("token", 1, "bot", "chat")
        coEvery { sink.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val reader = CanFdDiagnosticReader(helper, settings, sink) { 1000 }
        assertNull(reader.state.value.result)
        reader.refresh()
        assertEquals(CacheReadStatus.EMPTY, reader.state.value.result?.status)
        assertEquals(1000L, reader.state.value.at)
        assertEquals(DtcSendOutcome.SENT, reader.send())
        coVerify { sink.sendMessage("token", 1, match { it.contains("кэш пуст") && it.contains("опрос ЭБУ не выполнялся") }, null, false) }
        coVerify(exactly = 0) { helper.dtcScanStart(any(), any()) }
        coVerify(exactly = 0) { helper.dtcEraseStart(any(), any(), any(), any(), any()) }
    }
    @Test fun `lost channel is unavailable never an empty successful report`() = runTest {
        val helper = mockk<HelperClient>()
        coEvery { helper.readCanFdDiagnosticCache() } returns null
        val r = CanFdDiagnosticReader(helper, mockk(), mockk())
        r.refresh()
        assertEquals(CacheReadStatus.UNAVAILABLE, r.state.value.result?.status)
        coEvery { helper.readCanFdDiagnosticCache() } throws IllegalStateException("offline")
        r.refresh()
        assertFalse(r.state.value.busy)
        assertEquals(CacheReadStatus.UNAVAILABLE, r.state.value.result?.status)
    }
    @Test fun `unparsed data never become DTC codes and fit one Telegram message`() {
        val report = CanFdDiagnosticReader.report(CacheReadState(at = 1000,
            result = CanFdCacheRead(CacheReadStatus.UNDECODED, ByteArray(4096) { 0x59 })))
        assertTrue(report.length < 4096)
        assertTrue(report.contains("Не является расшифрованным списком DTC"))
        assertTrue(report.contains("стирание ошибок не выполнялись"))
    }
}
