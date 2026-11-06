package com.bydmate.app.data.dtc

import android.content.Context
import android.os.SystemClock
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton
import com.bydmate.app.helper.dtc.readDtcSystemProperty

data class DtcLiveSample(val gear: Int?, val elapsedMs: Long)

/** App-scoped owner: leaving settings must not interrupt an ECU operation. */
@Singleton
class DtcFeature @Inject constructor(
    @ApplicationContext context: Context,
    helper: HelperClient,
    settings: SettingsRepository,
    sink: TelegramBackupSink,
) {
    val readOnlyFirmware = com.bydmate.app.helper.dtc.CanFdDiagnosticCache.supports(::readDtcSystemProperty)
    val cacheReader = CanFdDiagnosticReader(helper, settings, sink)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = DtcScanRepository(
        helper, context.getSharedPreferences(DtcScanRepository.PREFS_NAME, Context.MODE_PRIVATE),
        settings, sink, scope,
        liveSample = {
            TrackingService.lastData.value?.let {
                DtcLiveSample(it.gear, TrackingService.lastVehicleSampleElapsedMs)
            }
        },
        serviceRunning = { TrackingService.isRunning.value },
        elapsedNow = SystemClock::elapsedRealtime,
        wallNow = System::currentTimeMillis,
        eraseEnabled = com.bydmate.app.helper.dtc.DtcWire.ERASE_ENABLED && !readOnlyFirmware,
        scanTotal = if (readOnlyFirmware) com.bydmate.app.helper.dtc.CanFdDtcReadOnlyProtocol.ADDRESSES.size else com.bydmate.app.helper.dtc.DtcWire.ADDRESSES.size,
        describer = DtcDescriber(
            open = { context.assets.open("dtc/$it").reader(Charsets.UTF_8) },
            autoType = { CarAutoType.read(helper) },
            energy = { DtcEnergy.EV },
        ),
    )
}
