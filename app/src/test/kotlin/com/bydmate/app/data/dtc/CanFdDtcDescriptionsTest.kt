package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CanFdDtcDescriptionsTest {
    private fun summary() = DtcParser.summarize(listOf(
        DtcAddressResult(0x741, "ok", hex("5901FF000002"), hex("59020D9AB70009C2478709"), hex("62F1944A550011AB93002600")),
        DtcAddressResult(0x720, "ok", hex("5901FF000001"), hex("59020DAA4B9208"), hex("62F1944B310011ABF0001103")),
    ))

    @Test fun `model 148 actual faults receive Russian reference wording in the Telegram report`() = runBlocking {
        val describer = DtcDescriber(
            open = { File("src/main/assets/dtc/$it").reader() }, autoType = { 148 }, energy = { DtcEnergy.EV },
            dispatcher = Dispatchers.Unconfined, reference = { CanFdDtcDescriptions.lookup(it, true) },
        )
        val summary = summary()
        val descriptions = describer.describe(summary.found)
        assertEquals(3, descriptions.size)
        assertTrue(descriptions.values.all { it.level == DtcDescLevel.SIMILAR && !it.ru.isNullOrBlank() })
        val text = DtcTelegramText.build(summary, "now", com.bydmate.app.ui.settings.RussianDtcLabels, { descriptions[it] })
        for (description in descriptions.values) assertTrue(text, text.contains(description.ru!!))
        assertTrue(text.contains(com.bydmate.app.ui.settings.RussianDtcLabels.similarNote))
        assertEquals(2, summary.now)
        assertEquals(1, summary.old)
    }

    @Test fun `reference wording is not attached to other firmware addresses or codes`() {
        for (found in summary().found) {
            assertNull(CanFdDtcDescriptions.lookup(found, false))
            assertNull(CanFdDtcDescriptions.lookup(found.copy(addr = 0x715), true))
            assertNull(CanFdDtcDescriptions.lookup(found.copy(row = found.row.copy(code = "P0000-00")), true))
        }
    }

    @Test fun `reference English wording and code associations exist in shipped BYD tables`() {
        val texts = File("src/main/assets/dtc/texts.tsv").readLines().associate { line ->
            val fields = line.split('\t'); fields[0] to fields[2]
        }
        val rows = File("src/main/assets/dtc/rows.tsv").readLines().map { it.split('\t') }
        for (found in summary().found) {
            val description = CanFdDtcDescriptions.lookup(found, true)!!
            assertTrue(rows.any { it[2] == found.row.code.replace("-", "") && texts[it[3]] == description.en })
        }
    }
}
