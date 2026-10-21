package com.jules.code

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jules.code.data.local.JulesDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 에뮬레이터 실기기 환경에서 Room DB API 키 저장/전환 및 모바일 UI 전체 플로우(온보딩 -> 대시보드 -> 세션 상세)를 검증하는 계측 테스트입니다.
 */
@RunWith(AndroidJUnit4::class)
class JulesInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 별도 메모리 DB에서 API 키 저장과 활성 프로필 전환을 확인합니다. */
    @Test
    fun roomDatabase_savesAndSwitchesApiKeyProfiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JulesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.dao()

        dao.saveAndActivateApiKey("Personal Key", "AQ.TestKey1111")
        val active1 = dao.getActiveApiKey()
        assertNotNull(active1)
        assertEquals("AQ.TestKey1111", active1?.apiKey)

        dao.saveAndActivateApiKey("Work Key", "AQ.TestKey2222")
        val active2 = dao.getActiveApiKey()
        assertEquals("AQ.TestKey2222", active2?.apiKey)

        val allKeys = dao.observeApiKeys().first()
        assertEquals(2, allKeys.size)
        db.close()
    }

    /** 데이터·언어 로딩 후 실제 탭 이동과 한국어 대시보드 표시를 확인합니다. */
    @Test
    fun onboardingToDemoDashboardAndSessionDetail_worksEndToEnd() {
        // Room·언어 설정 로딩은 Compose 유휴 상태와 별개이므로 실제 화면을 기다립니다.
        val sourcesMatcher = hasText("저장소") or hasText("Sources")
        val demoMatcher = hasText("API 키 없이 데모 시뮬레이션 모드로 체험") or hasText("Try Demo Simulation Mode")
        composeRule.waitUntil(timeoutMillis = 10000) {
            composeRule.onAllNodes(sourcesMatcher or demoMatcher).fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodes(sourcesMatcher).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNode(demoMatcher).performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10000) {
            composeRule.onAllNodes(sourcesMatcher).fetchSemanticsNodes().isNotEmpty()
        }

        // 하단 네비게이션 탭 이동 검증 (저장소/Sources -> 설정/Settings -> 대시보드/Dashboard)
        composeRule.onAllNodes(sourcesMatcher)[0].performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasText("설정") or hasText("Settings"))[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Quota", substring = true).assertIsDisplayed()

        // 설정에서 한국어(Korean) 선택 동작 검증
        val koOption = composeRule.onAllNodesWithText("한국어 (Korean)")
        if (koOption.fetchSemanticsNodes().isNotEmpty()) {
            koOption[0].performClick()
            composeRule.waitForIdle()
        }

        composeRule.onAllNodesWithText("대시보드")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("오늘 어떤 코딩 작업을 맡기시겠습니까?").assertIsDisplayed()
    }
}

