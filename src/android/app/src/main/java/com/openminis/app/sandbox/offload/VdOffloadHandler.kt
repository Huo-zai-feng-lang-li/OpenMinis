package com.openminis.app.sandbox.offload

import android.content.Context
import android.os.SystemClock
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.display.AgentDisplayApi
import com.openminis.app.display.MinisAgentDisplayManager
import com.openminis.app.display.VdCreateResult
import com.openminis.app.display.VdTaskPinner
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.shizuku.MinisShellClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

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

    /**
     * Display ids the last [sweepIdleDisplays] released; read back by
     * `create`/`run` envelopes (keeps the sweep result next to the create
     * call that triggered it without threading it through signatures).
     */
    private var lastSweepReleased: List<Int> = emptyList()

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

        /** Idle-frame threshold for the pre-create sweep (see [sweepIdleDisplays]). */
        private const val IDLE_FRAME_LIMIT_MS = 10 * 60_000L
        /** More than this many FRESH displays ⇒ sweep them all before creating. */
        private const val MAX_IDLE_DISPLAYS = 2

        /** `run --wait` default: 15s is long enough for cold starts of any
         *  target app, short enough that an agent shell call never hangs. */
        private const val RUN_DEFAULT_WAIT_MS = 15_000L

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
                   Leak guard: before creating, idle displays are released
                   automatically (never produced a frame, or last frame
                   >10 min old); if >2 idle displays survive, all are
                   released first — see the `released` array in the
                   envelope.
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
                   Move <component> onto <displayId> as user <u> via
                   Shizuku: `am force-stop --user <u> <package>` FIRST,
                   then `am start --user <u> --display <displayId>
                   -n <component>`. The force-stop is load-bearing: when
                   the app is already running on the main display, a bare
                   `am start` only reports "delivered to currently
                   running top-most instance" (exit 0, never moves it) or
                   fails outright — killing the target's own instance is
                   how it actually lands on the virtual display. Needs
                   Shizuku READY (`android-shizuku-cli service status`).
                   app 被钉在屏上（自动 move-stack 拉回，3 次失败报 APP_MOVES_TASK）
  run <component> [--user <u>] [--w N --h N --dpi N] [--wait <ms>]
                   One-shot: create the display (leak-sweep included),
                   move <component> onto it, wait for its first
                   non-black frame, and save that first-frame PNG.
                   `framed=false` on --wait timeout / all-black frames
                   (the created display stays up — `release` it after);
                   any earlier failure releases it again.
                   app 被钉在屏上（自动 move-stack 拉回，3 次失败报 APP_MOVES_TASK）
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

