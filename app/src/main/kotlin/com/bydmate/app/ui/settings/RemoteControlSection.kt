package com.bydmate.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.remote.RemoteControlManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteControlViewModel @Inject constructor(private val manager: RemoteControlManager) : ViewModel() {
    val state = manager.state
    fun connect() { viewModelScope.launch { manager.connect() } }
    fun enabled(value: Boolean) = manager.setEnabled(value)
}

@Composable
fun RemoteControlSection(viewModel: RemoteControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Text("Удалённое управление", style = MaterialTheme.typography.headlineSmall)
    Text("Кабинет: byd.slk-soft.ru\nКлимат, положение на карте и сезонные правила. Регистрация по приглашению администратора.")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Разрешить удалённое управление", modifier = Modifier.weight(1f))
        Switch(checked = state.enabled, onCheckedChange = viewModel::enabled)
    }
    Text(state.message)
    if (state.linked) {
        Text("Автомобиль привязан к аккаунту. Чтобы сменить владельца, сначала отвяжите машину в кабинете.")
    } else {
        Button(onClick = viewModel::connect) { Text(if (state.qrUrl.isEmpty()) "Подключить машину" else "Обновить QR-код") }
    }
    if (state.qrUrl.isNotEmpty()) {
        val bitmap = remember(state.qrUrl) {
            val matrix = MultiFormatWriter().encode(state.qrUrl, BarcodeFormat.QR_CODE, 384, 384)
            Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(384 * 384) { i -> if (matrix[i % 384, i / 384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, 384, 0, 0, 384, 384)
            }
        }
        Image(bitmap.asImageBitmap(), "QR для привязки автомобиля", Modifier.size(300.dp).background(Color.White))
        Text("Отсканируйте камерой телефона и войдите в кабинет. Не передавайте QR другим людям.")
    }
    Text("Задания выполняются только при готовности ADB, на стоящей машине в P. Выключенный автомобиль не запускается. Результат отправляется в Telegram из настроек BYDMate.", style = MaterialTheme.typography.bodySmall)
}
