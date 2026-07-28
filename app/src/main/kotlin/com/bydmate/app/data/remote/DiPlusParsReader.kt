package com.bydmate.app.data.remote

import android.util.Log
import com.bydmate.app.data.nativestack.ParsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the vehicle snapshot exposed by Di+ on its loopback HTTP endpoint.
 *
 * This is the primary DiLink 2 transport. The endpoint and Chinese parameter
 * names are the public contract used by BYDMate before the DiLink 5 native
 * autoservice migration.
 */
@Singleton
class DiPlusParsReader @Inject constructor(
    private val httpClient: OkHttpClient,
) : ParsReader {
    companion object {
        private const val TAG = "DiPlusParsReader"
        private const val BASE_URL = "http://127.0.0.1:8988/api/getDiPars"
        private const val TEMPLATE =
            "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}" +
                "|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}" +
                "|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}" +
                "|ChargingStatus:{充电状态}|BatCapacity:{电池容量}" +
                "|TotalElecCon:{总电耗}|Voltage12V:{蓄电池电压}" +
                "|MaxCellV:{最高电池电压}|MinCellV:{最低电池电压}" +
                "|ExtTemp:{车外温度}|Gear:{档位}|PowerState:{电源状态}" +
                "|InsideTemp:{车内温度}|ACStatus:{空调状态}" +
                "|ACTemp:{主驾驶空调温度}|FanLevel:{风量档位}" +
                "|ACCirc:{空调循环方式}|DoorFL:{主驾车门}" +
                "|DoorFR:{副驾车门}|DoorRL:{左后车门}|DoorRR:{右后车门}" +
                "|WindowFL:{主驾车窗打开百分比}|WindowFR:{副驾车窗打开百分比}" +
                "|WindowRL:{左后车窗打开百分比}|WindowRR:{右后车窗打开百分比}" +
                "|Sunroof:{天窗打开百分比}|Trunk:{后备箱门}|Hood:{引擎盖}" +
                "|SeatbeltFL:{主驾驶安全带状态}|LockFL:{主驾车门锁}" +
                "|TirePressFL:{左前轮气压}|TirePressFR:{右前轮气压}" +
                "|TirePressRL:{左后轮气压}|TirePressRR:{右后轮气压}" +
                "|DriveMode:{整车运行模式}|WorkMode:{整车工作模式}" +
                "|AutoPark:{自动驻车}|Rain:{雨量}|LightLow:{近光灯}|DRL:{日行灯}"
    }

    override suspend fun fetch(): DiParsData? = withContext(Dispatchers.IO) {
        runCatching {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("text", TEMPLATE)
                .build()
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return@use null
                parse(json.optString("val", ""))
            }
        }.onFailure {
            Log.w(TAG, "Di+ fetch failed: ${it.message}")
        }.getOrNull()
    }

    internal fun parse(raw: String): DiParsData? {
        val values = raw.split('|').mapNotNull { part ->
            val separator = part.indexOf(':')
            if (separator <= 0) null
            else part.substring(0, separator) to part.substring(separator + 1)
        }.toMap()

        fun int(name: String) = values[name]?.toIntOrNull()
        fun double(name: String) = values[name]?.toDoubleOrNull()
        fun Int?.cabin() = this?.takeIf { it in -50..80 }
        fun Int?.battery() = this?.takeIf { it in -40..80 }

        val soc = int("SOC")?.takeIf { it in 0..100 }
        val mileage = double("Mileage")?.takeIf { it >= 0.0 }?.div(10.0)
        val voltage12v = double("Voltage12V")?.let {
            when {
                it <= 0.0 -> null
                it > 100.0 -> it / 1000.0
                else -> it
            }
        }
        if (soc == null && mileage == null && voltage12v == null) return null

        return DiParsData(
            soc = soc,
            speed = int("Speed")?.takeIf { it >= 0 },
            mileage = mileage,
            power = double("Power"),
            chargeGunState = int("ChargeGun"),
            maxBatTemp = int("MaxBatTemp").battery(),
            avgBatTemp = int("AvgBatTemp").battery(),
            minBatTemp = int("MinBatTemp").battery(),
            chargingStatus = int("ChargingStatus"),
            batteryCapacityKwh = double("BatCapacity")?.takeIf { it > 0.0 },
            totalElecConsumption = double("TotalElecCon"),
            voltage12v = voltage12v,
            maxCellVoltage = double("MaxCellV")?.takeIf { it > 0.5 },
            minCellVoltage = double("MinCellV")?.takeIf { it > 0.5 },
            exteriorTemp = int("ExtTemp").cabin(),
            gear = int("Gear"),
            powerState = int("PowerState"),
            insideTemp = int("InsideTemp").cabin(),
            acStatus = int("ACStatus"),
            acTemp = int("ACTemp"),
            fanLevel = int("FanLevel"),
            acCirc = int("ACCirc"),
            doorFL = int("DoorFL"),
            doorFR = int("DoorFR"),
            doorRL = int("DoorRL"),
            doorRR = int("DoorRR"),
            windowFL = int("WindowFL"),
            windowFR = int("WindowFR"),
            windowRL = int("WindowRL"),
            windowRR = int("WindowRR"),
            sunroof = int("Sunroof"),
            trunk = int("Trunk"),
            hood = int("Hood"),
            seatbeltFL = int("SeatbeltFL"),
            lockFL = int("LockFL"),
            tirePressFL = int("TirePressFL"),
            tirePressFR = int("TirePressFR"),
            tirePressRL = int("TirePressRL"),
            tirePressRR = int("TirePressRR"),
            driveMode = int("DriveMode"),
            workMode = int("WorkMode"),
            autoPark = int("AutoPark"),
            rain = int("Rain"),
            lightLow = int("LightLow"),
            drl = int("DRL"),
        )
    }
}
