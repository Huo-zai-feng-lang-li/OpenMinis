package com.openminis.app.capsule

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.display.MinisAgentDisplayManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.shizuku.MinisShellClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** Max fps the capsule card stream is pushed at (Mika `capsule.PREVIEW_FPS`). */
const val PREVIEW_FPS = 5

/**
 * Ported from Mika `capsule/CapsulePreview.kt` (Apache-2.0): at most one
 * value per [intervalMs] (later ones within the window are conflated to
 * the latest) and a value identical to the last pushed one ([same]) is
 * skipped. Collect while the card is visible, cancel when it is hidden.
 * [T] may be nullable: nulls pass through (the preview stream publishes
 * null on black frames to clear the display).
 */
fun <T> Flow<T>.previewFrames(
    intervalMs: Long = 1_000L / PREVIEW_FPS,
    same: (T?, T?) -> Boolean = { a, b -> a === b },
): Flow<T> = flow {
    var last: T? = null
    var haveLast = false
    conflate().collect { f ->
        if (haveLast && (last === f || same(last, f))) return@collect
        last = f
        haveLast = true
        emit(f)
        delay(intervalMs)
    }
}

/**
 * A new frame equal to the previous one (same instance, or same size +
 * pixels) is not pushed. The virtual display recycles its old previews, so
 * check [Bitmap.isRecycled] before pixel-comparing.
 */
fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
    a === b || (!a.isRecycled && !b.isRecycled &&
        a.width == b.width && a.height == b.height && a.sameAs(b))

/**
 * The capsule controller: mounts the floating virtual-display preview on the
 * a11y service's overlay windows.
 *
 * Lazy + frame-gated (Mika lesson #117): the capsule only exists while a
 * virtual display does — display created → window opened; display released
 * or the a11y service destroyed → windows removed.
 *
 * Session-bound ([vd-session-binding]): the window tracks the display owned
 * by the ACTIVE chat session only (MinisAgentDisplayManager.
 * currentSessionDisplayId), not merely "some display is alive" — other
 * sessions' displays keep living in the background with their capsule
 * closed; switching sessions mounts/unmounts the window without destroying
 * any display. OEM ROMs that kill the
 * a11y service (MIUI re-bind) degrade to "no capsule" until
 * [onServiceConnected] re-mounts it; when the overlay is refused entirely
 * the fallback is the in-app preview (docs/virtual-display-preview.md).
 *
 * All public methods are main-thread safe: they hop onto the main looper
 * internally.
 */
object MinisCapsule {

    private const val TAG = "VdCapsule"

    private val main = Handler(Looper.getMainLooper())
    private var host: VdOverlayHost? = null
    private var hostService: MinisAccessibilityService? = null
    private var capsuleWindow: VdOverlayWindow? = null
    private var capsulePlacement: OverlayPlacement? = null
    private var dragStart: OverlayPlacement? = null
    private var lastPlaceMs = 0L
    private var managed = false

    private val onDisplayChanged: () -> Unit = { refresh() }

    /**
     * Called from [MinisAccessibilityService.onServiceConnected] — including
     * after an OEM kill + re-bind, where the capsule re-mounts itself if a
     * virtual display still exists.
     */
    fun onServiceConnected(service: MinisAccessibilityService) {
        main.post {
            if (!managed) {
                MinisAgentDisplayManager.addChangeListener(onDisplayChanged)
                managed = true
            }
            host = VdOverlayHost(service)
            AppLogger.info(TAG, "onServiceConnected: active displays = ${MinisAgentDisplayManager.list().map { it.displayId }}")
            refresh()
        }
    }

    /** Called from [MinisAccessibilityService.onDestroy]: the windows are gone with the token. */
    fun onServiceDestroyed() {
        main.post {
            teardown()
        }
    }

    /**
     * User tapped ×: the capsule is closed and stays closed until the next
     * display create/release event re-mounts it (a11y service teardown
     * always tears it down too).
     */
    fun hide() {
        main.post {
            closeWindows()
        }
    }

    /**
     * Show the expanded PiP over the main screen for [displayId] (tap the
     * capsule frame). No-ops when there is nothing to show.
     */
    fun expandPip(displayId: Int) {
        main.post {
            VdAgentPip.expand(currentService(), displayId, onClosed = { })
        }
    }

