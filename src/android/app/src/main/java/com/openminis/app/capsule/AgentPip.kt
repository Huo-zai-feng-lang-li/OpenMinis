package com.openminis.app.capsule

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.display.MinisAgentDisplayManager
import com.openminis.app.logging.AppLogger

/**
 * The expanded "PiP": tapping the capsule frame grows the preview into a
 * full-screen a11y overlay (same TYPE_ACCESSIBILITY_OVERLAY mechanism as
 * the capsule, so no overlay permission). Tap anywhere, or ×, to collapse.
 * Ported in spirit from Mika `ui/AgentPip.kt` (Apache-2.0): Mika expands
 * in place on the app's own surface; here it is a second overlay window
 * because the capsule lives on the a11y service.
 */
object VdAgentPip {

    private const val TAG = "VdCapsule"

    private var pipWindow: VdOverlayWindow? = null
    private var pipHost: VdOverlayHost? = null
    private var pipService: MinisAccessibilityService? = null

    /** Open the fullscreen preview of [displayId]; no-ops if it is already open. */
    fun expand(service: MinisAccessibilityService?, displayId: Int, onClosed: () -> Unit) {
        if (pipWindow?.isOpen == true) return
        val svc = service ?: MinisAccessibilityService.getInstance() ?: return
        if (pipService !== svc) {
            close()
            pipHost = VdOverlayHost(svc)
            pipService = svc
        }
        val host = pipHost!!
        val window = host.open(
            OverlayPlacement(android.view.Gravity.CENTER),
            onOutside = { collapse(onClosed) },
        ) {
            PipContent(displayId)
        } ?: run {
            AppLogger.warning(TAG, "pip overlay unavailable, capsule preview stays")
            return
        }
        window.setFullScreen(true)
        pipWindow = window
    }

    /** Close the fullscreen preview (the capsule card is untouched). */
    fun close() {
        pipWindow?.close()
        pipWindow = null
    }

    private fun collapse(onClosed: () -> Unit) {
        close()
        onClosed()
    }

    @Composable
    private fun PipContent(displayId: Int) {
        val display = remember(displayId) { MinisAgentDisplayManager.get(displayId) }
        var frame: Bitmap? by remember { mutableStateOf(null) }
        val context = LocalContext.current
        val density = LocalDensity.current

        LaunchedEffect(displayId) {
            val d = display ?: return@LaunchedEffect
            d.frames.previewFrames(1_000L / PREVIEW_FPS) { a, b ->
                a != null && b != null && sameFrame(a, b)
            }.collect { f ->
                val prev = frame
                if (f != null && prev != null && prev !== f) prev.recycle()
                frame = f
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { close() },
        ) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = stringResource(R.string.capsule_vd_display, displayId),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Row {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.capsule_vd_btn_close),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp).clickable { close() }.padding(4.dp),
                    )
                }
            }
            val screenW = context.resources.displayMetrics.widthPixels
            val frameW = ((screenW * 0.55f) / density.density).dp
            val aspect = frame?.let { it.width.toFloat() / it.height } ?: (9f / 19.5f)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(48.dp)
                    .width(frameW)
                    .aspectRatio(aspect, matchHeightConstraintsFirst = false)
                    .background(Color(0xFF000000)),
            ) {
                val f = frame
                if (f != null && !f.isRecycled) {
                    Image(
                        bitmap = f.asImageBitmap(),
                        contentDescription = stringResource(R.string.capsule_vd_display, displayId),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.capsule_vd_waiting),
                        fontSize = 14.sp,
                        color = Color(0x99FFFFFF),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
