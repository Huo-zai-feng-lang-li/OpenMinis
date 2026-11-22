package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.delay

/** The purple the pill is pinned in (sub agents, not the amber scheduled pill). */
internal val SubAgentAccent = Color(0xFF7C3AED)

/** A finished job lingers in its "✅ 完成" state this long, then the pill exits. */
private const val FINISHED_VISIBLE_MS = 3_000L

/**
 * [T-subagent-pill] A small pill pinned under the chat's top bar, right next
 * to [ScheduledTaskPill], while the current conversation has sub agent
 * (child-of-current) work running. One pill per job — up to three, the
 * MAX_CONCURRENT_CHILD_JOBS_PER_PARENT cap — each one:
 *
 * ```
 * [🤖] 00:02  第 1 轮        [⏸]
 * 模型策略: 与本对话相同 · Agnes 3.0 Flash (Ultra) · primary
 * ```
 *
 * - Data: AgentJobRegistry.jobs, filtered to target.parentSessionId == the
 *   chat's REAL session id (currentSessionId), so the pill never shows
 *   another session's children.
 * - The model line is known at dispatch (modelOrigin / modelLabel / tierUsed
 *   are stamped by the runner before the run starts), so it appears
 *   immediately — nothing waits for the run to finish.
 * - When a job finishes the pill flips to a compact "✅ 完成 · 5s · 4 轮"
 *   state and disappears [FINISHED_VISIBLE_MS] later; no running job and
 *   nothing that just finished means no pill at all.
 * - Pause/resume: the agent loop has no pause primitive, so the ⏸ button
 *   courses the child down the STEER lane (the same one
 *   `subagent_task action="steer"` uses) — asking it to stop working; ▶
 *   asks it to continue. If the job already closed, steer fails and the
 *   button simply reverts.
 * - Ticks once a second only while shown and the screen is resumed, the
 *   same LaunchedEffect pattern as ScheduledTaskPill.
 */
