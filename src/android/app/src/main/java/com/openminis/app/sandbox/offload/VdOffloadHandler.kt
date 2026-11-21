package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.display.AgentDisplayApi
import com.openminis.app.display.MinisAgentDisplayManager
import com.openminis.app.display.VdCreateResult
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject

/**
 * T-android-virtual-display-p0: `android-vd-cli` — the P0 in-process virtual
 * display surface.
 *
 * Creates a physically-invisible virtual display inside the Minis app
 * process ([MinisAgentDisplayManager], frame pipeline ported from Mika's
 * AgentDisplay), launches any app — including the dual-app / secondary user
 * (e.g. 999) — onto it via Shizuku `am start --user --display`, captures
 * frames from the app-side ImageReader as PNGs, and pokes the display with
 * Shizuku `input -d`. Reading the UI tree per display is the a11y side:
 * `android-a11y-cli ui displays | dump | find | info --display N`.
 *
 * P0 verification checklist (details: docs/virtual-display-P0.md). The
 * Shizuku shell-host display owner (trusted displays, uid 2000) is the M1
 * fallback wired into `create` — docs/virtual-display-M1.md.
 */
class VdOffloadHandler(private val context: Context) : NativeOffloadHandler {

    companion object {
        private const val TAG = "VdOffload"
        private const val TOOL = "android-vd-cli"

        /**
         * Same mount target as `android-a11y-cli ui screenshot`
         * (AccessibilityOffloadHandler): the session's
         * /var/minis/attachments dir bind-mounted into the guest, so the PNG
         * lands where `read_image` can open it.
         */
        private const val VAR_MINIS_ATTACHMENTS = "/var/minis/attachments"
        private const val VD_SHOT_SUBDIR = "screenshots"

        private const val HELP = """android-vd-cli — P0 in-process virtual display.

Builds a physically-invisible virtual display inside the Minis app process
(frames land in an app-side ImageReader, never on a physical screen), then
launches any app onto it (incl. dual-app user 999), screenshots its frames
and drives input — the full agent-loop backend, main screen untouched.

Usage:
  android-vd-cli <subcommand> [flags]

Subcommands:
  create [--w N] [--h N] [--dpi N]
                   Create a virtual display. Defaults to the main display's
                   physical size/density. Tries the in-process trusted flag
                   set first and downgrades once when the framework refuses
                   it; if even the untrusted PUBLIC display is refused
                   (the common outcome for a plain app uid), it AUTOMATICALLY
                   falls back to the Shizuku shell host (display owned by
                   uid 2000 — docs/virtual-display-M1.md). SHELL_CREATION_
                   FAILED means both owners refused — check Shizuku.
  list             Active in-process displays (+ keep-awake state, last
                   frame) and the display ids visible to the a11y service.
  shot <displayId> PNG of the latest frame →
                   /var/minis/attachments/screenshots/vd_<ts>.png
                   (same session dir as a11y shots; `path` is ready for
                   read_image). NO_FRAME while the pipeline hasn't
                   produced a frame; `blackFrame=true` means the capture
                   path is broken on this ROM (e.g. emulators after the
                   trusted downgrade).
  launch <displayId> --user <u> <component>
                   Launch <component> onto <displayId> as user <u> via
                   Shizuku: `am start --user <u> --display <displayId>
                   -n <component>`. Needs Shizuku READY
                   (`android-shizuku-cli service status`).
  tap <displayId> <x> <y>
                   Tap on the display via Shizuku: `input -d <displayId>
                   tap <x> <y>`.
  release <displayId>
                   Destroy the display, stop its keep-awake, free the
                   frame pipeline.

P0 verification checklist (details: docs/virtual-display-P0.md):
  1. create  — a 1080x2520 PUBLIC display is created (trusted→untrusted
               downgrade path exercised when the ROM refuses TRUSTED);
  2. launch  — a dual-app instance (user 999) starts ON the virtual display;
  3. a11y    — `android-a11y-cli ui displays` lists the display with the
               target app's windows; `ui dump --display <id>` reads their
               nodes (the biggest open question: cross-user visibility);
  4. shot    — the frame is non-black and the UI elements are visible;
  5. tap     — tap/type land on the virtual display; note the HyperOS
               back-key caveat: `input -d <id> key 4` may not reach the
               virtual display (Mika issue #622) — node actions replace it;
  6. longrun — the display stays awake for 30+ minutes and the main
               display's focus is never stolen.

Common flags: --compact / -q / --quiet.
"""
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        if (argv.isEmpty() || argv[0] in setOf("--help", "-h", "help")) return NativeOffloadResult(0, HELP)
        if (argv[0] == "--version") return NativeOffloadResult(0, "$TOOL 0.1 (P0)\n")

        val sub = argv[0]
        val args = OffloadArgs(argv.drop(1))

        // T330-style tri-state agent gate, same as the sibling a11y/shizuku
        // handlers: Settings → Permissions → Integrations.
        if (!OffloadGate.allow("display_cli", TOOL, request)) {
            return errEnvelope(
                "PERMISSION_DENIED",
                "Agent is not allowed to use android-vd-cli. Open Settings → Permissions → Integrations to change.",
                args,
            )
        }

        return try {
            when (sub) {
                "create"  -> create(args)
                "list"    -> list(args)
                "shot"    -> shot(args, request.sessionId)
                "launch"  -> launch(args)
                "tap"     -> tap(args)
                "release" -> release(args)
                else      -> NativeOffloadResult(2, "$TOOL: unknown subcommand '$sub'\n$HELP")
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${t.javaClass.simpleName} ${t.message}")
            errEnvelope("INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}", args)
        }
    }

    // ── create / list / release ────────────────────────────────────────────

    private fun create(args: OffloadArgs): NativeOffloadResult {
        val result = MinisAgentDisplayManager.create(
            context,
            width = args.getInt("w", "width"),
            height = args.getInt("h", "height"),
            dpi = args.getInt("dpi"),
        )
        return when (result) {
            is VdCreateResult.Ok -> {
                val d = result.display
                okEnvelope(
                    JSONObject()
                        .put("displayId", d.displayId)
                        .put("name", d.name)
                        .put("width", d.bufferWidth)
                        .put("height", d.bufferHeight)
                        .put("dpi", d.dpi)
                        .put("trusted", d.trusted)
                        .put("flags", "0x" + d.flags.toString(16))
                        .put("hint", "launch apps onto it: $TOOL launch ${d.displayId} --user 999 <component>"),
                    args,
                )
            }
            is VdCreateResult.Failed -> errEnvelope(result.code, result.message, args)
        }
    }

    private fun list(args: OffloadArgs): NativeOffloadResult {
        val arr = JSONArray()
        for (d in MinisAgentDisplayManager.list()) {
            arr.put(displayToJson(d))
        }
        val a11yVisible = runCatching {
            MinisAccessibilityService.getInstance()?.visibleDisplayIds()?.sorted() ?: emptyList()
        }.getOrDefault(emptyList())
        return okEnvelope(
            JSONObject()
                .put("activeDisplays", arr)
                .put("a11yVisibleDisplayIds", JSONArray(a11yVisible)),
            args,
        )
    }

    private fun release(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL release <displayId>", args)
        if (!MinisAgentDisplayManager.release(id)) {
            return errEnvelope("VD_NOT_FOUND", "no in-process virtual display $id; `list` shows the active ones", args)
        }
        return okEnvelope(JSONObject().put("displayId", id).put("released", true), args)
    }

    private fun displayToJson(d: AgentDisplayApi): JSONObject = JSONObject()
        .put("displayId", d.displayId)
        .put("name", d.name)
        .put("width", d.bufferWidth)
        .put("height", d.bufferHeight)
        .put("dpi", d.dpi)
        .put("trusted", d.trusted)
        .put("flags", "0x" + d.flags.toString(16))
        .put("hasFrame", d.hasFrameNow)
        .put("blackFrame", d.isLastFrameBlack())
        .put("createdWallMs", d.createdWallMs)

    // ── shot ───────────────────────────────────────────────────────────────

    private fun shot(args: OffloadArgs, sessionId: String?): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL shot <displayId>", args)
        val d = MinisAgentDisplayManager.get(id)
            ?: return errEnvelope("VD_NOT_FOUND", "no in-process virtual display $id; `create` it first", args)

        val png = d.latestFramePng()
            ?: return errEnvelope(
                "NO_FRAME",
                "display $id has produced no frame yet — wait a few seconds " +
                    "(the pipeline is rate-limited to ≤10fps) and retry; a display " +
                    "group that fell asleep also stops producing frames",
                args,
            )

        // Same session-attachments resolution as `android-a11y-cli ui
        // screenshot`: the per-session bind mount, not the process-global
        // last-writer map.
        val mountedSession = sessionId?.let { ExecutionCoordinator.mountedSessionIdFor(it) }
        val attachmentsHostDir: java.io.File? = mountedSession
            ?.let { PRootKernel.resolveSessionHostPath(it, VAR_MINIS_ATTACHMENTS, context) }
            ?: PRootKernel.resolveHostPath(VAR_MINIS_ATTACHMENTS)
        if (attachmentsHostDir == null) {
            return errEnvelope("NO_ATTACHMENTS_MOUNT",
                "cannot resolve $VAR_MINIS_ATTACHMENTS; run from an agent shell", args)
        }

        val dir = java.io.File(attachmentsHostDir, VD_SHOT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val filename = "vd_${System.currentTimeMillis()}.png"
        val file = java.io.File(dir, filename)
        return try {
            java.io.FileOutputStream(file).use { it.write(png) }
            okEnvelope(
                JSONObject()
                    .put("displayId", id)
                    .put("path", "$VAR_MINIS_ATTACHMENTS/$VD_SHOT_SUBDIR/$filename")
                    .put("minis_url", "minis://attachments/$VD_SHOT_SUBDIR/$filename")
                    .put("host_path", file.absolutePath)
                    .put("sizeBytes", png.size)
                    .put("blackFrame", d.isLastFrameBlack())
                    .put("width", d.width)
                    .put("height", d.height),
                args,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "vd shot write failed: ${t.message}")
            errEnvelope("WRITE_FAILED", "failed to write PNG: ${t.javaClass.simpleName}: ${t.message ?: ""}", args)
        }
    }

    // ── Shizuku-backed commands ────────────────────────────────────────────

    /**
     * Run a shell command via Shizuku (`ShizukuBackend.runProcess` through
     * the manager), returning null when Shizuku itself is not READY — the
     * manager reports that as exit code 126 — so callers can name the real
     * blocker instead of a shell error.
     */
    private fun shizukuRun(cmd: List<String>, timeoutMs: Long = 15_000): ShizukuManager.ProcessResult? {
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = timeoutMs)
        return r.takeIf { it.exitCode != 126 }
    }

