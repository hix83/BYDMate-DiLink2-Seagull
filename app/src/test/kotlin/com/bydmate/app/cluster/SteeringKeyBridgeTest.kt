package com.bydmate.app.cluster

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SteeringKeyBridgeTest {
    private lateinit var app: Application
    private lateinit var service: SteeringWheelKeyService
    private val sessions = mutableListOf<SteeringKeyLearningSession>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun session() = SteeringKeyLearningSession(app).also { sessions += it }

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        // Robolectric does not auto-grant the merged signature permission used by ContextCompat on API 28.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        service = Robolectric.buildService(SteeringWheelKeyService::class.java).create().get()
        service.onServiceConnected()
        idle()
    }

    @After fun cleanup() {
        sessions.forEach { it.close() }
        service.onDestroy()
        idle()
    }

    @Test fun `dialog commands arm service and capture returns through broadcasts`() {
        val session = session()
        session.start(); idle()
        assertTrue(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)))
        idle()
        assertEquals(SteeringKeyLearningSession.Capture(321, true), session.captured.value)
        assertTrue(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 321)))
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)))
    }

    @Test fun `blocked button stays in learning so next assignable press is captured`() {
        val session = session()
        session.start(); idle()
        service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 24)); idle()
        assertEquals(SteeringKeyLearningSession.Capture(24, false), session.captured.value)
        service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)); idle()
        assertEquals(SteeringKeyLearningSession.Capture(321, true), session.captured.value)
    }

    @Test fun `cancel and expiry release the filter without capturing or triggering an action`() {
        val session = session()
        session.start(); idle(); session.stop(); idle()
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)))
        session.start(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_001))
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)))
        assertNull(session.captured.value)
    }

    @Test fun `old session cancellation cannot stop a later dialog`() {
        val old = session(); val newer = session()
        old.start(); idle(); newer.start(); idle(); old.close(); idle()
        service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)); idle()
        assertEquals(321, newer.captured.value?.keyCode)
        assertNull(old.captured.value)
    }

    @Test fun `captured button without an UP cannot remain swallowed indefinitely`() {
        val session = session(); session.start(); idle()
        service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_001))
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)))
    }

    @Test fun `new voice binding reaches already running service without restarting it`() {
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 321)))
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putBoolean("voice_enabled", true).putInt("voice_keycode", 321).apply()
        SteeringKeyBridge.publishSettings(app); idle()
        assertTrue(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 321)))
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, DEFAULT_VOICE_KEYCODE)))
    }

    @Test fun `stale capture from earlier session is ignored after retry`() {
        val session = session(); session.start(); idle()
        val oldToken = shadowOf(app).broadcastIntents.last { it.action == SteeringKeyBridge.START }
            .getStringExtra(SteeringKeyBridge.SESSION)!!
        session.start(); idle()
        app.sendBroadcast(SteeringKeyBridge.command(app, SteeringKeyBridge.CAPTURE, oldToken).putExtra(SteeringKeyBridge.KEY, 321))
        idle(); assertNull(session.captured.value)
        service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, 321)); idle()
        assertEquals(321, session.captured.value?.keyCode)
    }

    @Test fun `new projection binding reaches running service without prefs reload`() {
        assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 355)))
        app.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, true)
            .putInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, 355).apply()
        SteeringKeyBridge.publishSettings(app); idle()
        assertTrue(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, 355)))
    }
}
