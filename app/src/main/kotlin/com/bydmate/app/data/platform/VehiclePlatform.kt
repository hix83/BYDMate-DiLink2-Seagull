package com.bydmate.app.data.platform

/**
 * Runtime platform choice. DiLink marketing versions are not exposed through
 * a stable Android API, so Android 9/API 28 is the conservative DiLink 2
 * signal. A future device probe can refine this without changing consumers.
 */
enum class VehiclePlatform {
    DILINK2,
    MODERN_DILINK,
}

object VehiclePlatformDetector {
    fun detect(sdkInt: Int): VehiclePlatform =
        if (sdkInt <= 28) VehiclePlatform.DILINK2 else VehiclePlatform.MODERN_DILINK
}
