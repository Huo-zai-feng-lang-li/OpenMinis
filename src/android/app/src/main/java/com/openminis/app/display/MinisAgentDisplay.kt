package com.openminis.app.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import com.openminis.app.logging.AppLogger
import com.openminis.app.shizuku.MinisShellClient
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Apache-2.0 attribution (Mika port):
 *
 * Frame pipeline, upright-frame geometry and the black-frame sampler in this
 * file are ported from wangzan101/Mika (Apache-2.0):
 *   - app/src/main/java/com/wangzan101/mika/cap/phone/display/AgentDisplay.kt
 *   - app/src/main/java/com/wangzan101/mika/cap/phone/display/DisplayFrameGeometry.kt
 *   - app/src/main/java/com/wangzan101/mika/cap/phone/display/DisplayFrame.kt
 *   - app/src/main/java/com/wangzan101/mika/cap/phone/display/FrameMath.kt (isBlack)
 *
 * Virtual-display flag handling, trusted-downgrade, post-create
 * windowing/IME configuration and the 4s keep-awake are ported from:
 *   - app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt
 *     (displayFlags, createDisplay's retryWithoutTrust catch branch,
 *      setFullscreenWindowingMode, setImePolicy, userActivity keep-awake)
 *   - app/src/main/java/com/wangzan101/mika/cap/phone/shell/DisplayAwake.kt
 *
 * Unlike Mika this runs in-process under the regular app uid instead of a
 * shell-uid helper: TRUSTED flag sets that the framework refuses are
 * downgraded once; if even the untrusted PUBLIC display is refused the
 * creation fails and reports CREATION_FAILED (the M1 Shizuku shell-host
 * route is the documented next step).
 */

/**
 * What the app sees from an agent virtual display, no matter who owns it:
 * the P0 in-process owner ([MinisAgentDisplay], uid = app) or the M1 Shizuku
 * shell host ([MinisShellAgentDisplay], uid 2000). `android-vd-cli` talks
 * to this shape, so the CREATION_FAILED fallback below is transparent to
 * the CLI surface.
 */
interface AgentDisplayApi : AutoCloseable {
    val displayId: Int
    val name: String
    val bufferWidth: Int
    val bufferHeight: Int
    val dpi: Int
    /** Flags the display was created with (the REQUESTED set, for shell-owned displays that may have downgraded). */
    val flags: Int
    val trusted: Boolean
    val createdWallMs: Long
    /** Logical size at the current rotation. */
    val width: Int
    val height: Int
    val hasFrameNow: Boolean
    fun latestFramePng(): ByteArray?
    fun latestFrameBitmap(): Bitmap?
    fun lastFrameAt(): Long?
    fun isLastFrameBlack(): Boolean?
    /** Release the display and its frame pipeline. Idempotent. */
    fun release()
    override fun close()
}

/** Outcome of [MinisAgentDisplayManager.create]. */
sealed class VdCreateResult {
    data class Ok(val display: AgentDisplayApi) : VdCreateResult()
    data class Failed(val code: String, val message: String) : VdCreateResult()
}

/**
 * One in-process virtual display: a `DisplayManager.createVirtualDisplay`
 * whose surface is an [ImageReader] owned by this object, so every frame the
 * display composites lands in-process and can be exported as PNG without any
 * screenshot permission.
 *
 * Frames are processed on a dedicated [HandlerThread] at ≤10 fps (a frame
 * arriving within 100ms of the last processed one is deferred until the
 * interval; [ImageReader.acquireLatestImage] drops the stale ones in between).
 */
