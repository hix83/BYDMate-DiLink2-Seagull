package com.bydmate.app.data.remote

import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramReportClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun send(token: String, chatId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cleanToken = token.trim()
            val cleanChatId = chatId.trim()
            if (!TOKEN.matches(cleanToken) || cleanChatId.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("invalid Telegram settings"))
            }
            runCatching {
                val body = FormBody.Builder()
                    .add("chat_id", cleanChatId)
                    .add("text", text.take(4096))
                    .add("disable_web_page_preview", "true")
                    .build()
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$cleanToken/sendMessage")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    val accepted = runCatching { JSONObject(payload).optBoolean("ok") }.getOrDefault(false)
                    if (!response.isSuccessful || !accepted) {
                        throw IllegalStateException("Telegram HTTP ${response.code}")
                    }
                }
            }
        }

    companion object {
        private val TOKEN = Regex("^[0-9]{6,}:[A-Za-z0-9_-]{20,}$")

        internal fun buildLastTripReport(trip: TripEntity?): String {
            val header = "BYDMate — отчёт"
            if (trip == null) return "$header\n\nПоездок пока нет. Подключение к боту работает."
            val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val lines = mutableListOf(
                header,
                "",
                "Последняя поездка: ${formatter.format(Date(trip.startTs))}",
            )
            trip.distanceKm?.let { lines += "Пробег: ${format(it, 1)} км" }
            trip.kwhConsumed?.let { lines += "Энергия: ${format(it, 2)} кВт·ч" }
            trip.kwhPer100km?.let { lines += "Расход: ${format(it, 1)} кВт·ч/100 км" }
            if (trip.socStart != null || trip.socEnd != null) {
                lines += "Заряд: ${trip.socStart?.let { "$it%" } ?: "—"} → ${trip.socEnd?.let { "$it%" } ?: "—"}"
            }
            trip.avgSpeedKmh?.let { lines += "Средняя скорость: ${format(it, 1)} км/ч" }
            trip.exteriorTemp?.let { lines += "Температура: $it °C" }
            return lines.joinToString("\n")
        }

        private fun format(value: Double, decimals: Int) =
            String.format(Locale.US, "%.${decimals}f", value)
    }
}
