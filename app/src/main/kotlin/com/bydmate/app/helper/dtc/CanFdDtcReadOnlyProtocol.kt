package com.bydmate.app.helper.dtc

/** Old CANFD container, confirmed in stock OTA bytecode and a live 0x7E0 DTC-count reply. */
object CanFdDtcReadOnlyProtocol {
    fun encode(request: ByteArray): ByteArray {
        require(request.size >= 7 && request[0] == 0x0A.toByte()) { "invalid diagnostic request" }
        val addr = ((request[1].toInt() and 255) shl 8) or (request[2].toInt() and 255)
        require(addr in DtcWire.ADDRESSES && (request[3].toInt() and 255) == request.size - 4)
        val sid = request[4].toInt() and 255
        val sub = request[5].toInt() and 255
        val allowed = when (sid) {
            0x19 -> when (sub) {
                1 -> request.size == 7 && request[6] == 0xFF.toByte()
                2 -> request.size == 7 && (request[6].toInt() and 255) in DtcWire.LIST_MASKS
                4 -> request.size == 10 && request[9] == 0xFF.toByte()
                else -> false
            }
            0x22 -> request.size == 7 && sub == 0xF1 && (request[6].toInt() and 255) in listOf(0x94, 0x97)
            else -> false
        }
        require(allowed) { "only whitelisted DTC/identity reads are allowed" }
        return request.copyOf().also { it[0] = 1 }
    }

    /** Unknown containers must remain foreign, never accidentally match the modern prefix. */
    fun normalize(frame: ByteArray): ByteArray = frame.copyOf().also {
        if (it.isNotEmpty()) it[0] = if (frame[0] == 1.toByte()) 0x0A else 0
    }

    fun gate(otaState: Int?, updateFlag: String?): String? = when {
        otaState == null || otaState != 0 -> "ota_not_idle state=$otaState"
        updateFlag != "false" -> "ota_not_idle flag=$updateFlag"
        else -> null
    }
}

class CanFdReadOnlyDevice(private val delegate: DtcDevice) : DtcDevice {
    override fun register(): String? = delegate.register()
    override fun unregister() = delegate.unregister()
    override fun send(payload: ByteArray): Int = delegate.send(CanFdDtcReadOnlyProtocol.encode(payload))
    override fun poll(timeoutMs: Long): ByteArray? = delegate.poll(timeoutMs)?.let(CanFdDtcReadOnlyProtocol::normalize)
    override fun drain() = delegate.drain()
}
