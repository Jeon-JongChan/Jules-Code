package com.jules.code

import android.content.Context
import androidx.activity.compose.setContent
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

    /** 큰 Base64 본문만 제외하고 활동·메시지·미디어 종류가 보존되는지 검사합니다. */
    @Test
    fun largeActivityResponseKeepsTimelineWithoutBufferingMedia() {
        val api = com.jules.code.data.remote.JulesApiClient()
        val raw = """{"activities":[{"id":"old","agentMessaged":{"message":"이전 작업"},"artifacts":[{"media":{"mimeType":"video/mp4","data":"${"A".repeat(2 * 1024 * 1024)}"}}]}]}"""
        val compact = api.readActivitiesBounded(java.io.ByteArrayInputStream(raw.toByteArray()))
        org.junit.Assert.assertTrue(compact.length < 1024)
        val activity = api.parseActivitiesFlexible(compact).activities.single()
        assertEquals("이전 작업", activity.resolvedAgentMessage)
        assertEquals("video/mp4", activity.artifacts.single().media?.mimeType)
        assertEquals("", activity.artifacts.single().media?.data)
    }

    /** 실제 v3 테이블 구조를 만든 뒤 v4 업그레이드가 내용과 고정 상태를 보존하는지 검사합니다. */
    @Test
    fun versionThreeCacheMigratesWithoutLosingSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, JulesDatabase::class.java).build()
        val schemas = mutableListOf<String>()
        source.openHelper.writableDatabase.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT IN ('cached_session_keys', 'room_master_table', 'android_metadata', 'sqlite_sequence')").use { cursor ->
            while (cursor.moveToNext()) schemas.add(cursor.getString(0))
        }
        source.close()
        val fixture = java.io.File(context.cacheDir, "migration-${java.util.UUID.randomUUID()}.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(fixture, null).use { old ->
            schemas.forEach { old.execSQL(it) }
            old.execSQL("INSERT INTO cached_sessions VALUES ('legacy', 'sessions/legacy', '보존 작업', '', 'COMPLETED', '', '', '', '', '', '', 1, 7, '', '', 0, '', 0, 0)")
            old.version = 3
        }
        val upgraded = Room.databaseBuilder(context, JulesDatabase::class.java, fixture.absolutePath)
            .addMigrations(JulesDatabase.MIGRATION_3_4).build()
        try {
            val row = upgraded.dao().observeSessions().first().single()
            assertEquals(7L, row.apiKeyId)
            assertEquals("보존 작업", row.title)
            assertEquals(true, row.isPinned)
        } finally {
            upgraded.close()
        }
    }

    /** 선택적으로 전달받은 실제 키로 API 파싱부터 키별 통계까지 검증하며 키는 메모리에만 둡니다. */
    @Test
    fun liveApiSessionsReachKeyUsage() = runBlocking {
        val key = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveApiKey")
        org.junit.Assume.assumeTrue("실제 API 검증 인자가 있는 경우에만 실행", !key.isNullOrBlank())
        val api = com.jules.code.data.remote.JulesApiClient()
        val expected = api.listAllSessions(key!!).getOrThrow()
        org.junit.Assert.assertTrue("테스트 계정의 세션이 조회되어야 합니다.", expected.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JulesDatabase::class.java).build()
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val id = db.dao().saveAndActivateApiKey("실제 API 검증", key)
            val vm = com.jules.code.ui.JulesViewModel(db.dao(), api)
            store.put("live", vm)
            val usage = kotlinx.coroutines.withTimeout(45000) {
                vm.apiKeyUsages.first { it[id]?.totalSessions == expected.size }.getValue(id)
            }
            assertEquals(expected.count { it.state == "COMPLETED" }, usage.completedCount)
            kotlinx.coroutines.withTimeout(45000) {
                vm.apiKeySyncStatuses.first { it[id]?.updatedAt != null && it[id]?.loading == false }
            }
            assertEquals(null, vm.apiKeySyncStatuses.value[id]?.error)
            // 완료된 과거 세션은 통계 조회와 별도로 실제 상세·활동 경로까지 검증합니다.
            val historical = expected.first()
            val expectedActivities = api.listAllActivities(key, historical.sessionId).getOrThrow()
            org.junit.Assert.assertTrue("과거 활동이 조회되어야 합니다.", expectedActivities.isNotEmpty())
            vm.openSession(historical.sessionId)
            val timeline = kotlinx.coroutines.withTimeout(45000) {
                vm.activities.first { it.size == expectedActivities.size }
            }
            assertEquals(expectedActivities.map { it.id }.toSet(), timeline.map { it.id }.toSet())
            composeRule.runOnUiThread {
                composeRule.activity.setContent {
                    com.jules.code.ui.theme.JulesTheme {
                        com.jules.code.ui.screens.SessionDetailScreen(vm, onBack = {})
                    }
                }
            }
            val targetIndex = vm.activities.value.indexOfLast { activity ->
                activity.artifacts.any { it.changeSet != null }
            }.takeIf { it >= 0 } ?: vm.activities.value.indexOfFirst { it.artifacts.isNotEmpty() }.coerceAtLeast(0)
            val targetId = vm.activities.value[targetIndex].id
            assertEquals(0, vm.activities.value.count { it.detailsLoaded })
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(targetIndex)
            composeRule.onNodeWithTag("activity-summary-$targetId").performClick()
            kotlinx.coroutines.withTimeout(120000) {
                vm.activities.first { list -> list.any { it.id == targetId && it.detailsLoaded } }
            }
            assertEquals(1, vm.activities.value.count { it.detailsLoaded })
            val detail = vm.activities.value.first { it.id == targetId }
            println("ACTIVITY_CONTENT_BYTES=" + detail.contentFiles.values.sumOf { java.io.File(it).length() })
            kotlinx.coroutines.delay(15000)
            composeRule.onRoot().assertExists()
            assertEquals(expectedActivities.size, vm.activities.value.size)
            vm.closeSession()
        } finally {
            store.clear()
            db.close()
        }
    }

    /** 같은 세션을 두 키로 저장해도 양쪽 소속이 유지되고 키 삭제가 다른 소속을 지우지 않습니다. */
    @Test
    fun sharedSessionKeepsBothKeyMemberships() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JulesDatabase::class.java).build()
        try {
            val dao = db.dao()
            val session = com.jules.code.data.local.CachedSessionEntity(
                "shared", "sessions/shared", "공유 작업", "테스트", "COMPLETED", "o/r", "main", "", "", "", "", apiKeyId = 1
            )
            dao.upsertSessions(listOf(session.copy(apiKeyId = 0)))
            dao.upsertSessions(listOf(session))
            dao.upsertSessions(listOf(session.copy(apiKeyId = 2)))
            assertEquals(setOf(1L, 2L), dao.observeSessions().first().map { it.apiKeyId }.toSet())
            dao.replaceSessionsForApiKey(1, emptyList())
            assertEquals(listOf(2L), dao.observeSessions().first().map { it.apiKeyId })
            dao.upsertSessions(listOf(session))
            dao.deleteSessionsByApiKey(1)
            assertEquals(listOf(2L), dao.observeSessions().first().map { it.apiKeyId })
        } finally {
            db.close()
        }
    }

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

