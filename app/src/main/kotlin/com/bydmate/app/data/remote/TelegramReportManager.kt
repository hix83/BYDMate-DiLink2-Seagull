package com.bydmate.app.data.remote

import android.location.Location
import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramReportManager @Inject constructor(
    private val client: TelegramReportClient,
    private val settings: SettingsRepository,
    private val trips: TripRepository,
) {
    data class Pending(val id: String, val createdMs: Long, val text: String)

    suspend fun sendPowerOffReport(
        data: DiParsData,
        rangeKm: Double?,
        tripKm: Double?,
        tripKwh: Double?,
        startedAtMs: Long?,
        location: Location?,
        nowMs: Long,
    ) {
        if (settings.getString(SettingsRepository.KEY_TELEGRAM_AUTO_REPORT, "false") != "true") return
        val lastTrip = trips.getLastTrip().firstOrNull()
        val text = buildPowerOffReport(data, rangeKm, tripKm, tripKwh, startedAtMs, location, lastTrip, nowMs)
        enqueue(Pending(UUID.randomUUID().toString().take(8), nowMs, text))
        drain("power_off")
    }

    suspend fun drain(reason: String) {
        val token = settings.getString(SettingsRepository.KEY_TELEGRAM_BOT_TOKEN, "").trim()
        val chat = settings.getString(SettingsRepository.KEY_TELEGRAM_CHAT_ID, "").trim()
        if (token.isEmpty() || chat.isEmpty()) return
        var queue = load()
        if (queue.isEmpty()) return
        Log.i(TAG, "outbox drain reason=$reason size=${queue.size}")
        while (queue.isNotEmpty()) {
            val item = queue.first()
            val late = System.currentTimeMillis() - item.createdMs > LATE_MS
            val text = if (late) item.text + "\n\n<i>Записано ${time(item.createdMs)}, отправлено позже</i>" else item.text
            if (client.send(token, chat, text).isFailure) return
            queue = queue.drop(1)
            save(queue)
            Log.i(TAG, "outbox sent id=${item.id} left=${queue.size}")
        }
    }

    suspend fun pendingCount(): Int = load().size

    private suspend fun enqueue(entry: Pending) {
        val queue = (load() + entry).sortedBy { it.createdMs }.takeLast(MAX_OUTBOX)
        save(queue)
        Log.i(TAG, "outbox add id=${entry.id} size=${queue.size}")
    }

    private suspend fun load(): List<Pending> {
        val raw = settings.getString(SettingsRepository.KEY_TELEGRAM_OUTBOX, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Pending(item.getString("id"), item.getLong("created"), item.getString("text"))
            }
        }.getOrElse {
            settings.setString(SettingsRepository.KEY_TELEGRAM_OUTBOX, "")
            emptyList()
        }
    }

    private suspend fun save(queue: List<Pending>) {
        val array = JSONArray()
        queue.forEach { array.put(JSONObject().put("id", it.id).put("created", it.createdMs).put("text", it.text)) }
        settings.setString(SettingsRepository.KEY_TELEGRAM_OUTBOX, if (queue.isEmpty()) "" else array.toString())
    }

    companion object {
        private const val TAG = "TgReport"
        private const val MAX_OUTBOX = 10
        private const val LATE_MS = 120_000L

        internal fun buildPowerOffReport(
            data: DiParsData,
            rangeKm: Double?,
            tripKm: Double?,
            tripKwh: Double?,
            startedAtMs: Long?,
            location: Location?,
            lastTrip: com.bydmate.app.data.local.entity.TripEntity?,
            nowMs: Long,
        ): String {
            val blocks = mutableListOf("<b>BYDMate: машина выключена в ${time(nowMs)}</b>")
            val car = mutableListOf<String>()
            val charge = listOfNotNull(
                data.soc?.takeIf { it in 0..100 }?.let { "заряд <b>$it%</b>" },
                rangeKm?.takeIf { it > 0 }?.let { "запас <b>${it.toInt()} км</b>" },
            )
            if (charge.isNotEmpty()) car += "🔋 " + charge.joinToString(", ").replaceFirstChar { it.uppercase() }
            data.mileage?.takeIf { it >= 1 }?.let { car += "🧭 Пробег ${it.toInt()} км" }
            val temps = listOfNotNull(data.exteriorTemp?.let { "снаружи ${signed(it)}°" }, data.insideTemp?.let { "в салоне ${signed(it)}°" })
            if (temps.isNotEmpty()) car += "🌡 " + temps.joinToString(", ").replaceFirstChar { it.uppercase() }
            if (car.isNotEmpty()) blocks += car.joinToString("\n")

            val liveKm = tripKm?.takeIf { it >= 0.1 }
            if (liveKm != null) {
                val duration = startedAtMs?.let { ((nowMs - it).coerceAtLeast(0) / 60_000).toInt() }
                val trip = mutableListOf("🚗 <b>Поездка завершена</b>")
                trip += "  ${one(liveKm)} км" + (duration?.let { " за $it мин" } ?: "")
                if (liveKm >= 2.0 && tripKwh != null && tripKwh > 0) trip += "  Расход ${one(tripKwh / liveKm * 100)} кВт·ч/100 км"
                blocks += trip.joinToString("\n")
            } else if (lastTrip != null) {
                blocks += TelegramReportClient.buildLastTripReport(lastTrip).substringAfter("\n\n")
            }
            location?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let {
                val lat = String.format(Locale.US, "%.6f", it.latitude)
                val lon = String.format(Locale.US, "%.6f", it.longitude)
                blocks += "📍 <a href=\"https://yandex.ru/maps/?pt=$lon,$lat&amp;z=16&amp;l=map\">Машина на карте</a>"
            }
            return blocks.joinToString("\n\n").take(4096)
        }

        private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
        private fun one(value: Double) = String.format(Locale.US, "%.1f", value)
        private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()
    }
}
