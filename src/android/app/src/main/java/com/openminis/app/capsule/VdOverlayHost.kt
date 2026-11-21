package com.openminis.app.capsule

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import kotlin.math.hypot

/**
 * Virtual-display capsule overlay — a live preview window floating over the
 * user's main screen, drawn by the a11y service's own window token
 * (TYPE_ACCESSIBILITY_OVERLAY), so it does NOT need the "display over other
 * apps" overlay permission.
 *
 * Ported from Mika `cap/phone/a11y/OverlayHost.kt` (Apache-2.0), trimmed to
 * the capsule's needs: open/place/update/resize/close, a draggable handle,
 * fullscreen toggle and outside-touch. Drag/hold-press gesture tracking is
 * kept (capsule moves with the finger, 1:1, no spring physics — the spring
 * corner-snapping of Mika's in-app `AgentPip` is an app-window feature and
 * is not ported).
 *
 * Lifetime: the service may be killed + re-bound by OEM ROMs (MIUI
 * "此服务出现故障"), so callers treat windows as best-effort:
 * `open` returns null when the token is gone, and the capsule controller
 * re-mounts after `onServiceConnected` (see MinisCapsule).
 */
class VdOverlayHost internal constructor(private val service: MinisAccessibilityService) {

    private val windows = mutableListOf<VdOverlayWindow>()

    /**
     * Add a content-sized overlay window. Untouched areas pass through to
     * the app underneath. Returns null (never throws) when the service is
     * already disconnected or the system refuses the window type.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun open(
        placement: OverlayPlacement,
        onOutside: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ): VdOverlayWindow? {
        val owners = VdOverlayOwners()
        val frame = VdOverlayFrame(service).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            addView(
                ComposeView(service).apply { setContent(content) },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            this.onOutside = onOutside
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Lay out against the full screen (status bar / notch / nav bar
            // included); the caller decides where to avoid via [OverlayArea].
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            gravity = placement.gravity
            x = placement.x
            y = placement.y
        }
        val window = VdOverlayWindow(this, frame, params, owners)
        owners.start()
        return try {
            wm().addView(frame, params)
            windows += window
            window
        } catch (e: RuntimeException) {
            // Token just went stale (service killed + re-bound) or the ROM
            // does not allow this window type: degrade to no overlay.
            AppLogger.warning(CAPSULE_TAG, "a11y overlay unavailable: ${e.message}")
            owners.destroy()
            null
        }
    }

    /** Screen size and the four system-bar/notch insets (rotates with the screen). */
    fun area(): OverlayArea = runCatching {
        val m = wm().currentWindowMetrics
        val i = m.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
        OverlayArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
    }.getOrElse {
        val d = service.resources.displayMetrics
        val bar = (24f * d.density).toInt()
        OverlayArea(d.widthPixels, d.heightPixels, insetTop = bar, insetBottom = bar)
    }

    /** Tear down every overlay (service disconnect / display released). */
    fun closeAll() {
        windows.toList().forEach(VdOverlayWindow::close)
    }

    internal fun wm(): WindowManager = service.getSystemService(WindowManager::class.java)

    internal fun forget(window: VdOverlayWindow) {
        windows.remove(window)
    }

    private companion object {
        const val CAPSULE_TAG = "VdCapsule"
    }
}

/** Where the window sits: [gravity] is a [Gravity] combo, [x]/[y] are pixels from that edge. */
data class OverlayPlacement(val gravity: Int, val x: Int = 0, val y: Int = 0)

/** Screen dimensions (current orientation) plus the system-bar / notch insets. */
data class OverlayArea(
    val width: Int,
    val height: Int,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
)

/**
 * Dragging the window: after the finger moves past the system touch slop
 * this counts as a drag; while inside the content, the buttons get a cancel
 * so a drag is never swallowed as a tap. [move]/[end] are screen-relative
 * deltas of the whole gesture (screen coordinates; the window follows the
 * finger without jitter).
 */
interface OverlayDrag {
    fun start()
    fun move(dx: Float, dy: Float)
    fun end(dx: Float, dy: Float)
}

