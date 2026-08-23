package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCaptureReadDecisionTest {
    @Test fun `positive read emits audio`() {
        assertEquals(AudioReadDecision.EMIT, audioReadDecision(1600))
    }

    @Test fun `zero read retries without emitting`() {
        assertEquals(AudioReadDecision.RETRY, audioReadDecision(0))
    }

    @Test fun `negative AudioRecord result stops capture`() {
        assertEquals(AudioReadDecision.STOP, audioReadDecision(-38))
        assertEquals(AudioReadDecision.STOP, audioReadDecision(-3))
    }
}
