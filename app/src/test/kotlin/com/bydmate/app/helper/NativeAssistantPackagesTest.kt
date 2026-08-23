package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAssistantPackagesTest {

    @Test fun `Seagull aispeech-only package is disabled`() {
        val applied = mutableListOf<Pair<String, Boolean>>()

        val ok = setNativeAssistantHiddenCore(
            hidden = 1,
            isInstalled = { it == "com.byd.autovoice.aispeech" },
            apply = { pkg, disabled -> applied += pkg to disabled; true },
        )

        assertTrue(ok)
        assertEquals(listOf("com.byd.autovoice.aispeech" to true), applied)
    }

    @Test fun `Leopard package family is restored together`() {
        val installed = setOf(
            "com.byd.autovoice",
            "com.byd.autovoice.engine",
            "com.byd.autovoice.tts",
        )
        val applied = mutableListOf<Pair<String, Boolean>>()

        val ok = setNativeAssistantHiddenCore(
            hidden = 0,
            isInstalled = { it in installed },
            apply = { pkg, disabled -> applied += pkg to disabled; true },
        )

        assertTrue(ok)
        assertEquals(installed, applied.map { it.first }.toSet())
        assertTrue(applied.all { !it.second })
    }

    @Test fun `invalid flag and absent family are rejected`() {
        assertFalse(setNativeAssistantHiddenCore(2, { true }, { _, _ -> true }))
        assertFalse(setNativeAssistantHiddenCore(1, { false }, { _, _ -> true }))
    }

    @Test fun `one failed installed package fails the operation`() {
        val installed = setOf("com.byd.autovoice", "com.byd.autovoice.engine")
        assertFalse(setNativeAssistantHiddenCore(
            hidden = 1,
            isInstalled = { it in installed },
            apply = { pkg, _ -> pkg != "com.byd.autovoice.engine" },
        ))
    }
}
