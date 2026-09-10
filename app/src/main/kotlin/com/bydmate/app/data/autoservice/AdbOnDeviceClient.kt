package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import com.bydmate.app.helper.parseAcCompressorUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to the on-device ADB daemon at 127.0.0.1:5555 (DiLink has WiFi
 * ADB enabled in dev settings) using a persistent RSA keypair stored in
 * `filesDir/adb_keys/`. Once paired, exposes `exec(cmd)` for one-shot
 * shell commands.
 *
 * Why on-device ADB? `service call autoservice ...` requires either system
 * UID, hidden API access, or shell UID. BYDMate runs as a normal app —
 * ADB shell uid is the only path. See `reference_adb_on_device_pattern.md`.
 *
 * Implementation: hand-rolled binary ADB protocol in [AdbProtocolClient]
 * (no external deps, no `adblib`). Auth flow uses standard RSA pubkey on
 * port 5555 — no TLS pairing / 6-digit code, the user accepts the «Allow USB
 * debugging?» dialog directly on DiLink.
 */
interface AdbOnDeviceClient {
    /** Initiates the ADB handshake. Suspends until handshake completes or fails. */
    suspend fun connect(): Result<Unit>
    suspend fun isConnected(): Boolean
    /** Executes a one-shot shell command and returns stdout, or null on failure. */
    suspend fun exec(cmd: String): String?
    /**
     * Grants PACKAGE_USAGE_STATS appop to our own package via shell uid. The
     * only non-autoservice write we permit through this client — needed so
     * UsageStatsManager.queryEvents returns data for camera-overlay detection.
     * No-op effect if already granted. Returns true on success.
     */
    suspend fun grantUsageStatsAppop(packageName: String): Boolean

    /** Grants the app permission needed to toggle Android wireless debugging after reboot. */
    suspend fun grantWriteSecureSettings(packageName: String): Boolean = false

    /** Temporarily gates RECORD_AUDIO for known Yandex apps while BYDMate owns the microphone.
     * Package names and the app-op are hardcoded; this is not a generic shell surface. */
    suspend fun setYandexMicrophoneBlocked(blocked: Boolean): Boolean = false

    /** Force-rebinds only BYDMate's steering AccessibilityService through the already-authorized
     * on-device ADB channel. Fallback for early boot when the helper binder is not alive yet. */
    suspend fun rebindSteeringAccessibility(): Boolean = false

    /** Idempotently sets the stock DiLink 2 compressor (snowflake) through a hardcoded
     *  climate-UI sequence under the on-device ADB shell uid. */
    suspend fun setAcCompressorViaStockUi(enable: Boolean): Boolean = false

    /** Sets the DiLink 2 cabin blower through the shell-uid autoservice channel.
     * Narrow hardcoded write: only levels 1..7 and the proven blower FID. */
    suspend fun setClimateFanLevel(level: Int): Boolean = false

    /**
     * Spawns the helper daemon under shell uid via app_process, using the app's
     * own signed base.apk as CLASSPATH (no dex push — integrity comes from the
     * APK signature). The daemon registers itself as the `bydmate_helper` binder
     * service; reachability is verified separately by the binder client's ping,
     * not here. Returns true if the spawn command was dispatched without error
     * (NOT a liveness guarantee).
     *
     * Hardcoded cmdline + process name — caller cannot inject. Uses the raw
     * protocol exec path (the public exec() write barrier only permits autoservice
     * GETs and would reject this).
     */
    suspend fun spawnHelper(): Boolean

    /**
     * Kills any running helper daemon (by exact nice-name) under shell uid, so a fresh
     * daemon from a newer app version can re-take the binder service and the exclusive
     * file lock. The lock auto-releases on process death. Hardcoded cmdline — no caller
     * input; harmless no-op when no daemon is running. Returns true if the kill command
     * was dispatched (NOT a guarantee the process is gone).
     */
    suspend fun killHelper(): Boolean

    /** Reads the daemon's stdout/stderr log (READY / ERR lines) for diagnostics. Null on transport error. */
    suspend fun readHelperLog(): String?

    /** Read-only check: is a process named `bydmate_helper` running? */
    suspend fun helperHeartbeat(): Boolean

    /** Closes any underlying socket. Idempotent. */
    suspend fun shutdown()
}