Text & UI:
  text <displayId> <text>
                   Type [text] on the display via the Shizuku shell host:
                   first tries KeyCharacterMap (virtual keyboard, supports
                   CJK/Unicode) and falls back to `input -d <displayId>
                   text` (ASCII only). Reports `method` ("keycharmap",
                   "input_text" or "unknown") and `ok`.
  find <displayId> <text> [--type node|image]
                   Search the display's a11y tree for [text]. node: matches
                   text/contentDescription containing [text]; image:
                   matches className containing "Image" or
                   contentDescription containing [text]. Up to 5 matches per
                   type; each returns {text, className, bounds{x,y,w,h},
                   clickX, clickY}. No matches → hint to fall back to shot
                   + visual. Requires the a11y service to be running and the
                   display to be visible to it; if the display's windows are
                   not in a11y getWindows() (untrusted displays may not be
                   visible), returns A11Y_DISPLAY_NOT_VISIBLE — do NOT
                   fabricate results, fall back to shot + visual.

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
                "create"  -> create(args, request.sessionId)
                "list"    -> list(args)
                "shot"    -> shot(args, request.sessionId)
                "launch"  -> launch(args)
                "run"     -> run(args, request.sessionId)
                "tap"     -> tap(args)
                "text"    -> text(args)
                "find"    -> find(args)
                "release" -> release(args)
                else      -> NativeOffloadResult(2, "$TOOL: unknown subcommand '$sub'\n$HELP")
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${t.javaClass.simpleName} ${t.message}")
            errEnvelope("INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}", args)
        }
    }

    // ── create / list / release ────────────────────────────────────────────

    private fun create(args: OffloadArgs, sessionId: String?): NativeOffloadResult {
        // Leak guard (field-measured: 7 consecutive attempts left displays
        // 194–200 unreleased): sweep idle displays BEFORE adding another
        // framebuffer. Idle = no active app ≈ no recent frame.
        return when (val r = createDisplay(args, sessionId)) {
            is VdCreateResult.Ok -> {
                val d = r.display
                okEnvelope(
                    JSONObject()
                        .put("displayId", d.displayId)
                        .put("name", d.name)
                        .put("width", d.bufferWidth)
                        .put("height", d.bufferHeight)
                        .put("dpi", d.dpi)
                        .put("trusted", d.trusted)
                        .put("flags", "0x" + d.flags.toString(16))
                        .put("released", JSONArray(lastSweepReleased))
                        .put("hint", "launch apps onto it: $TOOL launch ${d.displayId} --user 999 <component>"),
                    args,
                )
            }
            is VdCreateResult.Failed -> errEnvelope(r.code, r.message, args)
        }
    }

    /**
     * Shared create path for `create` and `run`: the [sweepIdleDisplays]
     * leak guard plus [MinisAgentDisplayManager.create] — one place that
     * owns the ordering, so both commands can't drift apart.
     */
    private fun createDisplay(args: OffloadArgs, ownerSession: String? = null): VdCreateResult {
        lastSweepReleased = sweepIdleDisplays()
        return MinisAgentDisplayManager.create(
            context,
            width = args.getInt("w", "width"),
            height = args.getInt("h", "height"),
            dpi = args.getInt("dpi"),
            // [vd-session-binding] tag the display with the creating chat
            // session so "one live display globally" can be enforced: when
            // another session becomes active, ChatScreen releases every
            // display owned by a *different* session (CapsulePreview's window
            // then auto-closes via the manager's change listener).
            ownerSession = ownerSession,
        )
    }

    /**
     * Pre-create sweep: release in-process displays with no active app,
     * conservatively judged by frame freshness — [AgentDisplayApi.lastFrameAt]
     * is the producer's monotonic timestamp in NANOseconds, so compare it
     * against [SystemClock.elapsedRealtimeNanos], not wall-clock millis.
     * A display is idle when it never produced a frame (null) or its last
     * frame is > [IDLE_FRAME_LIMIT_MS] old. If MORE THAN two fresh
     * displays survive (framebuffer accumulation), release them all before
     * the new one is created.
     */
    private fun sweepIdleDisplays(): List<Int> {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val released = mutableListOf<Int>()
        val survivors = MinisAgentDisplayManager.list()
            .filter { d ->
                val t = d.lastFrameAt()
                val idle = t == null || nowNanos - t > IDLE_FRAME_LIMIT_MS * 1_000_000L
                if (idle) {
                    if (MinisAgentDisplayManager.release(d.displayId)) released += d.displayId
                    false
                } else true
            }
        if (survivors.size > MAX_IDLE_DISPLAYS) {
            // The survivors are the ones WITH recent frames; holding >2 of
            // them is the accumulation case — drop them all, new create
            // follows.
            survivors.forEach { d ->
                if (MinisAgentDisplayManager.release(d.displayId)) released += d.displayId
            }
        }
        return released
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
        // [vd-task-pinner] stop the pinner BEFORE the display goes away:
        // its restart path would otherwise `am start` the app back onto a
        // dead display id (the framework rejects it, but the strikes flap
        // for a while). Unpinning a display that was never pinned is a
        // no-op.
        VdTaskPinner.unpin(id)
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

        return when (val w = writePng(d, png, sessionId)) {
            is PngWriteResult.Ok -> okEnvelope(
                JSONObject()
                    .put("displayId", id)
                    .put("path", w.path)
                    .put("minis_url", w.minisUrl)
                    .put("host_path", w.hostPath)
                    .put("sizeBytes", w.sizeBytes)
                    .put("blackFrame", d.isLastFrameBlack())
                    .put("width", d.width)
                    .put("height", d.height),
                args,
            )
            is PngWriteResult.Err -> errEnvelope(w.code, w.message, args)
        }
    }

    /**
     * Shared PNG-to-session-attachments write for `shot` and `run`:
     * /var/minis/attachments/screenshots/vd_<ts>.png under the session's
     * bind mount (same resolution as `android-a11y-cli ui screenshot` —
     * the per-session mount, not the process-global last-writer map).
     */
    private sealed class PngWriteResult {
        data class Ok(
            val path: String,
            val minisUrl: String,
            val hostPath: String,
            val sizeBytes: Int,
        ) : PngWriteResult()
        data class Err(val code: String, val message: String) : PngWriteResult()
    }

    private fun writePng(d: AgentDisplayApi, png: ByteArray, sessionId: String?): PngWriteResult {
        val mountedSession = sessionId?.let { ExecutionCoordinator.mountedSessionIdFor(it) }
        val attachmentsHostDir: java.io.File? = mountedSession
            ?.let { PRootKernel.resolveSessionHostPath(it, VAR_MINIS_ATTACHMENTS, context) }
            ?: PRootKernel.resolveHostPath(VAR_MINIS_ATTACHMENTS)
        if (attachmentsHostDir == null) {
            return PngWriteResult.Err("NO_ATTACHMENTS_MOUNT",
                "cannot resolve $VAR_MINIS_ATTACHMENTS; run from an agent shell")
        }

        val dir = java.io.File(attachmentsHostDir, VD_SHOT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val filename = "vd_${System.currentTimeMillis()}.png"
        val file = java.io.File(dir, filename)
        return try {
            java.io.FileOutputStream(file).use { it.write(png) }
            PngWriteResult.Ok(
                path = "$VAR_MINIS_ATTACHMENTS/$VD_SHOT_SUBDIR/$filename",
                minisUrl = "minis://attachments/$VD_SHOT_SUBDIR/$filename",
                hostPath = file.absolutePath,
                sizeBytes = png.size,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "vd shot write failed: ${t.message}")
            PngWriteResult.Err("WRITE_FAILED", "failed to write PNG: ${t.javaClass.simpleName}: ${t.message ?: ""}")
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

    /**
     * Shared force-stop + am-start move onto [target] for `launch` (where
     * [target] was given explicitly) and `run` (where it is the freshly
     * created display) — one owner of the load-bearing stop-then-start
     * order.
     */
    private sealed class LaunchResult {
        data class Ok(val packageName: String, val stdout: String) : LaunchResult()
        data class Err(val code: String, val message: String) : LaunchResult()
    }

    private fun launchComponent(
        target: AgentDisplayApi,
        user: String,
        component: String,
    ): LaunchResult {
        val id = target.displayId
        // Field bug: when the app is already running on the main display
        // (display 0), a bare `am start` exits 0 with only "delivered to
        // currently running top-most instance" — the task NEVER moves to
        // the virtual display (or the start fails). Force-stop the
        // component's own package first (scoped to --user; it is being
        // killed on purpose, it has to restart onto this display), then
        // start it into the target display.
        val pkg = component.substringBefore('/')

        val stop = shizukuRun(listOf("am", "force-stop", "--user", user, pkg))
        if (stop == null) {
            return LaunchResult.Err(
                "SHIZUKU_NOT_READY",
                "Shizuku is not READY; `launch` shells out `am force-stop --user $user $pkg` " +
                    "then `am start --user $user --display $id -n $component` with Shizuku " +
                    "privilege. Check `android-shizuku-cli service status`.",
            )
        }
        if (stop.exitCode != 0) {
            // Proceeding would just reproduce the original symptom (the old
            // instance is still on display 0 and am start won't move it).
            return LaunchResult.Err("OPERATION_FAILED", "am force-stop failed: ${stop.combined.take(300)}")
        }

        val r = shizukuRun(
            listOf("am", "start", "--user", user, "--display", id.toString(), "-n", component),
            timeoutMs = 15_000,
        )
        if (r == null) {
            return LaunchResult.Err(
                "SHIZUKU_NOT_READY",
                "Shizuku is not READY; `launch` shells out `am start --user $user --display $id -n $component` " +
                    "with Shizuku privilege. Check `android-shizuku-cli service status`.",
            )
        }
        return if (r.exitCode == 0) {
            // [vd-task-pinner] The app may pull its Task back to display 0
            // after the splash (PDD does) and nothing else watches for it:
            // start the 2s probe loop that moves it back (move-stack /
            // am start) and gives up with APP_MOVES_TASK after 3 straight
            // failed corrections. Idempotent — a re-launch on the same
            // display just restarts the watch.
            user.toIntOrNull()?.let { uid -> VdTaskPinner.pin(id, pkg, uid, component) }
            LaunchResult.Ok(pkg, r.stdout.trim().take(400))
        } else LaunchResult.Err("OPERATION_FAILED", "am start failed: ${r.combined.take(500)}")
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
        val target = MinisAgentDisplayManager.get(id)
            ?: return errEnvelope("VD_NOT_FOUND", "no in-process virtual display $id; `create` it first " +
                "(the target display must exist before launching onto it)", args)

        return when (val r = launchComponent(target, user, component)) {
            is LaunchResult.Ok -> okEnvelope(
                JSONObject()
                    .put("displayId", id)
                    .put("user", user)
                    .put("component", component)
                    .put("package", r.packageName)
                    .put("forceStopped", true)
                    .put("output", r.stdout),
                args,
            )
            is LaunchResult.Err -> errEnvelope(r.code, r.message, args)
        }
    }

    // ── run: create → launch → wait first frame → shot ─────────────────────

    private fun run(args: OffloadArgs, sessionId: String?): NativeOffloadResult {
        val component = args.positional.getOrNull(0)
            ?: return errEnvelope("INVALID_ARGS",
                "usage: $TOOL run <component> [--user <u>] [--w N --h N --dpi N] [--wait <ms>]", args)
        val user = args.get("user") ?: "0"
        if (user.toIntOrNull() == null) {
            return errEnvelope("INVALID_ARGS", "--user must be a numeric user id (got '$user')", args)
        }
        val waitMs = args.getLong("wait") ?: RUN_DEFAULT_WAIT_MS
        if (waitMs <= 0) return errEnvelope("INVALID_ARGS", "--wait must be > 0 ms", args)

        // 1. create — the sweep guard lives inside [createDisplay], so the
        //    one-shot command leaks exactly as little as a plain `create`.
        val created = createDisplay(args, sessionId)
        if (created is VdCreateResult.Failed) return errEnvelope(created.code, created.message, args)
        val d = (created as VdCreateResult.Ok).display
        val t0 = SystemClock.elapsedRealtime()

        // 2. launch — reuse the force-stop + am-start move so `run` and
        //    `launch` can never drift apart.
        val launchOutcome = launchComponent(d, user, component)
        if (launchOutcome is LaunchResult.Err) {
            // Nothing is using a display we just paid for; don't leave a
            // half-set framebuffer behind (leak guard, see the class KDoc).
            // `run` is the only caller that owns the display lifetime, so
            // cleanup lives here.
            val released = MinisAgentDisplayManager.release(d.displayId)
            return errEnvelope(
                launchOutcome.code,
                launchOutcome.message + " (display ${d.displayId} released" +
                    (if (released) "" else " — release failed") + ")",
                args,
            )
        }
        val pkg = component.substringBefore('/')

        // 2b. [vd-task-pinner] start the pin BEFORE the frame wait: an app
        //     that escapes after its splash (PDD) gets pulled back by the
        //     pinner's move-stack WITHIN the wait window, so the wait
        //     yields a live frame instead of timing out on black. This
        //     re-calls pin on purpose — launchComponent's own pin may have
        //     started up to 2s earlier; re-pinning resets the watch so
        //     round 1 lines up with the wait window (idempotent per display).
        val uid = user.toInt()
        VdTaskPinner.pin(d.displayId, pkg, uid, component)
        val taskIdNow = VdTaskPinner.peekTask(pkg)
            .tasks.firstOrNull { it.display == d.displayId }?.taskId

        // 3. wait for the first real frame: skip null and black, keep
        //    waiting otherwise.
        val waited = waitForFirstFrame(d, waitMs, pkg)
        val framed = waited.framed
        val waitedMs = SystemClock.elapsedRealtime() - t0
        AppLogger.info(TAG, "run: display ${d.displayId} $component framed=$framed waitedMs=$waitedMs")

        // 4. shot — write what we have. On timeout the frame may be
        //    missing or all-black; a partial PNG is still useful, so
        //    attempt the write and fold failures into the envelope
        //    instead of erroring out.
        val payload = JSONObject()
            .put("displayId", d.displayId)
            .put("user", user)
            .put("component", component)
            .put("package", pkg)
            .put("framed", framed)
            .put("framedReason", waited.reason)
            .put("waitedMs", waitedMs)
            // [vd-task-pinner] the pin state as of the frame-wait start:
            // `pinned` says the watch is running (it dies with the display
            // release or the APP_MOVES_TASK / 15-min idle stop), `taskId`
            // is the task id that `am display move-stack` takes (JSON null
            // when the probe found no task yet — the app is still starting
            // up; JSONObject.NULL so it serializes as a real null, not an
            // NPE from put(String, null)).
            .put("pinned", true)
            .put("taskId", taskIdNow ?: JSONObject.NULL)
        val png = d.latestFramePng()
        if (png != null) {
            when (val w = writePng(d, png, sessionId)) {
                is PngWriteResult.Ok -> {
                    payload.put("shotPath", w.path)
                    payload.put("shotMinisUrl", w.minisUrl)
                    payload.put("shotHostPath", w.hostPath)
                    payload.put("shotSizeBytes", w.sizeBytes)
                }
                is PngWriteResult.Err -> {
                    // A missing session mount (or write failure) is not
                    // worth hiding the launch/wait result over.
                    payload.put("shotError", w.code)
                }
            }
        } else {
            payload.put("shotError", "NO_FRAME")
        }
        return okEnvelope(payload, args)
    }

    /** Result of the run frame-wait: `framed` says whether a usable first
     *  frame was captured, `reason` explains the failure for the envelope. */
    private data class FrameWaitResult(val framed: Boolean, val reason: String)

    /**
     * Frame-wait timed out with no frame: before blaming the pipeline,
     * check where the app's task actually is — reusing the pinner's
     * dumpsys parse ([VdTaskPinner.peekTask]). The task on another
     * display (or gone) means a black screen is EXPECTED and the
     * `framedReason` should name it (APP_ESCAPED_DISPLAY + the pinner's
     * strike count, so the agent knows the pinner already fought it).
     * The task on the display means the black screen is a real
     * frame-pipeline symptom — keep the generic reason.
     */
    private fun escapeReason(d: AgentDisplayApi, pkg: String): String? {
        val peek = VdTaskPinner.peekTask(pkg)
        if (!peek.probed) return null // dumpsys/Shizuku unavailable — don't guess
        val onDisplay = peek.tasks.any { it.display == d.displayId }
        if (onDisplay) return null
        return "APP_ESCAPED_DISPLAY: $pkg task not on display ${d.displayId} " +
            "(pinner strikes=${VdTaskPinner.strikes(d.displayId)})"
    }

    /**
     * Subscribe to [d]'s [AgentDisplayApi.frames] until a usable frame
     * arrives. `frames` publishes BLACK frames as null and drops
     * near-black variants, so the only acceptance test that adds
     * information beyond "non-null" is [AgentDisplayApi.isLastFrameBlack]
     * on the full-res frame the preview stream may have downgraded away.
     * Never throws: timeout and all-black both come back as
     * `framed=false` + a reason.
     */
    private fun waitForFirstFrame(
        d: AgentDisplayApi,
        timeoutMs: Long,
        pkg: String,
    ): FrameWaitResult {
        val initial = d.frames.value
        if (initial != null && d.isLastFrameBlack() != true) {
            return FrameWaitResult(true, "frame already available before the wait window")
        }
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    // first{} on a StateFlow returns as soon as the CURRENT
                    // value matches (or a later emission does) and cancels the
                    // collection — so the wait ends at the first non-null
                    // frame, NOT after the full timeout window. The old
                    // collectLatest{...return@collectLatest} never stopped the
                    // collection (a StateFlow never completes), so run would
                    // always block the full [timeoutMs] even when the frame
                    // was ready in ~1s.
                    // null = no frame yet (or a black one the publisher's
                    // guard already nullified); the black double-check is
                    // done on the result below.
                    d.frames.first { it != null }
                }
            }
        } catch (t: Throwable) { // TimeoutCancellationException & co.
            AppLogger.info(TAG, "run: frame wait on ${d.displayId} ended: ${t.javaClass.simpleName}")
        }
        val final = d.frames.value
        return if (final != null && d.isLastFrameBlack() != true) {
            FrameWaitResult(true, "first non-black frame within ${timeoutMs}ms")
        } else if (d.latestFramePng() != null) {
            // A PNG exists but the preview stream says null/black — the
            // full-res capture is still a usable shot (the black guard
            // downgraded it); count it as framed, the PNG tells the truth.
            FrameWaitResult(true, "full-res frame present although preview stream is null/black")
        } else {
            // [vd-task-pinner] a black screen is expected when the app's
            // task escaped the display — name it (with the pinner's strike
            // count) instead of the generic timeout; when the task IS on
            // the display keep the generic reason (real pipeline symptom).
            FrameWaitResult(false, escapeReason(d, pkg) ?: "no non-black frame within ${timeoutMs}ms")
        }
    }


    private fun tap(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL tap <displayId> <x> <y>", args)
        val x = args.positional.getOrNull(1)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL tap <displayId> <x> <y>", args)
        val y = args.positional.getOrNull(2)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL tap <displayId> <x> <y>", args)

        // Only our managed virtual displays: a wrong id (e.g. 0) must not route
        // input to the user's main screen.
        MinisAgentDisplayManager.get(id)
            ?: return errEnvelope("VD_NOT_FOUND",
                "no active virtual display $id; `create` it first " +
                    "(main-screen input is `android-shizuku-cli input tap`, not this tool)", args)

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

    // ── text / find: typed input + UI locating on a virtual display ────────

    /**
     * `text <displayId> <text>` — type [text] on the display. Routed to
     * the Shizuku shell host's dual path ([MinisShellClient.injectText]:
     * KeyCharacterMap virtual keyboard first — the CJK/Unicode answer —
     * then shell `input -d <displayId> text`, ASCII only). This entry is
     * the CLI front of that host method: no re-implementation of the
     * injection logic.
     *
     * Honest `method` field: the host's `injectText` returns a bare
     * Boolean, so which of the two paths actually ran is not reachable
     * here — the envelope reports `method: "unknown"` (the "keycharmap" /
     * "input_text" / "both" variants would need an AIDL return change,
     * which is deliberately out of scope for this P0 surface).
     */
    private fun text(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL text <displayId> <text>", args)
        val text = args.positional.getOrNull(1)
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL text <displayId> <text>", args)
        if (text.isEmpty()) return errEnvelope("INVALID_ARGS", "<text> must not be empty", args)
        // Only managed virtual displays (same guard as `tap`): the host's
        // ownDisplay check would refuse the rest anyway, but naming the
        // real blocker up front is cheaper.
        MinisAgentDisplayManager.get(id)
            ?: return errEnvelope("VD_NOT_FOUND",
                "no active virtual display $id; `create` it first " +
                    "(the shell host can only type on a display it owns)", args)
        val ok = MinisShellClient.injectText(id, text)
        return okEnvelope(
            JSONObject()
                .put("displayId", id)
                .put("ok", ok)
                .put("method", "unknown")
                .put("len", text.length),
            args,
        )
    }

    /**
     * `find <displayId> <text> [--type node|image]` — locate [text] in
     * the display's a11y tree. Display-scoped window access is
     * [MinisAccessibilityService.rootsForDisplay] (the P0 virtual-display
     * surface: `windowsOnAllDisplays[displayId]` on API 30+; the service
     * file needed no change — the accessor already exists, so `find` is
     * built on it, not around it).
     *
     * Match rules (DFS over every root, up to 5 matches per type):
     *   node  — the node's text OR contentDescription CONTAINS [text];
     *   image — the node's className contains "Image" OR its
     *           contentDescription CONTAINS [text].
     *
     * Honest boundary (front risk, measured behaviour): when the a11y
     * service is not running, OR the display has NO window in the
     * service's `getWindowsOnAllDisplays()` (untrusted virtual displays
     * may be invisible to the user-0 a11y service — UNMEASURED), this
     * returns `A11Y_DISPLAY_NOT_VISIBLE`. It never fabricates matches:
     * fall back to `shot` + visual locating.
     */
    private fun find(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(0)?.toIntOrNull()
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL find <displayId> <text> [--type node|image]", args)
        val text = args.positional.getOrNull(1)
            ?: return errEnvelope("INVALID_ARGS", "usage: $TOOL find <displayId> <text> [--type node|image]", args)
        val type = args.get("type") ?: "node"
        if (type != "node" && type != "image") {
            return errEnvelope("INVALID_ARGS", "--type must be node or image (got '$type')", args)
        }
        val svc = MinisAccessibilityService.getInstance()
            ?: return errEnvelope(
                "A11Y_DISPLAY_NOT_VISIBLE",
                "the a11y service is not running, so no window of display $id is readable " +
                    "(该屏窗口对 a11y 不可见，退回 shot+视觉定位). Enable Settings → Accessibility " +
                    "→ Minis, or locate visually: $TOOL shot $id", args,
            )
        val roots = svc.rootsForDisplay(id)
        if (roots.isEmpty()) {
            return errEnvelope(
                "A11Y_DISPLAY_NOT_VISIBLE",
                "display $id has no window in the a11y service's getWindows() " +
                    "(an untrusted virtual display may be invisible to the user-0 a11y service — " +
                    "unmeasured ROM behaviour). 该屏窗口对 a11y 不可见，退回 shot+视觉定位: " +
                    "$TOOL shot $id", args,
            )
        }
        val matches = findNodes(roots, text, type)
        val payload = JSONObject()
            .put("displayId", id)
            .put("text", text)
            .put("type", type)
            .put("matches", matches)
        if (matches.isEmpty()) {
            payload.put("hint", "text not found in a11y tree of display $id; try shot + visual")
        }
        return okEnvelope(payload, args)
    }

    /**
     * DFS over [roots] with the spec's match rules; at most 5 matches,
     * bounded depth (60) so a pathological tree can't wedge the call.
     * Iterative (explicit stack) — a11y trees can be deep on a busy app.
     */
    private fun findNodes(
        roots: List<android.view.accessibility.AccessibilityNodeInfo>,
        text: String,
        type: String,
    ): JSONArray {
        val out = JSONArray()
        // Iterative DFS (explicit node+depth stack) — a11y trees can be
        // deep on a busy app, and the depth bound (60) stops a
        // pathological tree from wedging the call.
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        val depthStack = ArrayDeque<Int>()
        for (r in roots) {
            stack.addLast(r)
            depthStack.addLast(0)
        }
        while (stack.isNotEmpty() && out.length() < 5) {
            val node = stack.removeLast()
            val d = depthStack.removeLast()
            if (d > 60) continue
            val matched = if (type == "node") {
                node.text?.toString()?.contains(text) == true ||
                    node.contentDescription?.toString()?.contains(text) == true
            } else {
                (node.className?.toString() ?: "").contains("Image", ignoreCase = true) ||
                    (node.contentDescription?.toString() ?: "").contains(text)
            }
            if (matched) out.put(nodeToMatchJson(node))
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let {
                    stack.addLast(it)
                    depthStack.addLast(d + 1)
                }
            }
        }
        return out
    }

    /** The 5-field match shape: text, className, bounds{x,y,w,h}, clickX, clickY (center). */
    private fun nodeToMatchJson(n: android.view.accessibility.AccessibilityNodeInfo): JSONObject {
        val rect = android.graphics.Rect()
        n.getBoundsInScreen(rect)
        return JSONObject()
            .put("text", n.text?.toString() ?: n.contentDescription?.toString() ?: "")
            .put("className", n.className?.toString() ?: "")
            .put("bounds", JSONObject()
                .put("x", rect.left).put("y", rect.top)
                .put("w", rect.width()).put("h", rect.height()))
            .put("clickX", rect.centerX())
            .put("clickY", rect.centerY())
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
