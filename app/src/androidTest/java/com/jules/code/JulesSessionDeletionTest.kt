package com.jules.code

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.jules.code.data.local.CachedSessionEntity
import com.jules.code.ui.ProvideJulesLocalization
import com.jules.code.ui.formatLocalTimestamp
import com.jules.code.ui.screens.SessionSummaryCard
import com.jules.code.ui.theme.JulesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 좁은 화면의 지연 세션에서 삭제 버튼 노출과 확인·취소를 검증합니다. */
class JulesSessionDeletionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 좁은 카드에서 상태는 하나만 표시하고 날짜·시간은 한 줄로 유지합니다. */
    @Test
    fun summaryShowsOneStatusAndSingleLineTimestamp() {
        val session = androidx.compose.runtime.mutableStateOf(CachedSessionEntity(
            sessionId = "status-test", name = "sessions/status-test", title = "상태 표시 테스트",
            prompt = "테스트", state = "IN_PROGRESS", repoDisplay = "owner/repo", branchDisplay = "main",
            prUrl = "", prTitle = "", createTime = "2020-01-01T00:00:00Z", updateTime = "2020-01-01T00:00:00Z",
            lastActivityTime = "2020-01-01T00:00:00Z", lastOriginator = "agent"
        ))
        composeRule.setContent {
            JulesTheme {
                ProvideJulesLocalization(langPref = "ko") {
                    Box(Modifier.width(280.dp)) {
                        SessionSummaryCard(session.value, onClick = {}, onPinToggle = {}, onDelete = {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("지연 (Stalled)").assertIsDisplayed()
        composeRule.onNodeWithText("활성 • 코드 작업 중").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        val timestamp = formatLocalTimestamp(session.value.updateTime)
        composeRule.onNodeWithText(timestamp).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        composeRule.runOnIdle {
            session.value = session.value.copy(lastActivityTime = java.time.Instant.now().toString())
        }
        composeRule.onNodeWithText("지연 (Stalled)").assertDoesNotExist()
        composeRule.onNodeWithText("활성 • 코드 작업 중").assertIsDisplayed()
        layouts.clear()
        composeRule.onNodeWithText(timestamp)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
    }

    /** 좁은 화면에서도 조회 실패를 0건으로 오인하지 않고 로딩과 성공으로 전환합니다. */
    @Test
    fun keySyncStatusShowsLoadingFailureAndRecovery() {
        val status = androidx.compose.runtime.mutableStateOf<com.jules.code.ui.ApiKeySyncStatus?>(null)
        composeRule.setContent {
            JulesTheme {
                ProvideJulesLocalization(langPref = "ko") {
                    Box(Modifier.width(320.dp)) {
                        com.jules.code.ui.screens.ApiKeySyncStatusText(status.value)
                    }
                }
            }
        }
        composeRule.onNodeWithText("아직 조회하지 않음").assertIsDisplayed()
        composeRule.runOnIdle { status.value = com.jules.code.ui.ApiKeySyncStatus(loading = true) }
        composeRule.onNodeWithText("세션 조회 중…").assertIsDisplayed()
        composeRule.runOnIdle { status.value = com.jules.code.ui.ApiKeySyncStatus(error = "인증 오류") }
        composeRule.onNodeWithText("조회 실패(기존 캐시 표시): 인증 오류").assertIsDisplayed()
        composeRule.runOnIdle { status.value = com.jules.code.ui.ApiKeySyncStatus(updatedAt = 1L) }
        composeRule.onNodeWithText("동기화 완료", substring = true).assertIsDisplayed()
    }

    /** 320dp 카드에서도 삭제 버튼이 보이고 확인 전에는 삭제하지 않습니다. */
    @Test
    fun stalledSummaryShowsDeleteAndRequiresConfirmation() {
        var deletes = 0
        val session = CachedSessionEntity(
            sessionId = "stalled-test", name = "sessions/stalled-test", title = "지연된 테스트 작업",
            prompt = "테스트", state = "IN_PROGRESS", repoDisplay = "owner/repo", branchDisplay = "main",
            prUrl = "", prTitle = "", createTime = "2020-01-01T00:00:00Z", updateTime = "2020-01-01T00:00:00Z",
            lastActivityTime = "2020-01-01T00:00:00Z", lastOriginator = "agent"
        )
        composeRule.setContent {
            JulesTheme {
                ProvideJulesLocalization(langPref = "ko") {
                    Box(Modifier.width(320.dp)) {
                        SessionSummaryCard(session, onClick = {}, onPinToggle = {}, onDelete = { deletes++ }, onNudge = {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("세션 삭제").assertIsDisplayed().performClick()
        // 별도 Android 창의 첫 레이아웃이 끝난 뒤 확인창을 검사합니다.
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onNodeWithText("세션을 삭제할까요?").isDisplayed()
        }
        composeRule.onNodeWithText("세션을 삭제할까요?").assertIsDisplayed()
        assertEquals(0, deletes)
        composeRule.onNodeWithText("취소").performClick()
        assertEquals(0, deletes)
        composeRule.onNodeWithText("세션 삭제").performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onNode(hasText("세션 삭제") and hasAnyAncestor(isDialog())).isDisplayed()
        }
        composeRule.onNode(hasText("세션 삭제") and hasAnyAncestor(isDialog())).performClick()
        composeRule.runOnIdle { assertEquals(1, deletes) }
    }
}
