/*
 * M1 — app-side bridge to the Shizuku shell-host display service
 * ([MinisShellHostService]).
 *
 * UserService access (technical notes, see docs/virtual-display-M1.md):
 *
 * OpenMinis depends on `dev.rikka.shizuku:api` + `:provider` (v13.1.5) — NOT
 * the legacy `com.schizukadev:shizuku` v10 library. In v13 the privileged
 * "user service" is a first-class part of `rikka.shizuku.Shizuku`:
 *
 *   1. Shizuku's server (started via wireless/USB debugging → shell uid
 *      2000; via root → system uid) spawns a privileged `app_process`
 *      helper that instantiates [MinisShellHostService] by class name from
 *      the Minis APK (dual @Keep constructors) and runs it in a process
 *      named `com.openminis.app:shizuku` with shell privilege.
 *   2. The app calls `Shizuku.bindUserService(UserServiceArgs, conn)`;
 *      Shizuku's `ShizukuServiceConnection` delivers the host binder to
 *      `conn.onServiceConnected`, where it is unwrapped with
 *      `IMinisShell.Stub.asInterface(...)`.
 *   3. NO `<service>` entry is required in AndroidManifest — the class is
 *      loaded by reflection inside Shizuku's own helper process, not
 *      started via ActivityManager. (The official Shizuku sample declares a
 *      manifest service only for its *legacy* helper path; the v13
 *      starter path used here does not.) What IS required: R8 must not
 *      rename/shrink [MinisShellHostService] or the generated
 *      `IMinisShell` — see `proguard-rules.pro`.
 *
 * User-side prerequisites are exactly P0's: Shizuku installed and started
 * (`android-shizuku-cli service status` → state READY, uid 2000 when
 * started via wireless/USB debugging) and the app authorized in Shizuku
 * (Settings → Shizuku permission). The user service reuses that same
 * grant — no extra permission request on first use. `Shizuku.requestPermission(...)`
 * is the app-level permission flow (same request codes as P0's
 * `ShizukuBackend`).
 */

package com.openminis.app.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-process registry of the shell-host binder. All access is
 * synchronized: Shizuku's `onServiceConnected` fires on the app's main
 * thread while CLI calls come in on binder/handler threads.
 */
object MinisShellClient {

    private const val TAG = "MinisShellClient"

    /**
     * Bump whenever the host's AIDL surface or flag behaviour changes:
     * Shizuku recreates user services whose version differs from the
     * running one (ShizukuUserServiceManager's apk-changed handling).
     */
    private const val HOST_VERSION = 2

    @Volatile
    private var shell: IMinisShell? = null
    @Volatile
    private var lastError: String? = null
    private val lock = Any()
    private var connection: ServiceConnection? = null
    private var started = false

    /**
     * True when a live [IMinisShell] binder is in hand (or can be reached
     * on demand). Cheap probe: an identity() round-trip — if the helper
     * process died, Shizuku has already nulled our [shell] via
     * onServiceDisconnected.
     */
    fun isUsable(): Boolean {
        if (shell != null) return true
        val snap = ShizukuManager.snapshot.value.state
        if (snap != ShizukuManager.State.READY) {
            lastError = "Shizuku not READY (state=$snap) — start it in the Shizuku app (wireless/USB debugging = shell uid 2000)"
            return false
        }
        return ensureShell(timeoutMs = 1_000) != null
    }

    /** Last failure reason for `isUsable()`/call paths — for CLI error envelopes. */
    fun lastError(): String? = lastError

