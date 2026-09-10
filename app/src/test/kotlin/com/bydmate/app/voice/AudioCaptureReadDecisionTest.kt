package com.bydmate.app.voice

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `only a granted exclusive focus result may open the microphone`() {
        assertTrue(audioFocusGranted(AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
        assertFalse(audioFocusGranted(AudioManager.AUDIOFOCUS_REQUEST_FAILED))
        assertFalse(audioFocusGranted(AudioManager.AUDIOFOCUS_REQUEST_DELAYED))
    }

    @Test fun `capture closes on real focus loss but ignores duck and gain callbacks`() {
        assertTrue(shouldStopCaptureForFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(shouldStopCaptureForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertFalse(shouldStopCaptureForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(shouldStopCaptureForFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }
}
