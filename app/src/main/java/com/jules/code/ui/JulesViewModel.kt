package com.jules.code.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jules.code.data.local.*
import com.jules.code.data.model.*
import com.jules.code.data.remote.JulesApiClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * Jules 앱의 전체 상태와 비즈니스 로직을 관리하는 메인 ViewModel입니다.
 * API 키별 작업 소모량 분리 집계, 세션 무응답/스턱(Stalled) 자동 진단 및 복구 액션,
 * 세션/소스 동기화, 계획 승인, 메시지 전송, 데모 모드, 스케줄 템플릿을 통합 처리합니다.
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
        keys.associate { keyEntity ->
            val keySessions = allSess.filter {
                it.apiKeyId == keyEntity.id || (keyEntity.isActive && it.apiKeyId == 0L)
            }
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

    /** 로딩 상태 표시 플래그 */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 세션 메시지 전송 후 에이전트 응답 대기 로딩 플래그 */
    private val _isAwaitingResponse = MutableStateFlow(false)
    val isAwaitingResponse: StateFlow<Boolean> = _isAwaitingResponse.asStateFlow()

    /** 사용자 알림/오류 스낵바 메시지 */
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /** 데모 모드 활성화 여부 (API 키가 `"DEMO"`이거나 사용자가 데모 체험 선택 시) */
    val isDemoMode: StateFlow<Boolean> = activeApiKey
        .map { it?.apiKey == DEMO_API_KEY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var pollingJob: Job? = null
    private var refreshJob: Job? = null
    private val sessionCacheMutex = Mutex()
    private val deletedSessionIds = mutableSetOf<String>()
    private val _deletingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    /** 삭제 요청 중인 세션의 중복 실행과 버튼 조작을 막습니다. */
    val deletingSessionIds: StateFlow<Set<String>> = _deletingSessionIds.asStateFlow()

    /**
     * 채팅 전송 직후 서버 `listActivities` 응답에 아직 반영되지 않았더라도
     * 화면에서 0ms(즉시)로 사라지지 않고 유지되도록 관리하는 스레드 안전 낙관적(Optimistic) 사용자 메시지 버퍼입니다.
     */
    private val pendingOptimisticMessages = ConcurrentHashMap<String, CopyOnWriteArrayList<JulesActivity>>()

    /** 세션별 메모리 내 액티비티 캐시 (재진입 시 0ms 렌더링, 스레드 안전) */
    private val inMemoryActivitiesCache = ConcurrentHashMap<String, List<JulesActivity>>()

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
        _snackbarMessage.value = msg
    }

    /**
     * 새로운 API 키를 Room에 저장하고 즉시 활성화합니다.
     */
    fun saveApiKey(label: String, rawKey: String, tierCode: String? = null) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            showMessage("API 키를 입력해주세요.")
            return
        }
        val resolvedTier = tierCode ?: preferences.value["jules_plan_tier"] ?: "FREE"
        viewModelScope.launch {
            dao.saveAndActivateApiKey(label.ifBlank { "Jules API Key" }, trimmed, resolvedTier)
            showMessage("API 키가 저장 및 활성화되었습니다.")
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
            showMessage("데모 모드가 활성화되었습니다. 실제 API 키는 설정에서 언제든 등록 가능합니다.")
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
            showMessage("API 키 프로필을 전환했습니다.")
        }
    }

    /**
     * 저장된 API 키 프로필을 삭제합니다.
     */
    fun deleteApiKey(id: Long) {
        viewModelScope.launch {
            dao.deleteSessionsByApiKey(id)
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
                val otherKeys = apiKeys.value.filter { it.id != activeEntity.id && it.apiKey != DEMO_API_KEY }
                val otherKeysDeferred = otherKeys.map { other ->
                    async { runCatching { syncSessionsForApiKey(other) } }
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
                    showMessage("저장소 동기화 실패: ${err.message}")
                }

                activeSessionsDeferred.await().onFailure { err ->
                    showMessage("동기화 실패: ${err.message}")
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
        api.listAllSessions(keyEntity.apiKey).map { remoteSessions ->
            val existingById = allSessions.value.associateBy { it.sessionId }
            // 활성 세션(최대 10개)에 대해 병렬로 액티비티를 조회하여 실제 에이전트 마지막 활동 시각과 무응답 상태를 진단
            val aliveSessions = remoteSessions.filter { it.isAlive }.take(10)
            val activitiesBySessionId = aliveSessions.map { s ->
                async {
                    val acts = api.listAllActivities(keyEntity.apiKey, s.sessionId).getOrNull()
                    if (acts != null) {
                        cacheActivities(s.sessionId, acts)
                    }
                    s.sessionId to acts
                }
            }.awaitAll().toMap()

            val entities = remoteSessions.map { s ->
                val prev = existingById[s.sessionId]
                val freshActs = activitiesBySessionId[s.sessionId] ?: inMemoryActivitiesCache[s.sessionId]
                s.toEntity(
                    isPinned = prev?.isPinned ?: false,
                    apiKeyId = keyEntity.id,
                    activities = freshActs,
                    previousEntity = prev
                )
            }
            cacheSessions(entities)
        }
    }

    /** 삭제와 캐시 쓰기를 직렬화하여 늦은 응답이 삭제한 세션을 복구하지 않게 합니다. */
    private suspend fun cacheSessions(items: List<CachedSessionEntity>) = sessionCacheMutex.withLock {
        dao.upsertSessions(items.filterNot { it.sessionId in deletedSessionIds })
    }

    /** 메모리와 디스크의 활동 캐시를 삭제된 세션을 제외하고 함께 갱신합니다. */
    private suspend fun cacheActivities(sessionId: String, items: List<JulesActivity>) = sessionCacheMutex.withLock {
        if (sessionId !in deletedSessionIds) {
            // 전체 페이지의 Base64 첨부 직렬화도 UI 스레드 밖에서 처리합니다.
            val activitiesJson = withContext(Dispatchers.Default) { api.json.encodeToString(items) }
            dao.upsertCachedActivities(CachedSessionActivitiesEntity(sessionId, activitiesJson))
            inMemoryActivitiesCache[sessionId] = items
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
        val activeEntity = activeApiKey.value
        val key = activeEntity?.apiKey
        if (activeEntity == null || key.isNullOrBlank()) {
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
        val selectedSource = sources.value.find { it.name == sourceName }
        val repoKey = selectedSource?.let { "${it.owner}_${it.repo}".lowercase(java.util.Locale.ROOT) } ?: "repoless"
        val setupScript = currentPrefs["env_script_$repoKey"].orEmpty().trim()
        val envVars = currentPrefs["env_vars_$repoKey"].orEmpty().trim()
        val repoMemory = currentPrefs["repo_memory_$repoKey"].orEmpty().trim()
        val cleanBasePrompt = stripInternalDirectives(prompt.trim())
        val enrichedPrompt = buildEnrichedPrompt(
            basePrompt = cleanBasePrompt,
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
                    val initialUserAct = JulesActivity(
                        id = "optimistic-init-${created.sessionId}",
                        originator = "user",
                        description = if (responseLangCode == "ko") "작업 프롬프트 제출" else "Task Prompt Submitted",
                        createTime = created.createTime.ifBlank { java.time.Instant.now().toString() },
                        userMessaged = MessageEvent(message = cleanBasePrompt)
                    )
                    pendingOptimisticMessages.getOrPut(created.sessionId) { CopyOnWriteArrayList() }.add(initialUserAct)
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
        val cleanPrompt = stripInternalDirectives(session.prompt)
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
            modelId = preferences.value["jules_model_id"] ?: "GEMINI_3_1_PRO",
            enableCritic = preferences.value["enable_critic_agent"] != "false",
            enableCiFixer = preferences.value["enable_ci_fixer"] == "true",
            onCreated = onCreated
        )
    }

    /**
     * 특정 세션을 선택하여 로컬/메모리 캐시를 0ms로 즉시 표시하고,
     * 병렬(Parallel) 백그라운드 스트림 폴링을 시작합니다.
     */
    fun openSession(sessionId: String) {
        if (sessionId in deletedSessionIds) return
        val isSwitchingSession = _selectedSessionId.value != sessionId
        if (isSwitchingSession) {
            _isAwaitingResponse.value = false
        }
        _selectedSessionId.value = sessionId
        if (isSwitchingSession && _selectedSessionDetail.value?.sessionId != sessionId) {
            _selectedSessionDetail.value = null
        }

        // 1. 메모리 내 세션 리스트 및 액티비티 캐시에서 즉시(0ms) 바인딩하여 빈 화면 대기 제거
        allSessions.value.find { it.sessionId == sessionId }?.let { cachedEntity ->
            if (isSwitchingSession || _selectedSessionDetail.value == null) {
                _selectedSessionDetail.value = cachedEntity.toModel()
            }
        }
        val memActs = inMemoryActivitiesCache[sessionId]
        if (memActs != null) {
            _activities.value = mergeActivitiesWithOptimistic(sessionId, memActs)
        } else if (isSwitchingSession) {
            _activities.value = pendingOptimisticMessages[sessionId]?.toList() ?: emptyList()
        }

        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            // 2. Room DB에 캐시된 이전 액티비티가 있으면 네트워크 응답 전에 먼저 로드
            if (memActs == null) {
                dao.getCachedActivities(sessionId)?.let { cachedRow ->
                    runCatching {
                        withContext(Dispatchers.Default) {
                            api.json.decodeFromString<List<JulesActivity>>(cachedRow.activitiesJson)
                        }
                    }.onSuccess { diskActs ->
                        if (diskActs.isNotEmpty() && _selectedSessionId.value == sessionId) {
                            inMemoryActivitiesCache[sessionId] = diskActs
                            _activities.value = mergeActivitiesWithOptimistic(sessionId, diskActs)
                        }
                    }
                }
            }

            // 3. 실시간 고속 폴링 (진행 중이거나 낙관적 메시지/에이전트 응답 대기 중에는 고속 간격)
            while (true) {
                loadSessionDetailAndActivities(sessionId)
                val currentSession = _selectedSessionDetail.value
                val currentState = currentSession?.state ?: ""
                val hasPending = !pendingOptimisticMessages[sessionId].isNullOrEmpty()
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
                if (inMemoryActivitiesCache[sessionId].isNullOrEmpty()) {
                    val demoActs = buildDemoActivities(cached)
                    inMemoryActivitiesCache[sessionId] = demoActs
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
            if (activeApiKey.value?.id != activeEntity.id || sessionId in deletedSessionIds) return@coroutineScope
            val rawServerActs = activitiesResult.getOrNull()
            if (rawServerActs != null) {
                val merged = mergeActivitiesWithOptimistic(sessionId, rawServerActs)
                if (_selectedSessionId.value == sessionId) {
                    _activities.value = merged
                }
                cacheActivities(sessionId, rawServerActs)
            }

            sessionResult.onSuccess { session ->
                if (_selectedSessionId.value == sessionId) {
                    _selectedSessionDetail.value = session
                }
                val prev = dao.getCachedSession(sessionId)
                val pinned = prev?.isPinned ?: false
                val ownerKeyId = prev?.apiKeyId?.takeIf { it > 0L } ?: activeEntity.id
                cacheSessions(
                    listOf(
                        session.toEntity(
                            isPinned = pinned,
                            apiKeyId = ownerKeyId,
                            activities = rawServerActs ?: inMemoryActivitiesCache[sessionId],
                            previousEntity = prev
                        )
                    )
                )
            }
            sessionResult.onFailure { showMessage("세션 조회 실패: ${it.message}") }
            activitiesResult.onFailure { showMessage("활동 조회 실패: ${it.message}") }
        }
    }

    /**
     * 서버에서 내려온 액티비티 목록에서 프롬프트 내부 컨텍스트 꼬리표(`[Jules Agent Execution Context]`, `[언어 설정: ...]`)를
     * 깔끔하게 제거하여 표시하고, 아직 서버 인덱싱 전인 낙관적(Optimistic) 사용자 채팅을 유지합니다.
     */
    internal fun mergeActivitiesWithOptimistic(
        sessionId: String,
        serverActivities: List<JulesActivity>
    ): List<JulesActivity> {
        val cleanedServer = serverActivities.map { act ->
            val rawUser = act.resolvedUserMessage
            if (rawUser != null) {
                val cleanMsg = stripInternalDirectives(rawUser)
                act.copy(
                    userMessaged = MessageEvent(message = cleanMsg),
                    userMessage = null
                )
            } else {
                act
            }
        }

        val pendingList = pendingOptimisticMessages[sessionId]
        val combined = if (pendingList.isNullOrEmpty()) {
            cleanedServer
        } else {
            val serverUserTexts = cleanedServer.mapNotNull { it.resolvedUserMessage?.trim() }.toSet()
            pendingList.removeAll { opt ->
                val optText = opt.resolvedUserMessage?.trim().orEmpty()
                optText.isEmpty() || serverUserTexts.any { srv -> srv == optText || srv.startsWith(optText) }
            }
            if (pendingList.isEmpty()) {
                cleanedServer
            } else {
                cleanedServer + pendingList
            }
        }

        // 항상 시간순(과거 -> 최신 오름차순)으로 안정적으로 정렬하여 반환
        val sorted = sortActivitiesChronologically(combined)

        // 에이전트 응답이 도착했거나 세션이 종료된 경우에만 응답 대기 로딩 해제
        if (_selectedSessionId.value == sessionId && _isAwaitingResponse.value) {
            val hasPending = pendingOptimisticMessages[sessionId]?.isNotEmpty() == true
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
     * 전송 버튼을 누르는 즉시(0ms) 낙관적(Optimistic) UI로 타임라인에 채팅 버블을 먼저 추가하고,
     * 백그라운드로 API 전송 및 고속 폴링을 수행합니다.
     */
    fun sendMessage(sessionId: String, message: String) {
        val cleanInput = message.trim()
        if (cleanInput.isEmpty()) return
        val key = activeApiKey.value?.apiKey ?: return
        val langCode = JulesLocaleManager.resolveLocale(preferences.value["app_language"]).language
        val enrichedMessage = buildLocalizedFollowUpMessage(cleanInput, langCode)

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
                val updated = (inMemoryActivitiesCache[sessionId] ?: current).toMutableList()
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
                inMemoryActivitiesCache[sessionId] = sorted
                if (_selectedSessionId.value == sessionId) {
                    _activities.value = sorted
                    _isAwaitingResponse.value = false
                }
            }
            return
        }

        val sessionPending = pendingOptimisticMessages.getOrPut(sessionId) { CopyOnWriteArrayList() }
        sessionPending.add(optimisticUserAct)
        _activities.value = sortActivitiesChronologically(_activities.value + optimisticUserAct)

        // 2. 백그라운드 비동기 전송 및 즉시 폴링 루프 깨우기
        viewModelScope.launch {
            api.sendMessage(key, sessionId, enrichedMessage)
                .onSuccess {
                    if (_selectedSessionId.value == sessionId && activeApiKey.value?.apiKey == key) openSession(sessionId)
                }
                .onFailure { err ->
                    pendingOptimisticMessages[sessionId]?.removeAll { it.id == optimisticUserAct.id }
                    if (_selectedSessionId.value == sessionId) {
                        _activities.value = sortActivitiesChronologically(_activities.value.filterNot { it.id == optimisticUserAct.id })
                        _isAwaitingResponse.value = false
                    }
                    showMessage("메시지 전송 실패: ${err.message}")
                }
        }
    }

    /**
     * 세션을 삭제합니다.
     */
    fun deleteSession(sessionId: String, onDeleted: () -> Unit = {}) {
        if (sessionId in _deletingSessionIds.value || sessionId in deletedSessionIds) return
        _deletingSessionIds.update { it + sessionId }
        viewModelScope.launch {
          try {
            val cached = dao.getCachedSession(sessionId)
            val ownerId = cached?.apiKeyId?.takeIf { it != 0L } ?: activeApiKey.value?.id
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
            sessionCacheMutex.withLock {
                deletedSessionIds.add(sessionId)
                dao.deleteSessionCache(sessionId)
                inMemoryActivitiesCache.remove(sessionId)
                pendingOptimisticMessages.remove(sessionId)
            }
            if (_selectedSessionId.value == sessionId) {
                closeSession()
                _selectedSessionId.value = null
                _selectedSessionDetail.value = null
                _activities.value = emptyList()
            }
            showMessage("세션이 삭제되었습니다.")
            onDeleted()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            sessionCacheMutex.withLock { deletedSessionIds.remove(sessionId) }
            showMessage("세션 삭제 실패: ${error.message}")
          } finally {
            _deletingSessionIds.update { it - sessionId }
          }
        }
    }

    /** 선택한 활동만 단건 API로 갱신하고 기존 타임라인과 합칩니다. */
    fun refreshActivity(sessionId: String, activityId: String) {
        val owner = activeApiKey.value ?: return
        if (owner.apiKey == DEMO_API_KEY) return
        viewModelScope.launch {
            api.getActivity(owner.apiKey, sessionId, activityId).onSuccess { fresh ->
                if (activeApiKey.value?.id != owner.id || sessionId in deletedSessionIds) return@onSuccess
                val updated = inMemoryActivitiesCache[sessionId].orEmpty().map {
                    if (it.id == activityId || it.name.substringAfterLast('/') == activityId) fresh else it
                }
                cacheActivities(sessionId, updated)
                if (_selectedSessionId.value == sessionId) _activities.value = mergeActivitiesWithOptimistic(sessionId, updated)
            }.onFailure { showMessage("활동 새로고침 실패: ${it.message}") }
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
            }.onFailure { showMessage("저장소 조회 실패: ${it.message}") }
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
        val cleanPrompt = stripInternalDirectives(prompt)
        val cleanTitle = stripInternalDirectives(displayTitle).lineSequence().firstOrNull()?.take(80) ?: displayTitle

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

        /**
         * 세션의 무응답/스턱(Stalled) 상태 및 원인 진단 결과 데이터 모델입니다.
         */
        data class SessionHealthDiagnostic(
            val isStalled: Boolean,
            val reasonTitleKo: String,
            val reasonDetailKo: String,
            val reasonTitleEn: String,
            val reasonDetailEn: String,
            val silentDurationMinutes: Long,
            val unansweredUserCount: Int,
            val lastActivityTime: String,
            val lastOriginator: String,
            val currentStepTitle: String,
            val completedStepsCount: Int,
            val totalStepsCount: Int
        ) {
            /**
             * 포맷된 경과 시간 문자열(예: "4시간 5분", "12분")을 반환합니다.
             */
            fun formatSilentDuration(isKo: Boolean = true): String {
                val hours = silentDurationMinutes / 60
                val mins = silentDurationMinutes % 60
                return if (isKo) {
                    when {
                        hours > 0 && mins > 0 -> "${hours}시간 ${mins}분"
                        hours > 0 -> "${hours}시간"
                        else -> "${mins.coerceAtLeast(1)}분"
                    }
                } else {
                    when {
                        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                        hours > 0 -> "${hours}h"
                        else -> "${mins.coerceAtLeast(1)}m"
                    }
                }
            }
        }

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
                    reasonTitleKo = "",
                    reasonDetailKo = "",
                    reasonTitleEn = "",
                    reasonDetailEn = "",
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
                    reasonTitleKo = "정상 동작 중",
                    reasonDetailKo = "",
                    reasonTitleEn = "Operating Normally",
                    reasonDetailEn = "",
                    silentDurationMinutes = silentMinutes,
                    unansweredUserCount = unansweredUserMsgs,
                    lastActivityTime = lastActTime,
                    lastOriginator = lastOriginator,
                    currentStepTitle = currentStepTitle,
                    completedStepsCount = completedSteps,
                    totalStepsCount = totalSteps
                )
            }

            val durationKo = SessionHealthDiagnostic(
                true, "", "", "", "", silentMinutes, unansweredUserMsgs, lastActTime, lastOriginator, currentStepTitle, completedSteps, totalSteps
            ).formatSilentDuration(true)
            val durationEn = SessionHealthDiagnostic(
                true, "", "", "", "", silentMinutes, unansweredUserMsgs, lastActTime, lastOriginator, currentStepTitle, completedSteps, totalSteps
            ).formatSilentDuration(false)

            val hasAnyDiffOrBash = sorted.any { it.artifacts.isNotEmpty() || it.progressUpdated != null }

            val (titleKo, detailKo, titleEn, detailEn) = when {
                latestPlan != null && !hasAnyDiffOrBash && (hasPlanApprovedOnly || unansweredUserMsgs > 0) -> {
                    val stepInfoKo = if (currentStepTitle.isNotBlank()) "1단계('$currentStepTitle')" else "첫 실행 단계"
                    val msgInfoKo = if (unansweredUserMsgs > 0) " • 보낸 메시지 ${unansweredUserMsgs}건도 큐에서 대기 중입니다." else ""
                    val stepInfoEn = if (currentStepTitle.isNotBlank()) "Step 1 ('$currentStepTitle')" else "the first execution step"
                    val msgInfoEn = if (unansweredUserMsgs > 0) " • $unansweredUserMsgs follow-up message(s) are also queued without reply." else ""
                    listOf(
                        "실행 계획 승인 후 클라우드 VM 응답 멈춤 (${durationKo}째 무응답)",
                        "계획(${totalSteps}단계)은 승인되었으나 에이전트가 $stepInfoKo 에서 코드 패치나 터미널 로그를 생성하지 못한 채 멈춰 있습니다$msgInfoKo (대규모 프롬프트 컨텍스트 타임아웃 또는 백엔드 워커 교착 상태)",
                        "Cloud VM Stalled After Plan Approval ($durationEn silent)",
                        "Plan ($totalSteps steps) was approved, but the agent stalled at $stepInfoEn without emitting any code diffs or bash outputs$msgInfoEn"
                    )
                }
                unansweredUserMsgs > 0 -> {
                    listOf(
                        "후속 요청 ${unansweredUserMsgs}건 무응답 (${durationKo} 경과)",
                        "사용자가 보낸 후속 메시지가 ${unansweredUserMsgs}건 접수되었으나 에이전트가 ${durationKo} 동안 응답 액티비티를 생성하지 않고 있습니다.",
                        "$unansweredUserMsgs Follow-up Message(s) Unanswered ($durationEn elapsed)",
                        "Your $unansweredUserMsgs message(s) were recorded, but the agent has not emitted any response activity for $durationEn."
                    )
                }
                else -> {
                    val stepDescKo = if (currentStepTitle.isNotBlank()) " (현재 단계: $currentStepTitle)" else ""
                    val stepDescEn = if (currentStepTitle.isNotBlank()) " (Current step: $currentStepTitle)" else ""
                    listOf(
                        "에이전트 작업 지연 / 무응답 (${durationKo} 경과)",
                        "세션 상태는 '${translateState(session.state)}'이지만 마지막 이벤트 이후 ${durationKo} 동안 새로운 진행 로그가 없습니다$stepDescKo.",
                        "Agent Task Stalled / Unresponsive ($durationEn elapsed)",
                        "Session state is '${session.state}', but no new activity has been recorded for $durationEn$stepDescEn."
                    )
                }
            }

            return SessionHealthDiagnostic(
                isStalled = true,
                reasonTitleKo = titleKo,
                reasonDetailKo = detailKo,
                reasonTitleEn = titleEn,
                reasonDetailEn = detailEn,
                silentDurationMinutes = silentMinutes,
                unansweredUserCount = unansweredUserMsgs,
                lastActivityTime = lastActTime,
                lastOriginator = lastOriginator,
                currentStepTitle = currentStepTitle,
                completedStepsCount = completedSteps,
                totalStepsCount = totalSteps
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
            directives.add(MEDIA_DIRECTIVE)

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
            return "$message\n\n$langRule\n$MEDIA_DIRECTIVE"
        }

        /** 미디어 요청은 실제 API 첨부로 전달하도록 에이전트에 안내합니다. */
        private const val MEDIA_DIRECTIVE = "이미지·영상 요청 시 사용 가능한 내부 검증/브라우저 도구로 캡처·녹화하고 artifacts.media에 mimeType과 Base64 data를 첨부하세요. 파일 경로나 마크다운만 답하지 마세요. 도구가 없거나 첨부가 불가능하면 그 한계를 설명하고 결과를 꾸며내지 마세요."

        /**
         * 타임라인에 표시할 사용자 메시지에서 내부 자동 주입 지시어(`[Jules Agent Execution Context]`, `[언어 설정: ...]`)를 제거하여
         * 사용자가 실제로 입력한 순수 프롬프트만 깔끔하게 반환합니다.
         */
        fun stripInternalDirectives(rawMessage: String): String {
            return rawMessage
                .substringBefore("\n\n---\n[Jules Agent Execution Context]")
                .substringBefore("\n\n[언어 설정:")
                .substringBefore("\n\n[Language Setting:")
                .trim()
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


typealias ApiKeyUsageSummary = JulesViewModel.Companion.ApiKeyUsageSummary
typealias SessionHealthDiagnostic = JulesViewModel.Companion.SessionHealthDiagnostic
