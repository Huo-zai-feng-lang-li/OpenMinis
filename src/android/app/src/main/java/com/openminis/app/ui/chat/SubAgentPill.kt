package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.logging.AppLogger
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * [T-subagent-pill] A small centred pill under the chat's top bar while THIS
 * chat has sub agents running: the jobs whose target is a
 * [com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent] of this
 * session and that are still active (RUNNING or queued PENDING). The purple
 * twin of [ScheduledTaskPill], which it mirrors in layout, motion and
 * a11y so the two read as one row of status slots:
 *
 * - the groups glyph (hourglass while queued for a concurrency slot),
 * - a ticking elapsed clock ("00:48", then "1h 5m") or "Waiting" while
 *   queued,
 * - the live assistant-turn count ("Turn N" / "第 N 轮") fed by
 *   [AgentJobRegistry.setRoundCount] every 2 s while the child streams,
 * - "+N" for further live runs of this chat, one run shown.
 *
 * - Tap opens a status dialog: the run's identity (agent name / title,
 *   model label, elapsed, turn) plus Stop and Hide.
 * - Long-press (with a haptic) offers Stop (live runs only) and "Hide
 *   this pill"; the same two are TalkBack actions.
 * - Ticks once a second only while shown and the screen is resumed.
 *
 * Dismissals are per chat and in-memory only (like [ScheduledPillDismissals]):
 * hiding covers the live set at the moment; a NEW run brings the pill back.
 */
internal object SubAgentPillDismissals {
    val hidden = mutableStateMapOf<String, Set<String>>()

    fun hide(ids: Set<String>, sessionId: String) {
        hidden[sessionId] = (hidden[sessionId] ?: emptySet()) + ids
    }

    /** Forget jobs no longer live, so the entry empties once the batch is over. */
    fun prune(live: Set<String>, sessionId: String) {
        val h = hidden[sessionId] ?: return
        val kept = h intersect live
        if (kept != h) { if (kept.isEmpty()) hidden.remove(sessionId) else hidden[sessionId] = kept }
    }
}

/** Pure pill decisions for [SubAgentPill], JVM-tested. */
internal object SubAgentPillModel {
    /**
     * The jobs this session's pill shows: this chat's OWN sub agents -
     * child-of-current jobs of [sessionId] - that are still active.
     * Finished / failed / cancelled jobs drop out; queued PENDING jobs
     * stay (they are the "Waiting" face).
     */
    fun jobsFor(sessionId: String, jobs: Map<String, AgentJob>): List<AgentJob> =
        jobs.values.filter { it.isActive && it.target.parentSessionIdOrNull == sessionId }

    data class State(val job: AgentJob, /** "+N" */ val others: Int)

    /**
     * One run shown: the most recently STARTED live run wins (the one the
     * user is watching); with only queued runs, the oldest pending is
     * shown so the waiting state is stable, not shuffling.
     */
    fun state(jobs: List<AgentJob>): State? {
        if (jobs.isEmpty()) return null
        val main = jobs.filter { it.state == AgentJobState.RUNNING }
            .maxByOrNull { it.startedAtMs ?: 0L }
            ?: jobs.minBy { it.createdAtMs }
        return State(main, jobs.size - 1)
    }

