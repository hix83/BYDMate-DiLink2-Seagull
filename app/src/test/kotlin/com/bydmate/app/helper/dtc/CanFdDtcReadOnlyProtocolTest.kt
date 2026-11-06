package com.bydmate.app.helper.dtc

import com.bydmate.app.data.dtc.DtcParser
import com.bydmate.app.data.dtc.DtcKind
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.*
import org.junit.Test

class CanFdDtcReadOnlyProtocolTest {
    @Test fun `count uses the firmware container without changing UDS`() {
        assertArrayEquals(hex("0107E0031901FF"), CanFdDtcReadOnlyProtocol.encode(DtcWire.countRequest(0x7E0)))
    }

    @Test fun `only fixed supported reads may reach the transport`() {
        val requests = listOf(DtcWire.countRequest(0x712), DtcWire.listRequest(0x720, 0x0D),
            DtcWire.identityRequest(0x7E0), DtcWire.snapshotRequest(0x7E1, 0x123456))
        requests.forEach { request ->
            val encoded = CanFdDtcReadOnlyProtocol.encode(request)
            assertEquals(1.toByte(), encoded[0])
            assertArrayEquals(request.copyOfRange(1, request.size), encoded.copyOfRange(1, encoded.size))
        }
        listOf("0A07E00414FFFFFF", "0A07E003100100", "0A07E003110100", "0A07E003270100",
            "0A07E003220100", "0A07E003190300", "0A07E003190200", "0A0700031901FF",
            "0107E0031901FF", "0A07E0041901FF").forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { CanFdDtcReadOnlyProtocol.encode(hex(raw)) }
        }
    }

    @Test fun `live padded 7E0 response is a count of zero only for that block`() {
        val raw = hex("0107E806010101590109000000").copyOf(512)
        val match = DtcWire.match(CanFdDtcReadOnlyProtocol.normalize(raw), 0x7E0, 0x19, 1)
        assertTrue(match is DtcMatch.Answer)
        assertEquals(0, DtcParser.parseCount((match as DtcMatch.Answer).uds)!!.count)
        assertEquals(DtcMatch.NotMine, DtcWire.match(CanFdDtcReadOnlyProtocol.normalize(raw), 0x7E1, 0x19, 1))
    }

    @Test fun `modern or unknown prefix cannot masquerade as a legacy reply`() {
        for (prefix in listOf(0, 2, 10)) {
            val frame = hex("0107E806010101590109000000").also { it[0] = prefix.toByte() }
            assertEquals(DtcMatch.NotMine, DtcWire.match(CanFdDtcReadOnlyProtocol.normalize(frame), 0x7E0, 0x19, 1))
        }
    }

    @Test fun `unknown or active OTA always refuses reads`() {
        assertNull(CanFdDtcReadOnlyProtocol.gate(0, "false"))
        for (state in listOf(null, -1, 1, -10011)) assertNotNull(CanFdDtcReadOnlyProtocol.gate(state, "false"))
        for (flag in listOf(null, "", "true")) assertNotNull(CanFdDtcReadOnlyProtocol.gate(0, flag))
    }

    @Test fun `real Seagull saved fault is decoded without declaring missing ECUs healthy`() {
        val results = DtcWire.ADDRESSES.map { addr ->
            when (addr) {
                0x720 -> DtcAddressResult(addr, DtcWire.OUTCOME_OK, hex("5901FF000001"),
                    hex("59020DAA4B9208"), hex("62F1944B310011ABF0001103"))
                0x726, 0x782, 0x783, 0x7E0, 0x7F1 -> DtcAddressResult(addr,
                    DtcWire.OUTCOME_OK, hex("590109000000"), null, null)
                else -> DtcAddressResult(addr, DtcWire.OUTCOME_GW_TIMEOUT, null, null, null)
            }
        }
        val summary = DtcParser.summarize(results)
        assertEquals(6, summary.answered)
        assertEquals(18, summary.total)
        assertEquals(0, summary.now)
        assertEquals(1, summary.old)
        assertEquals(0, summary.pending)
        assertEquals("B2A4B-92", summary.found.single().row.code)
        assertEquals(DtcKind.OLD, summary.found.single().row.kind)
    }

    @Test fun `discovered 741 codes are active confirmed network faults`() {
        val count = DtcParser.parseCount(hex("5901FF000002"))!!
        val list = DtcParser.parseList(hex("59020D9AB70009C2478709"), count.format)
        assertEquals(2, count.count)
        assertEquals(listOf("B1AB7-00", "U0247-87"), list.rows.map { it.code })
        assertTrue(list.rows.all { it.kind == DtcKind.NOW && it.status == 9 })
    }

    @Test fun `766 short count stays unknown but its separate list is readable`() {
        assertNull(DtcParser.parseCount(hex("5901FF0000")))
        val list = DtcParser.parseList(hex("59020D"), null)
        assertFalse(list.negative)
        assertEquals(0, list.records)
        assertTrue(list.rows.isEmpty())
    }
}
