package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobThen
import com.openminis.app.agent.jobs.AgentJobTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-pill] The sub-agent pill under the chat's top bar, as plain
 * decisions: which jobs count (this chat's own live sub agents), which one
 * the pill shows, the elapsed clock and the hide rule.
 */
class SubAgentPillModelTest {

    private const val SID = "s0"
    private const val OTHER = "s1"

    private fun job(
        id: String,
        session: String = SID,
        state: AgentJobState = AgentJobState.RUNNING,
        createdAt: Long = 1_000L,
        startedAt: Long? = 2_000L,
        round: Int? = null,
    ): AgentJob = AgentJob(
        id = id,
        label = id,
        title = id,
        origin = AgentJobOrigin.TOOL,
        trigger = AgentJobTrigger.Once(0L),
        target = AgentJobTarget.ChildOfCurrent(session, "tool_$id"),
        prompt = null,
        then = AgentJobThen.None,
        state = state,
        createdAtMs = createdAt,
        startedAtMs = startedAt,
        roundCount = round,
    )

    private fun mapOf(vararg jobs: AgentJob): Map<String, AgentJob> =
        jobs.associateBy { it.id }

    // ── which jobs count ────────────────────────────────────────────────

    @Test
    fun `only this session's live child-of-current jobs count`() {
        val all = mapOf(
            job("a"),
            job("b", session = OTHER),
            job("c", state = AgentJobState.DONE),
            job("d", state = AgentJobState.CANCELLED),
            job("e", state = AgentJobState.FAILED),
            job("f", state = AgentJobState.TIMEOUT),
        )
        val ids = SubAgentPillModel.jobsFor(SID, all).map { it.id }.toSet()
        assertEquals(setOf("a"), ids)
    }

    @Test
    fun `queued pending jobs stay - they are the waiting face`() {
        val all = mapOf(job("a", state = AgentJobState.PENDING, startedAt = null))
        assertEquals(1, SubAgentPillModel.jobsFor(SID, all).size)
    }

    @Test
    fun `no jobs - nothing to show`() {
        assertNull(SubAgentPillModel.state(emptyList()))
    }

    // ── which one the pill shows ────────────────────────────────────────

    @Test
    fun `the most recently started live run wins`() {
        val old = job("old", startedAt = 10_000L)
        val new = job("new", startedAt = 20_000L)
        val s = SubAgentPillModel.state(listOf(old, new))
        assertEquals("new", s?.job?.id)
        assertEquals(1, s?.others)
    }

    @Test
    fun `with only queued runs the oldest pending is stable`() {
        val first = job("first", state = AgentJobState.PENDING, createdAt = 1_000L, startedAt = null)
        val second = job("second", state = AgentJobState.PENDING, createdAt = 5_000L, startedAt = null)
        val s = SubAgentPillModel.state(listOf(first, second))
        assertEquals("first", s?.job?.id)
        assertEquals(1, s?.others)
    }

    @Test
    fun `a live run plus a queued one - the live run shows`() {
        val live = job("live", startedAt = 30_000L)
        val queued = job("queued", state = AgentJobState.PENDING, startedAt = null)
        assertEquals("live", SubAgentPillModel.state(listOf(live, queued))?.job?.id)
    }

    // ── the elapsed clock ───────────────────────────────────────────────

    @Test
    fun `elapsed is null while queued - the pill shows waiting instead`() {
        assertNull(SubAgentPillModel.elapsed(job("q", state = AgentJobState.PENDING, startedAt = null), 0L))
    }

    @Test
    fun `elapsed under an hour is mm:ss`() {
        val j = job("j", startedAt = 0L)
        assertEquals("00:48", SubAgentPillModel.elapsed(j, 48_000L))
        assertEquals("59:59", SubAgentPillModel.elapsed(j, 3_599_000L))
    }

    @Test
    fun `elapsed up to a day is h m`() {
        val j = job("j", startedAt = 0L)
        assertEquals("1h 5m", SubAgentPillModel.elapsed(j, (1L * 3600 + 5 * 60) * 1000L))
    }

    @Test
    fun `beyond a day is d h`() {
        val j = job("j", startedAt = 0L)
        assertEquals("2d 3h", SubAgentPillModel.elapsed(j, (2L * 86_400 + 3L * 3600) * 1000L))
    }

    @Test
    fun `a finish clock never runs backwards`() {
        val j = job("j", startedAt = 50_000L)
        assertEquals("00:00", SubAgentPillModel.elapsed(j, 10_000L))
    }

    // ── the hide rule - same as the sched pill ─────────────────────────

    @Test
    fun `hidden only when every live job is dismissed`() {
        val live = setOf("a", "b")
        assertTrue(SubAgentPillModel.isDismissed(live, setOf("a", "b")))
        assertTrue(SubAgentPillModel.isDismissed(live, setOf("a", "b", "gone")))
        assertFalse(SubAgentPillModel.isDismissed(live, setOf("a")))
        assertFalse(SubAgentPillModel.isDismissed(emptySet(), setOf("a")))
    }
}