@Composable
internal fun SubAgentPill(currentSessionId: () -> String) {
    val context = LocalContext.current
    val jobs by AgentJobRegistry.jobs.collectAsState(initial = AgentJobRegistry.jobs.value)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // [T-subagent-pill] Read on every composition, like the scheduled pill:
    // a draft chat gets its real id on the first message.
    val sessionId = currentSessionId()

    val childJobs = jobs.values
        .filter {
            it.target is AgentJobTarget.ChildOfCurrent &&
                it.target.parentSessionId == sessionId
        }
        .sortedBy { it.createdAtMs }
    val running = childJobs.filter { it.isActive }
    val finished = childJobs.filter { job ->
        !job.isActive &&
            job.finishedAtMs != null &&
            now - job.finishedAtMs <= FINISHED_VISIBLE_MS
    }
    val shown = running.isNotEmpty() || finished.isNotEmpty()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(shown, lifecycleOwner) {
        if (!shown) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L - now % 1_000L)
            }
        }
    }

    val reduceMotion = animationsDisabled(context)
    val pausedJobs = remember { mutableStateMapOf<String, Boolean>() }

    AnimatedVisibility(
        visible = shown,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically { -it } + fadeIn(),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically { -it } + fadeOut(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
        ) {
            (running + finished).forEach { job ->
                SubAgentPillCard(
                    job = job,
                    now = now,
                    finished = !job.isActive,
                    paused = pausedJobs[job.id] == true,
                    onTogglePause = {
                        // The ⏸ control only composes while the job is live,
                        // so a tap here always addresses a steerable job —
                        // a steer that no longer lands just resets the flag.
                        val pausing = pausedJobs[job.id] != true
                        val ok = if (pausing) {
                            AgentJobRegistry.steer(job.id, PAUSE_INSTRUCTION)
                        } else {
                            AgentJobRegistry.steer(job.id, RESUME_INSTRUCTION)
                        }
                        if (ok) {
                            pausedJobs[job.id] = pausing
                            AppLogger.info("SubAgentPill", "job=${job.id.take(8)} session=${sessionId.take(8)} ${if (pausing) "paused" else "resumed"} via steer")
                        } else {
                            // The child is no longer steerable; the state will
                            // leave on its own, just forget the stale flag.
                            pausedJobs.remove(job.id)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One row of the pill: the 🤖 + clock + round body, the ⏸/▶ control, and —
 * while the job is live — the small model-strategy line under it.
 */
@Composable
private fun SubAgentPillCard(
    job: AgentJob,
    now: Long,
    finished: Boolean,
    paused: Boolean,
    onTogglePause: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val pending = job.state == AgentJobState.PENDING
    val baseMs = job.startedAtMs ?: job.createdAtMs
    val elapsedMs = (if (finished) job.finishedAtMs ?: now else now) - baseMs
    // Live count while the run ticks; for finished jobs that never got a
    // round tick (e.g. a scheduled child), the runner's "turns N" summary
    // line is the best source.
    val round = job.roundCount?.coerceAtLeast(1) ?: summaryTurns(job) ?: 1

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .shadow(6.dp, RoundedCornerShape(999.dp))
                .border(0.75.dp, SubAgentAccent.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .background(SubAgentAccent, RoundedCornerShape(999.dp))
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        ) {
            Text("🤖", fontSize = 14.sp, maxLines = 1)
            if (finished) {
                Text(
                    finishedLine(job, elapsedMs, round),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 7.dp),
                )
            } else {
                Text(
                    mmss(elapsedMs),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Text(
                    if (pending) "排队中" else "第 $round 轮",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTogglePause()
                    },
                    modifier = Modifier.size(28.dp),
                    contentDescription = if (paused) "恢复子代理" else "暂停子代理",
                ) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                        tint = if (paused) Color.White else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (!finished) {
            SubAgentModelLine(job, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
    }
}

/** "模型策略: 与本对话相同 · Agnes 3.0 Flash (Ultra) · primary" — 11sp, tinted. */
@Composable
private fun SubAgentModelLine(job: AgentJob, modifier: Modifier = Modifier) {
    val strategy = when (job.modelOrigin) {
        "inherited" -> "与本对话相同"
        "pinned" -> "指定模型组"
        "default_group" -> "默认模型组"
        "sub_group" -> "轻量模型组"
        else -> "继承"
    }
    val model = job.modelLabel ?: job.agentName ?: "—"
    val tier = job.tierUsed?.lowercase() ?: "primary"
    Text(
        "模型策略: $strategy · $model · $tier",
        fontSize = 11.sp,
        color = SubAgentAccent,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** The "turns N" of the runner's "Summary: tools … · turns N · tokens …" line, if it has one. */
private fun summaryTurns(job: AgentJob): Int? =
    job.summaryLine?.let { line ->
        Regex("turns\\s+(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

/** "✅ 完成 · 5s · 4 轮", state-accurate for the non-DONE endings. */
private fun finishedLine(job: AgentJob, elapsedMs: Long, round: Int): String {
    val seconds = elapsedMs / 1000
    return when (job.state) {
        AgentJobState.DONE -> "✅ 完成 · ${seconds}s · $round 轮"
        AgentJobState.FAILED -> "❌ 失败 · ${seconds}s · $round 轮"
        AgentJobState.CANCELLED -> "⏹ 已取消 · ${seconds}s · $round 轮"
        AgentJobState.TIMEOUT -> "⏱ 超时 · ${seconds}s · $round 轮"
        else -> "已结束"
    }
}

/** mm:ss; the cap is generous (the run budget is minutes, not hours). */
private fun mmss(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

/**
 * The course-correction texts, both in the child's own task language via
 * the model — English keeps them unambiguous no matter the model in play.
 * Delivered at the child's next turn boundary, like any steer.
 */
private const val PAUSE_INSTRUCTION =
    "[User paused this sub agent from the chat's top pill. Stop calling tools. " +
        "Reply with a two-line status: exactly where you are in the task and what is left, " +
        "so the user can resume you or steer you later.]"

private const val RESUME_INSTRUCTION =
    "[User resumed this sub agent from the chat's top pill. Continue the task from where you stopped.]"