class MinisAgentDisplay internal constructor(
    private val context: Context,
    private val displayManager: DisplayManager,
    private val virtualDisplay: VirtualDisplay,
    private val reader: ImageReader,
    private val thread: HandlerThread,
    override val name: String,
    override val bufferWidth: Int,
    override val bufferHeight: Int,
    override val dpi: Int,
    override val flags: Int,
    override val trusted: Boolean,
) : AgentDisplayApi {

    override val displayId: Int = virtualDisplay.display.displayId
    override val createdWallMs: Long = System.currentTimeMillis()

    private val handler = Handler(thread.looper)

    // The ImageReader is always the physical buffer size from creation time;
    // the app's logical coordinates rotate with the virtual display.
    private val geometry: VdFrameGeometry
        get() = VdFrameGeometry(
            bufferWidth, bufferHeight,
            runCatching { displayManager.getDisplay(displayId)?.rotation }.getOrNull() ?: Surface.ROTATION_0,
        )

    /** Logical width of the current orientation (buffer size swapped on 90/270). */
    override val width: Int get() = geometry.width
    /** Logical height of the current orientation. */
    override val height: Int get() = geometry.height

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // stride-padded full frame, width = rowStride / pixelStride
    private var hasFrame = false
    private var frameGeometry = VdFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var lastProcessedAt = 0L
    private var pending = false
    /** Monotonic-clock nanoseconds of the virtual display producer; published under [frameLock]. */
    private var frameTimestampNanos = 0L
    @Volatile
    private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // Frames may have been queued while the display was still being created;
        // drain them once the pipeline has a valid display id.
        handler.post { schedule() }
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            // No fresh frame: keep the existing null/freshness protection, log the failure,
            // do not pass a stale frame off as a new one.
            AppLogger.warning(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}")
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                // Rate-limiting or thread scheduling delays the consumer; the frame's moment
                // must be the producer's timestamp, not the copy's.
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
            }
        } finally {
            image.close()
        }
    }

    /** Full-frame PNG of the latest frame, or null while no frame has been produced yet. */
    override fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** Upright (rotated) copy of the latest frame at original resolution; caller recycles. */
    override fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else vdUprightFrame(bmp, frameGeometry, paint = paint)
    }

    /** Producer timestamp (monotonic ns) of the latest published frame, or null. */
    override fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    override val hasFrameNow: Boolean get() = synchronized(frameLock) { hasFrame && !released }

    /**
     * Black-frame guard (ported from Mika `FrameMath.isBlack`): sample a
     * 24×24 grid over the frame; ≥99.5% of the samples with all RGB channels
     * ≤ 8 means "no real content" (secure-window capture path, GPU failure,
     * or a display group that fell asleep).
     */
    override fun isLastFrameBlack(): Boolean? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else VdFrameMath.isBlack(bmp)
    }

    /**
     * Post-create configuration, ported from Mika `ShellService`:
     *  - fullscreen windowing mode (some ROMs default new virtual displays to
     *    freeform, shrinking apps to small windows and skewing a11y coords);
     *  - IME policy LOCAL so keyboards open on this display, not the user's.
     * All best-effort: failures are logged, never fatal (Mika semantics).
     */
    fun setupDisplayConfiguration() {
        setFullscreenWindowingMode()
        setImePolicyLocal()
    }

    private fun windowManagerService(): Any? = runCatching {
        // Hidden framework bridge; may be refused by the hidden-API
        // enforcement list on some ROMs — that just disables both tweaks.
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)!!
    }.onFailure {
        AppLogger.warning(TAG, "WindowManagerGlobal unavailable, skipping display tweaks: ${it.message}")
    }.getOrNull()

    private fun setFullscreenWindowingMode() = runCatching {
        val wm = windowManagerService() ?: error("no window manager service")
        // WindowConfiguration.WINDOWING_MODE_FULLSCREEN == 1 (hidden constant)
        wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java)
            .invoke(wm, displayId, 1)
    }.onFailure {
        AppLogger.warning(TAG, "setWindowingMode($displayId) failed; apps may open in freeform: ${it.message}")
    }.isSuccess

    private fun setImePolicyLocal() = runCatching {
        val wm = windowManagerService() ?: error("no window manager service")
        // IME_POLICY_LOCAL == 0: the IME opens on this display.
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
            .invoke(wm, displayId, 0)
    }.onFailure {
        AppLogger.warning(TAG, "setDisplayImePolicy($displayId) failed: ${it.message}")
    }.isSuccess

    /** Release the display, its reader and the frame thread. Idempotent. */
    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        // Finish on the frame thread to avoid racing a frame in flight.
        handler.post {
            runCatching { reader.close() }
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
        runCatching { virtualDisplay.release() }
            .onFailure { AppLogger.warning(TAG, "virtualDisplay.release($displayId) failed: ${it.message}") }
    }

    override fun close() = release()

    private companion object {
        const val TAG = "MinisAgentDisplay"
        const val FRAME_INTERVAL_MS = 100L
    }
}