    /**
     * Ensure a live host binder. Blocks up to [timeoutMs] waiting for
     * Shizuku's onServiceConnected (first start spawns the helper
     * process: slow). Returns the interface or null (see [lastError]).
     */
    fun ensureShell(timeoutMs: Long = 10_000): IMinisShell? = synchronized(lock) {
        shell?.let {
            lastError = null
            return@synchronized it
        }
        if (!ShizukuManager.isReady()) {
            lastError = "Shizuku not READY (state=${ShizukuManager.snapshot.value.state})"
            return@synchronized null
        }
        // Shizuku < v12 has no user-service support.
        val shizukuVersion = runCatching { Shizuku.getVersion() }.getOrDefault(0)
        if (shizukuVersion < 12) {
            lastError = "Shizuku v$shizukuVersion is too old for user services (need >= 12)"
            return@synchronized null
        }
        val args = userServiceArgs()
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                synchronized(lock) {
                    shell = IMinisShell.Stub.asInterface(service)
                    service.linkToDeath(
                        object : IBinder.DeathRecipient {
                            override fun binderDied() {
                                synchronized(lock) {
                                    shell = null
                                    lastError = "shell-host binder died"
                                }
                            }
                        },
                        0,
                    )
                    lastError = null
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) { shell = null }
            }
        }
        connection = conn
        val ok = runCatching {
            Shizuku.bindUserService(args, conn)
        }.isSuccess
        if (!ok) {
            lastError = "Shizuku.bindUserService failed: ${runCatching { Shizuku.bindUserService(args, conn) }.exceptionOrNull()?.message}"
            AppLogger.warning(TAG, "bindUserService failed: ${lastError}")
            return@synchronized null
        }
        // bindUserService starts (or reuses) the user service and reports
        // "connected" asynchronously on the main thread.
        val delivered = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val result = shell
        if (result == null) {
            val msg = if (delivered) "binder received but not an IMinisShell"
                else "timed out (${timeoutMs}ms) waiting for the shell host to come up"
            lastError = msg
            AppLogger.warning(TAG, msg)
            return@synchronized null
        }
        started = true
        val identity = runCatching { result.identity() }.getOrNull()
        AppLogger.info(
            TAG,
            "shell host connected: uid=${identity?.get(0)} pid=${identity?.get(1)} shizuku=$shizukuVersion",
        )
        result
    }

    // ── display surface (each: -1 / false / null when unreachable) ───────

    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        val s = ensureShell() ?: return -1
        return runCatching { s.createDisplay(surface, width, height, dpi) }
            .onFailure { lastError = "createDisplay binder call failed: ${it.message}" }
            .getOrDefault(-1)
    }

    fun releaseDisplay(displayId: Int) {
        val s = shell ?: return
        runCatching { s.releaseDisplay(displayId) }
            .onFailure { AppLogger.warning(TAG, "releaseDisplay($displayId) failed: ${it.message}") }
    }

    fun startAppInUser(displayId: Int, component: String, userId: Int): Int {
        val s = ensureShell() ?: return -1
        return runCatching { s.startAppInUser(displayId, component, userId) }
            .onFailure { lastError = "startAppInUser binder call failed: ${it.message}" }
            .getOrDefault(-1)
    }

    fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val s = ensureShell() ?: return false
        return runCatching { s.injectTap(displayId, x, y) }
            .onFailure { AppLogger.warning(TAG, "injectTap binder call failed: ${it.message}") }
            .getOrDefault(false)
    }

    fun injectKey(displayId: Int, keyCode: Int): Boolean {
        val s = ensureShell() ?: return false
        return runCatching { s.injectKey(displayId, keyCode) }
            .onFailure { AppLogger.warning(TAG, "injectKey binder call failed: ${it.message}") }
            .getOrDefault(false)
    }

    fun injectText(displayId: Int, text: String): Boolean {
        val s = ensureShell() ?: return false
        return runCatching { s.injectText(displayId, text) }
            .onFailure { AppLogger.warning(TAG, "injectText binder call failed: ${it.message}") }
            .getOrDefault(false)
    }

    fun runSystemCommand(displayId: Int, command: String): Bundle? {
        val s = ensureShell() ?: return null
        return runCatching { s.runSystemCommand(displayId, command) }
            .onFailure { AppLogger.warning(TAG, "runSystemCommand binder call failed: ${it.message}") }
            .getOrNull()
    }

    // ── task repin surface (HOST_VERSION 2; older helpers lack these) ────

    /** Move [taskId] onto [displayId]; false when unreachable or moved back nowhere. */
    fun moveTask(taskId: Int, displayId: Int): Boolean {
        val s = shell ?: return false
        return runCatching { s.moveTask(taskId, displayId) }
            .onFailure { lastError = "moveTask binder call failed: ${it.message}" }
            .getOrDefault(false)
    }

    /** Which display holds [taskId]; -1 when gone or unreachable. */
    fun displayOfTask(taskId: Int): Int {
        val s = shell ?: return -1
        return runCatching { s.displayOfTask(taskId) }
            .onFailure { lastError = "displayOfTask binder call failed: ${it.message}" }
            .getOrDefault(-1)
    }

    /** Most-recent taskId on [displayId]; -1 when empty or unreachable. */
    fun topTaskId(displayId: Int): Int {
        val s = shell ?: return -1
        return runCatching { s.topTaskId(displayId) }
            .onFailure { lastError = "topTaskId binder call failed: ${it.message}" }
            .getOrDefault(-1)
    }

    /** Package owning [taskId]; null when unknown or unreachable. */
    fun packageOfTask(taskId: Int): String? {
        val s = shell ?: return null
        return runCatching { s.packageOfTask(taskId) }
            .onFailure { lastError = "packageOfTask binder call failed: ${it.message}" }
            .getOrNull()
    }

    /** Task list on [displayId] (taskIds/packages/visible arrays); null when not ours. */
    fun listTasksOn(displayId: Int): Bundle? {
        val s = shell ?: return null
        return runCatching { s.listTasksOn(displayId) }
            .onFailure { lastError = "listTasksOn binder call failed: ${it.message}" }
            .getOrNull()
    }

    /**
     * Is [packageName] visible on the main screen? fail-closed: when the
     * binder is unreachable the answer is true (assume the user has it in
     * front — do not kill or move it).
     */
    fun visibleOnMainScreen(packageName: String, userId: Int): Boolean {
        val s = shell ?: return true
        return runCatching { s.visibleOnMainScreen(packageName, userId) }
            .onFailure { lastError = "visibleOnMainScreen binder call failed: ${it.message}" }
            .getOrDefault(true)
    }

    /** Re-pin [packageName]'s task back onto [displayId]; false when unreachable. */
    fun repinIfNeeded(displayId: Int, packageName: String): Boolean {
        val s = shell ?: return false
        return runCatching { s.repinIfNeeded(displayId, packageName) }
            .onFailure { lastError = "repinIfNeeded binder call failed: ${it.message}" }
            .getOrDefault(false)
    }

    /**
     * [T-vd-island-resident] The package most recently launched on
     * [displayId] (the resident island's subtitle), or null when the
     * display is unknown, no app was started on it, the host is not
     * bound, or the running helper predates the method. Deliberately
     * does NOT [ensureShell] a fresh 10 s bind just to read a label.
     */
    fun launchedApp(displayId: Int): String? {
        val s = shell ?: return null
        return runCatching { s.launchedApp(displayId) }
            .onFailure { AppLogger.warning(TAG, "launchedApp($displayId) binder call failed: ${it.message}") }
            .getOrNull()
    }

    /** Release every shell-host display (call on app teardown). */
    fun releaseAllDisplays() {
        val s = shell ?: return
        runCatching { s.releaseAll() }
            .onFailure { AppLogger.warning(TAG, "releaseAll binder call failed: ${it.message}") }
    }

    /**
     * Stop tracking the binder (unbinds the connection WITHOUT killing
     * the helper: `remove = false`, so the displays stay alive for the
     * next bind; use [releaseAllDisplays] first if they should die).
     */
    fun detach() {
        synchronized(lock) {
            connection?.let { conn ->
                runCatching { Shizuku.unbindUserService(userServiceArgs(), conn, false) }
                    .onFailure { t -> AppLogger.warning(TAG, "unbindUserService failed: ${t.message}") }
            }
            shell = null
            connection = null
            started = false
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, MinisShellHostService::class.java.name),
        )
            // daemon(false): the helper stops when the app process dies,
            // so no orphan display owner survives app restarts (Mika's
            // choice; its own restore-wish machinery is intentionally not
            // ported).
            .daemon(false)
            .processNameSuffix("shizuku")
            .version(HOST_VERSION)
}
