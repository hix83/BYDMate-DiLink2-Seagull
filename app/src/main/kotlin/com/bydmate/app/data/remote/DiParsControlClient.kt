package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Original Di+ command transport restored from BYDMate v2.8. */
@Singleton
class DiParsControlClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "DiParsControl"
        private const val BASE_URL = "http://127.0.0.1:8988/api/sendCmd"
        private const val PREFIX = "迪加"
        private val BLOCKED_PATTERNS = listOf("发送CAN", "执行SHELL", "下电")
    }

    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = command.removePrefix(PREFIX)
        if (BLOCKED_PATTERNS.any(normalized::contains)) {
            Log.w(TAG, "Blocked dangerous Di+ command")
            return@withContext false
        }
        runCatching {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("cmd", "$PREFIX$normalized")
                .build()
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string()
                response.isSuccessful && body != null &&
                    JSONObject(body).optBoolean("success", false)
            }
        }.onFailure {
            Log.w(TAG, "Di+ command failed: ${it.message}")
        }.getOrDefault(false)
    }
}
