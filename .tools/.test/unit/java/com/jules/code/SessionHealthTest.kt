package com.jules.code

import com.jules.code.data.model.JulesActivity
import com.jules.code.data.model.JulesSession
import com.jules.code.data.model.MessageEvent
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.JulesViewModel.Companion.SessionHealthReason
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** 표시 문구를 바꾸어도 실제 활동 시각과 작업 상태에 따른 진단이 유지되는지 검사합니다. */
class SessionHealthTest {
    private val started = "2026-11-10T00:00:00Z"
    private val now = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()

    /** 서버 updateTime만 갱신되어도 오래된 실제 활동을 새 진행으로 오인하지 않게 합니다. */
    @Test
    fun recentSessionMetadataDoesNotHideStalledActivity() {
        val session = session("IN_PROGRESS")
        val lastActivity = JulesActivity(id = "old", originator = "agent", createTime = started)
        val result = JulesViewModel.diagnoseSessionHealth(session, listOf(lastActivity), now)
        assertTrue(result.isStalled)
        assertEquals(SessionHealthReason.AGENT_STALLED, result.reason)
        assertEquals(60L, result.silentDurationMinutes)
    }

    /** 종료했거나 승인 대기인 세션은 활동이 오래되어도 작업 멈춤으로 표시하지 않습니다. */
    @Test
    fun completedAndApprovalWaitingSessionsAreNotStalled() {
        listOf("COMPLETED", "FAILED", "AWAITING_PLAN_APPROVAL").forEach { state ->
            val result = JulesViewModel.diagnoseSessionHealth(session(state), emptyList(), now)
            assertFalse(result.isStalled)
            assertEquals(SessionHealthReason.NORMAL, result.reason)
        }
    }

    /** 후속 요청의 무응답을 일반 활동 지연과 구분하되 실제 사용자 본문은 보존합니다. */
    @Test
    fun unansweredFollowupKeepsItsOwnReason() {
        val events = listOf(
            JulesActivity(id = "agent", originator = "agent", createTime = started),
            JulesActivity(id = "user", originator = "user", createTime = "2026-11-10T00:01:00Z",
                userMessaged = MessageEvent(message = "Keep my own text."))
        )
        val result = JulesViewModel.diagnoseSessionHealth(session("IN_PROGRESS"), events, now)
        assertTrue(result.isStalled)
        assertEquals(SessionHealthReason.UNANSWERED_MESSAGES, result.reason)
        assertEquals(1, result.unansweredUserCount)
    }

    /** 표시 언어나 기기 시간을 요구하지 않는 고정 시각의 원격 세션을 구성합니다. */
    private fun session(state: String) = JulesSession(
        name = "sessions/health", state = state, createTime = started,
        updateTime = "2026-11-10T01:00:00Z"
    )
}
