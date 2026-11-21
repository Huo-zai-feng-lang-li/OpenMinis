/*
 * M1 Shizuku shell-host display owner.
 *
 * Apache-2.0 attribution (Mika port):
 *
 * Ported from wangzan101/Mika (Apache-2.0, tag 7101e99):
 *   - cap/phone/shell/ShizukuShellUserService.kt (host class shape: a plain
 *     binder stub with @Keep (Context) and no-arg constructors, instantiated
 *     by Shizuku's privileged app_process via reflection)
 *   - cap/phone/shell/ShellService.kt (createDisplay's trusted downgrade,
 *     setFullscreenWindowingMode, setImePolicy, DisplayAwake keep-awake,
 *     startAppInUser's ILauncherApps cross-user path, injectKey/injectTap/
 *     injectText with setDisplayId + API 33 targeted uid + `input -d`
 *     fallback, runSystemCommand)
 *   - cap/phone/shell/DisplayAwake.kt
 *
 * Trimmed on purpose: NO audio muting, clipboard, devtools socket, file
 * policy, orientation watch, main-screen spill watching, restore wish, or
 * device-credential input (Mika's M2 concerns). See
 * docs/virtual-display-M1.md and THIRD_PARTY_LICENSES.md.
 */

package com.openminis.app.shizuku

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import com.openminis.app.BuildConfig
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku user-service entry point. Shizuku's server (running with shell
 * privilege when started via wireless/USB debugging) starts an
 * `app_process` helper that instantiates THIS class by name from the Minis
 * APK (dual @Keep constructors, exactly the Mika
 * `ShizukuShellUserService` shape) and hands its binder to the app.
 *
 * The class is NOT an `android.app.Service` and needs no manifest
 * `<service>` entry — see docs/virtual-display-M1.md §"UserService 接入".
 *
 * Every call is uid-checked against [allowedUid] (the Minis app uid);
 * calls that bypass Minis (e.g. from other shell processes) are refused.
 */
// No class-level @Keep: androidx Keep is not applicable to classes, and the
// release retention already comes from the proguard-rules.pro rule
// `-keep class com.openminis.app.shizuku.MinisShellHostService { *; }`.
class MinisShellHostService : IMinisShell.Stub {

    private val delegate: MinisShellDisplay

    @Keep
    constructor(context: Context) : super() {
        ensureMainLooper()
        delegate = MinisShellDisplay(resolveAllowedUid(context))
    }

    @Keep
    constructor() : super() {
        ensureMainLooper()
        delegate = MinisShellDisplay(resolveAllowedUid(null))
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        requireCaller()
        return delegate.createDisplay(surface, width, height, dpi)
    }

    override fun releaseDisplay(displayId: Int) {
        requireCaller()
        delegate.releaseDisplay(displayId)
    }

    override fun startAppInUser(displayId: Int, component: String, userId: Int): Int {
        requireCaller()
        return delegate.startAppInUser(displayId, component, userId)
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        requireCaller()
        return delegate.injectTap(displayId, x, y)
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        requireCaller()
        return delegate.injectKey(displayId, keyCode)
    }

    override fun injectText(displayId: Int, text: String): Boolean {
        requireCaller()
        return delegate.injectText(displayId, text)
    }

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        requireCaller()
        return delegate.runSystemCommand(displayId, command)
    }

    override fun identity(): IntArray {
        requireCaller()
        return delegate.identity()
    }

    override fun releaseAll() {
        requireCaller()
        delegate.releaseAll()
    }

    /**
     * Defense in depth: the binder only ever reaches the Minis app through
     * Shizuku, but refuse anyone else outright (Mika's destroy-guard shape,
     * extended to every method).
     */
    private fun requireCaller() {
        val uid = Binder.getCallingUid()
        if (uid != delegate.allowedUid && uid != Process.myUid() && uid != Process.SYSTEM_UID) {
            throw SecurityException("caller uid $uid is not the Minis app")
        }
    }

    private companion object {
        const val TAG = "MinisShellHost"

        fun ensureMainLooper() {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
        }

        /**
         * The Minis app uid, resolved the way Mika does: the context Shizuku
         * passes is only trusted when it IS the app's context; otherwise ask
         * the shell context's PackageManager for the app id.
         */
        fun resolveAllowedUid(context: Context?): Int {
            val fromContext = runCatching {
                context?.takeIf { it.packageName == BuildConfig.APPLICATION_ID }?.applicationInfo?.uid
            }.getOrNull()
            if (fromContext != null && fromContext > 0 &&
                fromContext != ShellContext.SHELL_UID && fromContext != Process.SYSTEM_UID
            ) {
                return fromContext
            }
            val pm = runCatching { context?.packageManager }.getOrNull() ?: ShellContext.create().packageManager
            return pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        }
    }
}

