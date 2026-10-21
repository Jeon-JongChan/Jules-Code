package com.jules.code

import com.jules.code.data.local.CachedSessionEntity
import com.jules.code.data.model.JulesSession
import com.jules.code.data.remote.JulesApiClient
import com.jules.code.ui.JulesLocaleManager
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.screens.parseDiffFileSummaries
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}

