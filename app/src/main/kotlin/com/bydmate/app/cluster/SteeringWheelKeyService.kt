package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.data.autoservice.ACTION_LEGACY_ADB_UI_PULSE
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.EntryPointAccessors
import androidx.core.content.ContextCompat

/**
 * Steering-wheel key filter for cluster projection. When the settings master switch
 * ([ClusterProjectionManager.KEY_MIRROR_ENABLED]) is ON, a short press of the configured trigger
 * button (keycode in [ClusterProjectionManager.KEY_TRIGGER_KEYCODE], default
 * [DEFAULT_TRIGGER_KEYCODE] = the right star) toggles Yandex Navi between the cluster and the centre
 * screen and is consumed; every other key (switch OFF, a non-trigger button, the right-star
 * long-press that opens the native action menu) passes through untouched.
 *
 * Inert unless enabled in secure settings (self-enabled via the daemon when the switch turns on —
 * no Accessibility UI on DiLink) AND the settings switch is on, so it does nothing for users who
 * never opt in.
 */
class SteeringWheelKeyService : AccessibilityService() {

    private var cachedEntryPoint: ClusterEntryPoint? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var legacyAdbReceiverRegistered = false
    private var keyBridgeRegistered = false
    private var learnSession: String? = null
    private var capturedUpKey: Int? = null
    private val expireCapturedKey = Runnable { capturedUpKey = null }
    private var voiceEnabled = false
    private var voiceKey = DEFAULT_VOICE_KEYCODE
    private var projectionEnabled = false
    private var projectionKey = DEFAULT_TRIGGER_KEYCODE
    private val expireLearning = Runnable { learnSession = null }
    private val keyBridge = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SteeringKeyBridge.START -> {
                    val session = intent.getStringExtra(SteeringKeyBridge.SESSION) ?: return
                    if (session.length != 36) return
                    learnSession = session
                    renewLearningTimeout()
                    Log.i(TAG, "learn armed")
                }
                SteeringKeyBridge.STOP -> if (intent.getStringExtra(SteeringKeyBridge.SESSION) == learnSession) clearLearning()
                SteeringKeyBridge.SETTINGS -> {
                    voiceEnabled = intent.getBooleanExtra("voice_enabled", false)
                    voiceKey = intent.getIntExtra("voice_keycode", DEFAULT_VOICE_KEYCODE)
                    projectionEnabled = intent.getBooleanExtra("mirror_enabled", false)
                    projectionKey = intent.getIntExtra("trigger_keycode", DEFAULT_TRIGGER_KEYCODE)
                    Log.i(TAG, "key settings received voice=$voiceKey projection=$projectionKey")
                }
            }
        }
    }

    private fun renewLearningTimeout() {
        mainHandler.removeCallbacks(expireLearning)
        mainHandler.postDelayed(expireLearning, SteeringKeyBridge.LEARN_TIMEOUT_MS)
    }

    private fun clearLearning() {
        learnSession = null
        mainHandler.removeCallbacks(expireLearning)
    }
    private val legacyAdbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_LEGACY_ADB_UI_PULSE) pulseLegacyAdbSwitch(attempt = 0)
        }
    }
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS  // 32
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        // Windows-aware reads: the Navigator projected to the instrument cluster lives on
        // display 2 and is invisible to rootInActiveWindow; these flags open getWindows()/
        // getWindowsOnAllDisplays(). Not-important views: some guidance widgets are marked
        // not-important-for-accessibility on the 2026 Navigator build (Codex fix 5).
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        if (!keyBridgeRegistered) {
            val filter = IntentFilter(SteeringKeyBridge.START).apply {
                addAction(SteeringKeyBridge.STOP); addAction(SteeringKeyBridge.SETTINGS)
            }
            ContextCompat.registerReceiver(this, keyBridge, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            keyBridgeRegistered = true
        }
        val voice = getSharedPreferences("voice", Context.MODE_PRIVATE)
        voiceEnabled = voice.getBoolean("voice_enabled", false)
        voiceKey = voice.getInt("voice_keycode", DEFAULT_VOICE_KEYCODE)
        projectionEnabled = prefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false)
        projectionKey = prefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE)
        if (!legacyAdbReceiverRegistered) {
            registerReceiver(legacyAdbReceiver, IntentFilter(ACTION_LEGACY_ADB_UI_PULSE))
            legacyAdbReceiverRegistered = true
        }
        instance = this
        isConnected = true
        // This service runs in :steering, so its companion fields are invisible to the main
        // process. Report the real framework bind across the existing explicit service bridge;
        // TrackingService uses this signal instead of mistaking the enabled-settings list for
        // a live AccessibilityService.
        TrackingService.reportSteeringA11yState(applicationContext, connected = true)
        Log.d(TAG, "connected; filtering steering-wheel keys")
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (capturedUpKey == event.keyCode) {
            if (!isDown) capturedUpKey = null
            return true
        }
        val session = learnSession
        if (session != null) {
            return when (learnDecision(event.keyCode, isDown)) {
                LearnAction.CAPTURE -> {
                    sendBroadcast(SteeringKeyBridge.command(this, SteeringKeyBridge.CAPTURE, session).putExtra(SteeringKeyBridge.KEY, event.keyCode))
                    capturedUpKey = event.keyCode
                    mainHandler.removeCallbacks(expireCapturedKey)
                    mainHandler.postDelayed(expireCapturedKey, 2_000L)
                    clearLearning()
                    Log.i(TAG, "learn captured code=${event.keyCode}")
                    true
                }
                LearnAction.REJECT -> {
                    sendBroadcast(SteeringKeyBridge.command(this, SteeringKeyBridge.CAPTURE, session).putExtra(SteeringKeyBridge.KEY, event.keyCode))
                    renewLearningTimeout()
                    true  // stay in learn mode; dialog shows "can't assign", waits for another key
                }
                LearnAction.CONSUME -> true
            }
        }
        // Voice check: runs after learn-mode, before star decision. Returns true only when voice is
        // enabled and the configured voice key is pressed (isDown). Non-voice keys fall through.
        when (voiceDecision(event.keyCode, isDown, voiceEnabled, voiceKey, event.repeatCount)) {
            VoiceKeyDecision.TRIGGER -> {
                Log.i(
                    TAG,
                    "voice key: code=${event.keyCode} action=${event.action} repeat=${event.repeatCount}",
                )
                // This accessibility service lives in :steering so the DiLink 2 framework
                // can bind it reliably. Never construct/run VoiceController here: process
                // statics such as TrackingService.lastData would be a separate empty copy.
                // Route the press to the already-running main-process service instead.
                TrackingService.requestVoicePtt(applicationContext)
                return true
            }
            // Swallow the matching key's UP edge too — otherwise it falls through to the
            // native BYD assistant, which owns the same hardware keycode (Finding 2).
            VoiceKeyDecision.CONSUME -> return true
            VoiceKeyDecision.IGNORE -> {}
        }
        return when (starDecision(event.keyCode, isDown, projectionEnabled, projectionKey)) {
            StarDecision.CONSUME_AND_TOGGLE -> {
                val ep = entryPoint()
                ClusterProjectionManager.toggle(applicationContext, ep.helperClient(), ep.helperBootstrap())
                true
            }
            StarDecision.CONSUME -> true
            StarDecision.PASS_THROUGH -> false
        }
    }

    private fun entryPoint(): ClusterEntryPoint =
        cachedEntryPoint ?: EntryPointAccessors
            .fromApplication(applicationContext, ClusterEntryPoint::class.java)
            .also { cachedEntryPoint = it }

    /** Root of the Navigator window wherever it lives: the active window, a minimized
     *  mini-window, or the instrument cluster (display 2, projection mode). Caller must
     *  recycle the returned node. Null when the Navigator has no window anywhere. */
    fun findNavigatorRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            if (active.packageName?.toString() in com.bydmate.app.navdata.NavPackages.GUIDANCE_SOURCES) return active
            @Suppress("DEPRECATION") runCatching { active.recycle() }
        }
        val windowList = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val byDisplay = windowsOnAllDisplays
                (0 until byDisplay.size()).flatMap { byDisplay.valueAt(it) }
            } else windows
        }.getOrNull() ?: return null
        for (window in windowList) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (root.packageName?.toString() in com.bydmate.app.navdata.NavPackages.GUIDANCE_SOURCES) return root
            @Suppress("DEPRECATION") runCatching { root.recycle() }
        }
        return null
    }

    // Single volatile read when the HUD feature is off - see NavA11yFeed.enabled.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        NavA11yFeed.onEvent(this, event)
    }
    override fun onInterrupt() { /* no-op */ }

    /**
     * DiLink 2 restricts the property that starts TCP ADB to its system-signed development app.
     * Once its screen is active, accessibility performs the same OFF -> ON pulse the user would
     * otherwise have to do by hand. View IDs keep this independent of the firmware language.
     */
    private fun pulseLegacyAdbSwitch(attempt: Int) {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val activePackage = root?.packageName?.toString()
        val switch = root?.let(::findLegacyAdbSwitch)
        if (activePackage != LEGACY_ADB_PACKAGE || switch == null) {
            if (activePackage == LEGACY_ADB_PACKAGE && !isTestToolsExpanded(root)) {
                val header = findTestToolsHeader(root)
                val expanded = header?.let(::clickNodeOrParent) == true
                Log.i(TAG, "legacy ADB TestTools expand: success=$expanded")
                @Suppress("DEPRECATION") runCatching { header?.recycle() }
                if (expanded) {
                    @Suppress("DEPRECATION") runCatching { root.recycle() }
                    mainHandler.postDelayed(
                        { pulseLegacyAdbSwitch(attempt + 1) },
                        LEGACY_UI_EXPAND_DELAY_MS,
                    )
                    return
                }
            }
            @Suppress("DEPRECATION") runCatching { root?.recycle() }
            if (attempt < LEGACY_UI_FIND_RETRIES) {
                mainHandler.postDelayed(
                    { pulseLegacyAdbSwitch(attempt + 1) },
                    LEGACY_UI_FIND_RETRY_MS,
                )
            } else {
                Log.w(TAG, "legacy ADB switch not found; activePackage=$activePackage")
            }
            return
        }

        val wasChecked = switch.isChecked
        val firstClick = clickNodeOrParent(switch)
        Log.i(TAG, "legacy ADB switch first click: checked=$wasChecked success=$firstClick")
        @Suppress("DEPRECATION") runCatching { switch.recycle() }
        @Suppress("DEPRECATION") runCatching { root.recycle() }
        if (!firstClick) return

        if (wasChecked) {
            mainHandler.postDelayed(
                {
                    val refreshedRoot = runCatching { rootInActiveWindow }.getOrNull()
                    val refreshedSwitch = refreshedRoot?.let(::findLegacyAdbSwitch)
                    val secondClick = refreshedSwitch?.let(::clickNodeOrParent) == true
                    Log.i(TAG, "legacy ADB switch second click: success=$secondClick")
                    @Suppress("DEPRECATION") runCatching { refreshedSwitch?.recycle() }
                    @Suppress("DEPRECATION") runCatching { refreshedRoot?.recycle() }
                    if (secondClick) returnFromLegacyAdbScreen()
                },
                LEGACY_UI_PULSE_GAP_MS,
            )
        } else {
            returnFromLegacyAdbScreen()
        }
    }

    private fun findLegacyAdbSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in LEGACY_ADB_SWITCH_IDS) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                .getOrNull()
                .orEmpty()
            nodes.firstOrNull {
                it.isCheckable || it.className?.toString()?.contains("Switch") == true
            }?.let { return it }
        }
        return null
    }

    private fun isTestToolsExpanded(root: AccessibilityNodeInfo): Boolean =
        runCatching {
            root.findAccessibilityNodeInfosByViewId(LEGACY_TEST_TOOLS_CONTENT_ID).isNotEmpty()
        }.getOrDefault(false)

    private fun findTestToolsHeader(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        runCatching { root.findAccessibilityNodeInfosByText(LEGACY_TEST_TOOLS_TITLE) }
            .getOrNull()
            .orEmpty()
            .firstOrNull { it.text?.toString() == LEGACY_TEST_TOOLS_TITLE }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(3) {
            if (candidate?.isClickable == true &&
                candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            ) return true
            candidate = candidate?.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun returnFromLegacyAdbScreen() {
        mainHandler.postDelayed(
            { performGlobalAction(GLOBAL_ACTION_BACK) },
            LEGACY_UI_RETURN_DELAY_MS,
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterLegacyAdbReceiver()
        instance = null
        isConnected = false
        TrackingService.reportSteeringA11yState(applicationContext, connected = false)
        Log.d(TAG, "unbound; star key filter inactive")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterLegacyAdbReceiver()
        instance = null
        isConnected = false
        TrackingService.reportSteeringA11yState(applicationContext, connected = false)
        super.onDestroy()
    }

    private fun unregisterLegacyAdbReceiver() {
        clearLearning()
        capturedUpKey = null
        mainHandler.removeCallbacks(expireCapturedKey)
        if (keyBridgeRegistered) {
            runCatching { unregisterReceiver(keyBridge) }
            keyBridgeRegistered = false
        }
        if (!legacyAdbReceiverRegistered) return
        runCatching { unregisterReceiver(legacyAdbReceiver) }
        legacyAdbReceiverRegistered = false
    }

    companion object {
        const val TAG = "SteeringWheelKeySvc"
        private const val LEGACY_ADB_PACKAGE = "com.byd.byddevelopmenttools"
        private val LEGACY_ADB_SWITCH_IDS = arrayOf(
            "$LEGACY_ADB_PACKAGE:id/wifi_adb_setting",
            "$LEGACY_ADB_PACKAGE:id/w2_wifi_adb_sw",
        )
        private const val LEGACY_TEST_TOOLS_CONTENT_ID =
            "$LEGACY_ADB_PACKAGE:id/test_tools_layout_id"
        private const val LEGACY_TEST_TOOLS_TITLE = "TestTools"
        private const val LEGACY_UI_FIND_RETRIES = 8
        private const val LEGACY_UI_FIND_RETRY_MS = 250L
        private const val LEGACY_UI_EXPAND_DELAY_MS = 400L
        private const val LEGACY_UI_PULSE_GAP_MS = 350L
        private const val LEGACY_UI_RETURN_DELAY_MS = 500L

        /**
         * True liveness signal. Set when the framework actually binds + connects this service (the
         * only moment the key filter is live), cleared on unbind/destroy. The Secure-settings
         * enabled-list is NOT reliable across boot/wake, so the re-assert watchdog in TrackingService
         * gates on THIS first. Mirrors OpenBYD's SteeringWheelAccessibilityService.isConnected.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        /** Live service instance for on-demand window reads (NaviScreenReader). Set on
         *  connect, cleared on unbind/destroy - callers must handle null. */
        @Volatile
        var instance: SteeringWheelKeyService? = null
            private set

    }
}
