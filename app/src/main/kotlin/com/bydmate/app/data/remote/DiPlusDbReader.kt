package com.bydmate.app.data.remote

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DiPlusTripRecord(
    val timeStart: Long,
    val timeEnd: Long,
    val mileage: Double,
    val travelTime: Double,
    val avgSpeed: Double,
    val socStart: Double,
    val socEnd: Double,
    val kwhConsumed: Double,
    val odometerStart: Double,
    val odometerEnd: Double,
)

/** Original read-only TripInfo database adapter from BYDMate v2.8. */
@Singleton
class DiPlusDbReader @Inject constructor() {
    companion object {
        private const val TAG = "DiPlusDbReader"
        const val DIPLUS_DB_PATH = "/storage/emulated/0/vandiplus/db/van_bm_db"
    }

    suspend fun readTripInfo(): List<DiPlusTripRecord> = withContext(Dispatchers.IO) {
        val dbFile = File(DIPLUS_DB_PATH)
        if (!dbFile.isFile) {
            Log.w(TAG, "Di+ database not found: $DIPLUS_DB_PATH")
            return@withContext emptyList()
        }
        val result = mutableListOf<DiPlusTripRecord>()
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            db.rawQuery(
                """SELECT time_start, time_end, mileage, travelTime, avgSpeed,
                          elecPer_start, elecPer_end, elecCon_start, elecCon_end,
                          mileage_start, mileage_end
                   FROM TripInfo ORDER BY time_start""",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += DiPlusTripRecord(
                        timeStart = cursor.getLong(0),
                        timeEnd = cursor.getLong(1),
                        mileage = cursor.getDouble(2),
                        travelTime = cursor.getDouble(3),
                        avgSpeed = cursor.getDouble(4),
                        socStart = cursor.getDouble(5),
                        socEnd = cursor.getDouble(6),
                        kwhConsumed = cursor.getDouble(8) - cursor.getDouble(7),
                        odometerStart = cursor.getDouble(9) / 10.0,
                        odometerEnd = cursor.getDouble(10) / 10.0,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reading Di+ TripInfo failed", e)
        } finally {
            db.close()
        }
        result
    }

    fun findMatchingTrip(trips: List<DiPlusTripRecord>, endTs: Long): DiPlusTripRecord? =
        trips.firstOrNull { kotlin.math.abs(it.timeEnd - endTs) < 120_000L }
}
