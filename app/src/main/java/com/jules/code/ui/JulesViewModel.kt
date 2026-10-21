package com.jules.code.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jules.code.data.local.*
import com.jules.code.data.model.*
import com.jules.code.data.remote.JulesApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Jules 앱의 전체 상태와 비즈니스 로직을 관리하는 메인 ViewModel입니다.
 * API 키 관리, 세션/소스 동기화, 계획 승인, 메시지 전송, 데모 모드, 스케줄 템플릿을 통합 처리합니다.
 */
class JulesViewModel(
    private val dao: JulesDao,
    private val api: JulesApiClient = JulesApiClient()
) : ViewModel() {

    /** 저장된 모든 API 키 프로필 목록 */
    val apiKeys: StateFlow<List<ApiKeyEntity>> = dao.observeApiKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 현재 활성화된 API 키 프로필 */
    val activeApiKey: StateFlow<ApiKeyEntity?> = dao.observeActiveApiKey()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 캐시된 코딩 세션 목록 */
    val sessions: StateFlow<List<CachedSessionEntity>> = dao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 캐시된 GitHub 저장소(Source) 목록 */
    val sources: StateFlow<List<CachedSourceEntity>> = dao.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 예약 작업(Scheduled) 및 프롬프트 템플릿 목록 */
    val templates: StateFlow<List<ScheduledTemplateEntity>> = dao.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 앱 전역 설정 맵 */
    val preferences: StateFlow<Map<String, String>> = dao.observePreferences()
        .map { list -> list.associate { it.prefKey to it.prefValue } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 현재 선택된 상세 세션 ID */
    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    /** 현재 선택된 세션의 실시간 상세 정보 (Outputs/PR 포함) */
    private val _selectedSessionDetail = MutableStateFlow<JulesSession?>(null)
    val selectedSessionDetail: StateFlow<JulesSession?> = _selectedSessionDetail.asStateFlow()

    /** 현재 선택된 세션의 액티비티(계획, 대화, 패치, Bash 출력) 목록 */
    private val _activities = MutableStateFlow<List<JulesActivity>>(emptyList())
    val activities: StateFlow<List<JulesActivity>> = _activities.asStateFlow()

    /** 로딩 상태 표시 플래그 */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 사용자 알림/오류 스낵바 메시지 */
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /** 데모 모드 활성화 여부 (API 키가 `"DEMO"`이거나 사용자가 데모 체험 선택 시) */
    val isDemoMode: StateFlow<Boolean> = activeApiKey
        .map { it?.apiKey == DEMO_API_KEY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var pollingJob: Job? = null

    init {
        // 기본 프롬프트 템플릿이 없으면 초기 템플릿 시드 생성
        viewModelScope.launch {
            if (dao.observeTemplates().first().isEmpty()) {
                seedDefaultTemplates()
            }
        }
        // 활성 API 키 변경 시 자동으로 세션 및 소스 목록 동기화
        viewModelScope.launch {
            activeApiKey.collectLatest { keyEntity ->
                if (keyEntity != null) {
                    refreshAll()
                }
            }
        }
    }

    /**
     * 스낵바 메시지를 소비(초기화)합니다.
     */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    /**
     * 스낵바에 안내 메시지를 표시합니다.
     */
    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    /**
     * 새로운 API 키를 Room에 저장하고 즉시 활성화합니다.
     */
    fun saveApiKey(label: String, rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            showMessage("API 키를 입력해주세요.")
            return
        }
        viewModelScope.launch {
            dao.saveAndActivateApiKey(label.ifBlank { "Jules API Key" }, trimmed)
            showMessage("API 키가 저장 및 활성화되었습니다.")
        }
    }

    /**
     * API 키 없이도 앱의 모든 UI/기능을 즉시 테스트할 수 있도록 내장 데모 모드를 활성화합니다.
     */
    fun activateDemoMode() {
        viewModelScope.launch {
            dao.saveAndActivateApiKey("데모 모드 (오프라인 시뮬레이션)", DEMO_API_KEY)
            seedDemoData()
            showMessage("데모 모드가 활성화되었습니다. 실제 API 키는 설정에서 언제든 등록 가능합니다.")
        }
    }

    /**
     * 등록된 다른 API 키 프로필로 전환합니다.
     */
    fun switchApiKey(id: Long) {
        viewModelScope.launch {
            dao.switchActiveApiKey(id)
            showMessage("API 키 프로필을 전환했습니다.")
        }
    }

    /**
     * 저장된 API 키 프로필을 삭제합니다.
     */
    fun deleteApiKey(id: Long) {
        viewModelScope.launch {
            dao.deleteApiKey(id)
            showMessage("API 키 프로필이 삭제되었습니다.")
        }
    }

    /**
     * 앱 환경설정 값을 업데이트합니다 (예: 커밋 작성자 모드, 자동 새로고침 여부).
     */
    fun updatePreference(key: String, value: String) {
        viewModelScope.launch {
            dao.setPreference(AppPreferenceEntity(key, value))
        }
    }

    /**
     * 서버(또는 데모 스토어)로부터 소스(GitHub 저장소)와 세션 목록을 모두 새로고침합니다.
     */
    fun refreshAll() {
        val key = activeApiKey.value?.apiKey ?: return
        if (key == DEMO_API_KEY) {
            viewModelScope.launch { seedDemoData() }
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val sourcesRes = api.listSources(key)
            sourcesRes.onSuccess { resp ->
                val entities = resp.sources.map { s ->
                    CachedSourceEntity(
                        name = s.name.ifBlank { "sources/${s.id}" },
                        id = s.id.ifBlank { s.name.removePrefix("sources/") },
                        owner = s.githubRepo?.owner ?: s.displayName.substringBefore("/", "github"),
                        repo = s.githubRepo?.repo ?: s.displayName.substringAfter("/", s.displayName),
                        isPrivate = s.githubRepo?.isPrivate ?: false,
                        defaultBranch = s.defaultBranchName,
                        branchesCsv = s.availableBranches.joinToString(",")
                    )
                }
                dao.upsertSources(entities)
            }

            val sessionsRes = api.listSessions(key)
            sessionsRes.onSuccess { resp ->
                val existingPinned = sessions.value.associate { it.sessionId to it.isPinned }
                val entities = resp.sessions.map { it.toEntity(existingPinned[it.sessionId] ?: false) }
                dao.upsertSessions(entities)
            }.onFailure { err ->
                showMessage("동기화 실패: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    /**
     * 새로운 Jules 코딩 세션을 생성합니다.
     * 모델 선택, Critic Agent 리뷰, CI Auto-Fixer, 저장소별 Setup Script/Env Vars/Memory를 프롬프트 컨텍스트에 결합합니다.
     */
    fun createSession(
        prompt: String,
        title: String,
        sourceName: String?,
        branchName: String?,
        requirePlanApproval: Boolean,
        autoCreatePr: Boolean,
        modelId: String = "GEMINI_3_1_PRO",
        enableCritic: Boolean = true,
        enableCiFixer: Boolean = false,
        onCreated: (String) -> Unit = {}
    ) {
        val key = activeApiKey.value?.apiKey
        if (key.isNullOrBlank()) {
            showMessage("먼저 API 키를 설정해주세요.")
            return
        }
        if (prompt.isBlank()) {
            showMessage("작업 지시 프롬프트를 입력해주세요.")
            return
        }

        val currentPrefs = preferences.value
        val langPref = currentPrefs["app_language"]
        val responseLangCode = JulesLocaleManager.resolveLocale(langPref).language
        val repoKey = sourceName?.removePrefix("sources/github/")?.removePrefix("sources/") ?: "repoless"
        val setupScript = currentPrefs["env_script_$repoKey"].orEmpty().trim()
        val envVars = currentPrefs["env_vars_$repoKey"].orEmpty().trim()
        val repoMemory = currentPrefs["repo_memory_$repoKey"].orEmpty().trim()
        val enrichedPrompt = buildEnrichedPrompt(
            basePrompt = prompt.trim(),
            modelId = modelId,
            enableCritic = enableCritic,
            enableCiFixer = enableCiFixer,
            setupScript = setupScript,
            envVars = envVars,
            repoMemory = repoMemory,
            responseLanguage = responseLangCode
        )

        viewModelScope.launch {
            _isLoading.value = true
            if (key == DEMO_API_KEY) {
                val newId = "demo-${System.currentTimeMillis() % 100000}"
                val repoDisp = sourceName?.removePrefix("sources/github/")?.removePrefix("sources/")
                    ?.ifBlank { "Repoless (Cloud VM)" } ?: "Repoless (Cloud VM)"
                val state = if (requirePlanApproval) "AWAITING_PLAN_APPROVAL" else "IN_PROGRESS"
                val entity = CachedSessionEntity(
                    sessionId = newId,
                    name = "sessions/$newId",
                    title = title.ifBlank { prompt.take(45) },
                    prompt = enrichedPrompt,
                    state = state,
                    repoDisplay = repoDisp,
                    branchDisplay = branchName ?: "main",
                    prUrl = "",
                    prTitle = "",
                    createTime = "방금 전",
                    updateTime = "방금 전",
                    isPinned = false
                )
                dao.upsertSessions(listOf(entity))
                _isLoading.value = false
                showMessage("새 코딩 세션이 시작되었습니다 (${getModelDisplayName(modelId)}).")
                openSession(newId)
                onCreated(newId)
                return@launch
            }

            val sourceCtx = if (!sourceName.isNullOrBlank()) {
                SourceContext(
                    source = if (sourceName.startsWith("sources/")) sourceName else "sources/$sourceName",
                    githubRepoContext = GithubRepoContext(startingBranch = branchName?.ifBlank { "main" } ?: "main")
                )
            } else null

            val req = CreateSessionRequest(
                prompt = enrichedPrompt,
                title = title.trim().ifBlank { null },
                sourceContext = sourceCtx,
                requirePlanApproval = if (requirePlanApproval) true else null,
                automationMode = if (autoCreatePr) "AUTO_CREATE_PR" else null
            )

            api.createSession(key, req)
                .onSuccess { created ->
                    dao.upsertSessions(listOf(created.toEntity(false)))
                    _isLoading.value = false
                    showMessage("Jules 세션이 생성되었습니다 (${getModelDisplayName(modelId)}).")
                    openSession(created.sessionId)
                    onCreated(created.sessionId)
                }
                .onFailure { err ->
                    _isLoading.value = false
                    showMessage("세션 생성 실패: ${err.message}")
                }
        }
    }

    /**
     * 특정 세션을 선택하여 상세 정보 및 액티비티 스트림 폴링을 시작합니다.
     */
    fun openSession(sessionId: String) {
        _selectedSessionId.value = sessionId
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                loadSessionDetailAndActivities(sessionId)
                val currentState = _selectedSessionDetail.value?.state ?: ""
                if (currentState in listOf("COMPLETED", "FAILED", "PAUSED") || activeApiKey.value?.apiKey == DEMO_API_KEY) {
                    break
                }
                delay(5_000)
            }
        }
    }

    /**
     * 현재 선택된 세션의 상세 데이터와 액티비티를 즉시 새로고침합니다.
     */
    fun refreshSelectedSession() {
        val id = _selectedSessionId.value ?: return
        viewModelScope.launch {
            loadSessionDetailAndActivities(id)
        }
    }

    private suspend fun loadSessionDetailAndActivities(sessionId: String) {
        val key = activeApiKey.value?.apiKey ?: return
        if (key == DEMO_API_KEY) {
            val cached = dao.getCachedSession(sessionId)
            if (cached != null) {
                _selectedSessionDetail.value = cached.toModel()
                _activities.value = buildDemoActivities(cached)
            }
            return
        }

        api.getSession(key, sessionId).onSuccess { session ->
            _selectedSessionDetail.value = session
            val pinned = dao.getCachedSession(sessionId)?.isPinned ?: false
            dao.upsertSessions(listOf(session.toEntity(pinned)))
        }

        api.listActivities(key, sessionId).onSuccess { actResp ->
            _activities.value = actResp.activities
        }
    }

    /**
     * `AWAITING_PLAN_APPROVAL` 상태인 세션의 실행 계획(Plan)을 승인합니다.
     */
    fun approvePlan(sessionId: String) {
        val key = activeApiKey.value?.apiKey ?: return
        viewModelScope.launch {
            _isLoading.value = true
            if (key == DEMO_API_KEY) {
                val cached = dao.getCachedSession(sessionId)
                if (cached != null) {
                    val updated = cached.copy(
                        state = "COMPLETED",
                        prUrl = "https://github.com/${cached.repoDisplay}/pull/42",
                        prTitle = "feat: ${cached.title}",
                        updateTime = "방금 승인됨"
                    )
                    dao.upsertSessions(listOf(updated))
                    loadSessionDetailAndActivities(sessionId)
                }
                _isLoading.value = false
                showMessage("실행 계획이 승인되어 작업이 완료되었습니다.")
                return@launch
            }

            api.approvePlan(key, sessionId)
                .onSuccess {
                    showMessage("계획을 승인했습니다. Jules가 코드 수정을 시작합니다.")
                    loadSessionDetailAndActivities(sessionId)
                }
                .onFailure { err ->
                    showMessage("계획 승인 실패: ${err.message}")
                }
            _isLoading.value = false
        }
    }

    /**
     * 진행 중인 세션에 후속 프롬프트/피드백 메시지를 전송합니다.
     * 설정된 앱 표시 언어(또는 스마트폰 기본 언어)로 응답하도록 언어 지시어를 함께 전달합니다.
     */
    fun sendMessage(sessionId: String, message: String) {
        if (message.isBlank()) return
        val key = activeApiKey.value?.apiKey ?: return
        val langCode = JulesLocaleManager.resolveLocale(preferences.value["app_language"]).language
        val enrichedMessage = buildLocalizedFollowUpMessage(message.trim(), langCode)
        viewModelScope.launch {
            if (key == DEMO_API_KEY) {
                val current = _activities.value.toMutableList()
                current.add(
                    JulesActivity(
                        id = "user-${System.currentTimeMillis()}",
                        originator = "user",
                        description = "사용자 메시지",
                        createTime = "방금 전",
                        userMessaged = MessageEvent(message = message.trim())
                    )
                )
                current.add(
                    JulesActivity(
                        id = "agent-${System.currentTimeMillis() + 1}",
                        originator = "agent",
                        description = "Jules 응답",
                        createTime = "방금 전",
                        agentMessaged = MessageEvent(
                            message = if (langCode == "ko") {
                                "요청하신 추가 지시사항(\"${message.trim()}\")을 반영하여 패치와 테스트를 업데이트했습니다."
                            } else {
                                "Applied your follow-up instructions (\"${message.trim()}\") and updated the patch and tests."
                            }
                        )
                    )
                )
                _activities.value = current
                return@launch
            }

            api.sendMessage(key, sessionId, enrichedMessage)
                .onSuccess {
                    showMessage("메시지를 전송했습니다.")
                    loadSessionDetailAndActivities(sessionId)
                }
                .onFailure { err ->
                    showMessage("메시지 전송 실패: ${err.message}")
                }
        }
    }

    /**
     * 세션을 삭제합니다.
     */
    fun deleteSession(sessionId: String, onDeleted: () -> Unit = {}) {
        val key = activeApiKey.value?.apiKey ?: return
        viewModelScope.launch {
            if (key != DEMO_API_KEY) {
                api.deleteSession(key, sessionId).onFailure { err ->
                    showMessage("원격 세션 삭제 오류(로컬 캐시는 제거됨): ${err.message}")
                }
            }
            dao.deleteCachedSession(sessionId)
            if (_selectedSessionId.value == sessionId) {
                _selectedSessionId.value = null
                _selectedSessionDetail.value = null
                _activities.value = emptyList()
            }
            showMessage("세션이 삭제되었습니다.")
            onDeleted()
        }
    }

    /**
     * 세션 상단 고정(Pin) 상태를 토글합니다.
     */
    fun togglePinSession(sessionId: String, currentPinned: Boolean) {
        viewModelScope.launch {
            dao.setSessionPinned(sessionId, !currentPinned)
        }
    }

    /**
     * 예약 작업(Scheduled Task) 또는 반복 프롬프트 템플릿을 저장합니다.
     */
    fun saveTemplate(
        title: String,
        prompt: String,
        sourceName: String,
        branchName: String,
        cadence: String,
        requirePlanApproval: Boolean,
        autoCreatePr: Boolean
    ) {
        if (title.isBlank() || prompt.isBlank()) {
            showMessage("템플릿 제목과 프롬프트를 모두 입력해주세요.")
            return
        }
        viewModelScope.launch {
            dao.insertTemplate(
                ScheduledTemplateEntity(
                    title = title.trim(),
                    prompt = prompt.trim(),
                    sourceName = sourceName,
                    branchName = branchName.ifBlank { "main" },
                    cadence = cadence,
                    requirePlanApproval = requirePlanApproval,
                    autoCreatePr = autoCreatePr
                )
            )
            showMessage("작업 템플릿/스케줄이 저장되었습니다.")
        }
    }

    /**
     * 저장된 예약 작업/템플릿을 즉시 실행하여 새 세션을 생성합니다.
     */
    fun runTemplateNow(template: ScheduledTemplateEntity, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            dao.markTemplateExecuted(template.id)
            createSession(
                prompt = template.prompt,
                title = template.title,
                sourceName = template.sourceName.ifBlank { null },
                branchName = template.branchName.ifBlank { "main" },
                requirePlanApproval = template.requirePlanApproval,
                autoCreatePr = template.autoCreatePr,
                onCreated = onCreated
            )
        }
    }

    /**
     * 저장된 작업 템플릿을 삭제합니다.
     */
    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            dao.deleteTemplate(id)
            showMessage("템플릿이 삭제되었습니다.")
        }
    }

    /**
     * 현재 세션의 활동 내역과 코드 변경점을 요약한 오디오 체인지로그(TTS용 스크립트) 텍스트를 생성합니다.
     */
    fun buildAudioSummaryScript(session: JulesSession?, acts: List<JulesActivity>): String {
        if (session == null) return "선택된 세션 정보가 없습니다."
        val planSteps = acts.mapNotNull { it.planGenerated?.plan?.steps }.flatten()
        val patches = acts.flatMap { it.artifacts }.mapNotNull { it.changeSet?.gitPatch }
        val commands = acts.flatMap { it.artifacts }.mapNotNull { it.bashOutput }

        return buildString {
            append("Jules 코딩 세션 브리핑을 시작합니다. ")
            append("작업 제목은 ${session.displayTitle} 이며, 현재 상태는 ${translateState(session.state)} 입니다. ")
            append("대상 저장소는 ${session.repoDisplay}, 브랜치는 ${session.branchDisplay} 입니다. ")
            if (planSteps.isNotEmpty()) {
                append("수립된 실행 계획은 총 ${planSteps.size}단계이며, 주요 단계는 ${planSteps.first().displayTitle} 입니다. ")
            }
            if (commands.isNotEmpty()) {
                val passCount = commands.count { it.exitCode == 0 }
                append("클라우드 VM에서 총 ${commands.size}개의 터미널 명령을 실행했고 ${passCount}개가 성공했습니다. ")
            }
            if (patches.isNotEmpty()) {
                val commitMsg = patches.last().suggestedCommitMessage.ifBlank { "코드 변경 패치" }
                append("생성된 최신 커밋 요약은 다음과 같습니다: $commitMsg. ")
            }
            session.outputs.firstOrNull()?.pullRequest?.let { pr ->
                if (pr.url.isNotBlank()) {
                    append("최종 결과물로 GitHub Pull Request가 생성되었습니다: ${pr.title}.")
                }
            }
        }
    }

    private suspend fun seedDefaultTemplates() {
        val defaults = listOf(
            ScheduledTemplateEntity(
                title = "의존성 보안 취약점 점검 및 패치",
                prompt = "저장소의 패키지 매니페스트를 점검하여 보안 취약점이 있거나 오래된 의존성을 패치 버전으로 업데이트하고 테스트를 통과시켜줘.",
                sourceName = "",
                branchName = "main",
                cadence = "WEEKLY",
                requirePlanApproval = true,
                autoCreatePr = true
            ),
            ScheduledTemplateEntity(
                title = "누락된 단위 테스트 자동 보강",
                prompt = "최근 변경된 핵심 비즈니스 로직 모듈에서 엣지 케이스 단위 테스트를 추가하고 전체 테스트 스위트를 실행해 검증해줘.",
                sourceName = "",
                branchName = "main",
                cadence = "DAILY",
                requirePlanApproval = false,
                autoCreatePr = true
            ),
            ScheduledTemplateEntity(
                title = "린트 경고 및 데드 코드 정리",
                prompt = "프로젝트 내 사용하지 않는 import, 데드 코드, 컴파일러 경고를 모두 정리하고 빌드 정상 여부를 확인해줘.",
                sourceName = "",
                branchName = "main",
                cadence = "MANUAL",
                requirePlanApproval = false,
                autoCreatePr = true
            )
        )
        defaults.forEach { dao.insertTemplate(it) }
    }

    private suspend fun seedDemoData() {
        val demoSources = listOf(
            CachedSourceEntity(
                name = "sources/github/Jeon-JongChan/Jules-Code",
                id = "github/Jeon-JongChan/Jules-Code",
                owner = "Jeon-JongChan",
                repo = "Jules-Code",
                isPrivate = false,
                defaultBranch = "main",
                branchesCsv = "main,develop,feat/mobile-ui"
            ),
            CachedSourceEntity(
                name = "sources/github/google/jules-samples",
                id = "github/google/jules-samples",
                owner = "google",
                repo = "jules-samples",
                isPrivate = false,
                defaultBranch = "main",
                branchesCsv = "main,staging"
            )
        )
        dao.upsertSources(demoSources)

        if (dao.observeSessions().first().isEmpty()) {
            val demoSessions = listOf(
                CachedSessionEntity(
                    sessionId = "demo-1001",
                    name = "sessions/demo-1001",
                    title = "모바일 반응형 네비게이션 및 다크 테마 개선",
                    prompt = "웹 대시보드의 사이드바를 모바일 하단 네비게이션 바로 변환하고 다크 테마 대비율을 개선해줘.",
                    state = "COMPLETED",
                    repoDisplay = "Jeon-JongChan/Jules-Code",
                    branchDisplay = "main",
                    prUrl = "https://github.com/Jeon-JongChan/Jules-Code/pull/12",
                    prTitle = "feat(ui): mobile responsive navigation and dark theme contrast",
                    createTime = "2026-10-21T00:30:00Z",
                    updateTime = "2026-10-21T00:45:00Z",
                    isPinned = true
                ),
                CachedSessionEntity(
                    sessionId = "demo-1002",
                    name = "sessions/demo-1002",
                    title = "Room DB API 키 다중 프로필 암호화/트랜잭션 점검",
                    prompt = "첫 실행 시 API 키를 안전하게 저장하고 여러 키를 전환할 수 있도록 Room DAO 및 단위 테스트를 작성해줘.",
                    state = "AWAITING_PLAN_APPROVAL",
                    repoDisplay = "Jeon-JongChan/Jules-Code",
                    branchDisplay = "feat/mobile-ui",
                    prUrl = "",
                    prTitle = "",
                    createTime = "2026-10-21T01:10:00Z",
                    updateTime = "2026-10-21T01:15:00Z",
                    isPinned = false
                )
            )
            dao.upsertSessions(demoSessions)
        }
    }

    private fun buildDemoActivities(session: CachedSessionEntity): List<JulesActivity> {
        return listOf(
            JulesActivity(
                id = "act-1",
                originator = "user",
                description = "작업 프롬프트 제출",
                createTime = session.createTime,
                userMessaged = MessageEvent(message = session.prompt)
            ),
            JulesActivity(
                id = "act-2",
                originator = "agent",
                description = "Jules가 실행 계획을 생성했습니다.",
                createTime = session.createTime,
                planGenerated = PlanGeneratedEvent(
                    plan = ExecutionPlan(
                        id = "plan-${session.sessionId}",
                        steps = listOf(
                            PlanStep(id = "s1", index = 0, title = "저장소 클론 및 클라우드 VM 환경 초기화", state = "COMPLETED"),
                            PlanStep(id = "s2", index = 1, title = "대상 모듈 분석 및 코드 패치(ChangeSet) 작성", state = "COMPLETED"),
                            PlanStep(id = "s3", index = 2, title = "단위 테스트 실행 및 GitHub PR 생성", state = if (session.state == "COMPLETED") "COMPLETED" else "PENDING")
                        )
                    )
                )
            ),
            JulesActivity(
                id = "act-3",
                originator = "agent",
                description = "클라우드 VM 테스트 및 코드 변경 수행",
                createTime = session.updateTime,
                progressUpdated = ProgressUpdatedEvent(
                    title = "코드 수정 및 검증 완료",
                    description = "요청하신 요구사항에 맞춰 파일 패치를 생성하고 검증 명령을 실행했습니다."
                ),
                artifacts = listOf(
                    ActivityArtifact(
                        bashOutput = BashOutputArtifact(
                            command = "./gradlew testDebugUnitTest",
                            output = "> Task :app:testDebugUnitTest\nJulesApiParserTest > parseFlexibleActivities PASSED\n\nBUILD SUCCESSFUL in 4s",
                            exitCode = 0
                        )
                    ),
                    ActivityArtifact(
                        changeSet = ChangeSetArtifact(
                            source = "sources/github/${session.repoDisplay}",
                            gitPatch = GitPatchData(
                                baseCommitId = "83db4fcc705b11ef911e",
                                suggestedCommitMessage = "feat: implement ${session.title}",
                                unidiffPatch = """
                                    diff --git a/app/src/main/java/com/jules/code/MainActivity.kt b/app/src/main/java/com/jules/code/MainActivity.kt
                                    index 83db4fc..2f8670d 100644
                                    --- a/app/src/main/java/com/jules/code/MainActivity.kt
                                    +++ b/app/src/main/java/com/jules/code/MainActivity.kt
                                    @@ -12,4 +12,6 @@ class MainActivity : ComponentActivity() {
                                    -    // Legacy Web Layout
                                    +    // Mobile Optimized Material3 Navigation Bar
                                    +    JulesMobileApp(viewModel = viewModel)
                                     }
                                """.trimIndent()
                            )
                        )
                    )
                )
            )
        )
    }

    private fun JulesSession.toEntity(isPinned: Boolean): CachedSessionEntity {
        val pr = outputs.firstOrNull { it.pullRequest != null }?.pullRequest
        return CachedSessionEntity(
            sessionId = sessionId,
            name = name.ifBlank { "sessions/$sessionId" },
            title = displayTitle,
            prompt = prompt,
            state = state,
            repoDisplay = repoDisplay,
            branchDisplay = branchDisplay,
            prUrl = pr?.url ?: "",
            prTitle = pr?.title ?: "",
            createTime = createTime,
            updateTime = updateTime,
            isPinned = isPinned
        )
    }

    private fun CachedSessionEntity.toModel(): JulesSession {
        val outputsList = if (prUrl.isNotBlank()) {
            listOf(SessionOutput(pullRequest = PullRequestOutput(url = prUrl, title = prTitle)))
        } else emptyList()
        return JulesSession(
            name = name,
            id = sessionId,
            title = title,
            prompt = prompt,
            state = state,
            sourceContext = SourceContext(
                source = "sources/github/$repoDisplay",
                githubRepoContext = GithubRepoContext(startingBranch = branchDisplay)
            ),
            outputs = outputsList,
            createTime = createTime,
            updateTime = updateTime
        )
    }

    companion object {
        const val DEMO_API_KEY = "DEMO_MODE_KEY"

        /**
         * Google Jules에서 선택 가능한 기반 AI 모델 옵션입니다.
         */
        data class JulesModelOption(
            val id: String,
            val shortName: String,
            val description: String,
            val badge: String
        )

        val AVAILABLE_MODELS = listOf(
            JulesModelOption(
                id = "GEMINI_3_1_PRO",
                shortName = "Gemini 3.1 Pro",
                description = "깊은 추론 & 대규모 아키텍처 리팩토링 (Pro/Ultra 권장)",
                badge = "DEEP REASONING"
            ),
            JulesModelOption(
                id = "GEMINI_3_6_FLASH",
                shortName = "Gemini 3.6 Flash",
                description = "초고속 코드 패치, 단위 테스트 생성 및 빠른 반복 작업",
                badge = "FAST"
            )
        )

        fun getModelDisplayName(modelId: String?): String =
            AVAILABLE_MODELS.find { it.id == modelId }?.shortName ?: "Gemini 3.1 Pro"

        /**
         * 사용자가 선택한 모델, Critic Agent 리뷰, CI Auto-Fixer, 저장소 환경 설정/메모리,
         * 그리고 현재 앱 표시 언어(`ko` 한국어 / `en` 영어) 응답 지시어를
         * Jules 클라우드 VM 에이전트가 준수하도록 프롬프트 컨텍스트로 결합합니다.
         */
        fun buildEnrichedPrompt(
            basePrompt: String,
            modelId: String,
            enableCritic: Boolean,
            enableCiFixer: Boolean,
            setupScript: String,
            envVars: String,
            repoMemory: String,
            responseLanguage: String = "ko"
        ): String {
            val directives = mutableListOf<String>()
            val langInstruction = if (responseLanguage.lowercase().startsWith("ko")) {
                "Response Language: 한국어 (Korean) — 반드시 모든 실행 계획(Execution Plan), 진행 상태 설명(Progress Updates), 에이전트 답변(Agent Messages)을 한국어로 작성하세요."
            } else {
                "Response Language: English — Always write all Execution Plans, Progress Updates, and Agent Messages in English."
            }
            directives.add(langInstruction)

            val modelName = getModelDisplayName(modelId)
            directives.add("Model Profile: $modelName")
            if (enableCritic) {
                directives.add("Critic Agent: ENABLED (Perform self-critique for security, edge cases, and regressions before finalizing ChangeSet)")
            }
            if (enableCiFixer) {
                directives.add("CI Auto-Fixer: ENABLED (Verify build/test suite passes with exit code 0 and fix any lint/test failures automatically)")
            }
            if (setupScript.isNotBlank()) {
                directives.add("VM Setup Script:\n$setupScript")
            }
            if (envVars.isNotBlank()) {
                directives.add("Environment Variables:\n$envVars")
            }
            if (repoMemory.isNotBlank()) {
                directives.add("Repository Memory & Conventions (AGENTS.md):\n$repoMemory")
            }
            return "$basePrompt\n\n---\n[Jules Agent Execution Context]\n" + directives.joinToString("\n") { "- $it" }
        }

        /**
         * 세션 진행 중 후속 메시지(`sendMessage`)를 보낼 때도 설정된 언어(`ko`/`en`)로 답변하도록 지시어를 부착합니다.
         */
        fun buildLocalizedFollowUpMessage(message: String, responseLanguage: String = "ko"): String {
            val langRule = if (responseLanguage.lowercase().startsWith("ko")) {
                "[언어 설정: 한국어(Korean)로 답변 및 진행 상황을 작성해 주세요.]"
            } else {
                "[Language Setting: Please respond and write progress updates in English.]"
            }
            return "$message\n\n$langRule"
        }

        /**
         * Google Jules 요금제 티어별 일일 작업 한도(Rolling 24h) 및 동시 실행(Concurrent) 한도입니다.
         */
        data class PlanTierLimits(
            val code: String,
            val displayName: String,
            val dailyLimit: Int,
            val concurrentLimit: Int
        )

        val PLAN_TIERS = listOf(
            PlanTierLimits("FREE", "Free (기본 15회/일 • 동시 3개)", 15, 3),
            PlanTierLimits("PRO", "Google AI Pro (100회/일 • 동시 15개)", 100, 15),
            PlanTierLimits("ULTRA", "Google AI Ultra (300회/일 • 동시 60개)", 300, 60)
        )

        fun getTierLimits(tierCode: String?): PlanTierLimits =
            PLAN_TIERS.find { it.code.equals(tierCode, ignoreCase = true) } ?: PLAN_TIERS.first()

        /**
         * 세션 목록에서 최근 24시간(Rolling 24h Window) 이내에 생성된 세션 수를 계산합니다.
         */
        fun countRolling24hSessions(sessions: List<CachedSessionEntity>, nowMillis: Long = System.currentTimeMillis()): Int {
            val cutoff = nowMillis - 24L * 60L * 60L * 1000L
            return sessions.count { s ->
                val ct = s.createTime.trim()
                if (ct.contains("방금") || ct.contains("오늘")) {
                    true
                } else {
                    try {
                        java.time.Instant.parse(ct).toEpochMilli() >= cutoff
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }

        /**
         * 현재 동시 실행 중(Concurrent Active)인 세션 수를 계산합니다.
         */
        fun countConcurrentActiveSessions(sessions: List<CachedSessionEntity>): Int =
            sessions.count {
                it.state.uppercase() in listOf(
                    "QUEUED",
                    "PLANNING",
                    "IN_PROGRESS",
                    "AWAITING_PLAN_APPROVAL",
                    "AWAITING_USER_FEEDBACK"
                )
            }

        /**
         * 세션 상태 코드를 한국어 표시 레이블로 변환합니다.
         */
        fun translateState(state: String): String = when (state.uppercase()) {
            "QUEUED" -> "대기열 등록됨"
            "PLANNING" -> "계획 수립 중"
            "AWAITING_PLAN_APPROVAL" -> "계획 승인 대기"
            "AWAITING_USER_FEEDBACK" -> "사용자 피드백 대기"
            "IN_PROGRESS" -> "코드 작업 중"
            "PAUSED" -> "일시중지됨"
            "COMPLETED" -> "작업 완료"
            "FAILED" -> "실패"
            else -> state.ifBlank { "상태 미정" }
        }

        /**
         * Context로부터 `JulesViewModel`을 생성하는 Factory입니다.
         */
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = JulesDatabase.getInstance(context)
                return JulesViewModel(db.dao()) as T
            }
        }
    }
}

