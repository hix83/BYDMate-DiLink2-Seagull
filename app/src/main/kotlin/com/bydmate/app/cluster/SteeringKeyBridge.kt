package com.bydmate.app.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Same-UID broadcasts bridge the settings process and the :steering accessibility process. */
object SteeringKeyBridge {
    const val START = "com.bydmate.app.steering.LEARN_START"
    const val STOP = "com.bydmate.app.steering.LEARN_STOP"
    const val CAPTURE = "com.bydmate.app.steering.LEARN_CAPTURE"
    const val SETTINGS = "com.bydmate.app.steering.SETTINGS"
    const val SESSION = "session"
    const val KEY = "keycode"
    const val LEARN_TIMEOUT_MS = 10_000L

    fun command(context: Context, action: String, session: String): Intent =
        Intent(action).setPackage(context.packageName).putExtra(SESSION, session)

    fun publishSettings(context: Context) {
        val voice = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val cluster = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        context.sendBroadcast(Intent(SETTINGS).setPackage(context.packageName)
            .putExtra("voice_enabled", voice.getBoolean("voice_enabled", false))
            .putExtra("voice_keycode", voice.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE))
            .putExtra("mirror_enabled", cluster.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false))
            .putExtra("trigger_keycode", cluster.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE)))
    }
}

/** A dialog owns one session. Late captures and stop messages from older dialogs are ignored. */
class SteeringKeyLearningSession(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var token: String? = null
    private var closed = false
    data class Capture(val keyCode: Int, val assignable: Boolean)
    val captured = MutableStateFlow<Capture?>(null)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val session = token ?: return
            if (intent?.action != SteeringKeyBridge.CAPTURE || intent.getStringExtra(SteeringKeyBridge.SESSION) != session) return
            val key = intent.getIntExtra(SteeringKeyBridge.KEY, -1)
            if (key < 0) return
            captured.value = Capture(key, isAssignable(key))
        }
    }
    init {
        ContextCompat.registerReceiver(this.context, receiver, IntentFilter(SteeringKeyBridge.CAPTURE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun start() {
        check(!closed)
        stop()
        captured.value = null
        val session = UUID.randomUUID().toString()
        token = session
        SteeringKeyBridge.publishSettings(context)
        context.sendBroadcast(SteeringKeyBridge.command(context, SteeringKeyBridge.START, session))
    }

    fun stop() {
        token?.let { context.sendBroadcast(SteeringKeyBridge.command(context, SteeringKeyBridge.STOP, it)) }
        token = null
    }

    override fun close() {
        if (closed) return
        stop()
        context.unregisterReceiver(receiver)
        closed = true
    }
}
