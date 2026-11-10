package com.jules.code.ui

import android.content.Context
import androidx.annotation.StringRes
import com.jules.code.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jules.code.data.local.*
import com.jules.code.data.model.*
import com.jules.code.data.remote.JulesApiClient
import com.jules.code.ui.timeline.SessionTimelineStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString

/** 키별 조회 상태를 구분하여 실패를 세션 0개로 오인하지 않게 합니다. */
data class ApiKeySyncStatus(val loading: Boolean = false, val error: String? = null, val updatedAt: Long? = null)

/**
 * Jules 앱의 전체 상태와 비즈니스 로직을 관리하는 메인 ViewModel입니다.
 * API 키별 작업 소모량 분리 집계, 세션 무응답/스턱(Stalled) 자동 진단 및 복구 액션,
 * 세션/소스 동기화, 계획 승인, 메시지 전송, 데모 모드, 스케줄 템플릿을 통합 처리합니다.
 */
class JulesViewModel(
    private val dao: JulesDao,
    private val api: JulesApiClient = JulesApiClient()
) : ViewModel() {

    private val _apiKeySyncStatuses = MutableStateFlow<Map<Long, ApiKeySyncStatus>>(emptyMap())
    /** API 키별 목록 조회 진행 상태 및 마지막 성공 시각입니다. */
    val apiKeySyncStatuses = _apiKeySyncStatuses.asStateFlow()

    /** 저장된 모든 API 키 프로필 목록 */
    val apiKeys: StateFlow<List<ApiKeyEntity>> = dao.observeApiKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 현재 활성화된 API 키 프로필 */
    val activeApiKey: StateFlow<ApiKeyEntity?> = dao.observeActiveApiKey()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 전체 API 키의 모든 캐시된 코딩 세션 목록 (API 키별 소모량 전체 집계용) */
    val allSessions: StateFlow<List<CachedSessionEntity>> = dao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 현재 활성 API 키에 속한 코딩 세션 목록 (대시보드 및 세션 목록 표시용) */
    val sessions: StateFlow<List<CachedSessionEntity>> = combine(allSessions, activeApiKey) { list, active ->
        if (active == null) emptyList()
        else list.filter { it.apiKeyId == active.id || it.apiKeyId == 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** 각 API 키별 작업 소모량(일일 사용량, 동시 실행 슬롯, 완료/진행/응답지연 통계) 실시간 맵 */
    val apiKeyUsages: StateFlow<Map<Long, ApiKeyUsageSummary>> = combine(
        apiKeys,
        allSessions,
        preferences
    ) { keys, allSess, prefs ->
        val fallbackTier = prefs["jules_plan_tier"] ?: "FREE"
        val sessionsByKey = allSess.groupBy { it.apiKeyId }
        keys.associate { keyEntity ->
            val keySessions = sessionsByKey[keyEntity.id].orEmpty() +
                if (keyEntity.isActive) sessionsByKey[0L].orEmpty() else emptyList()
            val tierCode = keyEntity.planTier.ifBlank { fallbackTier }
            keyEntity.id to computeApiKeyUsage(keyEntity, keySessions, tierCode)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 현재 선택된 상세 세션 ID */
    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    /** 현재 선택된 세션의 실시간 상세 정보 (Outputs/PR 포함) */
    private val _selectedSessionDetail = MutableStateFlow<JulesSession?>(null)
    val selectedSessionDetail: StateFlow<JulesSession?> = _selectedSessionDetail.asStateFlow()

    /** 현재 선택된 세션의 액티비티(계획, 대화, 패치, Bash 출력) 목록 */
    private val _activities = MutableStateFlow<List<JulesActivity>>(emptyList())
    val activities: StateFlow<List<JulesActivity>> = _activities.asStateFlow()

    private val _isTimelineLoading = MutableStateFlow(false)
    /** 첫 활동 조회 중 빈 목록을 활동 없음으로 오인하지 않게 합니다. */
    val isTimelineLoading = _isTimelineLoading.asStateFlow()
    private val _loadingActivityIds = MutableStateFlow<Set<String>>(emptySet())
    /** 사용자가 펼친 활동의 중복 단건 요청을 막고 로딩 상태를 표시합니다. */
    val loadingActivityIds = _loadingActivityIds.asStateFlow()
    private val _activityErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 개별 활동 조회 실패를 해당 항목에서 다시 시도할 수 있게 합니다. */
    val activityErrors = _activityErrors.asStateFlow()
    /** 여러 항목을 연속으로 펼쳐도 큰 원문 다운로드는 최대 두 개만 동시 실행합니다. */
    private val activityLoadSemaphore = Semaphore(2)
    private val loadedActivities = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, JulesActivity>(8, 0.75f, true) {
            /** 원문은 파일에 두고 최근 8개 활동의 작은 메타데이터만 유지합니다. */
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JulesActivity>?): Boolean = size > 8
        }
    )

    /** 로딩 상태 표시 플래그 */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 세션 메시지 전송 후 에이전트 응답 대기 로딩 플래그 */
    private val _isAwaitingResponse = MutableStateFlow(false)
    val isAwaitingResponse: StateFlow<Boolean> = _isAwaitingResponse.asStateFlow()

    /** 사용자 알림/오류 스낵바 메시지 */
    private val _snackbarMessage = MutableStateFlow<UiText?>(null)
    val snackbarMessage: StateFlow<UiText?> = _snackbarMessage.asStateFlow()

    /** 데모 모드 활성화 여부 (API 키가 `"DEMO"`이거나 사용자가 데모 체험 선택 시) */
    val isDemoMode: StateFlow<Boolean> = activeApiKey
        .map { it?.apiKey == DEMO_API_KEY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var pollingJob: Job? = null
    private var refreshJob: Job? = null
    /** 삭제·캐시 상한·낙관적 병합 규칙은 타임라인 상태 소유자가 관리합니다. */
    private val timeline = SessionTimelineStore(dao, api.json)
    private val _deletingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    /** 삭제 요청 중인 세션의 중복 실행과 버튼 조작을 막습니다. */
    val deletingSessionIds: StateFlow<Set<String>> = _deletingSessionIds.asStateFlow()

    init {
        // 기본 프롬프트 템플릿이 없으면 초기 템플릿 시드 생성
        viewModelScope.launch {
            if (dao.observeTemplates().first().isEmpty()) {
                seedDefaultTemplates()
            }
        }
        // 활성 API 키 변경 시 자동으로 세션 및 소스 목록 동기화
        viewModelScope.launch {
            activeApiKey.map { it?.id to it?.apiKey }.distinctUntilChanged().collectLatest { (id, key) ->
                refreshJob?.cancel()
                closeSession()
                _selectedSessionId.value = null
                _selectedSessionDetail.value = null
                _activities.value = emptyList()
                dao.clearSources()
                if (id != null && !key.isNullOrBlank()) {
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
        _snackbarMessage.value = UiText.Literal(msg)
    }

    /** 표시 언어와 분리된 리소스 문구를 스낵바에 전달합니다. */
    fun showMessage(@StringRes resource: Int, vararg arguments: Any) {
        _snackbarMessage.value = UiText.Resource(resource, arguments.toList())
    }

    /**
     * 새로운 API 키를 Room에 저장하고 즉시 활성화합니다.
     */
    fun saveApiKey(label: String, rawKey: String, tierCode: String? = null) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            showMessage(R.string.message_key_required)
            return
        }
        val resolvedTier = tierCode ?: preferences.value["jules_plan_tier"] ?: "FREE"
        viewModelScope.launch {
            dao.saveAndActivateApiKey(label.ifBlank { "Jules API Key" }, trimmed, resolvedTier)
            showMessage(R.string.message_key_saved)
        }
    }

    /**
     * 특정 API 키 프로필의 요금제 티어(FREE/PRO/ULTRA)를 개별 변경합니다.
     */
    fun updateApiKeyTier(apiKeyId: Long, tierCode: String) {
        viewModelScope.launch {
            dao.updateApiKeyTier(apiKeyId, tierCode)
            if (activeApiKey.value?.id == apiKeyId) {
                dao.setPreference(AppPreferenceEntity("jules_plan_tier", tierCode))
            }
        }
    }

    /**
     * 현재 활성 API 키의 요금제 티어를 순환(FREE -> PRO -> ULTRA)하거나 설정합니다.
     */
    fun setActiveKeyTier(tierCode: String) {
        viewModelScope.launch {
            dao.setPreference(AppPreferenceEntity("jules_plan_tier", tierCode))
            activeApiKey.value?.let { active ->
                dao.updateApiKeyTier(active.id, tierCode)
            }
        }
    }

    /**
     * API 키 없이도 앱의 모든 UI/기능을 즉시 테스트할 수 있도록 내장 데모 모드를 활성화합니다.
     */
    fun activateDemoMode() {
        viewModelScope.launch {
            val demoId = dao.saveAndActivateApiKey("데모 모드 (오프라인 시뮬레이션)", DEMO_API_KEY)
            seedDemoData(demoId)
            showMessage(R.string.message_demo_enabled)
        }
    }

    /**
     * 등록된 다른 API 키 프로필로 전환합니다.
     */
    fun switchApiKey(id: Long) {
        if (id == activeApiKey.value?.id || apiKeys.value.none { it.id == id }) return
        closeSession()
        viewModelScope.launch {
            dao.switchActiveApiKey(id)
            showMessage(R.string.message_profile_switched)
        }
    }

    /**
     * 저장된 API 키 프로필을 삭제합니다.
     */
    fun deleteApiKey(id: Long) {
        viewModelScope.launch {
            dao.deleteSessionsByApiKey(id)
            dao.deleteApiKey(id)
            showMessage(R.string.message_profile_deleted)
        }
    }

    /**
     * 앱 환경설정 값을 업데이트합니다 (예: 커밋 작성자 모드, 자동 새로고침 여부).
     */
    fun updatePreference(key: String, value: String) {
        viewModelScope.launch {
            dao.setPreference(AppPreferenceEntity(key, value))
            if (key == "jules_plan_tier") {
                activeApiKey.value?.let { active ->
                    dao.updateApiKeyTier(active.id, value)
                }
            }
        }
    }

    /**
     * 서버(또는 데모 스토어)로부터 활성 API 키의 소스/세션 및 등록된 모든 API 키의 세션 작업 소모량/진단 정보를 동기화합니다.
     */
    fun refreshAll() {
        val activeEntity = activeApiKey.value ?: return
        val key = activeEntity.apiKey
        if (key == DEMO_API_KEY) {
            viewModelScope.launch { seedDemoData(activeEntity.id) }
            return
        }
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            try {
              coroutineScope {
                val sourcesDeferred = async { api.listAllSources(key) }
                val activeSessionsDeferred = async { syncSessionsForApiKey(activeEntity) }

                // 비활성 API 키들도 백그라운드에서 병렬로 세션 목록을 동기화하여 키별 소모량(24h/동시실행)을 최신 상태로 유지
                // 화면의 구독 여부에 따라 비어 있을 수 있는 StateFlow 캐시 대신 DB 목록을 읽습니다.
                val otherKeys = dao.observeApiKeys().first().filter { it.id != activeEntity.id && it.apiKey != DEMO_API_KEY }
                val otherKeysDeferred = otherKeys.map { other ->
                    async {
                        syncSessionsForApiKey(other).onFailure { err ->
                            showMessage(R.string.message_profile_sync_failed, other.label, err.message.orEmpty())
                        }
                    }
                }

                sourcesDeferred.await().onSuccess { resp ->
                    val entities = resp.map { s ->
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
                    if (activeApiKey.value?.id == activeEntity.id) dao.replaceSources(entities)
                }.onFailure { err ->
                    showMessage(R.string.message_sources_failed, err.message.orEmpty())
                }

                activeSessionsDeferred.await().onFailure { err ->
                    showMessage(R.string.message_sync_failed, err.message.orEmpty())
                }
                otherKeysDeferred.awaitAll()
              }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 특정 API 키 프로필의 세션 목록을 조회하고, 현재 진행 중(Alive)인 세션은 액티비티 타임라인을 함께 점검하여
     * 무응답/스턱(Stalled) 여부 및 진행 단계를 DB에 갱신합니다.
     */
    private suspend fun syncSessionsForApiKey(keyEntity: ApiKeyEntity): Result<Unit> = coroutineScope {
        val previous = _apiKeySyncStatuses.value[keyEntity.id] ?: ApiKeySyncStatus()
        _apiKeySyncStatuses.update { it + (keyEntity.id to previous.copy(loading = true, error = null)) }
        try {
        val result = api.listAllSessions(keyEntity.apiKey).map { remoteSessions ->
            val existingById = dao.observeSessions().first().associateBy { it.sessionId }
            // 목록은 즉시 표시하고, 활동 조회가 느려도 세션 상태 표시를 막지 않습니다.
            val initialEntities = remoteSessions.map { s ->
                val prev = existingById[s.sessionId]
                s.toEntity(isPinned = prev?.isPinned ?: false, apiKeyId = keyEntity.id, previousEntity = prev)
            }
            timeline.cacheSessions(initialEntities, replaceForKey = keyEntity.id)
            // 활성 세션(최대 10개)에 대해 병렬로 액티비티를 조회하여 실제 에이전트 마지막 활동 시각과 무응답 상태를 진단
            val aliveSessions = remoteSessions.filter { it.isAlive }.take(10)
            val activitiesBySessionId = aliveSessions.map { s ->
                async {
                    val acts = api.listAllActivities(keyEntity.apiKey, s.sessionId).getOrNull()
                    if (acts != null) {
                        timeline.cacheActivities(s.sessionId, acts, _selectedSessionId.value)
                    }
                    s.sessionId to acts
                }
            }.awaitAll().toMap()

            val entities = remoteSessions.map { s ->
                val prev = existingById[s.sessionId]
                val freshActs = activitiesBySessionId[s.sessionId] ?: timeline.cached(s.sessionId)
                s.toEntity(
                    isPinned = prev?.isPinned ?: false,
                    apiKeyId = keyEntity.id,
                    activities = freshActs,
                    previousEntity = prev
                )
            }
            timeline.cacheSessions(entities)
        }
        _apiKeySyncStatuses.update { it + (keyEntity.id to ApiKeySyncStatus(
            error = result.exceptionOrNull()?.let { it.message ?: "조회 실패" },
            updatedAt = if (result.isSuccess) System.currentTimeMillis() else previous.updatedAt
        )) }
        result
        } catch (cancelled: CancellationException) {
            _apiKeySyncStatuses.update { it + (keyEntity.id to previous) }
            throw cancelled
        } catch (error: Exception) {
            _apiKeySyncStatuses.update { it + (keyEntity.id to previous.copy(error = error.message ?: "조회 실패")) }
            Result.failure(error)
        }
    }

    /**
     * 새로운 Jules 코딩 세션을 생성합니다.
     * 사용자가 확인한 프롬프트 원문과 공식 요청 옵션만 전송합니다.
     */
    fun createSession(
        prompt: String,
        title: String,
        sourceName: String?,
        branchName: String?,
        requirePlanApproval: Boolean,
        autoCreatePr: Boolean,
        onCreated: (String) -> Unit = {}
    ) {
        val activeEntity = activeApiKey.value
        val key = activeEntity?.apiKey
        if (activeEntity == null || key.isNullOrBlank()) {
            showMessage(R.string.message_key_required)
            return
        }
        if (prompt.isBlank()) {
            showMessage(R.string.message_prompt_required)
            return
        }

        val currentPrefs = preferences.value
        val langPref = currentPrefs["app_language"]
        val responseLangCode = JulesLocaleManager.resolveLocale(langPref).language
        // 앱 설정이나 저장소 메모리를 자동 주입하지 않고 입력 원문을 전송합니다.
        val cleanBasePrompt = prompt

        viewModelScope.launch {
            _isLoading.value = true
            if (key == DEMO_API_KEY) {
                val newId = "demo-${System.currentTimeMillis() % 100000}"
                val repoDisp = sourceName?.removePrefix("sources/github/")?.removePrefix("sources/")
                    ?.ifBlank { "Repoless (Cloud VM)" } ?: "Repoless (Cloud VM)"
                val state = if (requirePlanApproval) "AWAITING_PLAN_APPROVAL" else "IN_PROGRESS"
                val nowIso = java.time.Instant.now().toString()
                val entity = CachedSessionEntity(
                    sessionId = newId,
                    name = "sessions/$newId",
                    title = title.ifBlank { cleanBasePrompt.take(45) },
                    prompt = cleanBasePrompt,
                    state = state,
                    repoDisplay = repoDisp,
                    branchDisplay = branchName ?: "main",
                    prUrl = "",
                    prTitle = "",
                    createTime = nowIso,
                    updateTime = nowIso,
                    isPinned = false,
                    apiKeyId = activeEntity.id,
                    lastActivityTime = nowIso,
                    lastOriginator = "agent"
                )
                dao.upsertSessions(listOf(entity))
                _isLoading.value = false
                showMessage(R.string.message_session_created)
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
                prompt = cleanBasePrompt,
                title = title.trim().ifBlank { null },
                sourceContext = sourceCtx,
                requirePlanApproval = if (requirePlanApproval) true else null,
                automationMode = if (autoCreatePr) "AUTO_CREATE_PR" else null
            )

            api.createSession(key, req)
                .onSuccess { created ->
                    val initialUserAct = JulesActivity(
                        id = "optimistic-init-${created.sessionId}",
                        originator = "user",
                        description = if (responseLangCode == "ko") "작업 프롬프트 제출" else "Task Prompt Submitted",
                        createTime = created.createTime.ifBlank { java.time.Instant.now().toString() },
                        userMessaged = MessageEvent(message = cleanBasePrompt)
                    )
                    timeline.enqueue(created.sessionId, initialUserAct)
                    dao.upsertSessions(
                        listOf(
                            created.toEntity(
                                isPinned = false,
                                apiKeyId = activeEntity.id,
                                activities = listOf(initialUserAct),
                                previousEntity = null
                            )
                        )
                    )
                    _selectedSessionDetail.value = created
                    _isLoading.value = false
                    showMessage(R.string.message_session_created)
                    openSession(created.sessionId)
                    onCreated(created.sessionId)
                }
                .onFailure { err ->
                    _isLoading.value = false
                    showMessage(R.string.message_session_failed, err.message.orEmpty())
                }
        }
    }

    /**
     * 응답이 멈춘(Stalled) 세션에 깨우기/재촉(Nudge) 메시지를 원터치로 전송합니다.
     * 계획 승인 이후 Step 1에서 멈춰 있는 경우 첫 단계 실행 및 진행 상황 보고를 명시적으로 요청합니다.
     */
    fun nudgeStalledSession(sessionId: String) {
        val currentEntity = allSessions.value.find { it.sessionId == sessionId }
        val stepHint = currentEntity?.currentStepTitle?.takeIf { it.isNotBlank() }
        val langCode = JulesLocaleManager.resolveLocale(preferences.value["app_language"]).language
        val nudgeText = if (langCode == "ko") {
            if (stepHint != null) {
                "승인된 실행 계획의 현재 단계('$stepHint') 코드 작성 및 터미널 검증을 즉시 수행하고 진행 상태(Progress Update)를 출력해 주세요."
            } else {
                "현재 단계의 코드 작업을 즉시 진행하고 진행 상태를 업데이트해 주세요."
            }
        } else {
            if (stepHint != null) {
                "Please proceed immediately with the current step ('$stepHint') of the approved plan and emit a progress update."
            } else {
                "Please proceed immediately with the code changes and report your progress."
            }
        }
        sendMessage(sessionId, nudgeText)
    }

    /**
     * 응답이 멈춘(Stalled) 세션과 동일한 프롬프트/저장소/브랜치 설정으로 새 클라우드 VM 세션을 즉시 재생성합니다.
     */
    fun recreateStalledSession(session: JulesSession, onCreated: (String) -> Unit = {}) {
        val cleanPrompt = session.prompt
        val sourceName = session.sourceContext?.source?.takeIf { !it.contains("Repoless", ignoreCase = true) }
        val branchName = session.branchDisplay
        val cleanTitle = session.displayTitle.lineSequence().firstOrNull()?.take(50) ?: "재시작 세션"
        createSession(
            prompt = cleanPrompt,
            title = cleanTitle,
            sourceName = sourceName,
            branchName = branchName,
            requirePlanApproval = false,
            autoCreatePr = true,
            onCreated = onCreated
        )
    }

    /**
     * 특정 세션을 선택하여 로컬/메모리 캐시를 0ms로 즉시 표시하고,
     * 병렬(Parallel) 백그라운드 스트림 폴링을 시작합니다.
     */
    fun openSession(sessionId: String) {
        if (timeline.isDeleted(sessionId)) return
        val isSwitchingSession = _selectedSessionId.value != sessionId
        if (isSwitchingSession) {
            _isAwaitingResponse.value = false
        }
        _selectedSessionId.value = sessionId
        _isTimelineLoading.value = true
        if (isSwitchingSession && _selectedSessionDetail.value?.sessionId != sessionId) {
            _selectedSessionDetail.value = null
        }

        // 1. 메모리 내 세션 리스트 및 액티비티 캐시에서 즉시(0ms) 바인딩하여 빈 화면 대기 제거
        allSessions.value.find { it.sessionId == sessionId }?.let { cachedEntity ->
            if (isSwitchingSession || _selectedSessionDetail.value == null) {
                _selectedSessionDetail.value = cachedEntity.toModel()
            }
        }
        val memActs = timeline.cached(sessionId)
        if (memActs != null) {
            _activities.value = mergeActivitiesWithOptimistic(sessionId, memActs)
        } else if (isSwitchingSession) {
            _activities.value = timeline.pending(sessionId)
        }

        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // 2. Room DB에 캐시된 이전 액티비티가 있으면 네트워크 응답 전에 먼저 로드
            if (memActs == null) {
                val cachedRow = try { dao.getCachedActivities(sessionId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                cachedRow?.let {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            api.json.decodeFromString<List<JulesActivity>>(cachedRow.activitiesJson)
                        }
                    }.onSuccess { diskActs ->
                        if (diskActs.isNotEmpty() && _selectedSessionId.value == sessionId) {
                            timeline.remember(sessionId, diskActs, _selectedSessionId.value)
                            _activities.value = mergeActivitiesWithOptimistic(sessionId, diskActs)
                        }
                    }
                }
            }

            // 3. 실시간 고속 폴링 (진행 중이거나 낙관적 메시지/에이전트 응답 대기 중에는 고속 간격)
            while (true) {
                try {
                    loadSessionDetailAndActivities(sessionId)
                } finally {
                    if (_selectedSessionId.value == sessionId && pollingJob == kotlin.coroutines.coroutineContext[Job]) {
                        _isTimelineLoading.value = false
                    }
                }
                val currentSession = _selectedSessionDetail.value
                val currentState = currentSession?.state ?: ""
                val hasPending = timeline.pending(sessionId).isNotEmpty()
                val lastActIsUser = _activities.value.lastOrNull()?.originator?.equals("user", ignoreCase = true) == true
                val stillWaitingForAgent = hasPending || (lastActIsUser && currentSession?.isAlive != false)
                if (!stillWaitingForAgent) {
                    _isAwaitingResponse.value = false
                }
                if ((!stillWaitingForAgent && currentState in listOf("COMPLETED", "FAILED", "PAUSED")) ||
                    activeApiKey.value?.apiKey == DEMO_API_KEY
                ) {
                    _isAwaitingResponse.value = false
                    break
                }
                delay(if (_isAwaitingResponse.value) 1_500L else 2_500L)
            }
        }
    }

    /**
     * 세션 상세 화면을 닫을 때 백그라운드 폴링 루프를 즉시 중지하여 불필요한 네트워크/배터리 소모를 방지합니다.
     */
    fun closeSession() {
        _isTimelineLoading.value = false
        pollingJob?.cancel()
        pollingJob = null
        _isAwaitingResponse.value = false
        _selectedSessionId.value = null
        _selectedSessionDetail.value = null
        _activities.value = emptyList()
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

    /**
     * `getSession`과 `listActivities`를 직렬이 아닌 `async` 병렬로 동시에 호출하여
     * 타임라인 로딩 지연을 절반 이하로 단축합니다.
     */
    private suspend fun loadSessionDetailAndActivities(sessionId: String) {
        val activeEntity = activeApiKey.value ?: return
        val key = activeEntity.apiKey
        if (key == DEMO_API_KEY) {
            val cached = dao.getCachedSession(sessionId)
            if (cached != null) {
                _selectedSessionDetail.value = cached.toModel()
                if (timeline.cached(sessionId).isNullOrEmpty()) {
                    val demoActs = buildDemoActivities(cached)
                    timeline.remember(sessionId, demoActs, _selectedSessionId.value)
                    _activities.value = demoActs
                }
            }
            return
        }

        coroutineScope {
            val sessionDeferred = async { api.getSession(key, sessionId) }
            val activitiesDeferred = async { api.listAllActivities(key, sessionId) }

            val sessionResult = sessionDeferred.await()
            val activitiesResult = activitiesDeferred.await()

            // 전환·삭제 이후 도착한 요청 결과는 현재 화면을 덮어쓰지 않습니다.
            if (activeApiKey.value?.id != activeEntity.id || timeline.isDeleted(sessionId)) return@coroutineScope
            val rawServerActs = activitiesResult.getOrNull()?.map { summary ->
                loadedActivities["${activeEntity.id}/$sessionId/${summary.id.ifBlank { summary.name.substringAfterLast('/') }}"] ?: summary
            }
            if (rawServerActs != null) {
                val merged = mergeActivitiesWithOptimistic(sessionId, rawServerActs)
                if (_selectedSessionId.value == sessionId) {
                    _activities.value = merged
                }
                timeline.cacheActivities(sessionId, rawServerActs, _selectedSessionId.value)
            }

            sessionResult.onSuccess { session ->
                if (_selectedSessionId.value == sessionId) {
                    _selectedSessionDetail.value = session
                }
                val prev = dao.getCachedSession(sessionId)
                val pinned = prev?.isPinned ?: false
                val ownerKeyId = activeEntity.id
                timeline.cacheSessions(
                    listOf(
                        session.toEntity(
                            isPinned = pinned,
                            apiKeyId = ownerKeyId,
                            activities = rawServerActs ?: timeline.cached(sessionId),
                            previousEntity = prev
                        )
                    )
                )
            }
            sessionResult.onFailure { showMessage(R.string.message_session_load_failed, it.message.orEmpty()) }
            activitiesResult.onFailure { showMessage(R.string.message_activity_failed, it.message.orEmpty()) }
        }
    }

    /**
     * 서버에서 받은 사용자 메시지 원문을 보존하고 인덱싱 전 낙관적 채팅을 병합합니다.
     */
    internal fun mergeActivitiesWithOptimistic(
        sessionId: String,
        serverActivities: List<JulesActivity>
    ): List<JulesActivity> {
        val sorted = timeline.merge(sessionId, serverActivities)

        // 에이전트 응답이 도착했거나 세션이 종료된 경우에만 응답 대기 로딩 해제
        if (_selectedSessionId.value == sessionId && _isAwaitingResponse.value) {
            val hasPending = timeline.pending(sessionId).isNotEmpty()
            val lastIsUser = sorted.lastOrNull()?.originator?.equals("user", ignoreCase = true) == true
            val sessionAlive = _selectedSessionDetail.value?.isAlive != false
            if (!hasPending && (!lastIsUser || !sessionAlive)) {
                _isAwaitingResponse.value = false
            }
        }

        return sorted
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
                    val nowIso = java.time.Instant.now().toString()
                    val updated = cached.copy(
                        state = "COMPLETED",
                        prUrl = "https://github.com/${cached.repoDisplay}/pull/42",
                        prTitle = "feat: ${cached.title}",
                        updateTime = nowIso,
                        lastActivityTime = nowIso,
                        lastOriginator = "agent"
                    )
                    dao.upsertSessions(listOf(updated))
                    loadSessionDetailAndActivities(sessionId)
                }
                _isLoading.value = false
                showMessage(R.string.message_demo_completed)
                return@launch
            }

            api.approvePlan(key, sessionId)
                .onSuccess {
                    showMessage(R.string.message_plan_approved)
                    loadSessionDetailAndActivities(sessionId)
                }
                .onFailure { err ->
                    showMessage(R.string.message_approval_failed, err.message.orEmpty())
                }
            _isLoading.value = false
        }
    }

    /**
     * 진행 중인 세션에 후속 프롬프트/피드백 메시지를 전송합니다.
     * 전송 버튼을 누르는 즉시(0ms) 낙관적(Optimistic) UI로 타임라인에 채팅 버블을 먼저 추가하고,
     * 백그라운드로 API 전송 및 고속 폴링을 수행합니다.
     */
    fun sendMessage(sessionId: String, message: String) {
        val cleanInput = message
        if (cleanInput.isBlank()) return
        val key = activeApiKey.value?.apiKey ?: return
        val langCode = JulesLocaleManager.resolveLocale(preferences.value["app_language"]).language

        // 1. 낙관적(Optimistic) 사용자 메시지 즉시(0ms) 타임라인 반영 및 로딩 상태 활성화
        _isAwaitingResponse.value = true
        val nowIso = java.time.Instant.now().toString()
        val optimisticUserAct = JulesActivity(
            id = "optimistic-user-${System.currentTimeMillis()}",
            originator = "user",
            description = if (langCode == "ko") "전송됨" else "Sent",
            createTime = nowIso,
            userMessaged = MessageEvent(message = cleanInput)
        )

        if (key == DEMO_API_KEY) {
            val current = _activities.value.toMutableList()
            current.add(optimisticUserAct)
            _activities.value = sortActivitiesChronologically(current)

            viewModelScope.launch {
                delay(1200L) // 실제 에이전트 응답 생성 지연 시뮬레이션
                val updated = (timeline.cached(sessionId) ?: current).toMutableList()
                updated.add(
                    JulesActivity(
                        id = "agent-${System.currentTimeMillis() + 1}",
                        originator = "agent",
                        description = "Jules 응답",
                        createTime = java.time.Instant.now().toString(),
                        agentMessaged = MessageEvent(
                            message = if (langCode == "ko") {
                                "요청하신 추가 지시사항(\"$cleanInput\")을 반영하여 패치와 테스트를 업데이트했습니다."
                            } else {
                                "Applied your follow-up instructions (\"$cleanInput\") and updated the patch and tests."
                            }
                        )
                    )
                )
                val sorted = sortActivitiesChronologically(updated)
                timeline.remember(sessionId, sorted, _selectedSessionId.value)
                if (_selectedSessionId.value == sessionId) {
                    _activities.value = sorted
                    _isAwaitingResponse.value = false
                }
            }
            return
        }

        timeline.enqueue(sessionId, optimisticUserAct)
        _activities.value = sortActivitiesChronologically(_activities.value + optimisticUserAct)

        // 2. 백그라운드 비동기 전송 및 즉시 폴링 루프 깨우기
        viewModelScope.launch {
            api.sendMessage(key, sessionId, cleanInput)
                .onSuccess {
                    if (_selectedSessionId.value == sessionId && activeApiKey.value?.apiKey == key) openSession(sessionId)
                }
                .onFailure { err ->
                    timeline.reject(sessionId, optimisticUserAct.id)
                    if (_selectedSessionId.value == sessionId) {
                        _activities.value = sortActivitiesChronologically(_activities.value.filterNot { it.id == optimisticUserAct.id })
                        _isAwaitingResponse.value = false
                    }
                    showMessage(R.string.message_send_failed, err.message.orEmpty())
                }
        }
    }

    /**
     * 세션을 삭제합니다.
     */
    fun deleteSession(sessionId: String, onDeleted: () -> Unit = {}) {
        if (sessionId in _deletingSessionIds.value || timeline.isDeleted(sessionId)) return
        _deletingSessionIds.update { it + sessionId }
        viewModelScope.launch {
          try {
            val cached = dao.getCachedSession(sessionId)
            val memberships = dao.observeSessions().first().filter { it.sessionId == sessionId }
            val ownerId = memberships.firstOrNull { it.apiKeyId == activeApiKey.value?.id }?.apiKeyId
                ?: cached?.apiKeyId?.takeIf { it != 0L } ?: activeApiKey.value?.id
            val owner = dao.observeApiKeys().first().find { it.id == ownerId }
                ?: error("이 세션의 API 키 프로필을 찾을 수 없습니다.")
            if (owner.apiKey != DEMO_API_KEY) {
                val result = api.deleteSession(owner.apiKey, sessionId)
                val error = result.exceptionOrNull()
                // 이미 원격에서 삭제된 세션(404)도 로컬 정리를 허용합니다.
                if (error != null && (error as? com.jules.code.data.remote.JulesApiException)?.statusCode != 404) {
                    throw error
                }
            }
            timeline.deleteSession(sessionId)
            if (_selectedSessionId.value == sessionId) {
                closeSession()
                _selectedSessionId.value = null
                _selectedSessionDetail.value = null
                _activities.value = emptyList()
            }
            showMessage(R.string.message_session_deleted)
            onDeleted()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            timeline.rollbackDeletion(sessionId)
            showMessage(R.string.message_delete_failed, error.message.orEmpty())
          } finally {
            _deletingSessionIds.update { it - sessionId }
          }
        }
    }

    /** 선택한 활동만 단건 API로 갱신하고 기존 타임라인과 합칩니다. */
    fun refreshActivity(sessionId: String, activityId: String, force: Boolean = false) {
        if (activityId in _loadingActivityIds.value) return
        val owner = activeApiKey.value ?: return
        if (owner.apiKey == DEMO_API_KEY) return
        _loadingActivityIds.update { it + activityId }
        _activityErrors.update { it - activityId }
        viewModelScope.launch {
          try {
            val cacheKey = "${owner.id}/$sessionId/$activityId"
            val result = loadedActivities[cacheKey]?.takeUnless { force }?.let { Result.success(it) }
                ?: activityLoadSemaphore.withPermit { api.getActivity(owner.apiKey, sessionId, activityId) }
            result.onSuccess { fresh ->
                if (activeApiKey.value?.id != owner.id || timeline.isDeleted(sessionId)) return@onSuccess
                val existing = timeline.cached(sessionId).orEmpty().find {
                    it.id == activityId || it.name.substringAfterLast('/') == activityId
                }
                val detail = fresh.copy(description = fresh.description.ifBlank { existing?.description.orEmpty() })
                loadedActivities[cacheKey] = detail
                val updated = timeline.cached(sessionId).orEmpty().map {
                    if (it.id == activityId || it.name.substringAfterLast('/') == activityId) detail else it
                }
                timeline.cacheActivities(sessionId, updated, _selectedSessionId.value)
                if (_selectedSessionId.value == sessionId) _activities.value = mergeActivitiesWithOptimistic(sessionId, updated)
            }.onFailure { error ->
                _activityErrors.update { it + (activityId to (error.message ?: "활동 조회 실패")) }
            }
          } finally { _loadingActivityIds.update { it - activityId } }
        }
    }

    /** 저장소 단건 조회로 브랜치와 메타데이터를 갱신합니다. */
    fun refreshSource(sourceName: String) {
        val owner = activeApiKey.value ?: return
        if (owner.apiKey == DEMO_API_KEY) return
        viewModelScope.launch {
            api.getSource(owner.apiKey, sourceName).onSuccess { source ->
                if (activeApiKey.value?.id == owner.id) {
                    dao.upsertSources(listOf(CachedSourceEntity(
                        name = source.name.ifBlank { sourceName },
                        id = source.id.ifBlank { sourceName.removePrefix("sources/") },
                        owner = source.githubRepo?.owner.orEmpty(),
                        repo = source.githubRepo?.repo.orEmpty(),
                        isPrivate = source.githubRepo?.isPrivate ?: false,
                        defaultBranch = source.defaultBranchName,
                        branchesCsv = source.availableBranches.joinToString(",")
                    )))
                }
            }.onFailure { showMessage(R.string.message_sources_failed, it.message.orEmpty()) }
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
            showMessage(R.string.message_template_required)
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
            showMessage(R.string.message_template_saved)
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
            showMessage(R.string.message_template_deleted)
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

    private suspend fun seedDemoData(demoKeyId: Long) {
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

        val currentDemoSessions = dao.observeSessions().first().filter { it.apiKeyId == demoKeyId || it.sessionId.startsWith("demo-") }
        val demoSeededKey = "demo_seeded_$demoKeyId"
        val wasSeeded = dao.observePreferences().first().any { it.prefKey == demoSeededKey && it.prefValue == "true" }
        if (currentDemoSessions.isEmpty() && !wasSeeded) {
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
                    isPinned = true,
                    apiKeyId = demoKeyId,
                    lastActivityTime = "2026-10-21T00:45:00Z",
                    lastOriginator = "agent",
                    completedStepsCount = 3,
                    totalStepsCount = 3
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
                    isPinned = false,
                    apiKeyId = demoKeyId,
                    lastActivityTime = "2026-10-21T01:15:00Z",
                    lastOriginator = "agent",
                    currentStepTitle = "단위 테스트 실행 및 GitHub PR 생성",
                    completedStepsCount = 2,
                    totalStepsCount = 3
                )
            )
            dao.upsertSessions(demoSessions)
        }
        dao.setPreference(AppPreferenceEntity(demoSeededKey, "true"))
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

    private fun JulesSession.toEntity(
        isPinned: Boolean,
        apiKeyId: Long,
        activities: List<JulesActivity>? = null,
        previousEntity: CachedSessionEntity? = null
    ): CachedSessionEntity {
        val pr = outputs.firstOrNull { it.pullRequest != null }?.pullRequest
        val cleanPrompt = prompt
        val cleanTitle = displayTitle.lineSequence().firstOrNull()?.take(80) ?: displayTitle

        val diag = if (activities != null) {
            diagnoseSessionHealth(this, activities)
        } else null

        return CachedSessionEntity(
            sessionId = sessionId,
            name = name.ifBlank { "sessions/$sessionId" },
            title = cleanTitle,
            prompt = cleanPrompt,
            state = state,
            repoDisplay = repoDisplay,
            branchDisplay = branchDisplay,
            prUrl = pr?.url ?: "",
            prTitle = pr?.title ?: "",
            createTime = createTime,
            updateTime = updateTime,
            isPinned = isPinned,
            apiKeyId = apiKeyId,
            lastActivityTime = diag?.lastActivityTime ?: previousEntity?.lastActivityTime.orEmpty(),
            lastOriginator = diag?.lastOriginator ?: previousEntity?.lastOriginator.orEmpty(),
            unansweredUserCount = diag?.unansweredUserCount ?: previousEntity?.unansweredUserCount ?: 0,
            currentStepTitle = diag?.currentStepTitle ?: previousEntity?.currentStepTitle.orEmpty(),
            completedStepsCount = diag?.completedStepsCount ?: previousEntity?.completedStepsCount ?: 0,
            totalStepsCount = diag?.totalStepsCount ?: previousEntity?.totalStepsCount ?: 0
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

        /** 에이전트 마지막 응답 이후 무응답 멈춤(Stalled) 의심 임계값: 5분 */
        const val STALLED_AGENT_THRESHOLD_MS = 5L * 60L * 1000L

        /** 사용자의 후속 메시지 전송 이후 에이전트 무응답 임계값: 2분 */
        const val STALLED_USER_MSG_THRESHOLD_MS = 2L * 60L * 1000L

        /**
         * API 키별 작업 소모량 및 세션 상태 요약 데이터 모델입니다.
         */
        data class ApiKeyUsageSummary(
            val apiKeyId: Long,
            val label: String,
            val tier: PlanTierLimits,
            val used24h: Int,
            val remainingDaily: Int,
            val concurrentUsed: Int,
            val remainingConcurrent: Int,
            val totalSessions: Int,
            val completedCount: Int,
            val failedCount: Int,
            val stalledCount: Int
        )

        /** 표시 언어와 관계없이 세션 지연의 판정 원인을 구분합니다. */
        enum class SessionHealthReason { NONE, NORMAL, PLAN_STALLED, UNANSWERED_MESSAGES, AGENT_STALLED }

        /** 판정 결과와 원시 수치만 전달하며 문장과 시간 단위는 화면에서 번역합니다. */
        data class SessionHealthDiagnostic(
            val isStalled: Boolean,
            val reason: SessionHealthReason,
            val silentDurationMinutes: Long,
            val unansweredUserCount: Int,
            val lastActivityTime: String,
            val lastOriginator: String,
            val currentStepTitle: String,
            val completedStepsCount: Int,
            val totalStepsCount: Int,
            val sessionState: String = ""
        )

        /**
         * 특정 API 키에 속한 세션 목록으로부터 일일 소모량(24h), 동시 실행 슬롯, 완료/실패/응답지연 통계를 계산합니다.
         */
        fun computeApiKeyUsage(
            keyEntity: ApiKeyEntity,
            keySessions: List<CachedSessionEntity>,
            tierCodeOverride: String? = null,
            nowMillis: Long = System.currentTimeMillis()
        ): ApiKeyUsageSummary {
            val tier = getTierLimits(tierCodeOverride ?: keyEntity.planTier)
            val used24h = countRolling24hSessions(keySessions, nowMillis)
            val remainingDaily = (tier.dailyLimit - used24h).coerceAtLeast(0)
            val concurrentUsed = countConcurrentActiveSessions(keySessions)
            val remainingConcurrent = (tier.concurrentLimit - concurrentUsed).coerceAtLeast(0)
            val completed = keySessions.count { it.state.equals("COMPLETED", ignoreCase = true) }
            val failed = keySessions.count { it.state.equals("FAILED", ignoreCase = true) }
            val stalled = keySessions.count { isCachedSessionStalled(it, nowMillis) }

            return ApiKeyUsageSummary(
                apiKeyId = keyEntity.id,
                label = keyEntity.label,
                tier = tier,
                used24h = used24h,
                remainingDaily = remainingDaily,
                concurrentUsed = concurrentUsed,
                remainingConcurrent = remainingConcurrent,
                totalSessions = keySessions.size,
                completedCount = completed,
                failedCount = failed,
                stalledCount = stalled
            )
        }

        /**
         * 캐시된 세션 엔티티 기준으로 현재 응답 지연/멈춤(Stalled) 상태인지 판별합니다.
         */
        fun isCachedSessionStalled(
            session: CachedSessionEntity,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            if (session.state.uppercase() !in listOf("IN_PROGRESS", "PLANNING", "QUEUED")) return false
            val refIso = session.lastActivityTime.ifBlank { session.createTime }
            val refMillis = parseActivityTimestamp(refIso)
            if (refMillis <= 0L || refMillis > nowMillis) return false
            val elapsed = nowMillis - refMillis
            return if (session.unansweredUserCount > 0 || session.lastOriginator.equals("user", ignoreCase = true)) {
                elapsed >= STALLED_USER_MSG_THRESHOLD_MS
            } else {
                elapsed >= STALLED_AGENT_THRESHOLD_MS
            }
        }

        /**
         * 세션과 액티비티 타임라인을 분석하여 왜 작업이 진행되지 않거나 묵묵부답인지 정확히 진단합니다.
         * (주의: Jules API의 `session.updateTime`은 단순 조회 시에도 현재 시각으로 갱신되므로 반드시 마지막 Activity 생성 시각과 비교해야 합니다.)
         */
        fun diagnoseSessionHealth(
            session: JulesSession?,
            activities: List<JulesActivity>,
            nowMillis: Long = System.currentTimeMillis()
        ): SessionHealthDiagnostic {
            if (session == null) {
                return SessionHealthDiagnostic(
                    isStalled = false,
                    reason = SessionHealthReason.NONE,
                    silentDurationMinutes = 0L,
                    unansweredUserCount = 0,
                    lastActivityTime = "",
                    lastOriginator = "",
                    currentStepTitle = "",
                    completedStepsCount = 0,
                    totalStepsCount = 0
                )
            }

            val sorted = sortActivitiesChronologically(activities)
            val allPlans = sorted.mapNotNull { it.planGenerated?.plan }
            val latestPlan = allPlans.lastOrNull()
            val steps = latestPlan?.steps.orEmpty()
            val totalSteps = steps.size
            val completedSteps = steps.count { it.state.equals("COMPLETED", ignoreCase = true) }
            val currentStep = steps.firstOrNull { !it.state.equals("COMPLETED", ignoreCase = true) }
            val currentStepTitle = currentStep?.displayTitle.orEmpty()

            val lastAct = sorted.lastOrNull()
            val lastActTime = lastAct?.createTime?.ifBlank { session.createTime } ?: session.createTime
            val lastOriginator = lastAct?.originator.orEmpty()

            // 마지막 에이전트 활동 이후 사용자가 보낸 미응답(Unanswered) 메시지 개수 및 최초 대기 시작 시각 계산
            val lastAgentIdx = sorted.indexOfLast { it.originator.equals("agent", ignoreCase = true) }
            val trailingUserActs = if (lastAgentIdx >= 0) {
                sorted.subList(lastAgentIdx + 1, sorted.size)
            } else {
                sorted
            }
            val unansweredUserMsgs = trailingUserActs.count {
                it.originator.equals("user", ignoreCase = true) && it.resolvedUserMessage != null
            }
            val hasPlanApprovedOnly = trailingUserActs.any { it.planApproved != null }

            val lastAgentTimeStr = if (lastAgentIdx >= 0) sorted[lastAgentIdx].createTime else session.createTime
            val lastAgentMillis = parseActivityTimestamp(lastAgentTimeStr).takeIf { it in 1..nowMillis } ?: nowMillis
            val lastEventMillis = parseActivityTimestamp(lastActTime).takeIf { it in 1..nowMillis } ?: lastAgentMillis

            val agentSilentMs = (nowMillis - lastAgentMillis).coerceAtLeast(0L)
            val lastEventSilentMs = (nowMillis - lastEventMillis).coerceAtLeast(0L)
            val silentMinutes = agentSilentMs / 60_000L

            val stateUpper = session.state.uppercase()
            val isWorkingState = stateUpper in listOf("IN_PROGRESS", "PLANNING", "QUEUED")

            val isStalled = isWorkingState && (
                (unansweredUserMsgs > 0 && lastEventSilentMs >= STALLED_USER_MSG_THRESHOLD_MS) ||
                    (agentSilentMs >= STALLED_AGENT_THRESHOLD_MS)
                )

            if (!isStalled) {
                return SessionHealthDiagnostic(
                    isStalled = false,
                    reason = SessionHealthReason.NORMAL,
                    silentDurationMinutes = silentMinutes,
                    unansweredUserCount = unansweredUserMsgs,
                    lastActivityTime = lastActTime,
                    lastOriginator = lastOriginator,
                    currentStepTitle = currentStepTitle,
                    completedStepsCount = completedSteps,
                    totalStepsCount = totalSteps,
                    sessionState = session.state
                )
            }

            val hasAnyDiffOrBash = sorted.any { it.artifacts.isNotEmpty() || it.progressUpdated != null }
            val reason = when {
                latestPlan != null && !hasAnyDiffOrBash && (hasPlanApprovedOnly || unansweredUserMsgs > 0) -> SessionHealthReason.PLAN_STALLED
                unansweredUserMsgs > 0 -> SessionHealthReason.UNANSWERED_MESSAGES
                else -> SessionHealthReason.AGENT_STALLED
            }

            return SessionHealthDiagnostic(
                isStalled = true,
                reason = reason,
                silentDurationMinutes = silentMinutes,
                unansweredUserCount = unansweredUserMsgs,
                lastActivityTime = lastActTime,
                lastOriginator = lastOriginator,
                currentStepTitle = currentStepTitle,
                completedStepsCount = completedSteps,
                totalStepsCount = totalSteps,
                sessionState = session.state
            )
        }

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
         * Context로부터 `JulesViewModel`을 생성하는 Factory입니다.
         */
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = JulesDatabase.getInstance(context)
                return JulesViewModel(db.dao(), JulesApiClient(contentDirectory = java.io.File(context.cacheDir, "activity-content"))) as T
            }
        }
    }
}


typealias ApiKeyUsageSummary = JulesViewModel.Companion.ApiKeyUsageSummary
typealias SessionHealthDiagnostic = JulesViewModel.Companion.SessionHealthDiagnostic
