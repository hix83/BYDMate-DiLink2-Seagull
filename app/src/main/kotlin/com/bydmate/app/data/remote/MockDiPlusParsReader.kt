package com.bydmate.app.data.remote

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin

/**
 * Debug-only vehicle signal generator used by the Android emulator.
 *
 * It models a short, repeating Seagull city drive. Selection of this reader is
 * guarded by BuildConfig.DEBUG and emulator detection in PlatformParsReader,
 * so it can never replace Di+ on a release build or a physical head unit.
 */
@Singleton
class MockDiPlusParsReader @Inject constructor() {
    fun fetch(): DiParsData {
        val seconds = SystemClock.elapsedRealtime() / 1_000.0
        val phase = seconds % 90.0
        val speed = when {
            phase < 10 -> phase * 4.0
            phase < 35 -> 40.0 + sin(phase / 4.0) * 8.0
            phase < 50 -> 42.0 - (phase - 35.0) * 2.8
            phase < 60 -> 0.0
            phase < 75 -> (phase - 60.0) * 2.2
            else -> 33.0 - (phase - 75.0) * 2.2
        }.coerceAtLeast(0.0)
        val moving = speed >= 1.0
        val power = if (moving) 3.5 + speed * 0.22 + sin(seconds) * 2.5 else 0.4
        val distance = seconds * 0.006

        return DiParsData(
            soc = (78.0 - distance / 4.0).toInt().coerceIn(20, 78),
            speed = speed.toInt(),
            mileage = 12_486.3 + distance,
            power = power,
            chargeGunState = 0,
            maxBatTemp = 29,
            avgBatTemp = 27,
            minBatTemp = 26,
            chargingStatus = 0,
            batteryCapacityKwh = 38.88,
            totalElecConsumption = 12.4,
            voltage12v = 13.8,
            maxCellVoltage = 3.31,
            minCellVoltage = 3.29,
            exteriorTemp = 24,
            gear = if (moving) 4 else 1,
            powerState = 2,
            insideTemp = 22,
            acStatus = 1,
            acTemp = 22,
            fanLevel = 2,
            acCirc = 1,
            doorFL = 0,
            doorFR = 0,
            doorRL = 0,
            doorRR = 0,
            windowFL = 0,
            windowFR = 0,
            windowRL = 0,
            windowRR = 0,
            sunroof = 0,
            trunk = 0,
            hood = 0,
            seatbeltFL = 1,
            lockFL = 2,
            tirePressFL = 250,
            tirePressFR = 252,
            tirePressRL = 248,
            tirePressRR = 251,
            driveMode = 1,
            workMode = 1,
            autoPark = 0,
            rain = 0,
            lightLow = 0,
            drl = 1,
        )
    }
}