/** One window added by [VdOverlayHost.open]. All no-ops once closed. */
class VdOverlayWindow internal constructor(
    private val host: VdOverlayHost,
    private val frame: VdOverlayFrame,
    private val params: WindowManager.LayoutParams,
    private val owners: VdOverlayOwners,
) {
    var isOpen = true
        private set

    val width: Int get() = frame.width
    val height: Int get() = frame.height

    /** Move the window. */
    fun place(placement: OverlayPlacement) {
        if (!isOpen) return
        if (params.gravity == placement.gravity && params.x == placement.x &&
            params.y == placement.y) return
        params.gravity = placement.gravity
        params.x = placement.x
        params.y = placement.y
        update()
    }

    /**
     * Enable dragging via [drag], or pass null to disable. [handle] is the
     * sub-rect of the window (window-local pixels, e.g. the title bar) that
     * starts a drag; null means any touch in the window drags it.
     */
    fun draggable(drag: OverlayDrag?, handle: Rect? = null) {
        if (!isOpen) return
        frame.drag = drag
        frame.handle = handle?.let { Rect(it) }
    }

    /** Full-screen (the expanded PiP dims everything behind) or back to content-sized. */
    fun setFullScreen(full: Boolean) {
        if (!isOpen) return
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT
        else WindowManager.LayoutParams.WRAP_CONTENT
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        update()
    }

    /** User pressed BACK inside a focusable window (IMM focus is ours). */
    fun onBack(onBack: (() -> Unit)?) {
        if (isOpen) frame.onBack = onBack
    }

    /** Content size changed (capsule → card): [onResize] re-places the window. */
    fun onResize(onResize: (() -> Unit)?) {
        if (isOpen) frame.onResize = onResize
    }

    /**
     * Hide or show the whole window explicitly: merely clearing the Compose
     * content waits for the next layout, and the old frame can linger in
     * the system window canvas — visibility must be toggled on purpose.
     */
    fun setVisible(visible: Boolean) {
        if (!isOpen) return
        frame.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Accept keyboard input (IME pops up against this window); turn off
     * immediately when collapsing so focus returns to the app underneath.
     */
    fun setFocusable(focusable: Boolean) {
        if (!isOpen) return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        update()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        frame.release()
        host.forget(this)
        runCatching { host.wm().removeViewImmediate(frame) }
        owners.destroy()
    }

    private fun update() {
        runCatching { host.wm().updateViewLayout(frame, params) }.onFailure { close() }
    }

}

/**
 * Root of an overlay window: the Compose content plus gesture plumbing.
 * Catches outside-down ([onOutside]); when draggable, a touch inside the
 * handle ([handle], or the whole window when unset) that moves past the
 * touch slop is intercepted and handed to [drag].
 */
internal class VdOverlayFrame(context: Context) : android.widget.FrameLayout(context) {
    var onOutside: (() -> Unit)? = null
    var onResize: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var drag: OverlayDrag? = null
    var handle: Rect? = null

    private var slop = 0f
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    /** The cancel we send into the content ourselves is not a system cancel. */
    private var ownCancel = false

    init {
        slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                onResize?.invoke()
            }
        }
    }

    fun release() {
        onOutside = null
        onResize = null
        onBack = null
        drag = null
        handle = null
        tracking = false
        dragging = false
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val outside = onOutside ?: return false
            outside()
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val back = onBack
        if (back != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e) && (dragging)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = track(e)

    private fun track(e: MotionEvent): Boolean {
        if (ownCancel) return true
        val d = drag
        if (d == null) {
            tracking = false
            dragging = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                tracking = handle?.contains(e.x.toInt(), e.y.toInt()) ?: true
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    d.start()
                }
                if (dragging) d.move(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) d.end(e.rawX - downX, e.rawY - downY)
                tracking = false
                dragging = false
            }
        }
        return tracking
    }
}

/**
 * Compose outside an Activity must get its lifecycle, saved-state and
 * ViewModel owners from somewhere: these enter RESUMED when the window is
 * added and are destroyed when it is closed.
 */
internal class VdOverlayOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState =
            Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
