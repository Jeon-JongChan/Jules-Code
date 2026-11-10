package com.jules.code.ui.health

import androidx.compose.runtime.Composable
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jules.code.R
import com.jules.code.ui.SessionHealthDiagnostic
import com.jules.code.ui.JulesViewModel.Companion.SessionHealthReason

/** 경과 시간을 리소스의 단위와 자리 순서로 표시하여 언어 분기를 화면 밖으로 퍼뜨리지 않습니다. */
@Composable
fun healthSilentDuration(minutes: Long): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours > 0 && remaining > 0 -> stringResource(R.string.health_duration_hours_minutes, hours, remaining)
        hours > 0 -> stringResource(R.string.health_duration_hours, hours)
        else -> stringResource(R.string.health_duration_minutes, remaining.coerceAtLeast(1))
    }
}

/** 원격 상태 코드를 앱 언어의 리소스로 바꾸고 알 수 없는 상태는 원문을 보존합니다. */
@Composable
fun sessionStateLabel(state: String): String = sessionStateLabel(LocalContext.current, state)

/** TTS 콜백에서도 화면과 같은 상태 번역 정책을 사용하도록 Context 기반 표시를 제공합니다. */
fun sessionStateLabel(context: Context, state: String): String = when (state.uppercase(java.util.Locale.ROOT)) {
    "QUEUED" -> context.getString(R.string.state_queued)
    "PLANNING" -> context.getString(R.string.state_planning)
    "AWAITING_PLAN_APPROVAL" -> context.getString(R.string.state_awaiting_plan)
    "AWAITING_USER_FEEDBACK" -> context.getString(R.string.state_awaiting_feedback)
    "IN_PROGRESS" -> context.getString(R.string.state_in_progress)
    "PAUSED" -> context.getString(R.string.state_paused)
    "COMPLETED" -> context.getString(R.string.state_completed)
    "FAILED" -> context.getString(R.string.state_failed)
    else -> state
}

/** 언어 중립 판정 원인과 수치를 받아 건강진단 제목을 현재 언어로 렌더링합니다. */
@Composable
fun healthDiagnosticTitle(diagnostic: SessionHealthDiagnostic): String {
    val duration = healthSilentDuration(diagnostic.silentDurationMinutes)
    return when (diagnostic.reason) {
        SessionHealthReason.NONE -> ""
        SessionHealthReason.NORMAL -> stringResource(R.string.health_normal_title)
        SessionHealthReason.PLAN_STALLED -> stringResource(R.string.health_plan_title, duration)
        SessionHealthReason.UNANSWERED_MESSAGES -> stringResource(R.string.health_followup_title, diagnostic.unansweredUserCount, duration)
        SessionHealthReason.AGENT_STALLED -> stringResource(R.string.health_agent_title, duration)
    }
}

/** 진단 문장의 조합과 번역은 이 표시 module에만 두며 모델은 표시 언어를 알지 못하게 합니다. */
@Composable
fun healthDiagnosticDetail(diagnostic: SessionHealthDiagnostic): String {
    val duration = healthSilentDuration(diagnostic.silentDurationMinutes)
    val step = if (diagnostic.currentStepTitle.isBlank()) stringResource(R.string.health_first_step)
        else diagnostic.currentStepTitle
    return when (diagnostic.reason) {
        SessionHealthReason.NONE, SessionHealthReason.NORMAL -> ""
        SessionHealthReason.PLAN_STALLED -> stringResource(
            R.string.health_plan_detail, diagnostic.totalStepsCount, step, diagnostic.unansweredUserCount
        )
        SessionHealthReason.UNANSWERED_MESSAGES -> stringResource(
            R.string.health_followup_detail, diagnostic.unansweredUserCount, duration
        )
        SessionHealthReason.AGENT_STALLED -> stringResource(
            R.string.health_agent_detail, sessionStateLabel(diagnostic.sessionState), duration, step
        )
    }
}
