package com.bydmate.app.data.dtc

import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.CacheReadStatus
import com.bydmate.app.helper.dtc.CanFdCacheRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CacheReadState(val busy: Boolean = false, val at: Long = 0, val result: CanFdCacheRead? = null)

/** Cached GET only. Never uses the legacy scan/erase API or any OTA control command. */
class CanFdDiagnosticReader(
    private val helper: HelperClient,
    private val settings: SettingsRepository,
    private val sink: TelegramBackupSink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(CacheReadState())
    val state = mutable.asStateFlow()

    suspend fun refresh() {
        if (mutable.value.busy) return
        mutable.value = CacheReadState(busy = true)
        try {
            val result = helper.readCanFdDiagnosticCache() ?: CanFdCacheRead(CacheReadStatus.UNAVAILABLE)
            mutable.value = CacheReadState(at = now(), result = result)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.value = CacheReadState(at = now(), result = CanFdCacheRead(CacheReadStatus.UNAVAILABLE))
        } finally {
            if (mutable.value.busy) mutable.value = CacheReadState()
        }
    }

    suspend fun send(): DtcSendOutcome = try {
        sendCurrent()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { DtcSendOutcome.FAILED }

    private suspend fun sendCurrent(): DtcSendOutcome {
        val current = mutable.value
        if (current.busy || current.result == null) return DtcSendOutcome.FAILED
        val config = settings.getTgBackupConfig()
        val chatId = config.chatId ?: return DtcSendOutcome.NO_BOT
        if (!config.configured) return DtcSendOutcome.NO_BOT
        return sink.sendMessage(config.token, chatId, report(current), null, false).fold(
            onSuccess = { DtcSendOutcome.SENT }, onFailure = {
                if (it is CancellationException) throw it
                DtcSendOutcome.FAILED
            },
        )
    }

    companion object {
        fun description(status: CacheReadStatus): String = when (status) {
            CacheReadStatus.EMPTY -> "Штатный кэш пуст. Это не означает, что ошибок нет: опрос ЭБУ не выполнялся."
            CacheReadStatus.UNDECODED -> "Получены данные штатного кэша. Формат ещё не подтверждён — список кодов и отсутствие ошибок не определены."
            CacheReadStatus.NOT_PARK -> "Чтение доступно только в P."
            CacheReadStatus.UNSUPPORTED_FIRMWARE -> "Эта прошивка не входит в проверенный профиль чтения."
            CacheReadStatus.UNAVAILABLE -> "Кэш недоступен. Проверьте обновление и связь с helper. Отсутствие ошибок не подтверждено."
        }

        fun report(state: CacheReadState): String = buildString {
            appendLine("BYDMate — чтение кэша DiLink 2/CANFD")
            appendLine("Получено: ${DtcScanRepository.formatShort(state.at)}")
            appendLine("Источник: штатный OTA-кэш, getFaultCodeInfo (1032/815)")
            state.result?.let {
                appendLine(description(it.status))
                if (it.status == CacheReadStatus.UNDECODED) {
                    appendLine("Размер: ${it.bytes.size} байт. Не является расшифрованным списком DTC.")
                    appendLine("HEX (до 512 байт): " + it.bytes.take(512).joinToString("") { b -> "%02X".format(b) })
                }
            }
            append("Активный опрос и стирание ошибок не выполнялись.")
        }
    }
}
