package com.openminis.app.display

import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [vd-task-pinner] Keeps a launched app's Task ON its virtual display.
 *
 * Field symptom (measured): after `am start --user <u> --display <D>` lands
 * the app, some apps (PDD `com.xunmeng.pinduoduo` is the reference case)
 * move their own Task back to display 0 as soon as the splash finishes —
 * and nothing watches for it: the display goes black, and every `shot`
 * replays the last non-black frame (the stale-frame symptom is fixed on
 * the capture side by `latestFramePng`'s fresh-frame-first contract; here
 * it is fixed on the task side, which is the real cause).
 *
 * The pinner is a process-level monitor: one [CoroutineScope]
 * (`SupervisorJob()` + [Dispatchers.IO]) owning one 2s probe loop PER
 * VIRTUAL DISPLAY (keyed by displayId; re-pinning the same display cancels
 * the old loop first). Each round:
 *
 *   1. probe — Shizuku `dumpsys activity activities` (15s timeout;
 *      exitCode != 0 counts as a failed probe and the round is SKIPPED —
 *      no verdict, no correction, no strike).
 *   2. parse — stdout is scanned line by line: a `displayId=N` header line
 *      switches the current section; inside a section, any line carrying
 *      both `Task{` and the watched package is a candidate (its id comes
 *      from `Task{<hex> #<n>`, its display is the section's N, "visible"
 *      from the line's own `visible=true` flag).
 *   3. pick the target task — prefer a `visible=true` one; else the first;
 *      none at all means the app was killed.
 *   4. correct —
 *        no task at all      → `am start --user <u> --display <D> -n <c>`
 *                              (restart + re-land on the display);
 *        task on display ≠ D → `am display move-stack <taskId> <D>`
 *                              (device-verified: reaches the framework's
 *                              moveRootTaskToDisplay);
 *        task on D           → nothing to do.
 *   5. strikes — after a correction, if the NEXT round's probe still shows
 *      the wrong state (still off-D or still no task), that is one strike
 *      (three consecutive strikes → stop the loop, report APP_MOVES_TASK
 *      honestly — the app actively fights the virtual display, see PDD; a
 *      correction war just flaps the task back and forth). A probe that
 *      found the task back on D resets strikes to 0.
 *   6. idle stop — 15 straight minutes without a correction action (the
 *      app has been stable on the display the whole time) also stops the
 *      loop to save the dumpsys cost, closing with active=true.
 */
object VdTaskPinner {

    private const val TAG = "VdTaskPinner"

    /** Probe cadence: 2s stays well under a splash's lifetime; dumpsys is cheap enough. */
    private const val PROBE_INTERVAL_MS = 2_000L
    /** dumpsys is occasionally slow on a loaded device; 15s keeps the probe from flapping. */
    private const val PROBE_TIMEOUT_MS = 15_000L
    /** Three "still wrong after correcting" rounds in a row = the app wins. */
    private const val MAX_STRIKES = 3
    /** Stable on-D this long without any correction action → stop probing (saves the dumpsys cost). */
    private const val IDLE_STOP_MS = 15 * 60_000L

    /**
     * What the pinner knows about a display (read back via [status], and by
     * the CLI `launch`/`run` envelopes that embed the pin state).
     * `active=false` + `appMovesTask=true` is the APP_MOVES_TASK outcome:
     * the app pulled its Task back to the default display three times in a
     * row after corrections and the pinner gave up — report it honestly,
     * do not keep fighting.
     */
    data class PinOutcome(
        val displayId: Int,
        val pkg: String,
        val active: Boolean,
        val appMovesTask: Boolean,
        val lastAction: String,
    )

    /** One probe hit: a task line of the watched package, per display section. */
    data class ProbeTask(val taskId: Int, val display: Int, val visible: Boolean)

    /**
     * [peekTask] result. `probed=false` = the dumpsys round itself failed
     * (Shizuku not READY / non-zero exit / 15s timeout) — callers must not
     * treat that as "task missing"; `tasks` is empty when the probe ran
     * and found no task lines (the app was killed, or dumpsys stdout was
     * empty).
     */
    data class TaskPeek(val probed: Boolean, val tasks: List<ProbeTask>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val states = ConcurrentHashMap<Int, PinState>()

    /** Per-display monitor state; the loop's job is cancel-able via [Job.cancel]. */
    private class PinState(
        val pkg: String,
        val userId: Int,
        val component: String,
    ) {
        @Volatile
        var job: kotlinx.coroutines.Job? = null
        @Volatile
        var outcome: PinOutcome? = null
        /** Current "still wrong after correcting" streak (0 = none). */
        @Volatile
        var strikes = 0
    }

    /**
     * Watch [displayId] for [pkg] and keep correcting it back there.
     * Re-pinning the same display cancels the previous loop first (a new
     * launch wins over the stale watch) — pin is idempotent per display.
     */
    fun pin(displayId: Int, pkg: String, userId: Int, component: String) {
        val state = synchronized(lock) {
            states.getOrPut(displayId) { PinState(pkg, userId, component) }.also {
                it.strikes = 0
                it.outcome = PinOutcome(
                    displayId, pkg, active = true, appMovesTask = false, lastAction = "pinned",
                )
            }
        }
        // Cancel the previous loop of this display (re-pin), then start the new one.
        synchronized(lock) {
            val replaced = state.job
            state.job = null
            replaced?.let { it.cancel() }
        }
        val job = scope.launch {
            pinLoop(displayId, state)
        }
        synchronized(lock) { state.job = job }
        AppLogger.info(
            TAG,
            "pin: display $displayId $pkg (user $userId $component) — 2s probe started"
        )
    }

    /**
     * Stop watching [displayId] and forget its outcome. The caller owns the
     * display's lifetime from here on (a `release` on a display that was
     * never pinned is a no-op).
     */
    fun unpin(displayId: Int) {
        val state = synchronized(lock) {
            states.remove(displayId)
        } ?: return
        synchronized(lock) {
            state.job?.let { it.cancel() }
            state.outcome = null
        }
        AppLogger.info(TAG, "unpin: display $displayId (${state.pkg})")
    }

    /** Stop every pinner (application teardown — the process dies with them anyway). */
    fun unpinAll() {
        val all = synchronized(lock) {
            states.values.toList().also { states.clear() }
        }
        all.forEach { s ->
            synchronized(lock) {
                s.job?.let { it.cancel() }
                s.outcome = null
            }
        }
        AppLogger.info(TAG, "unpinAll: ${all.size} pinner(s) stopped")
    }

    /** The pinner's current/last state for [displayId] (null when nothing is pinned there). */
    fun status(displayId: Int): PinOutcome? = synchronized(lock) {
        states[displayId]?.outcome
    }

    /**
     * One-shot probe of the watched package's tasks — the same dumpsys
     * parse the loop runs, exposed for callers that need a single
     * "where is the task" verdict (the CLI `run` envelope's `taskId`
     * field and the escape diagnostic on frame-wait timeout).
     */
    fun peekTask(pkg: String): TaskPeek {
        val tasks = probe(pkg)
        return TaskPeek(probed = tasks != null, tasks = tasks ?: emptyList())
    }

    /** The pinner's strike counter for [displayId] (0 when nothing is pinned there). */
    fun strikes(displayId: Int): Int = synchronized(lock) {
        states[displayId]?.strikes ?: 0
    }

    // ── the loop ────────────────────────────────────────────────────────────

    private suspend fun pinLoop(displayId: Int, state: PinState) {
        val pkg = state.pkg
        var correctedLastRound = false
        var lastCorrectionAtMs = SystemClock.elapsedRealtime()
        // currentCoroutineContext() (not the implicit `isActive`): this is a
        // member suspend function, so the implicit receiver is this object,
        // not the coroutine's Job.
        while (currentCoroutineContext().isActive) {
            delay(PROBE_INTERVAL_MS)
            val probe = probe(pkg)
            if (probe == null) {
                // Probe failure (Shizuku not READY → exit 126, dumpsys non-zero,
                // or a 15s timeout): no verdict this round. Skip the
                // correction WITHOUT touching the strike counter — a strike
                // means "we corrected it and it still escaped", not "we
                // could not look".
                setOutcome(
                    displayId,
                    PinOutcome(
                        displayId, pkg, active = true, appMovesTask = false,
                        lastAction = "probe failed (shizuku/dumpsys) — round skipped",
                    ),
                )
                continue
            }
            // Pick the target task: a task already ON [displayId] wins (visible
            // or not — a background-only app that simply stayed on the virtual
            // display must count as stable, not as an escape); otherwise the
            // most-plausible task elsewhere gets moved back. On dual-physical-
            // display devices the same Task line is repeated under several
            // displayId sections, so "prefer on-target" keeps the verdict sane.
            val target = probe.firstOrNull { it.display == displayId && it.visible }
                ?: probe.firstOrNull { it.display == displayId }
                ?: probe.firstOrNull { it.visible }
                ?: probe.firstOrNull()
            if (target != null && target.display == displayId) {
                // Correctly placed: the escape streak ends here.
                state.strikes = 0
                correctedLastRound = false
                val idleMs = SystemClock.elapsedRealtime() - lastCorrectionAtMs
                if (idleMs >= IDLE_STOP_MS) {
                    // 15 straight minutes without a correction action (stable on
                    // the display the whole time) — stop probing to save the
                    // dumpsys cost; close as a success, not a failure.
                    val mins = idleMs / 60_000L
                    AppLogger.info(
                        TAG,
                        "display $displayId: $pkg stable on the virtual display for $mins min — stopping the pin loop (idle)"
                    )
                    setOutcome(
                        displayId,
                        PinOutcome(
                            displayId, pkg, active = true, appMovesTask = false,
                            lastAction = "idle-stable (${mins} min)",
                        ),
                    )
                    return
                }
                continue
            }
            // Wrong state: the app was killed (no task lines at all) or its
            // task sits on another display.
            if (correctedLastRound) {
                state.strikes++
                if (state.strikes >= MAX_STRIKES) {
                    AppLogger.warning(
                        TAG,
                        "APP_MOVES_TASK: $pkg escaped virtual display $displayId 3 times in a row; " +
                            "it likely detects non-default displays (see PDD) — report honestly, do not keep fighting"
                    )
                    setOutcome(
                        displayId,
                        PinOutcome(
                            displayId, pkg, active = false, appMovesTask = true,
                            lastAction = "stopped: APP_MOVES_TASK",
                        ),
                    )
                    return
                }
            }
            val action = if (target == null) {
                // No task at all → the app was killed; restart + re-land.
                restart(displayId, state.userId, state.component)
            } else {
                // Task exists but on another display → move it back.
                moveStack(displayId, target.taskId)
            }
            lastCorrectionAtMs = SystemClock.elapsedRealtime()
            correctedLastRound = true
            AppLogger.info(TAG, "display $displayId: $pkg escaped to display ${target?.display ?: "none (killed)"} — $action")
            setOutcome(
                displayId,
                PinOutcome(displayId, pkg, active = true, appMovesTask = false, lastAction = action),
            )
        }
    }

    // ── probe + corrections ────────────────────────────────────────────────

    /**
     * One dumpsys round: the watched package's task lines, per display
     * section. Returns null on probe failure (Shizuku not READY → the
     * manager reports exit 126, a non-zero dumpsys exit, or the 15s
     * timeout) — the caller skips the round.
     *
     * Parsing boundary: stdout is scanned line by line; a `displayId=N`
     * header line switches the current section, and inside a section a
     * line carrying BOTH `Task{` and the package is a candidate (its id
     * from `Task{<hex> #<n>`, "visible" from the line's own `visible=true`
     * flag). An EMPTY stdout (exit 0) is NOT a probe failure: it parses to
     * "no task lines" (the app was killed / nothing running) and the
     * restart path handles it; only a non-zero exit / timeout skips.
     */
    private fun probe(pkg: String): List<ProbeTask>? {
        val r = ShizukuManager.runProcess(
            arrayOf("dumpsys", "activity", "activities"),
            timeoutMs = PROBE_TIMEOUT_MS,
        )
        if (r.exitCode != 0) return null
        var currentDisplay = -1
        val tasks = mutableListOf<ProbeTask>()
        for (line in r.stdout.lineSequence()) {
            val header = DISPLAY_ID_LINE.find(line)
            if (header != null) {
                currentDisplay = header.groupValues[1].toInt()
                continue
            }
            if (currentDisplay != -1 && line.contains("Task{") && line.contains(pkg)) {
                val taskId = TASK_ID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                tasks += ProbeTask(taskId, currentDisplay, line.contains("visible=true"))
            }
        }
        return tasks
    }

    /**
     * Correction ①: no task of the package anywhere → restart it and land
     * it on [displayId] (same order `VdOffloadHandler` uses for `launch`).
     */
    private fun restart(displayId: Int, userId: Int, component: String): String {
        val r = runShell(listOf(
            "am", "start", "--user", userId.toString(),
            "--display", displayId.toString(), "-n", component,
        ))
        return when {
            r == null -> "am start skipped (Shizuku not READY)"
            r.exitCode == 0 -> "am start $component back onto display $displayId (task was gone — restart + re-land)"
            else -> "am start failed: exit=${r.exitCode} ${firstLine(r.combined)}"
        }
    }

    /**
     * Correction ②: the task exists on another display → `am display
     * move-stack` (device-verified: the command reaches the framework's
     * moveRootTaskToDisplay, unlike a bare `am start` which just delivers
     * to the running instance).
     */
    private fun moveStack(displayId: Int, taskId: Int): String {
        val r = runShell(listOf(
            "am", "display", "move-stack", taskId.toString(), displayId.toString(),
        ))
        return when {
            r == null -> "move-stack skipped (Shizuku not READY)"
            r.exitCode == 0 -> "am display move-stack $taskId -> display $displayId"
            else -> "move-stack failed: exit=${r.exitCode} ${firstLine(r.combined)}"
        }
    }

    /**
     * Shizuku shell execution; null when Shizuku itself is not READY (the
     * manager reports that as exit code 126) — same convention as
     * `VdOffloadHandler.shizukuRun`.
     */
    private fun runShell(cmd: List<String>): ShizukuManager.ProcessResult? {
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = PROBE_TIMEOUT_MS)
        return r.takeIf { it.exitCode != 126 }
    }

    private fun firstLine(s: String): String = s.lineSequence().firstOrNull()?.take(200) ?: ""

    private fun setOutcome(displayId: Int, outcome: PinOutcome) {
        synchronized(lock) {
            states[displayId]?.outcome = outcome
        }
    }

    /** Per-display section header inside `dumpsys activity activities`. */
    private val DISPLAY_ID_LINE = Regex("""^\s*displayId=(\d+)\s*$""")
    /** `Task{<hex> #<taskId>` — the decimal id is what move-stack takes. */
    private val TASK_ID = Regex("""Task\{[0-9a-f]+ #(\d+)""")
}
