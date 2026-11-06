package com.bydmate.app.helper.dtc

import android.os.Parcel
import android.os.IBinder

/** A cached firmware report, NEVER an ECU scan result or a count of faults. */
enum class CacheReadStatus { EMPTY, UNDECODED, UNAVAILABLE, NOT_PARK, UNSUPPORTED_FIRMWARE }

data class CanFdCacheRead(val status: CacheReadStatus, val bytes: ByteArray = byteArrayOf(), val nativeStatus: Int? = null)

object CanFdDiagnosticCache {
    const val MAX_BYTES = 4096
    const val DEVICE = 1032
    const val FAULT_CACHE_FID = 815
    const val GET_BUFFER_TRANSACTION = 13

    /** Exact wire layout verified in libbydauto.so; no caller-supplied device, FID or transaction. */
    fun readNative(svc: IBinder, descriptor: String): Pair<Int, ByteArray?>? {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(descriptor)
            request.writeInt(DEVICE)
            request.writeInt(FAULT_CACHE_FID)
            if (!svc.transact(GET_BUFFER_TRANSACTION, request, response, 0) || response.dataAvail() < 8) null
            else response.readInt() to readBoundedBytes(response)
        } finally { request.recycle(); response.recycle() }
    }

    /** Exact inspected firmware only. A missing manager guard alone never enables a fallback. */
    fun supports(property: (String) -> String?): Boolean =
        property("ro.hardware") == "mt6765" &&
            property("ro.product.device") == "k65v1_64_bsp" &&
            property("ro.car.protocol") == "CANFD" &&
            property("sys.tcp_client_ver") == "mtk_2.1h.202312130_canfd"

    /** No write seam: the only possible operation is the inspected cached getBuffer. */
    fun read(supported: Boolean, gear: Int?, getBuffer: () -> Pair<Int, ByteArray?>?): CanFdCacheRead {
        if (!supported) return CanFdCacheRead(CacheReadStatus.UNSUPPORTED_FIRMWARE)
        if (gear != 1) return CanFdCacheRead(CacheReadStatus.NOT_PARK)
        val reply = runCatching { getBuffer() }.getOrNull()
            ?: return CanFdCacheRead(CacheReadStatus.UNAVAILABLE)
        val bytes = reply.second
        if (reply.first != 0 || bytes == null || bytes.size > MAX_BYTES || isSentinel(bytes)) {
            return CanFdCacheRead(CacheReadStatus.UNAVAILABLE, nativeStatus = reply.first)
        }
        return CanFdCacheRead(if (bytes.isEmpty()) CacheReadStatus.EMPTY else CacheReadStatus.UNDECODED, bytes.copyOf(), reply.first)
    }

    // SDK failures may be returned as a four-byte value instead of a buffer.
    private fun isSentinel(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val be = bytes.fold(0) { value, b -> (value shl 8) or (b.toInt() and 255) }
        val le = Integer.reverseBytes(be)
        return be in -2147482648..-2147482548 || le in -2147482648..-2147482548 ||
            be in -10099..-10000 || le in -10099..-10000
    }

    fun writeReply(p: Parcel, result: CanFdCacheRead) {
        p.writeInt(result.status.ordinal)
        p.writeInt(result.nativeStatus ?: Int.MIN_VALUE)
        p.writeByteArray(result.bytes)
    }

    fun readReply(p: Parcel): CanFdCacheRead? {
        if (p.dataAvail() < 12) return null
        val status = CacheReadStatus.entries.getOrNull(p.readInt()) ?: return null
        val native = p.readInt().takeUnless { it == Int.MIN_VALUE }
        val bytes = readBoundedBytes(p) ?: return null
        if (status != CacheReadStatus.UNDECODED && bytes.isNotEmpty()) return null
        if (status == CacheReadStatus.UNDECODED && bytes.isEmpty()) return null
        return CanFdCacheRead(status, bytes, native)
    }

    /** Check the declared length BEFORE Parcel allocates; Android byte arrays use padded bytes. */
    fun readBoundedBytes(p: Parcel): ByteArray? {
        if (p.dataAvail() < 4) return null
        val position = p.dataPosition()
        val size = p.readInt()
        if (size !in 0..MAX_BYTES || p.dataAvail() < (size + 3) / 4 * 4) return null
        p.setDataPosition(position)
        return p.createByteArray()
    }
}
