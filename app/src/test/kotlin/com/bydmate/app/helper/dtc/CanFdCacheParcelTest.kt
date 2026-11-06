package com.bydmate.app.helper.dtc

import android.os.Parcel
import android.os.IBinder
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CanFdCacheParcelTest {
    @Test fun `native request is a fixed GET of the inspected cache with no writes`() {
        val binder = mockk<IBinder>()
        every { binder.transact(any(), any(), any(), any()) } answers {
            assertEquals(13, firstArg<Int>())
            val request = secondArg<Parcel>(); request.setDataPosition(0)
            request.enforceInterface("android.gui.BYDAutoServer")
            assertEquals(1032, request.readInt()); assertEquals(815, request.readInt())
            assertEquals(0, request.dataAvail())
            val reply = thirdArg<Parcel>()
            reply.writeInt(0); reply.writeByteArray(byteArrayOf()); reply.setDataPosition(0)
            true
        }
        val result = CanFdDiagnosticCache.readNative(binder, "android.gui.BYDAutoServer")!!
        assertEquals(0, result.first); assertArrayEquals(byteArrayOf(), result.second)
        verify(exactly = 1) { binder.transact(13, any(), any(), 0) }
    }
    @Test fun `reply round trip preserves cached bytes and native status`() {
        val p = Parcel.obtain()
        try {
            CanFdDiagnosticCache.writeReply(p, CanFdCacheRead(CacheReadStatus.UNDECODED, byteArrayOf(1, 2, 3), 0))
            p.setDataPosition(0)
            val result = CanFdDiagnosticCache.readReply(p)!!
            assertEquals(CacheReadStatus.UNDECODED, result.status)
            assertEquals(0, result.nativeStatus)
            assertArrayEquals(byteArrayOf(1, 2, 3), result.bytes)
        } finally { p.recycle() }
    }
    @Test fun `short oversized null and inconsistent replies are refused before allocation`() {
        for (size in listOf(-1, 4097, Int.MAX_VALUE)) {
            val p = Parcel.obtain()
            try { p.writeInt(size); p.setDataPosition(0); assertNull(CanFdDiagnosticCache.readBoundedBytes(p)) }
            finally { p.recycle() }
        }
        val p = Parcel.obtain()
        try {
            p.writeInt(CacheReadStatus.EMPTY.ordinal); p.writeInt(0); p.writeByteArray(byteArrayOf(1))
            p.setDataPosition(0); assertNull(CanFdDiagnosticCache.readReply(p))
        } finally { p.recycle() }
    }
}