    private fun launch(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL launch <displayId> --user <u> <component>", args)
        val user = args.get("user") ?: "0"
        val userOk = user.toIntOrNull() != null
        if (!userOk) return errEnvelope("INVALID_ARGS", "--user must be a numeric user id (got '$user')", args)
        val component = args.positional.getOrNull(1)
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL launch <displayId> --user <u> <component> " +
                "(component: pkg/activity or pkg/.MainActivity)", args)
        MinisAgentDisplayManager.get(id)
            ?: return errEnvelope("VD_NOT_FOUND", "no in-process virtual display $id; `create` it first " +
                "(the target display must exist before launching onto it)", args)

        val r = shizukuRun(
            listOf("am", "start", "--user", user, "--display", id.toString(), "-n", component),
            timeoutMs = 15_000,
        )
        if (r == null) {
            return errEnvelope("SHIZUKU_NOT_READY",
                "Shizuku is not READY; `launch` shells out `am start --user $user --display $id -n $component` " +
                    "with Shizuku privilege. Check `android-shizuku-cli service status`.", args)
        }
        return if (r.exitCode == 0) {
            okEnvelope(
                JSONObject()
                    .put("displayId", id)
                    .put("user", user)
                    .put("component", component)
                    .put("output", r.stdout.trim().take(400)),
                args,
            )
        } else errEnvelope("OPERATION_FAILED", "am start failed: ${r.combined.take(500)}", args)
    }

    private fun tap(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL tap <displayId> <x> <y>", args)
        val x = args.positional.getOrNull(1)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL tap <displayId> <x> <y>", args)
        val y = args.positional.getOrNull(2)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL tap <displayId> <x> <y>", args)

        val r = shizukuRun(listOf("input", "-d", id.toString(), "tap", x.toString(), y.toString()))
        if (r == null) {
            return errEnvelope("SHIZUKU_NOT_READY",
                "Shizuku is not READY; `tap` shells out `input -d $id tap $x $y`. " +
                    "Check `android-shizuku-cli service status`.", args)
        }
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("displayId", id).put("x", x).put("y", y), args)
        } else errEnvelope("OPERATION_FAILED", "input tap failed: ${r.combined.take(300)}", args)
    }

    // ── envelope helpers (same shape as the sibling handlers) ─────────────

    private fun okEnvelope(payload: Any, args: OffloadArgs): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", payload).toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun errEnvelope(code: String, message: String, args: OffloadArgs): NativeOffloadResult {
        val body = JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()
        return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
    }
}