    private fun refresh() {
        val service = currentService() ?: return
        if (service !== hostService) {
            closeWindows()
            host = VdOverlayHost(service)
            hostService = service
        }
        // [vd-session-binding] Capsule-session binding: show ONLY the display
        // owned by the ACTIVE chat session (set via
        // MinisAgentDisplayManager.setCurrentSession from ChatScreen). Other
        // sessions' displays keep living in the background but their
        // capsule stays closed; switching back to a session that still owns
        // a display re-mounts it. No display is destroyed on switch — the
        // switch only moves the binding.
        val targetId = MinisAgentDisplayManager.currentSessionDisplayId()
        AppLogger.info(TAG, "refresh: active displays = ${MinisAgentDisplayManager.list().map { it.displayId }}, current-session display = $targetId, mounting = ${targetId != null}")
        if (targetId == null) {
            closeWindows()
            return
        }
        val target = MinisAgentDisplayManager.get(targetId)
        if (target == null) {
            closeWindows()
            return
        }
        openCapsule(service, target.displayId)
    }

    private fun openCapsule(service: MinisAccessibilityService, displayId: Int) {
        if (capsuleWindow?.isOpen == true) return
        val host = host ?: VdOverlayHost(service).also {
            this.host = it
            hostService = service
        }
        val area = host.area()
        val placement = OverlayPlacement(
            Gravity.BOTTOM or Gravity.END,
            x = area.insetRight + 48,
            y = area.insetBottom + 48,
        )
        val window = host.open(placement, onOutside = null) {
            CapsuleContent(displayId)
        }
        if (window == null) {
            AppLogger.warning(TAG, "openCapsule failed: VdOverlayHost.open returned null (overlay window refused?)")
            return
        }
        capsulePlacement = placement
        // The whole card is the drag handle; a tap still reaches the frame
        // (drag only after the touch slop). The window follows the finger 1:1.
        //
        // Correctness: OverlayDrag.move()/end() pass the CUMULATIVE delta since
        // the gesture's DOWN point, so each move() call recomputes the target
        // from the gesture-start placement ([dragStart]), NOT from the
        // already-moved [capsulePlacement] (subtracting a cumulative delta from
        // a moved position compounds the error and makes the card run ahead of
        // the finger). gravity is BOTTOM|END, so the x offset is measured from
        // the RIGHT edge and the y offset from the BOTTOM edge — a rightward /
        // downward finger delta DECREASES both offsets.
        window.draggable(object : OverlayDrag {
            override fun start() {
                dragStart = capsulePlacement
            }

            override fun move(dx: Float, dy: Float) {
                moveCapsule(dx, dy)
            }

            override fun end(dx: Float, dy: Float) {
                // Final precise snap (throttled MOVEs may have left the card
                // up to 1 frame behind the finger on a fast release).
                moveCapsule(dx, dy, final = true)
                dragStart = null
            }
        })
        capsuleWindow = window
        AppLogger.info(TAG, "capsule mounted on display $displayId")
    }

    /**
     * Move the capsule to the position [dx]/[dy] (screen-relative, + =
     * right / down) past the gesture-start anchor [dragStart], clamped to the
     * screen. [dx]/[dy] are the CUMULATIVE deltas since the drag's DOWN
     * point, so this recomputes from [dragStart] every time (never from the
     * already-moved [capsulePlacement], which would compound the error).
     */
    private fun moveCapsule(dx: Float, dy: Float, final: Boolean = false) {
        val w = capsuleWindow ?: return
        val s = dragStart ?: return
        val a = host?.area() ?: return
        val nx = (s.x - dx).toInt().coerceIn(0, maxOf(0, a.width - w.width))
        val ny = (s.y - dy).toInt().coerceIn(0, maxOf(0, a.height - w.height))
        val np = OverlayPlacement(s.gravity, nx, ny)
        capsulePlacement = np
        // Throttle updateViewLayout to ~60Hz: touch moves arrive at 90-120Hz
        // and each place() is a synchronous WindowManager IPC; unthrottled
        // that is the capsule's own jank source. Non-final moves that fall
        // inside the 16ms window are skipped (the next move carries the newer
        // cumulative delta); the final move always lands precisely.
        val now = SystemClock.uptimeMillis()
        if (!final && now - lastPlaceMs < 16L) return
        lastPlaceMs = now
        w.place(np)
    }

    /** Screen metrics for sizing the capsule content (null when no host yet). */
    fun currentArea(): OverlayArea? = host?.area()

    private fun closeWindows() {
        capsuleWindow?.close()
        capsuleWindow = null
        capsulePlacement = null
        dragStart = null
        VdAgentPip.close()
    }