/**
 * M1 — a virtual display whose *owner* is the Shizuku shell host (uid 2000),
 * reached through [MinisShellClient], while the app process still owns the
 * [ImageReader] the host was handed. The frame pipeline is identical to
 * [MinisAgentDisplay] (≤10fps, stride handling, upright rotation, black-frame
 * guard); only the creation/release of the [VirtualDisplay] moves to the
 * privileged helper. This is what [MinisAgentDisplayManager.shellFallback]
 * returns when in-process creation is refused.
 *
 * Unlike [MinisAgentDisplay] the local process does NOT hold a
 * [VirtualDisplay] handle (it never called `createVirtualDisplay`), so there
 * is no local `virtualDisplay.release()`; `release()` tells the host to free
 * its side and shuts down the app-side reader/thread.
 */
class MinisShellAgentDisplay internal constructor(
    private val context: Context,
    private val reader: ImageReader,
    override val displayId: Int,
    override val name: String,
    override val bufferWidth: Int,
    override val bufferHeight: Int,
    override val dpi: Int,
) : AgentDisplayApi {

    override val flags: Int = VdFlags.displayFlags(Build.VERSION.SDK_INT, trusted = true)
    /**
     * The host was asked for the trusted set; if this ROM's shell lacks
     * ADD_TRUSTED_DISPLAY it silently downgraded and we cannot tell from the
     * binder (the AIDL returns only the displayId). Reported as requested.
     */
    override val trusted: Boolean = true
    override val createdWallMs: Long = System.currentTimeMillis()

    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    // Same fixed physical buffer; logical size rotates with the display.
    // The display is owned by the shell host, so rotation is read from the
    // app-visible Display object (not a local VirtualDisplay handle).
    private val geometry: VdFrameGeometry
        get() = VdFrameGeometry(
            bufferWidth, bufferHeight,
            runCatching {
                (context.getSystemService(DisplayManager::class.java) as? DisplayManager)
                    ?.getDisplay(displayId)?.rotation
            }.getOrNull() ?: Surface.ROTATION_0,
        )

    override val width: Int get() = geometry.width
    override val height: Int get() = geometry.height

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null
    private var hasFrame = false
    private var frameGeometry = VdFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var lastProcessedAt = 0L
    private var pending = false
    private var frameTimestampNanos = 0L
    @Volatile
    private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        handler.post { schedule() }
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            AppLogger.warning(SHELL_TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}")
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
            }
        } finally {
            image.close()
        }
    }

    override fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    override fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else vdUprightFrame(bmp, frameGeometry, paint = paint)
    }

    override fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    override val hasFrameNow: Boolean get() = synchronized(frameLock) { hasFrame && !released }

    override fun isLastFrameBlack(): Boolean? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else VdFrameMath.isBlack(bmp)
    }

    /**
     * Release the app-side pipeline and ask the shell host to free its
     * display. Idempotent. (Keep-awake for this display runs inside the host
     * process — see [MinisAgentDisplayManager.shellFallback].)
     */
    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { reader.close() }
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
        MinisShellClient.releaseDisplay(displayId)
        AppLogger.info(SHELL_TAG, "released shell-owned display $displayId")
    }

    override fun close() = release()

    private companion object {
        const val SHELL_TAG = "MinisShellAgentDisplay"
        const val FRAME_INTERVAL_MS = 100L
    }
}

/**
 * Black-frame guard (ported from Mika `FrameMath.isBlack`, Apache-2.0):
 * sample a 24×24 grid over the frame; ≥99.5% of the samples with all RGB
 * channels ≤ [BLACK_LEVEL] means "no real content".
 */
private object VdFrameMath {
    const val BLACK_SAMPLES = 24
    const val BLACK_LEVEL = 8

    fun isBlack(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) return false
        var dark = 0
        val total = BLACK_SAMPLES * BLACK_SAMPLES
        for (gy in 0 until BLACK_SAMPLES) for (gx in 0 until BLACK_SAMPLES) {
            val p = bmp.getPixel(
                gx * (w - 1) / (BLACK_SAMPLES - 1),
                gy * (h - 1) / (BLACK_SAMPLES - 1),
            )
            if ((p shr 16 and 0xff) <= BLACK_LEVEL &&
                (p shr 8 and 0xff) <= BLACK_LEVEL &&
                (p and 0xff) <= BLACK_LEVEL) dark++
        }
        return dark * 1000 >= total * 995
    }
}

