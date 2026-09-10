package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.platform.VehiclePlatform
import com.bydmate.app.data.remote.DiParsControlClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleApiDilink2CompressorTest {
    private val helper = mockk<HelperClient>()
    private val diPlus = mockk<DiParsControlClient>()
    private val api = VehicleApiImpl(
        parsReader = mockk<ParsReader>(relaxed = true),
        autoservice = mockk<AutoserviceClient>(relaxed = true),
        helper = helper,
        allowlist = WriteAllowlist.EMPTY,
        writeLogDao = mockk<VehicleWriteLogDao>(relaxed = true),
        seatStore = object : SeatChannelStore {
            override fun winner() = SeatChannel.UNKNOWN
            override fun setWinner(channel: SeatChannel) = Unit
        },
        diPlusControl = diPlus,
    )

    @Test
    fun `DiLink 2 AC on starts climate through DiPlus then confirms compressor`() = runTest {
        coEvery { diPlus.sendCommand("自动空调") } returns true
        coEvery { helper.setAcCompressor(true) } returns true

        val result = api.dispatchForPlatform("自动空调", VehiclePlatform.DILINK2)

        assertTrue(result.isSuccess)
        coVerifySequence {
            diPlus.sendCommand("自动空调")
            helper.setAcCompressor(true)
        }
    }

    @Test
    fun `DiLink 2 ventilation does not enable compressor`() = runTest {
        coEvery { diPlus.sendCommand("打开空调通风") } returns true

        val result = api.dispatchForPlatform("打开空调通风", VehiclePlatform.DILINK2)

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { helper.setAcCompressor(any()) }
    }

    @Test
    fun `DiLink 2 AC on fails visibly when stock compressor action fails`() = runTest {
        coEvery { diPlus.sendCommand("自动空调") } returns true
        coEvery { helper.setAcCompressor(true) } returns false

        val result = api.dispatchForPlatform("自动空调", VehiclePlatform.DILINK2)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.HelperUnreachable)
    }

    @Test
    fun `DiLink 2 uses on-device ADB compressor path when it is available`() = runTest {
        val adb = mockk<AdbOnDeviceClient>()
        val apiWithAdb = VehicleApiImpl(
            parsReader = mockk<ParsReader>(relaxed = true),
            autoservice = mockk<AutoserviceClient>(relaxed = true),
            helper = helper,
            allowlist = WriteAllowlist.EMPTY,
            writeLogDao = mockk<VehicleWriteLogDao>(relaxed = true),
            seatStore = object : SeatChannelStore {
                override fun winner() = SeatChannel.UNKNOWN
                override fun setWinner(channel: SeatChannel) = Unit
            },
            diPlusControl = diPlus,
            adbOnDevice = adb,
        )
        coEvery { diPlus.sendCommand("自动空调") } returns true
        coEvery { adb.setAcCompressorViaStockUi(true) } returns true

        val result = apiWithAdb.dispatchForPlatform("自动空调", VehiclePlatform.DILINK2)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { adb.setAcCompressorViaStockUi(true) }
        coVerify(exactly = 0) { helper.setAcCompressor(any()) }
    }

    @Test
    fun `DiLink 2 blower bypasses false-success DiPlus and requires native readback`() = runTest {
        val adb = mockk<AdbOnDeviceClient>()
        val apiWithAdb = VehicleApiImpl(
            parsReader = mockk<ParsReader>(relaxed = true),
            autoservice = mockk<AutoserviceClient>(relaxed = true),
            helper = helper,
            allowlist = WriteAllowlist.EMPTY,
            writeLogDao = mockk<VehicleWriteLogDao>(relaxed = true),
            seatStore = object : SeatChannelStore {
                override fun winner() = SeatChannel.UNKNOWN
                override fun setWinner(channel: SeatChannel) = Unit
            },
            diPlusControl = diPlus,
            adbOnDevice = adb,
        )
        coEvery { adb.setClimateFanLevel(2) } returns true

        val result = apiWithAdb.dispatchForPlatform("设置风量2", VehiclePlatform.DILINK2)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { adb.setClimateFanLevel(2) }
        coVerify(exactly = 0) { diPlus.sendCommand(any()) }
    }

    @Test
    fun `DiLink 2 blower reports failure when native readback fails`() = runTest {
        val adb = mockk<AdbOnDeviceClient>()
        val apiWithAdb = VehicleApiImpl(
            parsReader = mockk<ParsReader>(relaxed = true),
            autoservice = mockk<AutoserviceClient>(relaxed = true),
            helper = helper,
            allowlist = WriteAllowlist.EMPTY,
            writeLogDao = mockk<VehicleWriteLogDao>(relaxed = true),
            seatStore = object : SeatChannelStore {
                override fun winner() = SeatChannel.UNKNOWN
                override fun setWinner(channel: SeatChannel) = Unit
            },
            diPlusControl = diPlus,
            adbOnDevice = adb,
        )
        coEvery { adb.setClimateFanLevel(4) } returns false

        val result = apiWithAdb.dispatchForPlatform("设置风量4", VehiclePlatform.DILINK2)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { diPlus.sendCommand(any()) }
    }
}