    private fun teardown() {
        MinisAgentDisplayManager.removeChangeListener(onDisplayChanged)
        managed = false
        closeWindows()
        host = null
        hostService = null
    }

    private fun currentService(): MinisAccessibilityService? =
        MinisAccessibilityService.getInstance()
}

/**
 * The capsule card: drag-anywhere live preview of virtual display
 * [displayId]. Tap the frame to expand into the fullscreen PiP
 * ([VdAgentPip]). Top bar: back / pause-eye / close — back injects
 * KEYCODE_BACK into the virtual display via the shell host; × hides the
 * capsule; the eye freezes/releases the frame stream.
 */
@Composable
fun CapsuleContent(displayId: Int) {
    val display = remember(displayId) { MinisAgentDisplayManager.get(displayId) }
    val displayIdLabel = stringResource(R.string.capsule_vd_display, displayId)
    var frame: Bitmap? by remember { mutableStateOf(null) }
    var paused by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0L) }
    // Tapping the frame toggles the expanded (bigger, moved up) state; the
    // window is BOTTOM|END-anchored so it grows up + to the left.
    var expanded by remember { mutableStateOf(false) }

    // Stream: the display's preview frames (≤10 fps upstream, short side
    // ≤360px) throttled to ≤PREVIEW_FPS and deduped; cancel when paused.
    LaunchedEffect(displayId, paused) {
        if (paused) return@LaunchedEffect
        val d = display ?: return@LaunchedEffect
        d.frames.previewFrames(1_000L / PREVIEW_FPS) { a, b ->
            a != null && b != null && sameFrame(a, b)
        }.collect { f ->
            val prev = frame
            if (f != null && prev != null && prev !== f) prev.recycle()
            frame = f
            tick++
        }
    }

    // Re-evaluate the "stale" indicator once a second even without new frames.
    LaunchedEffect(displayId) {
        while (true) {
            delay(1_000L)
            tick++
        }
    }

    val lastFrameAt = remember(displayId, tick) {
        runCatching { display?.lastFrameAt() }.getOrNull()
    }
    val nowNanos = remember(displayId, tick) { SystemClock.elapsedRealtimeNanos() }
    val stale = lastFrameAt != null && nowNanos - lastFrameAt > STALE_AFTER_NANOS

    // Card size: default 3/4 of screen width × 2/5 of screen height,
    // expanded 4/5 × 3/5 (bigger + higher). Falls back to 1080×2520 px.
    // [area] is in pixels; divide by the display density (px-per-dp) so the
    // card is a fixed fraction of the physical screen, then express as Dp.
    val area = remember { MinisCapsule.currentArea() }
    val pxPerDp = LocalDensity.current.density
    val cardW = ((area?.width ?: 1080) * (if (expanded) 0.8f else 0.75f)) / pxPerDp
    val cardH = ((area?.height ?: 2520) * (if (expanded) 0.6f else 0.4f)) / pxPerDp

    Column(
        modifier = Modifier
            .size(cardW.dp, cardH.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE61B1B1B))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayIdLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xEEEEEEEE),
                modifier = Modifier.weight(1f),
            )
            CapsuleButton(Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.capsule_vd_btn_back)) {
                MinisShellClient.injectKey(displayId, 4) // KEYCODE_BACK
            }
            CapsuleButton(
                if (paused) Icons.Filled.PlayCircle else Icons.Filled.Pause,
                stringResource(R.string.capsule_vd_btn_pause)
            ) { paused = !paused }
            CapsuleButton(Icons.Filled.Close,
                stringResource(R.string.capsule_vd_btn_close)) {
                MinisCapsule.hide()
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF000000))
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (f != null && !f.isRecycled) {
                Image(
                    bitmap = f.asImageBitmap(),
                    contentDescription = displayIdLabel,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.capsule_vd_waiting),
                    fontSize = 13.sp,
                    color = Color(0x99FFFFFF),
                )
            }
            if (stale) {
                Text(
                    text = stringResource(R.string.capsule_vd_stale),
                    fontSize = 11.sp,
                    color = Color(0xFFFFD60A),
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun CapsuleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    // 44dp target (Material min) with a 22dp glyph centered, so the row of
    // buttons is actually tappable (the old 22dp+2dp pad was ~26dp and
    // nearly impossible to hit on the small card).
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color(0xBBFFFFFF),
            modifier = Modifier.size(22.dp),
        )
    }
}

private const val STALE_AFTER_NANOS = 3_000_000_000L // 3s
