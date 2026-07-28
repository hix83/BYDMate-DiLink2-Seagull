package com.bydmate.app.data.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bydmate.app.BuildConfig
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
    @ApplicationContext private val context: Context,
) : ParsReader {
    private var consecutiveDiPlusFailures = 0
    private var lastDiPlusRelaunchTs = 0L

    override suspend fun fetch(): DiParsData? =
        fetchFor(VehiclePlatformDetector.detect(Build.VERSION.SDK_INT))

    internal suspend fun fetchFor(platform: VehiclePlatform): DiParsData? = when (platform) {
        VehiclePlatform.DILINK2 -> {
            val data = diPlus.fetch()
            if (data != null) {
                consecutiveDiPlusFailures = 0
                data
            } else {
                consecutiveDiPlusFailures++
                maybeRelaunchDiPlus()
                native.fetch() ?: if (useEmulatorMock()) mockDiPlus.fetch() else null
            }
        }
        VehiclePlatform.MODERN_DILINK -> native.fetch() ?: diPlus.fetch()
    }

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
            Log.w("PlatformParsReader", "Unable to relaunch Di+: ${it.message}")
        }
    }
}
