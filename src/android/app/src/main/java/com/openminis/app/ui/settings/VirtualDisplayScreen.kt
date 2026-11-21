package com.openminis.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.openminis.app.R
import com.openminis.app.capsule.MinisCapsule
import com.openminis.app.display.MinisAgentDisplay
import com.openminis.app.display.MinisAgentDisplayManager
import com.openminis.app.display.VdCreateResult
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VD_ATTACHMENTS = "/var/minis/attachments"
private const val VD_SHOT_SUBDIR = "screenshots"
private const val VD_STALE_MS = 3_000L

/**
 * Settings › Virtual display: manage the in-process virtual displays
 * ([MinisAgentDisplayManager]) and follow one of them live. The floating
 * capsule overlay (com.openminis.app.capsule) is the primary path — this
 * page is the manual management surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualDisplayScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // One entry per create/release action + display id; the list is rebuilt
    // from the manager on every change.
    var displays by remember {
        mutableStateOf<Set<MinisAgentDisplay>>(MinisAgentDisplayManager.list().toSet())
    }
    var status by remember { mutableStateOf<String?>(null) }
    var pkg by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("0") }
    var w by remember { mutableStateOf("1080") }
    var h by remember { mutableStateOf("2520") }
    var dpi by remember { mutableStateOf("420") }
    var busy by remember { mutableStateOf(false) }
    var previewId by remember { mutableStateOf<Int?>(null) }

    fun refresh() {
        displays = MinisAgentDisplayManager.list().toSet()
        if (previewId != null && MinisAgentDisplayManager.get(previewId!!) == null) previewId = null
    }

    fun runAction(label: String, block: () -> Unit) {
        busy = true
        status = null
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (t: Throwable) {
                    status = t.message ?: t.javaClass.simpleName
                }
            }
            refresh()
            if (status == null) status = label
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vd_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── manage ────────────────────────────────────────────────────
            SettingsSectionLike(stringResource(R.string.vd_settings_manage)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = w, onValueChange = { w = it }, label = { Text("W") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = h, onValueChange = { h = it }, label = { Text("H") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dpi, onValueChange = { dpi = it }, label = { Text("DPI") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                StatusText(status)
                Text(
                    text = stringResource(R.string.vd_create_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(
                        label = stringResource(R.string.vd_btn_create),
                        enabled = !busy,
                        onClick = {
                            runAction(stringResource(R.string.vd_created)) {
                                val r = MinisAgentDisplayManager.create(
                                    context,
                                    w.toIntOrNull() ?: 1080,
                                    h.toIntOrNull() ?: 2520,
                                    dpi.toIntOrNull() ?: 420,
                                )
                                if (r is VdCreateResult.Failed) error(r.code + ": " + r.message)
                            }
                        },
                    )
                    displays.forEach { d ->
                        SmallButton(
                            label = stringResource(R.string.vd_btn_release, d.displayId),
                            enabled = !busy,
                            onClick = {
                                runAction(stringResource(R.string.vd_released)) {
                                    MinisAgentDisplayManager.release(d.displayId)
                                }
                            },
                        )
                    }
                }
            }

            // ── list + preview ───────────────────────────────────────────
            SettingsSectionLike(stringResource(R.string.vd_settings_list)) {
                if (displays.isEmpty()) {
                    Text(
                        text = stringResource(R.string.vd_none),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                displays.forEach { d ->
                    val selected = previewId == d.displayId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                previewId = if (selected) null else d.displayId
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.capsule_vd_display, d.displayId),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(
                                    R.string.vd_screen_meta,
                                    d.bufferWidth, d.bufferHeight, d.dpi,
                                    if (d.trusted) "trusted" else "public",
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (selected) {
                            Text("●", fontSize = 12.sp, color = Color(0xFF34C759))
                        }
                    }
                }
                val shown = previewId?.let { MinisAgentDisplayManager.get(it) }
                if (shown != null) {
                    VdPreviewPane(shown)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(stringResource(R.string.vd_btn_expand)) {
                            MinisCapsule.expandPip(shown.displayId)
                        }
                        SmallButton(stringResource(R.string.vd_btn_shot)) {
                            runAction(stringResource(R.string.vd_shot_ok)) {
                                val png = shown.latestFramePng()
                                    ?: error(stringResource(R.string.vd_shot_no_frame))
                                val dir = PRootKernel.resolveHostPath(VD_ATTACHMENTS)
                                    ?: error(stringResource(R.string.vd_shot_no_mount))
                                val f = java.io.File(dir, VD_SHOT_SUBDIR).apply { mkdirs() }
                                val out = java.io.File(f, "vd_ui_${System.currentTimeMillis()}.png")
                                java.io.FileOutputStream(out).use { it.write(png) }
                            }
                        }
                    }
                }

                // ── launch an app onto the display ───────────────────────
                SettingsSectionLike(stringResource(R.string.vd_settings_launch)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = pkg,
                            onValueChange = { pkg = it },
                            label = { Text(stringResource(R.string.vd_pkg_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            label = { Text("user") },
                            singleLine = true,
                            modifier = Modifier.weight(0.5f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.vd_launch_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    SmallButton(stringResource(R.string.vd_btn_launch)) {
                        val target = shown ?: MinisAgentDisplayManager.list().lastOrNull()
                        val p = pkg.trim()
                        if (p.isEmpty()) return@SmallButton
                        runAction(stringResource(R.string.vd_launch_ok, p)) {
                            if (target == null) error(stringResource(R.string.vd_launch_no_display))
                            val r = ShizukuManager.runProcess(
                                listOf(
                                    "am", "start",
                                    "--user", user.trim().ifEmpty { "0" },
                                    "--display", target.displayId.toString(),
                                    "-n", p,
                                ).toTypedArray(),
                                timeoutMs = 15_000,
                            )
                            if (r.exitCode != 0) error("am start: ${r.combined.take(300)}")
                        }
                    }
                }
            }
        }
    }
}

/** Inline 500ms-poll live frame (the capsule overlay is the primary path). */
@Composable
private fun VdPreviewPane(display: MinisAgentDisplay) {
    var frame: Bitmap? by remember(display.displayId) { mutableStateOf(null) }
    var stale by remember(display.displayId) { mutableStateOf(false) }

    LaunchedEffect(display.displayId) {
        while (true) {
            val copy = withContext(Dispatchers.Default) {
                runCatching { display.latestFrameBitmap() }.getOrNull()
            }
            copy?.let {
                val prev = frame
                if (prev != null && prev !== it) prev.recycle()
                frame = it
            }
            stale = display.lastFrameAt().let { ts ->
                ts == null || System.nanoTime() - ts > VD_STALE_MS
            }
            delay(500L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color(0xFF000000), RoundedCornerShape(12.dp))
            .let {
                val f = frame
                if (f != null) {
                    val aspect = f.width.toFloat() / f.height
                    it.heightIn((320f / aspect).dp)
                } else it.height(320.dp)
            },
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null) {
            Image(
                bitmap = f.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
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
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
        }
        Text(
            text = stringResource(R.string.capsule_vd_display, display.displayId),
            fontSize = 11.sp,
            color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}

@Composable
private fun SettingsSectionLike(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.05f),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled) { onClick() },
    )
}

@Composable
private fun StatusText(status: String?) {
    if (status != null) {
        Text(text = status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}
