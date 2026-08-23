package com.bydmate.app.data.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.nativestack.NativeParsReader
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.DiPlusParsReader
import com.bydmate.app.data.remote.MockDiPlusParsReader
import com.bydmate.app.service.DiPlusWatchdog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the transport order for the detected head unit.
 *
 * DiLink 2: Di+ first, native autoservice as a best-effort fallback.
 * Newer DiLink: preserve upstream's native-first behaviour, with Di+ as a
 * harmless fallback when it is installed.
 */
@Singleton
class PlatformParsReader @Inject constructor(
    private val diPlus: DiPlusParsReader,
    private val native: NativeParsReader,
    private val mockDiPlus: MockDiPlusParsReader,
    private val autoservice: AutoserviceClient,
    @ApplicationContext private val context: Context,
) : ParsReader {
    private var consecutiveDiPlusFailures = 0
    private var lastDiPlusRelaunchTs = 0L
    private var lastDilink2BatteryAttemptTs = 0L
    private var cachedDilink2Battery: Dilink2BatterySupplement? = null

    override suspend fun fetch(): DiParsData? =
        fetchFor(VehiclePlatformDetector.detect(Build.VERSION.SDK_INT))

    internal suspend fun fetchFor(platform: VehiclePlatform): DiParsData? = when (platform) {
        VehiclePlatform.DILINK2 -> {
            val data = diPlus.fetch()
            if (data != null) {
                consecutiveDiPlusFailures = 0
                supplementDilink2Battery(data)
            } else {
                consecutiveDiPlusFailures++
                maybeRelaunchDiPlus()
                native.fetch() ?: if (useEmulatorMock()) mockDiPlus.fetch() else null
            }
        }
        VehiclePlatform.MODERN_DILINK -> native.fetch() ?: diPlus.fetch()
    }

    /**
     * Di+ 1.3.8 on the Seagull exposes the requested fields but returns zero for
     * 12 V and traction-battery temperatures. The same values are available from
     * the DiLink 2 autoservice Binder:
     *
     * - 12 V is a float in real volts;
     * - battery temperatures are already real °C on DiLink 2 (there is no -40
     *   offset used by the newer DiLink/Leopard signal map);
     * - max/min cell voltage use the float transaction on DiLink 2, while the
     *   newer-platform map exposes the same FIDs as scaled integers.
     *
     * Di+ also reports the Seagull cabin sensor as -2000. For this fork the
     * dashboard/agent "cabin temperature" intentionally means the climate target,
     * so mirror ACTemp into insideTemp for every valid DiLink 2 snapshot.
     *
     * Read only these missing fields instead of invoking NativeParsReader.fetch(),
     * which would issue a full multi-dozen-FID scan on every normal Di+ tick.
     */
    private suspend fun supplementDilink2Battery(data: DiParsData): DiParsData {
        val needsVoltage = data.voltage12v == null
        val needsMaxTemp = data.maxBatTemp == null || data.maxBatTemp == 0
        val needsAvgTemp = data.avgBatTemp == null || data.avgBatTemp == 0
        val needsMinTemp = data.minBatTemp == null || data.minBatTemp == 0
        val needsMaxCellVoltage = data.maxCellVoltage == null
        val needsMinCellVoltage = data.minCellVoltage == null
        if (!needsVoltage && !needsMaxTemp && !needsAvgTemp && !needsMinTemp &&
            !needsMaxCellVoltage && !needsMinCellVoltage
        ) return data.copy(insideTemp = data.acTemp)

        val now = System.currentTimeMillis()
        if (now - lastDilink2BatteryAttemptTs >= DILINK2_BATTERY_REFRESH_MS) {
            lastDilink2BatteryAttemptTs = now
            if (autoservice.isAvailable()) {
                val previous = cachedDilink2Battery
                val voltage = autoservice
                    .getFloat(DILINK2_BODY_DEVICE, DILINK2_12V_FID)
                    ?.toDouble()
                    ?.takeIf { it > 0.0 }
                    ?: previous?.voltage12v
                val maxTemp = autoservice
                    .getInt(DILINK2_BATTERY_DEVICE, DILINK2_MAX_BAT_TEMP_FID)
                    ?.takeIf(::isPlausibleTemperature)
                    ?: previous?.maxBatTemp
                val minTemp = autoservice
                    .getInt(DILINK2_BATTERY_DEVICE, DILINK2_MIN_BAT_TEMP_FID)
                    ?.takeIf(::isPlausibleTemperature)
                    ?: previous?.minBatTemp
                val firstCellVoltage = autoservice
                    .getFloat(DILINK2_BATTERY_DEVICE, DILINK2_MAX_CELL_VOLTAGE_FID)
                    ?.toDouble()
                    ?.takeIf(::isPlausibleCellVoltage)
                    ?: previous?.maxCellVoltage
                val secondCellVoltage = autoservice
                    .getFloat(DILINK2_BATTERY_DEVICE, DILINK2_MIN_CELL_VOLTAGE_FID)
                    ?.toDouble()
                    ?.takeIf(::isPlausibleCellVoltage)
                    ?: previous?.minCellVoltage
                // These are separate Binder reads and the pack voltage may move between
                // them under load. Sort the pair so consumers never see a negative delta.
                val maxCellVoltage = when {
                    firstCellVoltage != null && secondCellVoltage != null ->
                        maxOf(firstCellVoltage, secondCellVoltage)
                    else -> firstCellVoltage
                }
                val minCellVoltage = when {
                    firstCellVoltage != null && secondCellVoltage != null ->
                        minOf(firstCellVoltage, secondCellVoltage)
                    else -> secondCellVoltage
                }

                if (voltage != null || maxTemp != null || minTemp != null ||
                    maxCellVoltage != null || minCellVoltage != null
                ) {
                    cachedDilink2Battery = Dilink2BatterySupplement(
                        voltage,
                        maxTemp,
                        minTemp,
                        maxCellVoltage,
                        minCellVoltage,
                    )
                    Log.i(
                        TAG,
                        "DiLink2 ADB battery supplement: 12V=$voltage, maxTemp=$maxTemp, " +
                            "minTemp=$minTemp, maxCellV=$maxCellVoltage, minCellV=$minCellVoltage",
                    )
                }
            }
        }

        val supplement = cachedDilink2Battery ?: return data.copy(insideTemp = data.acTemp)
        val avgTemp = when {
            supplement.maxBatTemp != null && supplement.minBatTemp != null ->
                (supplement.maxBatTemp + supplement.minBatTemp) / 2
            else -> supplement.maxBatTemp ?: supplement.minBatTemp
        }
        return data.copy(
            voltage12v = if (needsVoltage) supplement.voltage12v else data.voltage12v,
            maxBatTemp = if (needsMaxTemp) supplement.maxBatTemp else data.maxBatTemp,
            avgBatTemp = if (needsAvgTemp) avgTemp else data.avgBatTemp,
            minBatTemp = if (needsMinTemp) supplement.minBatTemp else data.minBatTemp,
            maxCellVoltage = if (needsMaxCellVoltage) supplement.maxCellVoltage else data.maxCellVoltage,
            minCellVoltage = if (needsMinCellVoltage) supplement.minCellVoltage else data.minCellVoltage,
            insideTemp = data.acTemp,
        )
    }

    private fun isPlausibleTemperature(value: Int): Boolean = value in -40..80
    private fun isPlausibleCellVoltage(value: Double): Boolean = value in 0.5..5.0

    private fun useEmulatorMock(): Boolean =
        BuildConfig.DEBUG && (
            Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for")
            )

    private fun maybeRelaunchDiPlus() {
        val now = System.currentTimeMillis()
        if (!DiPlusWatchdog.shouldRelaunch(
                failuresCount = consecutiveDiPlusFailures,
                threshold = 3,
                nowMs = now,
                lastRelaunchTs = lastDiPlusRelaunchTs,
                cooldownMs = 5L * 60_000L,
            )
        ) return
        lastDiPlusRelaunchTs = now

        runCatching {
            val intent = Intent().apply {
                setClassName(
                    "com.van.diplus",
                    "com.van.diplus.activity.StartMainServiceActivity",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.recoverCatching { firstError ->
            val intent = context.packageManager
                .getLaunchIntentForPackage("com.van.diplus")
                ?: throw firstError
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "Unable to relaunch Di+: ${it.message}")
        }
    }

    private data class Dilink2BatterySupplement(
        val voltage12v: Double?,
        val maxBatTemp: Int?,
        val minBatTemp: Int?,
        val maxCellVoltage: Double?,
        val minCellVoltage: Double?,
    )

    private companion object {
        const val TAG = "PlatformParsReader"
        const val DILINK2_BATTERY_REFRESH_MS = 30_000L
        const val DILINK2_BODY_DEVICE = 1001
        const val DILINK2_BATTERY_DEVICE = 1014
        const val DILINK2_12V_FID = 1128267816
        const val DILINK2_MAX_BAT_TEMP_FID = 1148190752
        const val DILINK2_MIN_BAT_TEMP_FID = 1148190736
        const val DILINK2_MAX_CELL_VOLTAGE_FID = 1147142192
        const val DILINK2_MIN_CELL_VOLTAGE_FID = 1147142160
    }
}