@Singleton
class AdbOnDeviceClientImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val keyStore: AdbKeyStore,
) : AdbOnDeviceClient {

    /**
     * Test seam — UNIT TESTS ONLY. Lets the test layer swap the real
     * socket-backed protocol for a fake. Default factory creates the real
     * [AdbProtocolClient] with the persisted keypair.
     */
    @Suppress("unused")  // assigned via internal setter from tests
    internal var protocolFactory: () -> AdbProtocol = {
        AdbProtocolClient(
            keyPair = keyStore.loadOrGenerate(),
            certificateProvider = { keyStore.loadOrGenerateCertificate() },
        )
    }

    @Volatile private var protocol: AdbProtocol? = null

    // used for packageCodePath when spawning the helper daemon
    private val ctx = context

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val p = protocol ?: protocolFactory().also { protocol = it }
            val ok = p.connect()
            if (ok) Result.success(Unit) else Result.failure(IOException("ADB connect refused"))
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        protocol?.isConnected() ?: false
    }

    override suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        // Structural barrier against accidental WRITE — only allow GETs to autoservice.
        require(cmd.matches(WRITE_BARRIER_REGEX)) {
            "AdbOnDeviceClient: refused command (write barrier): $cmd"
        }
        val p = protocol ?: return@withContext null
        try {
            // runInterruptible bridges coroutine cancellation to the blocking
            // ADB socket read: a parent withTimeout/withTimeoutOrNull will
            // post a Thread.interrupt(), which the socket implementation
            // raises as InterruptedIOException and unwinds out of p.exec.
            // Without this wrapper, withTimeoutOrNull(900ms) abandons the
            // coroutine but the socket read keeps blocking up to 5 s
            // (SOCKET_TIMEOUT_MS), pinning single-flight in TrackingService.
            kotlinx.coroutines.runInterruptible { p.exec(cmd) }
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    override suspend fun grantUsageStatsAppop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Only permit our own package — never grant appops to anything else.
        require(packageName.matches(PACKAGE_NAME_REGEX)) {
            "grantUsageStatsAppop: refused package $packageName"
        }
        val cmd = "appops set $packageName GET_USAGE_STATS allow"
        val p = protocol ?: return@withContext false
        try {
            // appops prints nothing on success; null means transport error.
            // Treat any non-null output as failure (e.g. "Bad permission") too.
            val out = p.exec(cmd) ?: return@withContext false
            out.isBlank()
        } catch (e: Exception) {
            Log.w(TAG, "grantUsageStatsAppop failed: ${e.message}")
            false
        }
    }

    override suspend fun grantWriteSecureSettings(packageName: String): Boolean = withContext(Dispatchers.IO) {
        require(packageName.matches(PACKAGE_NAME_REGEX)) {
            "grantWriteSecureSettings: refused package $packageName"
        }
        val p = protocol ?: run {
            if (connect().isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        runCatching {
            // `pm grant` is silent both on success and on an idempotent re-grant.
            p.exec("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")?.isBlank() == true
        }.onFailure { Log.w(TAG, "grantWriteSecureSettings failed: ${it.message}") }
            .getOrDefault(false)
    }

    override suspend fun setYandexMicrophoneBlocked(blocked: Boolean): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val connected = connect()
            if (connected.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        val mode = if (blocked) "ignore" else "allow"
        var installed = 0
        var allApplied = true
        try {
            for (packageName in YANDEX_MIC_PACKAGES) {
                val path = p.exec("pm path $packageName")
                if (path == null) {
                    allApplied = false
                    continue
                }
                if (!path.startsWith("package:")) continue
                installed++
                val out = p.exec("appops set $packageName RECORD_AUDIO $mode")
                if (out == null || out.isNotBlank()) allApplied = false
            }
            Log.i(TAG, "Yandex microphone app-op: blocked=$blocked installed=$installed ok=$allApplied")
            installed > 0 && allApplied
        } catch (e: Exception) {
            Log.w(TAG, "Yandex microphone app-op failed: ${e.message}")
            false
        }
    }

    override suspend fun rebindSteeringAccessibility(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val connected = connect()
            if (connected.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        try {
            val currentRaw = p.exec("settings get secure enabled_accessibility_services")
                ?: return@withContext false
            val current = currentRaw.trim().takeUnless { it == "null" }.orEmpty()
            val others = current.split(':')
                .filter { it.isNotBlank() && canonicalAccessibilityComponent(it) != STEERING_A11Y_CANONICAL }
            val without = others.joinToString(":")
            val withBydMate = (others + STEERING_A11Y_COMPONENT).joinToString(":")
            if (p.exec("settings put secure enabled_accessibility_services ${shellQuote(without)}") == null) {
                return@withContext false
            }
            // 200 ms was occasionally swallowed by DiLink 2 during cold wake. The proven
            // on-car manual-equivalent cycle needs a full second between removal and re-add.
            Thread.sleep(1_000L)
            if (p.exec("settings put secure enabled_accessibility_services ${shellQuote(withBydMate)}") == null) {
                return@withContext false
            }
            if (p.exec("settings put secure accessibility_enabled 1") == null) return@withContext false
            repeat(6) {
                val connected = p.exec(STEERING_A11Y_DUMPSYS_CHECK)?.trim() == "connected"
                if (connected) {
                    Log.i(TAG, "Steering accessibility rebound through on-device ADB")
                    return@withContext true
                }
                Thread.sleep(500L)
            }
            Log.w(TAG, "Steering accessibility rebind dispatched but :steering did not start")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Steering accessibility ADB rebind failed: ${e.message}")
            false
        }
    }

    override suspend fun setAcCompressorViaStockUi(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val connected = connect()
            if (connected.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        val dumpPath = AC_UI_DUMP_PATH

        fun readState(): com.bydmate.app.helper.AcCompressorUiState? {
            repeat(2) {
                val dumped = p.exec("uiautomator dump --compressed $dumpPath")
                if (dumped != null) {
                    val xml = p.exec("cat $dumpPath")
                    parseAcCompressorUiState(xml.orEmpty())?.let { return it }
                }
                Thread.sleep(250L)
            }
            return null
        }

        val launched = p.exec(
            "am start -W -a OPEN_AIR_CONDITIONING " +
                "-n com.byd.airconditioning/.mainactivity.FullScreenMainActivity"
        ) ?: return@withContext false
        if (launched.contains("Error", ignoreCase = true)) return@withContext false

        try {
            Thread.sleep(350L)
            val before = readState() ?: return@withContext false
            if (before.selected == enable) return@withContext true
            if (p.exec("input tap ${before.centerX} ${before.centerY}") == null) {
                return@withContext false
            }
            Thread.sleep(300L)
            readState()?.selected == enable
        } catch (e: Exception) {
            Log.w(TAG, "setAcCompressorViaStockUi failed: ${e.message}")
            false
        } finally {
            runCatching { p.exec("input keyevent BACK") }
            runCatching { p.exec("rm -f $dumpPath") }
        }
    }

    override suspend fun setClimateFanLevel(level: Int): Boolean = withContext(Dispatchers.IO) {
        require(level in CLIMATE_FAN_MIN..CLIMATE_FAN_MAX) {
            "setClimateFanLevel: level must be $CLIMATE_FAN_MIN..$CLIMATE_FAN_MAX"
        }
        val p = protocol ?: run {
            val connected = connect()
            if (connected.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        try {
            // Deliberately bypass public exec()'s read-only barrier through this one
            // narrow API. Live-validated on the connected DiLink 2: tx=6 returns 1
            // and read FID 1077936156 changes to the requested stage.
            val write = p.exec(
                "service call autoservice 6 i32 $CLIMATE_DEV i32 $CLIMATE_FAN_WRITE_FID i32 $level"
            ) ?: return@withContext false
            if (!write.contains("Parcel(00000001")) {
                Log.w(TAG, "blower write rejected: $write")
                return@withContext false
            }
            repeat(5) {
                val read = p.exec(
                    "service call autoservice 5 i32 $CLIMATE_DEV i32 $CLIMATE_FAN_READ_FID"
                )
                if (parseAutoserviceReadInt(read) == level) {
                    Log.i(TAG, "DiLink 2 blower level confirmed: $level")
                    return@withContext true
                }
                Thread.sleep(150L)
            }
            Log.w(TAG, "blower write accepted but readback did not reach $level")
            false
        } catch (e: Exception) {
            Log.w(TAG, "setClimateFanLevel failed: ${e.message}")
            false
        }
    }

    override suspend fun spawnHelper(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val r = connect()
            if (r.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        try {
            // Raw protocol exec — bypasses the public exec() write barrier by design
            // (this is not an autoservice GET). Hardcoded, no caller input.
            // CLASSPATH = the app's own signed base.apk; setsid detaches the daemon
            // into its own session. The trailing poll-loop keeps THIS shell alive until
            // the daemon registers (or 3s elapse): the on-device ADB closes the exec
            // stream the instant `&` backgrounds the job, and adbd SIGHUPs the subprocess
            // — without the loop the still-booting JVM dies before its first println
            // (empty log, no registration). Mirrors the proven BYD EV Pro / aps_diplus
            // spawn recipe; see reference_autoservice_write_channel.md.
            val spawnCmd =
                "CLASSPATH=${ctx.packageCodePath} setsid app_process /system/bin " +
                "--nice-name=$HELPER_PROCESS_NAME com.bydmate.app.helper.HelperDaemon " +
                "${android.os.Process.myUid()} </dev/null >$HELPER_LOG_PATH 2>&1 & " +
                "for i in 1 2 3; do service list 2>/dev/null | grep -q $HELPER_PROCESS_NAME && break; sleep 1; done"
            // exec() returns null only on a dead/disconnected socket (AdbProtocolClient.exec) — an
            // honest false here matters: HelperBootstrap.ensureRunningLocked() persists the spawned
            // versionCode ONLY after a dispatch that actually succeeded, and bails otherwise.
            p.exec(spawnCmd) != null
        } catch (e: Exception) {
            Log.w(TAG, "spawnHelper failed: ${e.message}")
            false
        }
    }

    override suspend fun killHelper(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val r = connect()
            if (r.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        try {
            // Raw protocol exec (hardcoded, no caller input). Kill by exact process NAME (comm)
            // so a fresh daemon (newer app version) can re-take the binder service + file lock;
            // the exclusive flock auto-releases on death. Selecting via `ps -A -o PID,NAME` +
            // exact `==` match (same discipline as helperHeartbeat below) — NOT `pgrep -f`:
            // `pgrep -f` matches the whole cmdline, and this kill shell's OWN argv contains
            // "bydmate_helper", so it self-matched and could `kill -9` itself before reaching
            // the daemon (pid-order dependent), leaving the stale daemon alive. The ps/awk/sh
            // shells here have comm != "bydmate_helper" and cannot self-match. Empty match →
            // `kill -9` runs for no pids → harmless.
            // Honest false on a dead socket, same reasoning as spawnHelper above.
            p.exec("for p in \$(ps -A -o PID,NAME | awk '\$2==\"$HELPER_PROCESS_NAME\"{print \$1}'); do kill -9 \$p; done") != null
        } catch (e: Exception) {
            Log.w(TAG, "killHelper failed: ${e.message}")
            false
        }
    }

    override suspend fun readHelperLog(): String? = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext null
        runCatching { p.exec("cat $HELPER_LOG_PATH") }.getOrNull()
    }

    override suspend fun helperHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        val out = runCatching { p.exec("ps -A -o NAME") }.getOrNull() ?: return@withContext false
        out.lineSequence().any { it.trim() == HELPER_PROCESS_NAME }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            try {
                protocol?.disconnect()
            } catch (_: Exception) { /* idempotent */ }
            protocol = null
        }
    }

    companion object {
        private const val TAG = "AdbOnDevice"

        // Block ANY write attempt at the boundary.
        // Allow only: service call autoservice <5|7|9> i32 <dev> i32 <fid>
        // Rejects tx=6 (setInt), tx=8 (setBuffer), and arbitrary shell.
        private val WRITE_BARRIER_REGEX = Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")

        // Narrow whitelist for self-grants — only our own package.
        private val PACKAGE_NAME_REGEX = Regex("""^com\.bydmate\.app$""")

        // Helper daemon — hardcoded so neither caller can inject paths/cmdlines.
        private const val HELPER_PROCESS_NAME = "bydmate_helper"
        private const val HELPER_LOG_PATH = "/data/local/tmp/bydmate_helper.log"
        private const val AC_UI_DUMP_PATH = "/data/local/tmp/bydmate_ac_ui.xml"
        private const val CLIMATE_DEV = 1000
        private const val CLIMATE_FAN_WRITE_FID = 501219340
        private const val CLIMATE_FAN_READ_FID = 1077936156
        private const val CLIMATE_FAN_MIN = 1
        private const val CLIMATE_FAN_MAX = 7
        private const val STEERING_A11Y_COMPONENT =
            "com.bydmate.app/com.bydmate.app.cluster.SteeringWheelKeyService"
        private const val STEERING_A11Y_CANONICAL = STEERING_A11Y_COMPONENT
        private const val STEERING_A11Y_DUMPSYS_CHECK =
            "dumpsys accessibility | grep -q 'Service\\[label=BYDMate' && echo connected"
        private val YANDEX_MIC_PACKAGES = listOf(
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexmaps",
            "ru.yandex.music",
        )

        internal fun canonicalAccessibilityComponent(raw: String): String {
            val slash = raw.indexOf('/')
            if (slash <= 0 || slash == raw.lastIndex) return raw
            val pkg = raw.substring(0, slash)
            val cls = raw.substring(slash + 1).let { if (it.startsWith('.')) pkg + it else it }
            return "$pkg/$cls"
        }

        internal fun parseAutoserviceReadInt(output: String?): Int? {
            val hex = Regex("""Parcel\([0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")
                .find(output.orEmpty())?.groupValues?.get(1) ?: return null
            return hex.toLongOrNull(16)?.toInt()
        }

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
