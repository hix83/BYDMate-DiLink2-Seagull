package com.bydmate.app.helper.dtc

import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DtcScanParcelTest {
    @Test fun `all 19 Seagull results survive the helper parcel`() {
        val addresses = CanFdDtcReadOnlyProtocol.ADDRESSES
        val results = addresses.map { DtcAddressResult(it, "ok", byteArrayOf(0x59, 1, 9, 0, 0, 0), null, null) }
        val parcel = Parcel.obtain()
        try {
            writeDtcScanStatus(parcel, DtcScanStatus(DtcWire.STATE_DONE, 19, 19, "", results))
            parcel.setDataPosition(0)
            val status = readDtcScanStatus(parcel)!!
            assertEquals(19, status.total)
            assertEquals(19, status.done)
            assertEquals(addresses.toList(), status.results.map { it.addr })
        } finally { parcel.recycle() }
    }

    @Test fun `more results than any supported profile are rejected`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(DtcWire.STATE_DONE); parcel.writeInt(20); parcel.writeInt(20)
            parcel.writeString(""); parcel.writeInt(20)
            parcel.setDataPosition(0)
            assertNull(readDtcScanStatus(parcel))
        } finally { parcel.recycle() }
    }
}