/**
 * Process-wide registry of the in-process virtual displays. Follows the app's
 * existing singleton-holder pattern (cf. `MinisAccessibilityService._instance`):
 * a single object managing all live displays for the lifetime of the process.
 */
object MinisAgentDisplayManager {

    private const val TAG = "VdDisplay"
    private const val NAME_PREFIX = "minis-vd"

    private val lock = Any()
    private val active = LinkedHashMap<Int, AgentDisplayApi>()
    private val awake = VdAwake(::pokeDisplay)
    private var userActivity: Pair<Any, Method>? = null
    private var userActivityResolved = false

    /**
     * Create a virtual display. [width]/[height]/[dpi] default to the main
     * display's physical size/density (Mika creates agent displays 1:1 with
     * the main screen).
     *
     * Creation tries the trusted flag set first and retries exactly once
     * without the trust bits when the framework refuses them with a
     * [SecurityException] (Mika `retryWithoutTrust`: only a permission
     * denial is worth retrying; other failures are rethrown). M1: when even
     * that fails (`CREATION_FAILED`), the call automatically falls back to
     * the Shizuku shell host ([MinisShellClient.createDisplay], display
     * owned by uid 2000) — see [shellFallback] and docs/virtual-display-M1.md.
     */
    fun create(
        context: Context,
        width: Int? = null,
        height: Int? = null,
        dpi: Int? = null,
    ): VdCreateResult {
        val dm = context.getSystemService(DisplayManager::class.java)
        val main = dm.displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?: dm.displays.firstOrNull()
        val metrics = android.util.DisplayMetrics()
        main?.let { it.getRealMetrics(metrics) } // deprecated API-33+; still the all-version answer
        val w = width ?: metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val h = height ?: metrics.heightPixels.takeIf { it > 0 } ?: 2340
        val d = dpi ?: metrics.densityDpi.takeIf { it > 0 } ?: 420
        if (w < 320 || h < 320) {
            return VdCreateResult.Failed("INVALID_ARGS", "width/height must be at least 320px (got ${w}x$h)")
        }

        val name = "$NAME_PREFIX-${System.currentTimeMillis()}"
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var trusted = true
        var flags = VdFlags.displayFlags(Build.VERSION.SDK_INT, trusted)
        val virtualDisplay: VirtualDisplay = try {
            dm.createVirtualDisplay(name, w, h, d, reader.surface, flags)
        } catch (se: SecurityException) {
            // Trusted displays need ADD_TRUSTED_DISPLAY, which the framework may
            // deny to a plain app process. Drop the trust-only bits and retry.
            AppLogger.warning(TAG, "trusted virtual display rejected (${se.message}); retrying untrusted")
            trusted = false
            flags = VdFlags.displayFlags(Build.VERSION.SDK_INT, trusted)
            try {
                dm.createVirtualDisplay(name, w, h, d, reader.surface, flags)
            } catch (e: Throwable) {
                // M1: the framework refused even an untrusted PUBLIC display in
                // the app process (the likely outcome on stock framework — a
                // plain app may only create PRIVATE displays). Hand the same
                // ImageReader surface to the Shizuku shell host, which owns
                // the display with shell privilege.
                // P0 review fix: broad catch (Throwable, not just
                // SecurityException) so an unexpected retry failure degrades
                // to the shell fallback instead of escaping the method.
                return shellFallback(context, name, reader, w, h, d,
                    "trusted: ${se.message}; untrusted: ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (e: Throwable) {
            // Non-security failure in-process (OOM, …) — still try the shell
            // host; it is the documented M1 path for any CREATION_FAILED.
            return shellFallback(context, name, reader, w, h, d,
                "${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
        }

        val thread = HandlerThread("$name").apply { start() }
        val display = MinisAgentDisplay(
            context, dm, virtualDisplay, reader, thread,
            name, w, h, d, flags, trusted,
        )
        display.setupDisplayConfiguration()
        if (awakeNeeded(display.flags, display.displayId)) awake.start(display.displayId)
        synchronized(lock) { active[display.displayId] = display }
        AppLogger.info(TAG, "created $name: displayId=${display.displayId} trusted=$trusted flags=0x${flags.toString(16)}")
        return VdCreateResult.Ok(display)
    }

    /**
     * M1 fallback, entered from [create] on ANY in-process CREATION_FAILED.
     *
     * The app still owns the ImageReader (frames keep landing in-process);
     * only the display owner moves to the Shizuku shell host (uid 2000),
     * which is the only identity that can create PUBLIC + TRUSTED displays
     * and start apps into secondary users. The resulting
     * [MinisShellAgentDisplay] is registered like an in-process one, so
     * `android-vd-cli` (create/shot/release) is transparent to the switch.
     *
     * Fails (code `SHELL_CREATION_FAILED`) when the host is unreachable
     * (Shizuku not READY / helper too old) or the host itself refuses —
     * in which case the reader is closed and the caller sees the reason
     * from [MinisShellClient.lastError].
     */
    private fun shellFallback(
        context: Context,
        name: String,
        reader: ImageReader,
        w: Int,
        h: Int,
        d: Int,
        inProcessError: String,
    ): VdCreateResult {
        AppLogger.info(TAG, "in-process creation failed ($inProcessError); trying Shizuku shell host")
        val shellId = MinisShellClient.createDisplay(reader.surface, w, h, d)
        if (shellId < 0) {
            reader.close()
            return VdCreateResult.Failed(
                "SHELL_CREATION_FAILED",
                "In-process createVirtualDisplay was refused ($inProcessError) and the Shizuku shell host " +
                    "could not create the display either: ${MinisShellClient.lastError() ?: "unknown"}. " +
                    "Prerequisites: Shizuku installed + started (wireless/USB debugging, shell uid 2000) " +
                    "+ Minis authorized in Shizuku — see docs/virtual-display-M1.md.",
            )
        }
        val display = MinisShellAgentDisplay(context, reader, shellId, name, w, h, d)
        // Keep-awake for a shell-owned OWN_DISPLAY_GROUP display lives INSIDE
        // the host process (its own DisplayAwake port); the local one would
        // report user activity for a display it does not own.
        synchronized(lock) { active[shellId] = display }
        AppLogger.info(TAG, "shell host created display $shellId for $name (in-process: $inProcessError)")
        return VdCreateResult.Ok(display)
    }

    fun get(displayId: Int): AgentDisplayApi? = synchronized(lock) { active[displayId] }
    fun list(): List<AgentDisplayApi> = synchronized(lock) { active.values.toList() }

    /** Destroy one display and stop its keep-awake. True if it was tracked. */
    fun release(displayId: Int): Boolean {
        val d = synchronized(lock) { active.remove(displayId) } ?: return false
        awake.stop(displayId)
        d.release()
        AppLogger.info(TAG, "released display $displayId")
        return true
    }

    /** Destroy every display (in-process and shell-owned), e.g. on application teardown. */
    fun releaseAll() {
        synchronized(lock) {
            active.values.toList().also { active.clear() }
                .forEach { awake.stop(it.displayId); it.release() }
        }
    }

    // ── keep-awake (Mika DisplayAwake semantics) ─────────────────────────────
    // A display created with OWN_DISPLAY_GROUP forms its own display group
    // with its own doze timer; injected input does not count as user
    // activity, so after the doze timeout the group sleeps: no more frames,
    // no input responses. While such a display is alive, report user
    // activity for it every 4s via IPowerManager.userActivity(display, ...).

    private fun awakeNeeded(flags: Int, displayId: Int): Boolean {
        if (flags and VdFlags.FLAG_OWN_DISPLAY_GROUP == 0) return false
        // Reports to the default group (the user's main screen) would keep the
        // user's screen alive too — only screens that truly own a group.
        return displayGroup(displayId) != VdAwake.DEFAULT_DISPLAY_GROUP
    }

    /** DisplayInfo.displayGroupId (hidden field); null when unreadable → treat as own group. */
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    private fun resolveUserActivity(): Pair<Any, Method>? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "power") ?: error("no power binder")
        val power = Class.forName("android.os.IPowerManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
        val method = VdAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity")
        power to method
    }.onFailure {
        AppLogger.warning(TAG, "display keep-awake unavailable: ${it.message}")
    }.getOrNull()

    private fun pokeDisplay(displayId: Int): Boolean {
        if (userActivity == null) {
            synchronized(lock) {
                if (!userActivityResolved) {
                    userActivity = resolveUserActivity()
                    userActivityResolved = true
                }
            }
        }
        val pair = userActivity ?: return false
        return try {
            pair.second.invoke(pair.first, displayId, SystemClock.uptimeMillis(), VdAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "userActivity($displayId) failed; display keep-awake off")
            false
        }
    }
}

/**
 * DisplayManager.VIRTUAL_DISPLAY_FLAG_* — the values are hand-written bits
 * (part of them are hidden constants), ported verbatim from Mika
 * `ShellService.kt` `displayFlags`.
 */
object VdFlags {
    const val FLAG_PUBLIC = 1 shl 0
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val FLAG_OWN_FOCUS = 1 shl 14
    const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /**
     * Flags for an agent virtual display. When [trusted] is false the bits
     * that only work on trusted displays are dropped: on systems where the
     * creator lacks ADD_TRUSTED_DISPLAY, a TRUSTED request is rejected with
     * a SecurityException; OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED and
     * OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED are likewise only usable on
     * trusted displays. The rest are independent of trust and kept.
     */
    fun displayFlags(sdk: Int, trusted: Boolean): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
            if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        }
        // With OWN_FOCUS alone, starting an app on the virtual display (or the
        // app opening a new page itself) makes it the top focus display and
        // the main display's focused window is cleared;
        // STEAL_TOP_FOCUS_DISABLED (needs OWN_FOCUS + TRUSTED) stops it
        // stealing the main display's focus.
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }
}