/**
 * The actual display owner, running with shell privilege. One instance per
 * Shizuku user-service process; displays are keyed by displayId.
 */
internal class MinisShellDisplay(
    /** Only the Minis app uid may call. */
    val allowedUid: Int,
) {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    /** Last (packageName, user) launched per display — the target uid for API 33+
     *  targeted input injection (avoids re-resolving the top task via hidden APIs). */
    private val lastLaunched = HashMap<Int, Pair<String, Int>>()

    // 不能用 getSystemService:那样拿到的 DisplayManager 绑的是系统上下文,包名与 shell uid 对不上
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    /** ILauncherApps (the launcher-icon-start path, `LauncherApps.startMainActivity`'s interface). */
    private val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps service unavailable", it) }.getOrNull()
    }

    private val awake = MinisShellDisplayAwake(::pokeDisplay)

    /** IPowerManager and its per-display userActivity method; null when unavailable. */
    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (MinisShellDisplayAwake.userActivityMethod(power.javaClass)
                ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    // ── create / release ──────────────────────────────────────────────────

    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // shell 没有 ADD_TRUSTED_DISPLAY 时(有的系统不给):去掉只对可信屏可用的几项再建一次。
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (e: Throwable) {
                Log.e(TAG, "createDisplay failed", e)
                -1
            }
        }
    }

    private fun createVirtualDisplay(
        surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean,
    ): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("minis-shell", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        setImePolicy(id)
        if (MinisShellDisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) {
            awake.start(id)
        }
        return id
    }

    fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        val vd = synchronized(displays) { displays.remove(displayId) }
        lastLaunched.remove(displayId)
        vd?.release()
    }

    fun releaseAll() {
        awake.stopAll()
        val all = synchronized(displays) { displays.values.toList().also { displays.clear() } }
        lastLaunched.clear()
        all.forEach { runCatching { it.release() } }
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val pair = userActivity ?: return false
        val (power, method) = pair
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), MinisShellDisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    /** DisplayInfo.displayGroupId (hidden field); unreadable → null (treated as own group). */
    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    /** 只往这个服务自己建的虚拟屏上启动/注入,调用方传错屏号(比如主屏)也不会打扰用户。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    /**
     * STEAL_TOP_FOCUS_DISABLED 保住主屏焦点,但有的系统默认把新虚拟屏设成 freeform,
     * App 因此缩成小窗、坐标受缩放影响。建屏后、启动任何页面前明确用全屏模式。
     */
    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            // WindowConfiguration.WINDOWING_MODE_FULLSCREEN = 1 (hidden constant)
            wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java)
                .invoke(wm, displayId, 1)
        }.onFailure {
            Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
            if (exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1") != 0) {
                Log.w(TAG, "could not set fullscreen windowing mode on display $displayId")
            }
        }
    }

    /** IWindowManager.setDisplayImePolicy: 输入法弹在虚拟屏上,不弹到用户的主屏。 */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    // ── start app in (secondary) user ─────────────────────────────────────

    /** The user the Minis app itself lives in (uid → user, Mika's `myUser`). */
    private val myUser: Int get() = allowedUid / PER_USER_RANGE

    /**
     * Open [component] onto [displayId] in user [userId].
     *
     * Simplified AppCopies whitelist (Mika's full version reads flags
     * strings + dumpsys): a user is usable when it exists, is running and
     * unlocked, and is not a guest / managed profile. Private-profile and
     * work-profile copies are refused (Mika refuses them too).
     *
     * @return 0 = started · 1 = unknown/bad component · 2 = display not
     *         ours · 3 = user not allowed · 4 = every launch path failed
     */
    fun startAppInUser(displayId: Int, component: String, userId: Int): Int {
        if (!ownDisplay(displayId)) return START_DISPLAY_NOT_OURS
        if (!userAllowed(userId)) {
            Log.w(TAG, "refused user $userId (not in the simplified whitelist)")
            return START_USER_NOT_ALLOWED
        }
        // "pkg/.Activity" or "pkg/activity" → ComponentName; bare "pkg" → launcher intent.
        // A leading-dot class name is expanded here (ComponentName does not
        // expand it on its own).
        val rawClass = component.substringAfter('/', "")
        val pkgPrefix = component.substringBefore('/')
        val componentName = rawClass.takeIf { it.isNotEmpty() && !it.contains('/') }
            ?.let { ComponentName(pkgPrefix, if (it.startsWith(".")) "$pkgPrefix${it}" else it) }
        val barePackage = component.takeIf { !component.contains('/') }
        val intent: Intent = when {
            componentName != null -> Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(componentName)
            else -> {
                val pkg = barePackage ?: return START_BAD_COMPONENT
                val launch = if (userId == myUser) {
                    context.packageManager.getLaunchIntentForPackage(pkg)
                } else {
                    // Cross-user bare package: let the am fallback resolve the
                    // user's own launcher page (Mika queries the user's
                    // launcher activities via a hidden API — left as a
                    // documented simplification).
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
                }
                if (launch == null) return START_BAD_COMPONENT
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()

        // 1) Cross-user: the "launcher opens the icon" path first — some
        //    systems pop a "which app should open this" chooser for
        //    programmatically-started second copies; the launcher path
        //    doesn't (Mika startAsLauncher, verbatim semantics).
        if (userId != myUser && componentName != null && startAsLauncher(componentName, userId, options)) {
            lastLaunched[displayId] = componentName.packageName to userId
            return START_OK
        }
        // 2) Hidden 12-arg IActivityTaskManager.startActivityAsUser.
        val launchedPkg = intent.component?.packageName ?: intent.getPackage()
        if (launchedPkg == null) return START_BAD_COMPONENT
        if (startActivityAsUserReflective(intent, options, userId)) {
            lastLaunched[displayId] = launchedPkg to userId
            return START_OK
        }
        // 3) `am start` fallback.
        val userArgs = if (userId == myUser) emptyList<String>() else listOf("--user", userId.toString())
        val cmd = mutableListOf("am", "start") + userArgs + listOf("--display", displayId.toString()) +
            when {
                componentName != null -> listOf("-n", componentName.flattenToShortString())
                else -> listOf("-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-p", barePackage!!)
            }
        val ok = exec(cmd[0], *cmd.drop(1).toTypedArray()) == 0
        if (ok) lastLaunched[displayId] = launchedPkg to userId
        return if (ok) START_OK else START_ERROR
    }

    /**
     * Like a launcher opening a second user's icon: ILauncherApps
     * .startActivityAsUser (the API behind LauncherApps.startMainActivity),
     * with the same launch options. Shell has cross-user rights and the
     * package name matches its own uid. False when the interface is absent
     * or refuses — the caller falls back to startActivityAsUser.
     */
    private fun startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean =
        try {
            val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
            val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
            // API 30+: (caller, callingPackage, callingFeatureId, component,
            // sourceBounds, opts, user); earlier: no callingFeatureId.
            val m = la.javaClass.methods.first {
                it.name == "startActivityAsUser" &&
                    it.parameterTypes.size in 6..7 &&
                    ComponentName::class.java in it.parameterTypes
            }
            val args: Array<Any?> = if (m.parameterTypes.size == 7) {
                arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
            } else {
                arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
            }
            m.invoke(la, *args)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launcher-style start of $component in user $userId failed", t)
            false
        }

    /**
     * 12-arg IActivityTaskManager.startActivityAsUser with
     * ActivityOptions.setLaunchDisplayId (Mika verbatim). False when the
     * reflection is unavailable or the framework returns a negative code —
     * the caller falls back to `am start`.
     */
    @SuppressLint("PrivateApi")
    private fun startActivityAsUserReflective(intent: Intent, options: Bundle, userId: Int): Boolean {
        val start = try {
            taskManager.javaClass.methods.firstOrNull {
                it.name == "startActivityAsUser" && it.parameterTypes.size == 12
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser lookup failed", t)
            null
        }
        if (start == null) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start")
            return false
        }
        return try {
            // (caller, callingPackage, callingFeatureId, intent, resolvedType,
            //  resultTo, resultWho, requestCode, flags, profilerInfo,
            //  options, userId)
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, if (userId == myUser) USER_CURRENT else userId,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($intent, user $userId) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser threw, falling back to am start", t)
            false
        }
    }

    /**
     * Simplified AppCopies.whitelist (Mika's core/uistate/AppCopies.usable
     * reduced to the fields a public API exposes): usable when the user
     * exists, is running, is unlocked, and is not a guest/managed
     * (work-profile) copy. Private profiles and work profiles are refused;
     * when in doubt (unreadable user info) the answer is NO.
     */
    private fun userAllowed(userId: Int): Boolean {
        if (userId == myUser) return true
        return try {
            val um = context.getSystemService(UserManager::class.java) ?: return false
            val info = um.getUserInfo(userId) // throws when the user doesn't exist
            val handle = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
            val flags = info.flags
            // Simplified AppCopies whitelist (Mika's full AppCopies.usable
            // reduced to public-API fields): the user must exist, be running,
            // be unlocked, and be a plain secondary user — NOT a guest, a
            // restricted user, or a managed work profile (FLAG_PROFILE covers
            // all profile kinds; a dual-app / 双开 copy is a separate *user*,
            // which passes). Private profiles (API 31+) and clones are
            // refused by construction — documented simplification.
            val usable = um.isUserRunning(handle) &&
                um.isUserUnlocked(handle) &&
                flags and (UserInfo.FLAG_GUEST or
                    UserInfo.FLAG_RESTRICTED or
                    UserInfo.FLAG_MANAGED_PROFILE or
                    UserInfo.FLAG_PROFILE) == 0
            usable
        } catch (t: Throwable) {
            Log.w(TAG, "userAllowed($userId) check failed: ${t.message}")
            false
        }
    }

    // ── input injection ───────────────────────────────────────────────────

    /**
     * Whole-device keys the agent must never be able to fire — power, sleep,
     * volume, home/recents/menu/mute. (Mika keeps an allow-list; the
     * deny-list is simpler and covers the dangerous set for this surface.)
     */
    private val KEY_DENYLIST = setOf(
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
    )

    fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) {
                exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (keyCode in KEY_DENYLIST) {
            Log.w(TAG, "refused key $keyCode (device-level key)")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD,
            0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        // 按键由系统按输入焦点路由,不认 event 上的 displayId:不定向的话会投到
        // 主屏焦点,虚拟屏上的 App 收不到返回键(API 33+ 定向投给启动过的 App 的 uid)。
        val uid = targetUidFor(displayId)
        val ok = if (uid != null) {
            injectPairTargeted(down, up, displayId, uid) {
                injectPair(down, up, displayId) {
                    exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
                }
            }
        } else {
            injectPair(down, up, displayId) {
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Mika issue #622 (HyperOS): log which channel actually worked
            Log.i(TAG, "injectKey BACK display=$displayId targeted=${uid != null} ok=$ok")
        }
        return ok
    }

    fun injectText(displayId: Int, text: String): Boolean {
        if (!ownDisplay(displayId) || text.isEmpty()) return false
        // 1) Virtual-keyboard key events (the shell-level answer to
        //    `input text`'s ASCII-only limitation for printable ASCII).
        val keys = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        }.getOrNull()
        if (keys != null && keys.isNotEmpty()) {
            val uid = targetUidFor(displayId)
            for (event in keys) {
                event.source = InputDevice.SOURCE_KEYBOARD
                val sent = if (uid != null) injectTo(event, displayId, uid)
                else inject(event, displayId, waitForFinish = true)
                // A failed dispatch can leave a prefix. Never replay it through
                // another input route (Mika injectAsciiText semantics).
                if (!sent) return false
            }
            return true
        }
        // 2) Fallback: shell `input text` — ASCII only; CJK/Unicode text
        //    input (clipboard + paste) is the M2 path.
        return exec("input", "-d", "$displayId", "text", text) == 0
    }

    /** Target uid for API 33+ targeted injection: the app we last started on this display. */
    private fun targetUidFor(displayId: Int): Int? {
        if (Build.VERSION.SDK_INT < 33) return null
        val launched = lastLaunched[displayId] ?: return null
        val (pkg, user) = launched
        return runCatching {
            context.packageManager.getPackageUidAsUser(pkg, 0, user)
        }.getOrNull()?.takeIf { it >= 0 }
    }

    /**
     * Inject a pair of events with the display set; the DOWN event decides:
     * if it wasn't delivered, run [fallback] (the shell command path);
     * if it was, the UP still goes through injection (retry once) so we
     * don't emit a duplicate full tap/key (Mika sendPair).
     */
    private inline fun injectPair(
        down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean,
    ): Boolean =
        if (!inject(down, displayId)) fallback() else inject(up, displayId) || inject(up, displayId)

    private inline fun injectPairTargeted(
        down: InputEvent, up: InputEvent, displayId: Int, targetUid: Int, fallback: () -> Boolean,
    ): Boolean =
        if (!injectTo(down, displayId, targetUid)) {
            // targeted delivery was refused → nontargeted pair, then shell
            injectPair(down, up, displayId, fallback)
        } else injectTo(up, displayId, targetUid) || injectTo(up, displayId, targetUid)

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean =
        try {
            event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
            val manager = inputManager()
            manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
                .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
        } catch (t: Throwable) {
            Log.w(TAG, "injectInputEvent failed", t)
            false
        }

    /**
     * API 33+ targeted delivery: only windows owned by [targetUid] receive
     * the event — if its focus isn't on this display the system refuses it,
     * so the event can never land on some other window (Mika injectTo).
     */
    @SuppressLint("PrivateApi")
    private fun injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java,
        ).invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }

    /** API 34+ the injection entry point moved to InputManagerGlobal. */
    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull()
        ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    // ── shell commands ─────────────────────────────────────────────────────

    fun runSystemCommand(displayId: Int, command: String): Bundle {
        if (displayId >= 0 && !ownDisplay(displayId)) {
            return commandResult(-1, "No task display is active.")
        }
        return execCommand(command, displayId.takeIf { it >= 0 })
    }

    /** Service process' `[uid, pid]`; uid 2000 = Shizuku running in shell mode. */
    fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    /** 以 shell 身份跑 `sh -c`,合并输出,限时限长;有屏时放进 MINIS_DISPLAY_ID。 */
    private fun execCommand(command: String, displayId: Int?): Bundle {
        fun result(code: Int, output: String) = commandResult(code, output)
        if (command.isBlank() || command.length > COMMAND_MAX_LEN) return result(-1, "Invalid command length.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                .apply { if (displayId != null) environment()["MINIS_DISPLAY_ID"] = displayId.toString() }
                .start()
        } catch (t: Throwable) {
            return result(-1, t.message ?: "Command could not start.")
        }
        val bytes = AtomicReference(byteArrayOf())
        val reader = Thread {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val room = COMMAND_OUTPUT_MAX - out.size()
                        if (room > 0) out.write(buffer, 0, minOf(count, room))
                    }
                }
                bytes.set(out.toByteArray())
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val output = String(bytes.get(), Charsets.UTF_8) +
            if (bytes.get().size >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
        return result(if (finished) process.exitValue() else -1, output)
    }

    private fun commandResult(code: Int, output: String): Bundle =
        Bundle().apply { putInt("exitCode", code); putString("output", output) }

    /** Fixed argv (no shell quoting) — for the `am` / `wm` / `input` fallbacks. */
    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /** 跑一条 shell 命令,返回 (退出码, 输出);超时返回 -1。 */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread {
            output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    // ── flag sets (Mika ShellService.displayFlags, verbatim) ──────────────

    /** 只有缺权限(SecurityException,如 shell 没有 ADD_TRUSTED_DISPLAY)才值得去掉可信标志再试。 */
    private fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

    companion object {
        private const val TAG = "MinisShell"

        // startAppInUser result codes (see IMinisShell.aidl)
        const val START_OK = 0
        const val START_BAD_COMPONENT = 1
        const val START_DISPLAY_NOT_OURS = 2
        const val START_USER_NOT_ALLOWED = 3
        const val START_ERROR = 4

        private const val TAP_MS = 50L
        private const val CMD_TIMEOUT_S = 10L
        private const val COMMAND_MAX_LEN = 4_000
        private const val COMMAND_OUTPUT_MAX = 64 * 1024
        private const val READER_JOIN_MS = 1_000L
        private const val PER_USER_RANGE = 100_000
        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        private const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        private const val INJECT_MODE_WAIT_FOR_RESULT = 1
        private const val INJECT_MODE_WAIT_FOR_FINISH = 2
        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0 (Mika: measured on a
        // real Android 14 device — IME opens on the virtual display, the
        // main display's focus is untouched).
        private const val IME_POLICY_LOCAL = 0

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (部分为隐藏常量,值与 P0 VdFlags 一致)
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /**
         * Ported verbatim from Mika ShellService.displayFlags: when
         * [trusted] is false the trust-only bits are dropped; a system
         * where shell lacks ADD_TRUSTED_DISPLAY rejects TRUSTED with a
         * SecurityException and this set is the retry.
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // With OWN_FOCUS alone, starting an app on the virtual display makes it the
            // top focus display and clears the main display's focused window;
            // STEAL_TOP_FOCUS_DISABLED (needs OWN_FOCUS + TRUSTED) stops that.
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        /** 按键等到系统定下投给谁;触摸照旧不等。 */
        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
            waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
            key -> INJECT_MODE_WAIT_FOR_RESULT
            else -> INJECT_MODE_ASYNC
        }
    }
}

