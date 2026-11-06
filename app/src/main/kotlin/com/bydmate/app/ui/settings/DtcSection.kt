package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.dtc.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal object RussianDtcLabels : DtcLabels {
    override val title = "BYDMate — диагностика ошибок"
    override fun checked(at: String, answered: Int) = "Проверка: $at · Ответили блоков: $answered/${com.bydmate.app.helper.dtc.DtcWire.ADDRESSES.size}"
    override val nowHeader = "Активные ошибки"
    override val oldHeader = "Сохранённые и ожидающие подтверждения"
    override val pendingMark = "ожидает подтверждения"
    override val noErrors = "В ответивших блоках видимых ошибок не найдено. Это не проверка всех систем автомобиля."
    override val noAnswers = "Ни один блок не ответил. Отсутствие ошибок не подтверждено."
    override val unverifiedNote = "Результат стирания неизвестен. Это последние известные коды, а не подтверждение текущего состояния. Нужен новый скан."
    override val footer = "Коды ошибок не заменяют диагностику в сервисе. Стирание не устраняет неисправность."
    override fun unread(count: Int) = "Не удалось прочитать ошибки в $count блоках."
    override fun moreCodes(count: Int) = "Ещё кодов: $count. Полный список — в приложении."
    override val keyHint = "Блок не увидел ключ, например после удалённого запуска."
    override val similarNote = "Некоторые описания взяты из руководств похожих моделей и требуют проверки."
    override fun blockName(name: DtcBlockName): String = when (name.block) {
        DtcBlock.ADAS -> "Помощники водителя"
        DtcBlock.BRAKES -> "Тормозная система"
        DtcBlock.STEERING -> "Рулевое управление"
        DtcBlock.AIRBAG -> "Подушки безопасности"
        DtcBlock.RADAR -> "Радар"
        DtcBlock.BMS -> "Тяговая батарея"
        DtcBlock.CHARGER -> "Зарядное устройство"
        DtcBlock.URE -> "Силовая электроника"
        DtcBlock.BODY -> "Кузовная электроника"
        DtcBlock.UNKNOWN -> "Блок 0x%X".format(name.addr)
    }
}

internal fun dtcErrorText(error: DtcScanError): String = when (error) {
    DtcScanError.NOT_PARK -> "Нужны свежие данные автомобиля и положение P."
    DtcScanError.NO_DIAG_SERVICE -> "На этой прошивке нет штатной диагностической службы BYD."
    DtcScanError.UNAVAILABLE -> "Диагностика недоступна или занята другой программой. Закройте другие диагностические приложения."
    DtcScanError.DAEMON_UNREACHABLE, DtcScanError.DAEMON_OUTDATED -> "Нет связи с обновлённым ADB-helper. Проверьте ADB; после установки новой версии может потребоваться перезапуск helper."
    DtcScanError.ERASE_NEEDS_SCAN -> "Перед стиранием выполните новое сканирование (результат действует 10 минут)."
    DtcScanError.ABORTED -> "Диагностика остановлена. Проверьте положение P и отсутствие другой диагностики."
    DtcScanError.STATUS_LOST -> "Связь потеряна. Результат неизвестен; выполните новое сканирование. Не повторяйте стирание вслепую."
}

internal fun dtcEraseText(verdict: DtcEraseVerdict): String = when (verdict) {
    DtcEraseVerdict.ERASED -> "Стирание подтверждено, при повторном чтении ошибок нет."
    DtcEraseVerdict.ERASED_BUT_BACK -> "Стирание подтверждено, но ошибки вернулись — неисправность сохраняется."
    DtcEraseVerdict.UNKNOWN_CODES_PRESENT -> "Стирание не подтверждено; при повторном чтении есть ошибки."
    DtcEraseVerdict.LIKELY_ERASED -> "Ошибок при повторном чтении нет, но подтверждения стирания не получено."
    DtcEraseVerdict.CONFIRMED_UNVERIFIED -> "Стирание подтверждено, повторное чтение не удалось. Нужна новая проверка."
    DtcEraseVerdict.UNCONFIRMED_UNVERIFIED -> "Результат неизвестен. Нужна новая проверка."
    DtcEraseVerdict.REFUSED -> "Блок отказал в стирании."
    DtcEraseVerdict.NOT_RUN -> "Команда не отправлена."
}

@HiltViewModel
class DtcViewModel @Inject constructor(feature: DtcFeature) : ViewModel() {
    val repository = feature.repository
    val message = MutableStateFlow("")
    val sending = MutableStateFlow(false)
    fun scan() { message.value = ""; repository.start() }
    fun erase(addresses: IntArray) { message.value = ""; repository.startErase(addresses) }
    fun send() {
        if (sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                message.value = when (repository.sendLastScan(RussianDtcLabels).outcome) {
                    DtcSendOutcome.SENT -> "Отчёт отправлен в Telegram."
                    DtcSendOutcome.NO_BOT -> "Настройте Telegram-бота в разделе «Интеграции»."
                    DtcSendOutcome.FAILED -> "Не удалось отправить отчёт. Проверьте сеть и настройки Telegram."
                }
            } finally { sending.value = false }
        }
    }
}

