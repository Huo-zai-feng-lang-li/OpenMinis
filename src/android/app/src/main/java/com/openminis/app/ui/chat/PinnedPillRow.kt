package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.agent.jobs.AgentJobRegistry
import kotlinx.coroutines.delay

/**
 * The pinned top-bar pill row: the scheduled-countdown pill and this
 * chat's sub-agent pill, with the original pre-vd layout rule:
 *
 * - exactly ONE of the two visible -> that pill is centred on the whole
 *   row (it owns the full width and centers itself);
 * - BOTH visible -> each keeps its own 50 % lane and centers in it
 *   (split, not jammed to the edges);
 * - neither visible -> an empty row, nothing pinned.
 *
 * The visibility decision mirrors each pill's own show/hide logic
 * (jobs + per-session dismissals) so the lanes switch live when a job
 * starts or finishes. The pills themselves stay self-contained: each
 * still no-ops when its own state says hidden, so this mount is a
 * pure layout container.
 */
@Composable
internal fun PinnedPillRow(currentSessionId: () -> String) {
    val context = LocalContext.current
    val manager = remember { ScheduledTaskManager(context.applicationContext) }
    val tasksFlow = remember { manager.store().observe() }
    val tasks by tasksFlow.collectAsState(initial = remember { manager.store().all() })
    val running by ScheduledSessionHold.runningTaskIds.collectAsState()
    val agentJobs by AgentJobRegistry.jobs.collectAsState()

    val sessionId = currentSessionId()

    val schedJobs = ScheduledPillModel.jobs(sessionId, tasks, running.keys, 0L) {
        ScheduledSessionHold.upstreamUnfinished(it, tasks, running.keys, agentJobs, 0L)
    }
    val schedLiveIds = schedJobs.mapTo(HashSet()) { it.id }
    val shownSched =
        ScheduledPillModel.state(schedJobs) != null &&
            !ScheduledPillModel.isDismissed(schedLiveIds, ScheduledPillDismissals.hidden[sessionId] ?: emptySet())

    val subLive = SubAgentPillModel.jobsFor(sessionId, agentJobs)
    val subLiveIds = subLive.mapTo(HashSet()) { it.id }
    val shownSub =
        SubAgentPillModel.state(subLive) != null &&
            !SubAgentPillModel.isDismissed(subLiveIds, SubAgentPillDismissals.hidden[sessionId] ?: emptySet())

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
    ) {
        when {
            shownSched && shownSub -> {
                Box(modifier = Modifier.weight(1f)) {
                    ScheduledTaskPill(currentSessionId = currentSessionId)
                }
                Box(modifier = Modifier.weight(1f)) {
                    SubAgentPill(currentSessionId = currentSessionId)
                }
            }
            shownSched -> ScheduledTaskPill(currentSessionId = currentSessionId)
            shownSub -> SubAgentPill(currentSessionId = currentSessionId)
            else -> Unit
        }
    }
}
