package com.bydmate.app.data.platform

import android.content.Context
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.nativestack.NativeParsReader
import com.bydmate.app.data.remote.DiPlusParsReader
import com.bydmate.app.data.remote.MockDiPlusParsReader
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformParsReaderTest {

    @Test
    fun `DiLink 2 reads cell voltages through float transaction and exposes delta inputs`() = runTest {
        val diPlus = mockk<DiPlusParsReader>()
        val autoservice = mockk<AutoserviceClient>()
        coEvery { diPlus.fetch() } returns diParsData(
            soc = 72,
            mileage = 43_290.0,
            acTemp = 28,
        )
        coEvery { autoservice.isAvailable() } returns true
        coEvery { autoservice.getFloat(any(), any()) } returns null
        coEvery { autoservice.getInt(any(), any()) } returns null
        coEvery { autoservice.getFloat(1014, 1147142192) } returns 3.307f
        coEvery { autoservice.getFloat(1014, 1147142160) } returns 3.310f

        val data = reader(diPlus, autoservice).fetchFor(VehiclePlatform.DILINK2)!!

        assertEquals(3.310, data.maxCellVoltage!!, 0.0001)
        assertEquals(3.307, data.minCellVoltage!!, 0.0001)
        assertEquals(0.003, data.maxCellVoltage - data.minCellVoltage, 0.0001)
        coVerify(exactly = 1) { autoservice.getFloat(1014, 1147142192) }
        coVerify(exactly = 1) { autoservice.getFloat(1014, 1147142160) }
    }

    @Test
    fun `DiLink 2 cabin temperature mirrors climate target`() = runTest {
        val diPlus = mockk<DiPlusParsReader>()
        val autoservice = mockk<AutoserviceClient>(relaxed = true)
        coEvery { diPlus.fetch() } returns diParsData(
            soc = 72,
            mileage = 43_290.0,
            insideTemp = null,
            acTemp = 28,
            voltage12v = 13.8,
            maxBatTemp = 19,
            avgBatTemp = 18,
            minBatTemp = 18,
            maxCellVoltage = 3.310,
            minCellVoltage = 3.307,
        )

        val data = reader(diPlus, autoservice).fetchFor(VehiclePlatform.DILINK2)!!

        assertEquals(28, data.insideTemp)
    }

    private fun reader(
        diPlus: DiPlusParsReader,
        autoservice: AutoserviceClient,
    ) = PlatformParsReader(
        diPlus = diPlus,
        native = mockk<NativeParsReader>(relaxed = true),
        mockDiPlus = mockk<MockDiPlusParsReader>(relaxed = true),
        autoservice = autoservice,
        context = mockk<Context>(relaxed = true),
    )
}