    /** "00:48" under an hour, "1h 5m" up to a day, "2d 3h" beyond; null while queued (no start yet). */
    fun elapsed(job: AgentJob, now: Long): String? {
        val start = job.startedAtMs ?: return null
        val s = ((now - start).coerceAtLeast(0L)) / 1000
        return when {
            s < 3600 -> String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
    }

    /** The pill is hidden only when every live job is dismissed - same rule as the sched pill. */
    fun isDismissed(live: Set<String>, hidden: Set<String>): Boolean =
        live.isNotEmpty() && hidden.containsAll(live)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentPill(currentSessionId: () -> String) {
    val context = LocalContext.current
    val jobsAll by AgentJobRegistry.jobs.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // [T-port-sched-pill-visible] analogue: read the REAL session id on every
    // composition so a draft chat ("__new__…") matches its own jobs after the
    // first message resolves the id.
    val sessionId = currentSessionId()

    val live = SubAgentPillModel.jobsFor(sessionId, jobsAll)
    val liveIds = live.mapTo(HashSet()) { it.id }
    LaunchedEffect(liveIds) { SubAgentPillDismissals.prune(liveIds, sessionId) }
    val hidden = SubAgentPillDismissals.hidden[sessionId] ?: emptySet<String>()
    val state = SubAgentPillModel.state(live)
        ?.takeIf { !SubAgentPillModel.isDismissed(liveIds, hidden) }

    var listOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val shown = state != null
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
    var openStatus by remember { mutableStateOf<AgentJob?>(null) }
    var confirmStop by remember { mutableStateOf<AgentJob?>(null) }
    AnimatedVisibility(
        visible = shown,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically { -it } + fadeIn(),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically { -it } + fadeOut(),
    ) {
        val s = state ?: return@AnimatedVisibility
        SubAgentPillCard(
            state = s,
            job = s.job,
            now = now,
            onOpen = { listOpen = true },
            onHide = {
                AppLogger.info("SubAgentPill", "hide session=${sessionId.take(8)} jobs=${liveIds.size}")
                SubAgentPillDismissals.hide(liveIds, sessionId)
            },
            onStop = { confirmStop = s.job },
        )
    }

    // [T-subagent-list-sheet] The original bottom sheet: one row per live
    // sub agent (badge + name + status line + purple clock); tapping a row
    // opens that run's detail dialog (identity + stop).
    if (listOpen) {
        SubAgentListSheet(
            live = live,
            now = now,
            onPick = { job ->
                listOpen = false
                openStatus = job
            },
            onDismiss = { listOpen = false },
        )
    }
    openStatus?.let { job ->
        SubAgentStatusDialog(
            job = job,
            liveJob = AgentJobRegistry.job(job.id),
            onDismiss = { openStatus = null },
            onStop = { confirmStop = job },
        )
    }
    if (confirmStop != null) {
        AlertDialog(
            onDismissRequest = { confirmStop = null },
            title = { Text(stringResource(R.string.subagent_pill_stop_confirm_title)) },
            text = { Text(stringResource(R.string.subagent_pill_stop_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val job = confirmStop ?: return@TextButton
                    stopLiveSubAgent(context, job)
                    AppLogger.info("SubAgentPill", "stopped job=${job.id.take(8)} session=${sessionId.take(8)}")
                    confirmStop = null
                    openStatus = null
                }) { Text(stringResource(R.string.subagent_pill_stop_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = null }) { Text(stringResource(R.string.sched_pill_not_now)) }
            },
        )
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun SubAgentListSheet(
    live: List<AgentJob>,
    now: Long,
    onPick: (AgentJob) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = helperAccent()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                stringResource(R.string.subagent_pill_list_title, live.size),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            if (live.isEmpty()) {
                Text(
                    stringResource(R.string.subagent_pill_list_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                live.forEach { job ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(job) }
                            .padding(vertical = 10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(accent.copy(alpha = 0.16f), CircleShape)
                                .border(0.75.dp, accent.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Groups,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                listRowTitle(job),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listRowStatus(job),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SubAgentPillModel.elapsed(job, now)?.let {
                            Text(
                                it,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TabularDigits,
                                color = accent,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "测试子代理：沙箱环…" - the original sheet row's first line. */
private fun listRowTitle(job: AgentJob): String {
    val agent = job.agentName?.takeIf { it.isNotBlank() }
    return if (agent != null) "$agent：${job.title}" else job.title
}

/** "运行中 · 第 2 轮 · shell_exec…" - the original sheet row's second line. */
@Composable
private fun listRowStatus(job: AgentJob): String {
    val parts = mutableListOf(
        stringResource(
            if (job.state == AgentJobState.RUNNING) R.string.helper_status_running
            else R.string.agent_status_queued,
        ),
    )
    job.roundCount?.takeIf { it > 0 }?.let { parts += stringResource(R.string.agent_callback_turn, it) }
    job.summaryLine?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}

/** Stop the run: a started child goes through the shared user-stop helper
 *  (cancels the same-turn siblings too); a queued run that never started
 *  cancels its job outright. */
private fun stopLiveSubAgent(context: Context, job: AgentJob) {
    val child = job.runSessionId
    if (child != null) {
        stopSubAgentByUser(context, child, "user stopped from the sub-agent pill")
    } else {
        AgentJobRegistry.cancel(job.id, "user stopped a queued run from the sub-agent pill")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubAgentPillCard(
    state: SubAgentPillModel.State,
    job: AgentJob,
    now: Long,
    onOpen: () -> Unit,
    onHide: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val running = job.state == AgentJobState.RUNNING
    val accent = helperAccent()
    val waitingLabel = stringResource(R.string.subagent_pill_waiting)
    val clock = SubAgentPillModel.elapsed(job, now) ?: waitingLabel
    val round = job.roundCount?.takeIf { it > 0 }?.let { stringResource(R.string.agent_callback_turn, it) }
    val hideLabel = stringResource(R.string.subagent_pill_hide)
    val stopLabel = stringResource(R.string.subagent_pill_stop)
    val hint = stringResource(R.string.subagent_pill_a11y_hint)
    // [T-subagent-pill-fix] buildSpoken is @Composable (stringResource) - it
    // must run in composable scope, hoisted out of the semantics lambda
    // (that call was the 1.29 CI compile error).
    val spoken = buildSpoken(state, job, running, clock, round)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .shadow(6.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .background(accent.copy(alpha = 0.06f), CircleShape)
                    .border(0.75.dp, accent.copy(alpha = 0.28f), CircleShape)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.Button
                        onClick(label = hint) { onOpen(); true }
                        customActions = listOfNotNull(
                            if (running) CustomAccessibilityAction(stopLabel) { onStop(); true } else null,
                            CustomAccessibilityAction(hideLabel) { onHide(); true },
                        )
                    }
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 13.dp),
            ) {
                SubAgentPillRing(running = running, accent = accent)
                // [T-subagent-list-sheet] The original (pre-vd) pill shows a
                // round COUNT badge with the total live sub agents instead
                // of a "+N" chip - one pill, badge says how many.
                val total = state.others + 1
                if (total > 1) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(18.dp)
                            .background(accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$total",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.surface,
                            maxLines = 1,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                    val isWaiting = clock == waitingLabel
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text(
                            clock,
                            fontSize = if (isWaiting) 13.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = TabularDigits,
                            color = accent,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (round != null) {
                        Text(
                            round,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            style = TabularDigits,
                            color = accent.copy(alpha = 0.7f), maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (running) {
                    DropdownMenuItem(
                        text = { Text(stopLabel, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onStop() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(hideLabel) },
                    leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                    onClick = { menuOpen = false; onHide() },
                )
            }
        }
    }
}

/** The status dialog the tap opens: identity + live facts + the two actions. */
@Composable
private fun SubAgentStatusDialog(
    job: AgentJob,
    liveJob: AgentJob?,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val accent = helperAccent()
    val live = liveJob ?: job
    val running = live.state == AgentJobState.RUNNING
    val name = live.agentName?.takeIf { it.isNotBlank() }
        ?: live.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.subagent_pill_dialog_title)
    val facts = buildList {
        live.modelLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        SubAgentPillModel.elapsed(live, System.currentTimeMillis())?.let { add(it) }
        live.roundCount?.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_callback_turn, it)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = accent)
        },
        text = {
            Text(
                if (facts.isEmpty()) stringResource(R.string.subagent_pill_waiting) else facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = { onDismiss(); onStop() }) {
                    Text(stringResource(R.string.subagent_pill_stop), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sched_pill_not_now)) }
        },
    )
}

/** One line for TalkBack, the pill's whole state (iOS accessibilityText analogue). */
@Composable
private fun buildSpoken(
    state: SubAgentPillModel.State,
    job: AgentJob,
    running: Boolean,
    clock: String,
    round: String?,
): String {
    val parts = mutableListOf(
        stringResource(R.string.subagent_pill_a11y_title),
        job.agentName?.takeIf { it.isNotBlank() } ?: job.title,
    )
    parts += if (running) clock else stringResource(R.string.subagent_pill_waiting)
    round?.let { parts += it }
    if (state.others > 0) parts += stringResource(R.string.subagent_pill_a11y_more, state.others)
    return parts.joinToString(" · ")
}

/** The glyph in its tinted circle, ringed full while live, open while queued. */
@Composable
private fun SubAgentPillRing(running: Boolean, accent: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            drawCircle(accent.copy(alpha = 0.15f))
            drawCircle(accent.copy(alpha = 0.18f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (running) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            if (running) Icons.Outlined.Groups else Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Digits of equal width, so the clock does not jitter while ticking. */
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")