/**
 * Port of Mika `DisplayAwake` (Apache-2.0): keep a display group that owns
 * its own doze timer (OWN_DISPLAY_GROUP displays) awake by reporting
 * per-display user activity every 4s; stop when the report can't be
 * delivered (the display still works, it just falls asleep on its own).
 * The timer lives here (P0's `VdAwake` shape) so the caller just pokes.
 */
internal class MinisShellDisplayAwake(private val poke: (Int) -> Boolean) {
    private val timer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "minis-shell-display-awake").apply { isDaemon = true }
    }
    private val running = HashMap<Int, () -> Unit>()
    @Volatile
    private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = timer.scheduleWithFixedDelay(
            { if (!poke(displayId)) giveUp() },
            0, PERIOD_MS, TimeUnit.MILLISECONDS,
        )
        synchronized(running) {
            running[displayId]?.invoke()
            running[displayId] = { cancel.cancel(false) }
        }
        // The first poke can fail (giveUp) before the registration above finished.
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) {
            running.values.toList().also { running.clear() }
        }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        // PowerManager.USER_ACTIVITY_EVENT_TOUCH
        const val EVENT_TOUCH = 2
        // Display.DEFAULT_DISPLAY_GROUP (hidden constant)
        private const val DEFAULT_DISPLAY_GROUP = 0

        /**
         * 要不要替这块屏报:只有它真的自成一组时才报。报到默认组(用户主屏所在组)
         * 会让用户的主屏也一直不灭。[groupId] 读不到为 null,按带没带组标志算。
         */
        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            flags and ownGroupFlag != 0 && groupId != DEFAULT_DISPLAY_GROUP

        /**
         * The per-display `userActivity(int, long, int, int)` overload on
         * IPowerManager (Android 12+); null when only the old overload
         * exists (it reports into the default group → would keep the user's
         * main screen alive, so we never use it).
         */
        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
        }

        private val PER_DISPLAY = arrayOf(
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
    }
}
