package com.jules.code.data.model

import kotlinx.serialization.Serializable

/**
 * Jules API 소스(연결된 GitHub 저장소) 목록 응답 모델입니다.
 */
@Serializable
data class SourcesResponse(
    val sources: List<JulesSource> = emptyList(),
    val nextPageToken: String? = null
)

/**
 * 개별 소스(저장소) 정보를 나타내는 데이터 클래스입니다.
 */
@Serializable
data class JulesSource(
    val name: String = "",
    val id: String = "",
    val githubRepo: GithubRepo? = null
) {
    /**
     * 화면 표시용 저장소 전체 이름(owner/repo)을 반환합니다.
     */
    val displayName: String
        get() = githubRepo?.let { "${it.owner}/${it.repo}" }
            ?: id.removePrefix("github/").ifBlank { name.removePrefix("sources/github/") }

    /**
     * 기본 브랜치 이름을 반환합니다.
     */
    val defaultBranchName: String
        get() = githubRepo?.defaultBranch?.displayName ?: "main"

    /**
     * 선택 가능한 브랜치 목록을 반환합니다.
     */
    val availableBranches: List<String>
        get() {
            val list = githubRepo?.branches?.mapNotNull { it.displayName.takeIf { b -> b.isNotBlank() } } ?: emptyList()
            val def = defaultBranchName
            return if (def in list) list else listOf(def) + list
        }
}

/**
 * GitHub 저장소 메타데이터 모델입니다.
 */
@Serializable
data class GithubRepo(
    val owner: String = "",
    val repo: String = "",
    val isPrivate: Boolean = false,
    val defaultBranch: GitBranch? = null,
    val branches: List<GitBranch> = emptyList()
)

/**
 * Git 브랜치 정보 모델입니다.
 */
@Serializable
data class GitBranch(
    val displayName: String = "main"
)

/**
 * Jules 세션 목록 응답 모델입니다.
 */
@Serializable
data class SessionsResponse(
    val sessions: List<JulesSession> = emptyList(),
    val nextPageToken: String? = null
)

/**
 * Jules 코딩 세션 리소스 모델입니다.
 */
@Serializable
data class JulesSession(
    val name: String = "",
    val id: String = "",
    val title: String = "",
    val prompt: String = "",
    val state: String = "STATE_UNSPECIFIED",
    val url: String = "",
    val sourceContext: SourceContext? = null,
    val requirePlanApproval: Boolean? = null,
    val automationMode: String? = null,
    val outputs: List<SessionOutput> = emptyList(),
    val createTime: String = "",
    val updateTime: String = ""
) {
    /**
     * 리소스 경로 또는 ID 필드에서 순수 세션 ID를 추출합니다.
     */
    val sessionId: String
        get() = id.ifBlank { name.substringAfterLast("/") }

    /**
     * 화면에 표시할 세션 제목(없으면 프롬프트 첫 줄)을 반환합니다.
     */
    val displayTitle: String
        get() = title.ifBlank { prompt.lineSequence().firstOrNull()?.take(60) ?: sessionId }

    /**
     * 연결된 저장소 표시 이름을 반환합니다.
     */
    val repoDisplay: String
        get() = sourceContext?.source
            ?.removePrefix("sources/github/")
            ?.removePrefix("sources/")
            ?.ifBlank { "Repoless (Cloud VM)" }
            ?: "Repoless (Cloud VM)"

    /**
     * 시작 브랜치 이름을 반환합니다.
     */
    val branchDisplay: String
        get() = sourceContext?.githubRepoContext?.startingBranch
            ?: sourceContext?.baseBranch
            ?: "main"
}

/**
 * 세션 생성 시 지정하는 소스(저장소 및 브랜치) 컨텍스트 모델입니다.
 */
@Serializable
data class SourceContext(
    val source: String = "",
    val githubRepoContext: GithubRepoContext? = null,
    val baseBranch: String? = null
)

/**
 * GitHub 저장소 시작 브랜치 컨텍스트입니다.
 */
@Serializable
data class GithubRepoContext(
    val startingBranch: String = "main"
)

/**
 * 세션 완료 산출물(Pull Request 등) 모델입니다.
 */
@Serializable
data class SessionOutput(
    val pullRequest: PullRequestOutput? = null
)

/**
 * 생성된 GitHub Pull Request 정보 모델입니다.
 */
@Serializable
data class PullRequestOutput(
    val url: String = "",
    val title: String = "",
    val description: String = ""
)

/**
 * 새 세션 생성 요청 바디 모델입니다.
 */
@Serializable
data class CreateSessionRequest(
    val prompt: String,
    val title: String? = null,
    val sourceContext: SourceContext? = null,
    val requirePlanApproval: Boolean? = null,
    val automationMode: String? = null
)

/**
 * 진행 중인 세션에 추가 메시지 전송 요청 바디 모델입니다.
 */
@Serializable
data class SendMessageRequest(
    val prompt: String
)