@Composable
fun DtcSection(viewModel: DtcViewModel = hiltViewModel()) {
    val repo = viewModel.repository
    val scan by repo.state.collectAsStateWithLifecycle()
    val saved by repo.last.collectAsStateWithLifecycle()
    val erase by repo.erase.collectAsStateWithLifecycle()
    val erasedAt by repo.erasedAt.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val busy = scan.phase == DtcScanPhase.SCANNING || erase?.phase == DtcErasePhase.ERASING
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<IntArray?>(null) }
    val summary = remember(saved) { saved?.let { DtcParser.summarize(it.results) } }
    var descriptions by remember { mutableStateOf<Map<DtcFound, DtcDescription>>(emptyMap()) }
    LaunchedEffect(saved) { descriptions = summary?.let { repo.describe(it) }.orEmpty() }

    Text("Диагностика ошибок", style = MaterialTheme.typography.titleLarge)
    Text("Чтение кодов блоков автомобиля и сброс с повторной проверкой. Только на стоящей машине в P. Для стирания скорость должна достоверно читаться как 0.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = true; viewModel.scan() }, enabled = !busy) { Text("Сканировать") }
        if (saved != null) OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Результат") }
    }
    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(if (scan.phase == DtcScanPhase.SCANNING) "Сканирование: ${scan.done}/${scan.total}" else "Стирание: ${erase?.done}/${erase?.total}")
    }
    scan.error?.let { Text(dtcErrorText(it)) }
    erase?.error?.let { Text(dtcErrorText(it)) }
    erase?.takeIf { it.phase == DtcErasePhase.ENDED && it.reason.isNotEmpty() && it.error == null }?.let {
        Text(when {
            it.reason.startsWith("speed") -> "Стирание остановлено: не подтверждена нулевая скорость автомобиля."
            it.reason.startsWith("not_park") -> "Стирание остановлено: не подтверждено положение P."
            it.reason.startsWith("diag_busy") || it.reason.contains("foreign") || it.reason.contains("traffic") -> "Стирание остановлено: обнаружена другая диагностическая активность."
            else -> "Стирание завершилось не полностью. Проверьте результаты блоков и выполните новый скан."
        })
    }
    if (message.isNotEmpty()) Text(message)
    val uncertain = saved != null && (erasedAt ?: 0L) >= saved!!.at
    if (uncertain || saved?.eraseLocked?.isNotEmpty() == true) Text("Результат стирания части кодов неизвестен. Показаны последние известные данные — выполните новое сканирование.")
    if (expanded && saved != null && summary != null) {
        Text("Проверено: ${DtcScanRepository.formatShort(saved!!.at)} · Ответили ${summary.answered}/${summary.total} блоков")
        Text("Активные: ${summary.now} · Сохранённые: ${summary.old} · Ожидающие: ${summary.pending}")
        if (summary.answered == 0) Text("Ни один блок не ответил. Отсутствие ошибок не подтверждено.")
        else if (summary.found.isEmpty()) Text(RussianDtcLabels.noErrors)
        if (summary.unreadBlocks > 0) Text(RussianDtcLabels.unread(summary.unreadBlocks))
        val groups = remember(saved) { DtcParser.groups(summary.found) }
        for (group in groups) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(RussianDtcLabels.blockName(group.block) + " · " + group.addrs.joinToString { "0x%X".format(it) }, style = MaterialTheme.typography.titleMedium)
                    for (row in group.rows) {
                        Text(row.code + " · " + when (row.kind) { DtcKind.NOW -> "активная"; DtcKind.OLD -> "сохранённая"; DtcKind.PENDING -> "ожидает подтверждения" })
                        val found = summary.found.firstOrNull { it.addr == group.addrs.first() && it.row == row }
                        descriptions[found]?.let { description ->
                            Text(description.en, style = MaterialTheme.typography.bodySmall)
                            if (description.level == DtcDescLevel.SIMILAR) Text(RussianDtcLabels.similarNote, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(enabled = !busy && !uncertain, onClick = {
                        val addrs = DtcErasePlan.eligibleBlock(saved, group.addrs.first(), System.currentTimeMillis())
                        if (addrs == null || addrs.isEmpty()) viewModel.message.value = "Перед стиранием выполните новое сканирование." else confirm = addrs
                    }) { Text("Стереть в этом блоке") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::send, enabled = !busy && !sending && !uncertain) { Text(if (sending) "Отправка…" else "В Telegram") }
            if (summary.found.isNotEmpty()) OutlinedButton(enabled = !busy && !uncertain, onClick = {
                val addrs = DtcErasePlan.eligible(saved, System.currentTimeMillis())
                if (addrs == null || addrs.isEmpty()) viewModel.message.value = "Перед стиранием выполните новое сканирование." else confirm = addrs
            }) { Text("Стереть все") }
        }
    }
    erase?.takeIf { it.phase == DtcErasePhase.ENDED }?.results?.forEach {
        Text("0x%X: %s".format(it.addr, dtcEraseText(dtcEraseVerdict(it))))
    }
    confirm?.let { addresses ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Стереть сохранённые ошибки?") },
            text = { Text("Будут очищены коды в ${addresses.size} блоках, включая историю и связанные диагностические данные. Это не устраняет неисправность; активные ошибки могут вернуться. Автомобиль должен оставаться в P со скоростью 0. После стирания коды будут прочитаны заново.") },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.erase(addresses) }) { Text("Стереть и проверить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } })
    }
    Spacer(Modifier.height(16.dp))
}
