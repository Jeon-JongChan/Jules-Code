package com.jules.code

import com.jules.code.data.local.ApiKeyEntity
import com.jules.code.data.local.CachedSessionEntity
import com.jules.code.data.model.ExecutionPlan
import com.jules.code.data.model.JulesSession
import com.jules.code.data.model.MessageEvent
import com.jules.code.data.model.PlanApprovedEvent
import com.jules.code.data.model.PlanGeneratedEvent
import com.jules.code.data.model.PlanStep
import com.jules.code.data.remote.JulesApiClient
import com.jules.code.ui.JulesLocaleManager
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.screens.parseDiffFileSummaries
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.jules.code.data.model.JulesActivity
import com.jules.code.data.model.sortActivitiesChronologically
import com.jules.code.data.model.parseActivityTimestamp
import org.junit.Test

/**
 * Jules API 응답 파서(문자열/객체 양면 gitPatch 호환 및 메시지 필드 통합)를 검증하는 단위 테스트입니다.
 */
class JulesApiParserTest {

    @Test
    fun parseFlexibleActivities_handlesBothObjectAndPrimitiveGitPatch() {
        val client = JulesApiClient()
        val sampleJson = """
            {
              "activities": [
                {
                  "name": "sessions/123/activities/a1",
                  "id": "a1",
                  "originator": "user",
                  "userMessaged": { "message": "Fix dark theme contrast" }
                },
                {
                  "name": "sessions/123/activities/a2",
                  "id": "a2",
                  "originator": "agent",
                  "planGenerated": {
                    "plan": {
                      "id": "p1",
                      "steps": [
                        { "id": "s1", "index": 0, "title": "Update theme colors" }
                      ]
                    }
                  },
                  "artifacts": [
                    {
                      "bashOutput": {
                        "command": "./gradlew test",
                        "output": "BUILD SUCCESSFUL",
                        "exitCode": 0
                      }
                    },
                    {
                      "changeSet": {
                        "source": "sources/github/owner/repo",
                        "gitPatch": {
                          "baseCommitId": "abc1234",
                          "unidiffPatch": "diff --git a/Theme.kt b/Theme.kt\n+val Dark = true",
                          "suggestedCommitMessage": "feat: dark theme"
                        }
                      }
                    }
                  ]
                },
                {
                  "name": "sessions/123/activities/a3",
                  "id": "a3",
                  "originator": "agent",
                  "artifacts": [
                    {
                      "changeSet": {
                        "gitPatch": "diff --git a/App.kt b/App.kt\n-old\n+new",
                        "baseCommit": "def5678",
                        "commitMessage": "refactor: app"
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val resp = client.parseActivitiesFlexible(sampleJson)
        assertEquals(3, resp.activities.size)
        assertEquals("Fix dark theme contrast", resp.activities[0].resolvedUserMessage)
        assertEquals(1, resp.activities[1].planGenerated?.plan?.steps?.size)
        assertEquals(0, resp.activities[1].artifacts[0].bashOutput?.exitCode)

        val patch1 = resp.activities[1].artifacts[1].changeSet?.gitPatch
        assertEquals("abc1234", patch1?.baseCommitId)
        assertTrue(patch1?.unidiffPatch?.contains("+val Dark = true") == true)

        val patch2 = resp.activities[2].artifacts[0].changeSet?.gitPatch
        assertEquals("def5678", patch2?.baseCommitId)
        assertEquals("refactor: app", patch2?.suggestedCommitMessage)
    }

    @Test
    fun julesSessionModel_extractsSessionIdAndRepoDisplay() {
        val session = JulesSession(
            name = "sessions/987654321",
            prompt = "Add unit tests\nMore details"
        )
        assertEquals("987654321", session.sessionId)
        assertEquals("Add unit tests", session.displayTitle)
        assertEquals("Repoless (Cloud VM)", session.repoDisplay)
    }

    @Test
    fun quotaCalculator_computesRolling24hAndConcurrentSlotsAccurately() {
        val now = 1_800_000_000_000L
        val recentIso = java.time.Instant.ofEpochMilli(now - 3600_000L).toString()
        val oldIso = java.time.Instant.ofEpochMilli(now - 48 * 3600_000L).toString()

        val sessions = listOf(
            CachedSessionEntity("s1", "sessions/s1", "T1", "P1", "IN_PROGRESS", "r", "main", "", "", recentIso, recentIso),
            CachedSessionEntity("s2", "sessions/s2", "T2", "P2", "COMPLETED", "r", "main", "", "", recentIso, recentIso),
            CachedSessionEntity("s3", "sessions/s3", "T3", "P3", "COMPLETED", "r", "main", "", "", oldIso, oldIso)
        )

        assertEquals(2, JulesViewModel.countRolling24hSessions(sessions, now))
        assertEquals(1, JulesViewModel.countConcurrentActiveSessions(sessions))
        assertEquals(100, JulesViewModel.getTierLimits("PRO").dailyLimit)
    }

    @Test
    fun modelOptionsAndEnrichedPrompt_appendsModelAndAgentDirectives() {
        assertEquals(2, JulesViewModel.AVAILABLE_MODELS.size)
        assertEquals("Gemini 3.1 Pro", JulesViewModel.getModelDisplayName("GEMINI_3_1_PRO"))
        assertEquals("Gemini 3.6 Flash", JulesViewModel.getModelDisplayName("GEMINI_3_6_FLASH"))

        val enriched = JulesViewModel.buildEnrichedPrompt(
            basePrompt = "Fix memory leak in parser",
            modelId = "GEMINI_3_1_PRO",
            enableCritic = true,
            enableCiFixer = true,
            setupScript = "./gradlew test",
            envVars = "CI=true",
            repoMemory = "Always write Kotlin docstrings"
        )
        assertTrue(enriched.contains("Fix memory leak in parser"))
        assertTrue(enriched.contains("Response Language: 한국어 (Korean)"))
        assertTrue(enriched.contains("Model Profile: Gemini 3.1 Pro"))
        assertTrue(enriched.contains("Critic Agent: ENABLED"))
        assertTrue(enriched.contains("CI Auto-Fixer: ENABLED"))
        assertTrue(enriched.contains("VM Setup Script:\n./gradlew test"))
        assertTrue(enriched.contains("Always write Kotlin docstrings"))

        val followUpKo = JulesViewModel.buildLocalizedFollowUpMessage("테스트도 추가해줘", "ko")
        assertTrue(followUpKo.contains("한국어(Korean)로 답변"))
        val followUpEn = JulesViewModel.buildLocalizedFollowUpMessage("Add tests too", "en")
        assertTrue(followUpEn.contains("in English"))
    }

    @Test
    fun localeManager_resolvesSystemDeviceLocaleAndExplicitOverrides() {
        // SYSTEM 설정 시 스마트폰의 국가/언어를 따라감
        assertEquals("ko", JulesLocaleManager.resolveLocale("SYSTEM", Locale.KOREA).language)
        assertEquals("en", JulesLocaleManager.resolveLocale("SYSTEM", Locale.US).language)
        assertEquals("en", JulesLocaleManager.resolveLocale(null, Locale.JAPAN).language)

        // 수동 언어 선택 시 기기 설정과 무관하게 지정된 언어로 고정됨
        assertEquals("en", JulesLocaleManager.resolveLocale("en", Locale.KOREA).language)
        assertEquals("ko", JulesLocaleManager.resolveLocale("ko", Locale.US).language)
    }

    @Test
    fun parseDiffFileSummaries_extractsFileNamesAndAddedRemovedCounts() {
        val sampleDiff = """
            diff --git a/app/src/main/java/com/jules/code/MainActivity.kt b/app/src/main/java/com/jules/code/MainActivity.kt
            index 1234567..89abcdef 100644
            --- a/app/src/main/java/com/jules/code/MainActivity.kt
            +++ b/app/src/main/java/com/jules/code/MainActivity.kt
            @@ -12,4 +12,5 @@ class MainActivity {
            -    val isDarkTheme = false
            +    val isDarkTheme = true
            +    val surfaceColor = Color(0xFF0A0E17)
            diff --git a/app/src/main/java/com/jules/code/Theme.kt b/app/src/main/java/com/jules/code/Theme.kt
            --- a/app/src/main/java/com/jules/code/Theme.kt
            +++ b/app/src/main/java/com/jules/code/Theme.kt
            @@ -1,3 +1,4 @@
            +val PrimaryIndigo = Color(0xFF6366F1)
        """.trimIndent()

        val summaries = parseDiffFileSummaries(sampleDiff)
        assertEquals(2, summaries.size)
        assertEquals("MainActivity.kt", summaries[0].shortName)
        assertEquals(2, summaries[0].added)
        assertEquals(1, summaries[0].removed)
        assertEquals("Theme.kt", summaries[1].shortName)
        assertEquals(1, summaries[1].added)
        assertEquals(0, summaries[1].removed)
    }

    @Test
    fun stripInternalDirectives_cleansLanguageAndExecutionBlocksFromTimeline() {
        val rawKo = "다크모드 버튼 수정해줘\n\n[언어 설정: 모든 답변, 진행 상황 요약, 에이전트 메시지 및 PR 설명은 반드시 한국어(Korean)로 작성해 주세요.]"
        assertEquals("다크모드 버튼 수정해줘", JulesViewModel.stripInternalDirectives(rawKo))

        val rawEnriched = JulesViewModel.buildEnrichedPrompt(
            basePrompt = "Refactor auth service",
            modelId = "GEMINI_3_6_FLASH",
            enableCritic = false,
            enableCiFixer = false,
            setupScript = "",
            envVars = "",
            repoMemory = "",
            responseLanguage = "en"
        )
        assertEquals("Refactor auth service", JulesViewModel.stripInternalDirectives(rawEnriched))
    }

    @Test
    fun sortActivitiesChronologically_ordersActivitiesByAscendingTime() {
        val actOld = JulesActivity(id = "1", createTime = "2026-10-21T01:00:00Z")
        val actMid = JulesActivity(id = "2", createTime = "2026-10-21T02:30:15.500Z")
        val actNew = JulesActivity(id = "3", createTime = "2026-10-21T03:15:00Z")
        val actRecent = JulesActivity(id = "4", createTime = "방금 전")

        // 역순 및 뒤섞인 순서 목록
        val scrambled = listOf(actNew, actOld, actRecent, actMid)
        val sorted = sortActivitiesChronologically(scrambled)

        assertEquals("1", sorted[0].id)
        assertEquals("2", sorted[1].id)
        assertEquals("3", sorted[2].id)
        assertEquals("4", sorted[3].id)

        // 동일 초(동일 타임스탬프)에 생성된 경우 사용자("user") 메시지가 에이전트("agent") 응답보다 먼저 배치되는지 검증
        val sameTimeAgent = JulesActivity(id = "aaa-agent-uuid", originator = "agent", createTime = "2026-10-21T05:00:00Z")
        val sameTimeUser = JulesActivity(id = "zzz-user-uuid", originator = "user", createTime = "2026-10-21T05:00:00Z")
        val tieSorted = sortActivitiesChronologically(listOf(sameTimeAgent, sameTimeUser))
        assertEquals("zzz-user-uuid", tieSorted[0].id)
        assertEquals("aaa-agent-uuid", tieSorted[1].id)
    }

    @Test
    fun julesSession_isAlive_reportsCorrectLiveness() {
        assertTrue(JulesSession(state = "IN_PROGRESS").isAlive)
        assertTrue(JulesSession(state = "PLANNING").isAlive)
        assertTrue(JulesSession(state = "QUEUED").isAlive)
        assertTrue(JulesSession(state = "AWAITING_PLAN_APPROVAL").isAlive)
        assertTrue(JulesSession(state = "AWAITING_USER_FEEDBACK").isAlive)

        assertFalse(JulesSession(state = "COMPLETED").isAlive)
        assertFalse(JulesSession(state = "FAILED").isAlive)
        assertFalse(JulesSession(state = "PAUSED").isAlive)
        assertFalse(JulesSession(state = "STATE_UNSPECIFIED").isAlive)
    }

    @Test
    fun parseActivityTimestamp_handlesVariousIsoFormatsAndFallback() {
        val t1 = parseActivityTimestamp("2026-10-21T01:00:00Z")
        val t2 = parseActivityTimestamp("2026-10-21T01:00:00.000Z")
        assertEquals(t1, t2)
        assertTrue(t1 > 0L)

        val tRelative = parseActivityTimestamp("방금 전")
        assertTrue(tRelative > t1)

        val tBlank = parseActivityTimestamp("")
        assertEquals(0L, tBlank)
    }

    @Test
    fun computeApiKeyUsage_separatesQuotaAndCountsPerApiKey() {
        val now = parseActivityTimestamp("2026-10-21T15:30:00Z")
        val key1 = ApiKeyEntity(id = 1L, label = "Key A", apiKey = "AQ.Key1", isActive = true, planTier = "FREE")
        val key2 = ApiKeyEntity(id = 2L, label = "Key B", apiKey = "AQ.Key2", isActive = false, planTier = "PRO")

        val key1Sessions = listOf(
            CachedSessionEntity(
                sessionId = "16342394822067107898",
                name = "sessions/16342394822067107898",
                title = "EquipNav",
                prompt = "Build EquipNav",
                state = "IN_PROGRESS",
                repoDisplay = "sys03-met/GIS-Device-Navigator",
                branchDisplay = "main",
                prUrl = "",
                prTitle = "",
                createTime = "2026-10-21T11:17:18Z",
                updateTime = "2026-10-21T15:28:00Z",
                apiKeyId = 1L,
                lastActivityTime = "2026-10-21T13:20:05Z",
                lastOriginator = "user",
                unansweredUserCount = 2,
                currentStepTitle = "Monorepo 워크스페이스 및 프로젝트 기본 구조 생성",
                completedStepsCount = 0,
                totalStepsCount = 10
            )
        )
        val key2Sessions = listOf(
            CachedSessionEntity(
                sessionId = "s-201",
                name = "sessions/s-201",
                title = "Done task",
                prompt = "P",
                state = "COMPLETED",
                repoDisplay = "owner/repo",
                branchDisplay = "main",
                prUrl = "https://github.com/owner/repo/pull/1",
                prTitle = "PR 1",
                createTime = "2026-10-21T14:00:00Z",
                updateTime = "2026-10-21T14:20:00Z",
                apiKeyId = 2L,
                lastActivityTime = "2026-10-21T14:20:00Z",
                lastOriginator = "agent"
            )
        )

        val usage1 = JulesViewModel.computeApiKeyUsage(key1, key1Sessions, nowMillis = now)
        assertEquals(1, usage1.used24h)
        assertEquals(14, usage1.remainingDaily)
        assertEquals(1, usage1.concurrentUsed)
        assertEquals(2, usage1.remainingConcurrent)
        assertEquals(1, usage1.stalledCount)

        val usage2 = JulesViewModel.computeApiKeyUsage(key2, key2Sessions, nowMillis = now)
        assertEquals(1, usage2.used24h)
        assertEquals(99, usage2.remainingDaily)
        assertEquals(0, usage2.concurrentUsed)
        assertEquals(1, usage2.completedCount)
        assertEquals(0, usage2.stalledCount)
    }

    @Test
    fun diagnoseSessionHealth_detectsStalledAfterPlanApprovalWithUnansweredUserMessages() {
        val now = parseActivityTimestamp("2026-10-21T15:30:00Z")
        val session = JulesSession(
            name = "sessions/16342394822067107898",
            id = "16342394822067107898",
            title = "EquipNav",
            state = "IN_PROGRESS",
            createTime = "2026-10-21T11:17:18.687508523Z",
            updateTime = "2026-10-21T15:29:59.000000Z" // API가 GET할 때마다 updateTime을 현재 시각으로 갱신하더라도 속지 않아야 함
        )
        val activities = listOf(
            JulesActivity(
                id = "5ec71fe5a85d44d7bbbe9543c051ebac",
                originator = "agent",
                createTime = "2026-10-21T11:22:50.274050Z",
                planGenerated = PlanGeneratedEvent(
                    plan = ExecutionPlan(
                        id = "ec2085b669ca417cb8dbecc06af243e6",
                        steps = listOf(
                            PlanStep(id = "s1", index = 0, title = "Monorepo 워크스페이스 및 프로젝트 기본 구조 생성", state = "PENDING"),
                            PlanStep(id = "s2", index = 1, title = "공통 API 타입 계약 정의", state = "PENDING")
                        )
                    )
                )
            ),
            JulesActivity(
                id = "b4f3d8582add45f696496252bbb7f044",
                originator = "user",
                createTime = "2026-10-21T11:22:51.727692Z",
                planApproved = PlanApprovedEvent(planId = "ec2085b669ca417cb8dbecc06af243e6")
            ),
            JulesActivity(
                id = "u1",
                originator = "user",
                createTime = "2026-10-21T11:39:15.704948066Z",
                userMessaged = MessageEvent(userMessage = "진행해")
            ),
            JulesActivity(
                id = "u2",
                originator = "user",
                createTime = "2026-10-21T13:20:05.767061435Z",
                userMessaged = MessageEvent(userMessage = "실행계획 실행하라고")
            )
        )

        val diag = JulesViewModel.diagnoseSessionHealth(session, activities, nowMillis = now)
        assertTrue(diag.isStalled)
        assertEquals(2, diag.unansweredUserCount)
        assertEquals("Monorepo 워크스페이스 및 프로젝트 기본 구조 생성", diag.currentStepTitle)
        assertEquals(0, diag.completedStepsCount)
        assertEquals(2, diag.totalStepsCount)
        assertTrue(diag.silentDurationMinutes >= 240L)
        assertTrue(diag.reasonTitleKo.contains("실행 계획 승인 후 클라우드 VM 응답 멈춤"))
        assertTrue(diag.reasonDetailKo.contains("보낸 메시지 2건"))
    }
}