/**
 * Port of Mika `DisplayAwake`: run the poke immediately, then every 4s;
 * stop it when the poke cannot report user activity (the display still
 * works normally, it just falls asleep on its own timer).
 */
private class VdAwake(private val poke: (Int) -> Boolean) {
    private val timer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "minis-vd-awake").apply { isDaemon = true }
    }
    private val running = HashMap<Int, () -> Unit>()
    @Volatile
    private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = timer.scheduleWithFixedDelay({
            if (!poke(displayId)) giveUp()
        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
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
        const val DEFAULT_DISPLAY_GROUP = 0

        /**
         * The per-display `userActivity(int displayId, long time, int event, int flags)`
         * overload on IPowerManager (Android 12+); the old overload without a
         * display id reports into the default group and would keep the user's
         * main screen alive — we only use the per-display one.
         */
        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(
                arrayOf(
                    Int::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            )
        }
    }
}

/**
 * ROTATION_0/90/180/270 are 0/1/2/3; the physical buffer is fixed, the
 * logical screen swaps width/height on a quarter turn.
 * Ported from Mika `DisplayFrameGeometry` (Apache-2.0).
 */
private data class VdFrameGeometry(val bufferWidth: Int, val bufferHeight: Int, val rotation: Int) {
    private val sideways get() = rotation == 1 || rotation == 3
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight
}

/**
 * The system rotates the logical picture clockwise into the fixed physical
 * buffer; rotate it back so screenshots use the app's logical coordinates.
 * Ported from Mika `DisplayFrame.uprightDisplayFrame` (Apache-2.0).
 */
private fun vdUprightFrame(
    buffer: Bitmap,
    geometry: VdFrameGeometry,
    maxWidth: Int = geometry.width,
    paint: Paint,
): Bitmap {
    val w = minOf(maxWidth, geometry.width)
    val h = maxOf(1, (geometry.height.toLong() * w / geometry.width).toInt())
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        scale(w.toFloat() / geometry.width, h.toFloat() / geometry.height)
        when (geometry.rotation) {
            1 -> { translate(0f, geometry.bufferWidth.toFloat()); rotate(-90f) }
            2 -> { translate(geometry.bufferWidth.toFloat(), geometry.bufferHeight.toFloat()); rotate(180f) }
            3 -> { translate(geometry.bufferHeight.toFloat(), 0f); rotate(90f) }
        }
        val content = Rect(0, 0, geometry.bufferWidth, geometry.bufferHeight)
        drawBitmap(buffer, content, content, paint)
    }
    return result
}