/**
 * 세션 내 액티비티(이벤트/로그/계획/코드변경) 목록 응답 모델입니다.
 */
@Serializable
data class ActivitiesResponse(
    val activities: List<JulesActivity> = emptyList(),
    val nextPageToken: String? = null
)

/**
 * 세션 타임라인의 개별 액티비티 리소스 모델입니다.
 */
@Serializable
data class JulesActivity(
    val name: String = "",
    val id: String = "",
    val originator: String = "agent",
    val description: String = "",
    val createTime: String = "",
    val planGenerated: PlanGeneratedEvent? = null,
    val planApproved: PlanApprovedEvent? = null,
    val userMessaged: MessageEvent? = null,
    val agentMessaged: MessageEvent? = null,
    val userMessage: MessageEvent? = null,
    val agentMessage: MessageEvent? = null,
    val progressUpdated: ProgressUpdatedEvent? = null,
    val sessionCompleted: SessionCompletedEvent? = null,
    val sessionFailed: SessionFailedEvent? = null,
    val artifacts: List<ActivityArtifact> = emptyList()
) {
    /**
     * 사용자 메시지 내용을 추출합니다 (스키마 변형 모두 호환).
     */
    val resolvedUserMessage: String?
        get() = (userMessaged ?: userMessage)?.resolvedText

    /**
     * 에이전트 응답 메시지 내용을 추출합니다 (스키마 변형 모두 호환).
     */
    val resolvedAgentMessage: String?
        get() = (agentMessaged ?: agentMessage)?.resolvedText
}

/**
 * 계획 생성 이벤트 페이로드입니다.
 */
@Serializable
data class PlanGeneratedEvent(
    val plan: ExecutionPlan? = null
)

/**
 * 에이전트가 수립한 단계별 실행 계획 모델입니다.
 */
@Serializable
data class ExecutionPlan(
    val id: String = "",
    val steps: List<PlanStep> = emptyList(),
    val createTime: String = ""
)

/**
 * 실행 계획의 개별 단계 모델입니다.
 */
@Serializable
data class PlanStep(
    val id: String = "",
    val index: Int = 0,
    val title: String = "",
    val description: String = "",
    val state: String = "PENDING"
) {
    /**
     * 단계 제목 또는 설명을 조합해 반환합니다.
     */
    val displayTitle: String
        get() = title.ifBlank { description.ifBlank { "Step ${index + 1}" } }
}

/**
 * 계획 승인 이벤트 페이로드입니다.
 */
@Serializable
data class PlanApprovedEvent(
    val planId: String = ""
)

/**
 * 사용자 또는 에이전트 대화 메시지 이벤트 페이로드입니다.
 */
@Serializable
data class MessageEvent(
    val message: String = "",
    val userMessage: String = "",
    val agentMessage: String = "",
    val text: String = "",
    val prompt: String = ""
) {
    /**
     * 다양한 필드명 중 실제 채워진 텍스트를 반환합니다.
     */
    val resolvedText: String
        get() = listOf(message, userMessage, agentMessage, text, prompt).firstOrNull { it.isNotBlank() } ?: ""
}

/**
 * 작업 진행 상황 업데이트 이벤트 페이로드입니다.
 */
@Serializable
data class ProgressUpdatedEvent(
    val title: String = "",
    val description: String = ""
)

/**
 * 세션 완료 이벤트 페이로드입니다.
 */
@Serializable
class SessionCompletedEvent

/**
 * 세션 실패 이벤트 페이로드입니다.
 */
@Serializable
data class SessionFailedEvent(
    val reason: String = ""
)

/**
 * 액티비티에서 생성된 산출물(코드 패치, 터미널 출력, 미디어) 모델입니다.
 */
@Serializable
data class ActivityArtifact(
    val changeSet: ChangeSetArtifact? = null,
    val bashOutput: BashOutputArtifact? = null,
    val media: MediaArtifact? = null
)

/**
 * Git 코드 변경셋(ChangeSet) 산출물 모델입니다.
 */
@Serializable
data class ChangeSetArtifact(
    val source: String = "",
    val gitPatch: GitPatchData? = null,
    val baseCommit: String = "",
    val commitMessage: String = ""
)

/**
 * Unified Diff 패치 및 커밋 메타데이터 모델입니다.
 */
@Serializable
data class GitPatchData(
    val baseCommitId: String = "",
    val unidiffPatch: String = "",
    val suggestedCommitMessage: String = ""
)

/**
 * 클라우드 VM 내 Bash 명령 실행 결과 산출물 모델입니다.
 */
@Serializable
data class BashOutputArtifact(
    val command: String = "",
    val output: String = "",
    val exitCode: Int = 0
)

/**
 * 에이전트가 생성한 이미지/미디어 산출물 모델입니다.
 */
@Serializable
data class MediaArtifact(
    val mimeType: String = "",
    val data: String = ""
)

