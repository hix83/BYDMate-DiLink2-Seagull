package com.bydmate.app.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteControlManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adb: AdbOnDeviceClient,
    private val vehicle: VehicleApi,
    private val telegram: TelegramReportManager,
) {
    data class State(val enabled: Boolean = false, val linked: Boolean = false, val qrUrl: String = "", val message: String = "Отключено")
    private val prefs = context.getSharedPreferences("remote_control", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(State(enabled = prefs.getBoolean("enabled", false), linked = prefs.getBoolean("linked", false)))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private fun sampleAge(): Long = if (TrackingService.lastVehicleSampleElapsedMs == 0L) Long.MAX_VALUE
        else android.os.SystemClock.elapsedRealtime() - TrackingService.lastVehicleSampleElapsedMs

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun token(): String? = prefs.getString("credential", null)?.let {
        val bytes = Base64.decode(it, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }

    private fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("credential", Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).commit())
    }

    private fun request(path: String, payload: JSONObject, authenticated: Boolean = true): JSONObject {
        val builder = Request.Builder().url(BASE_URL + path)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (authenticated) builder.header("Authorization", "Bearer ${token() ?: error("Нет ключа устройства")}")
        client.newCall(builder.build()).execute().use { response ->
            val data = JSONObject(response.body?.string() ?: "{}")
            check(response.isSuccessful) { data.optString("error", "HTTP ${response.code}") }
            return data
        }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (token() == null) saveToken(request("/api/device/register", JSONObject().put("name", "BYD Seagull"), false).getString("token"))
                val pair = request("/api/device/pair", JSONObject())
                prefs.edit().putBoolean("enabled", true).apply()
                _state.value = State(true, false, pair.getString("url"), "Отсканируйте QR телефоном. Код действует 10 минут.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(message = "Не удалось подключить: ${e.message}")
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        _state.value = _state.value.copy(enabled = enabled, qrUrl = "", message = if (enabled) "Ожидание связи" else "Удалённое управление отключено на машине")
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val startupId = UUID.randomUUID().toString()
        job = scope.launch {
            while (isActive) {
                if (prefs.getBoolean("enabled", false)) {
                    try { mutex.withLock { tick(startupId) } } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _state.value = _state.value.copy(message = "Связь недоступна: ${e.message}")
                    }
                }
                delay(15_000)
            }
        }
    }

    private suspend fun tick(startupId: String) {
        if (token() == null) saveToken(request("/api/device/register", JSONObject().put("name", "BYD Seagull"), false).getString("token"))
        // Persist BEFORE hardware writes. An interrupted command is reported as uncertain,
        // never automatically repeated after process death or an HTTP timeout.
        prefs.getString("journal", null)?.let { raw ->
            val saved = JSONObject(raw)
            if (!saved.has("status")) saved.put("status", "uncertain").put("details", "Приложение остановилось во время выполнения. Проверьте климат; при необходимости отправьте новое задание.")
            flushResult(saved)
        }
        val snapshot = TrackingService.lastData.value
        val adbReady = adb.connect().isSuccess && snapshot != null && TrackingService.vehicleDataConnected.value && sampleAge() in 0..30_000
        val telemetry = JSONObject().put("adb_ready", adbReady)
        snapshot?.let { telemetry.put("soc", it.soc).put("outside", it.exteriorTemp).put("speed", it.speed) }
        TrackingService.lastLocation.value?.let {
            telemetry.put("latitude", it.latitude).put("longitude", it.longitude).put("location_time", it.time / 1000)
        }
        val response = request("/api/device/poll", JSONObject().put("telemetry", telemetry).put("session", startupId))
        val linked = response.getBoolean("linked")
        prefs.edit().putBoolean("linked", linked).apply()
        _state.value = _state.value.copy(linked = linked, qrUrl = if (linked) "" else _state.value.qrUrl,
            message = if (linked) "На связи · ${if (adbReady) "ADB готов" else "ожидание ADB и данных автомобиля"}" else "Ожидание привязки в кабинете")
        val cmd = response.optJSONObject("command") ?: return
        // Remote climate cannot disrupt a moving car, or operate on unknown vehicle state.
        if (!adbReady || !RemoteClimateCommands.canExecute(snapshot?.speed, snapshot?.gear, sampleAge(), TrackingService.vehicleDataConnected.value)) {
            _state.value = _state.value.copy(message = "Задание ожидает остановки машины в P")
            return
        }
        if (cmd.getLong("expires") * 1000 <= System.currentTimeMillis()) return
        val c = cmd.getJSONObject("climate")
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(c.getBoolean("enabled"), c.getInt("temperature"), c.getInt("fan"), c.getInt("driver_heat"), c.getInt("passenger_heat")))
        val journal = JSONObject().put("id", cmd.getString("id"))
        check(prefs.edit().putString("journal", journal.toString()).commit())
        val lines = mutableListOf(cmd.optString("reason", "Удалённый климат"))
        var successes = 0
        for ((label, command) in commands) {
            currentCoroutineContext().ensureActive()
            if (!prefs.getBoolean("enabled", false)) { lines += "Остановлено: удалённое управление отключено"; break }
            val live = TrackingService.lastData.value
            if (!RemoteClimateCommands.canExecute(live?.speed, live?.gear, sampleAge(), TrackingService.vehicleDataConnected.value)) { lines += "Остановлено: машина не в P или нет свежих данных"; break }
            val result = vehicle.dispatch(command)
            if (result.isSuccess) { successes++; lines += "✓ $label: команда принята" }
            else lines += "✗ $label: ${result.exceptionOrNull()?.message?.take(180) ?: "не выполнено"}"
            delay(400)
        }
        val status = if (successes == commands.size) "success" else if (successes == 0) "failed" else "partial"
        val details = lines.joinToString("\n") + "\nПриём команды не гарантирует аппаратное подтверждение каждого параметра."
        journal.put("status", status).put("details", details)
        check(prefs.edit().putString("journal", journal.toString()).commit())
        flushResult(journal)
        _state.value = _state.value.copy(message = details)
    }

    private suspend fun flushResult(journal: JSONObject) {
        if (!journal.optBoolean("telegram_queued")) {
            val text = journal.getString("details").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val queued = telegram.sendAutomationReport("Удалённый климат", setOf(ReportField.LOCATION, ReportField.SOC, ReportField.TEMPS), text)
            journal.put("telegram_queued", true)
            journal.put("details", journal.getString("details") + if (queued) "\nTelegram: отчёт поставлен в очередь." else "\nTelegram не настроен на машине.")
            check(prefs.edit().putString("journal", journal.toString()).commit())
        }
        request("/api/device/result", journal)
        check(prefs.edit().remove("journal").commit())
    }

    companion object {
        const val BASE_URL = "https://byd.slk-soft.ru"
        private const val KEY_ALIAS = "bydmate_remote_control"
    }
}
