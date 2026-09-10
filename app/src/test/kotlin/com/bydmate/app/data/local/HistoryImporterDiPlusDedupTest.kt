package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.DiPlusDbReader
import com.bydmate.app.data.remote.DiPlusTripRecord
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.trips.TripSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryImporterDiPlusDedupTest {

    @Test
    fun `DiPlus sync replaces matching native row and runs recurring cleanup`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val tripRepository = mockk<TripRepository>(relaxed = true)
        val diPlus = mockk<DiPlusDbReader>()
        val start = 1_700_000_000_000L
        val native = TripEntity(
            id = 7L,
            startTs = start + 2_000L,
            endTs = start + 3_602_000L,
            distanceKm = 25.3,
            source = TripSource.NATIVE_POLLING,
        )
        val record = DiPlusTripRecord(
            timeStart = start,
            timeEnd = start + 3_600_000L,
            mileage = 25.0,
            travelTime = 3_600.0,
            avgSpeed = 25.0,
            socStart = 80.0,
            socEnd = 70.0,
            kwhConsumed = 4.5,
            odometerStart = 10_000.0,
            odometerEnd = 10_025.0,
        )
        coEvery {
            tripDao.deleteNativeDuplicatesOfDiPlus(
                TripSource.NATIVE_POLLING, TripSource.DIPLUS, 300_000L
            )
        } returns 2
        coEvery { diPlus.readTripInfo() } returns listOf(record)
        coEvery { tripDao.getByStartTsRange(any(), any()) } returns native

        val importer = HistoryImporter(
            context = mockk<Context>(relaxed = true),
            energyDataReader = mockk(relaxed = true),
            tripRepository = tripRepository,
            tripDao = tripDao,
            tripPointDao = mockk<TripPointDao>(relaxed = true),
            idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
            tripTombstoneDao = mockk<TripTombstoneDao>(relaxed = true),
            diPlusDbReader = diPlus,
        )

        val result = importer.syncFromDiPlus()

        val updated = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.updateTrip(capture(updated)) }
        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
        assertEquals(TripSource.DIPLUS, updated.captured.source)
        assertEquals(start, updated.captured.startTs)
        assertEquals(25.0, updated.captured.distanceKm!!, 0.001)
        assertEquals(4.5, updated.captured.kwhConsumed!!, 0.001)
        assertTrue(result.details.orEmpty().contains("2 native duplicates removed"))
    }
}
