package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcCompressorUiStateTest {
    @Test
    fun `reads selected compressor and center from stock climate hierarchy`() {
        val xml = """
            <hierarchy>
              <node resource-id="com.byd.airconditioning:id/ac_compressor_id"
                    class="android.widget.ImageView" selected="true"
                    bounds="[430,107][530,155]" />
            </hierarchy>
        """.trimIndent()

        val state = parseAcCompressorUiState(xml)!!

        assertTrue(state.selected)
        assertEquals(480, state.centerX)
        assertEquals(131, state.centerY)
    }

    @Test
    fun `reads compressor off independently of attribute order`() {
        val xml = """
            <node selected="false" bounds="[10,20][30,60]"
                  resource-id="com.byd.airconditioning:id/ac_compressor_id" />
        """.trimIndent()

        val state = parseAcCompressorUiState(xml)!!

        assertFalse(state.selected)
        assertEquals(20, state.centerX)
        assertEquals(40, state.centerY)
    }

    @Test
    fun `rejects a hierarchy without the exact compressor resource`() {
        assertNull(
            parseAcCompressorUiState(
                """<node resource-id="com.byd.airconditioning:id/electric_ac_power_id" """ +
                    """selected="true" bounds="[1,1][2,2]" />"""
            )
        )
    }
}
