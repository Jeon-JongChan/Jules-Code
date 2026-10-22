package com.jules.code.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jules.code.R
import com.jules.code.data.local.CachedSourceEntity
import com.jules.code.ui.JulesLocaleManager
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.theme.*
import com.jules.code.ui.JulesBrowserUtils

/**
 * 전체 코딩 세션을 검색어 및 상태(진행중, 승인대기, 완료, 실패)별로 필터링하고 관리하는 화면입니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    viewModel: JulesViewModel,
    onOpenSession: (String) -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var stateFilter by remember { mutableStateOf("ALL") }
    val nowMillis = System.currentTimeMillis()
    val stalledCount = remember(sessions, nowMillis) {
        sessions.count { JulesViewModel.isCachedSessionStalled(it, nowMillis) }
    }

    val filtered = remember(sessions, searchQuery, stateFilter, nowMillis) {
        sessions.filter { s ->
            val matchesQuery = searchQuery.isBlank() ||
                s.title.contains(searchQuery, ignoreCase = true) ||
                s.prompt.contains(searchQuery, ignoreCase = true) ||
                s.repoDisplay.contains(searchQuery, ignoreCase = true)
            val matchesState = when (stateFilter) {
                "STALLED" -> JulesViewModel.isCachedSessionStalled(s, nowMillis)
                "ACTIVE" -> s.state.uppercase() in listOf("QUEUED", "PLANNING", "IN_PROGRESS")
                "APPROVAL" -> s.state.uppercase() in listOf("AWAITING_PLAN_APPROVAL", "AWAITING_USER_FEEDBACK")
                "COMPLETED" -> s.state.equals("COMPLETED", ignoreCase = true)
                "FAILED" -> s.state.equals("FAILED", ignoreCase = true)
                else -> true
            }
            matchesQuery && matchesState
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.sessions_search_placeholder), color = JulesTextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = JulesTextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = JulesSurface,
                    unfocusedContainerColor = JulesSurface,
                    focusedBorderColor = JulesBrandPurple,
                    unfocusedBorderColor = JulesBorder
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to stringResource(R.string.filter_all, sessions.size),
                    "STALLED" to stringResource(R.string.filter_stalled, stalledCount),
                    "ACTIVE" to stringResource(R.string.filter_active),
                    "APPROVAL" to stringResource(R.string.filter_approval),
                    "COMPLETED" to stringResource(R.string.filter_completed),
                    "FAILED" to stringResource(R.string.filter_failed)
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = stateFilter == code,
                        onClick = { stateFilter = code },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.empty_filtered_sessions_title),
                    subtitle = stringResource(R.string.empty_filtered_sessions_subtitle)
                )
            }
        } else {
            items(filtered, key = { it.sessionId }) { session ->
                SessionSummaryCard(
                    session = session,
                    onClick = { onOpenSession(session.sessionId) },
                    onPinToggle = { viewModel.togglePinSession(session.sessionId, session.isPinned) },
                    onDelete = { viewModel.deleteSession(session.sessionId) },
                    onNudge = { viewModel.nudgeStalledSession(session.sessionId) }
                )
            }
        }
    }
}

/**
 * Jules에 연결된 GitHub 저장소(Sources) 목록 조회, 브랜치 확인, GitHub App 연동 관리 및
 * 저장소별 환경 설정 스크립트(Environment Setup Script) / 환경변수 / Repository Memory(AGENTS.md) 설정을 제공하는 화면입니다.
 */
@Composable
fun SourcesScreen(viewModel: JulesViewModel) {
    val context = LocalContext.current
    val sources by viewModel.sources.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    var filterText by remember { mutableStateOf("") }
    var configTargetSource by remember { mutableStateOf<CachedSourceEntity?>(null) }

    val filteredSources = remember(sources, filterText) {
        sources.filter {
            filterText.isBlank() ||
                it.owner.contains(filterText, ignoreCase = true) ||
                it.repo.contains(filterText, ignoreCase = true)
        }
    }

    // 저장소별 Environment Setup Script / Env Vars / Repository Memory 설정 다이얼로그 (Jules 웹 Repo Configuration 대응)
    configTargetSource?.let { target ->
        val repoSlug = "${target.owner}_${target.repo}".lowercase(java.util.Locale.ROOT)
        var scriptText by remember(target.name) { mutableStateOf(prefs["env_script_$repoSlug"] ?: "") }
        var envVarsText by remember(target.name) { mutableStateOf(prefs["env_vars_$repoSlug"] ?: "") }
        var memoryText by remember(target.name) { mutableStateOf(prefs["repo_memory_$repoSlug"] ?: "") }

        AlertDialog(
            onDismissRequest = { configTargetSource = null },
            title = {
                Text(
                    text = stringResource(R.string.dialog_repo_config_title, "${target.owner}/${target.repo}"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.dialog_repo_config_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = JulesTextMuted
                    )
                    OutlinedTextField(
                        value = scriptText,
                        onValueChange = { scriptText = it },
                        label = { Text(stringResource(R.string.dialog_repo_script_label)) },
                        placeholder = { Text("npm ci && npx playwright install", fontSize = 12.sp) },
                        maxLines = 3,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = envVarsText,
                        onValueChange = { envVarsText = it },
                        label = { Text(stringResource(R.string.dialog_repo_env_label)) },
                        placeholder = { Text("NODE_ENV=test, CI=true", fontSize = 12.sp) },
                        maxLines = 2,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = memoryText,
                        onValueChange = { memoryText = it },
                        label = { Text(stringResource(R.string.dialog_repo_memory_label)) },
                        placeholder = { Text("AGENTS.md conventions...", fontSize = 12.sp) },
                        maxLines = 3,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updatePreference("env_script_$repoSlug", scriptText.trim())
                        viewModel.updatePreference("env_vars_$repoSlug", envVarsText.trim())
                        viewModel.updatePreference("repo_memory_$repoSlug", memoryText.trim())
                        viewModel.showMessage("${target.owner}/${target.repo} saved.")
                        configTargetSource = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JulesBrandPurple, contentColor = Color.White)
                ) {
                    Text(stringResource(R.string.dialog_save), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { configTargetSource = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            containerColor = JulesSurfaceElevated
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sources_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.sources_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = JulesTextMuted
                            )
                        }
                        IconButton(onClick = { viewModel.refreshAll() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = JulesPrimary)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            JulesBrowserUtils.openUrlInDefaultBrowser(context, "https://github.com/apps/google-labs-jules/installations/new")
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, JulesBorderBright),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.sources_github_btn),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text(stringResource(R.string.sources_filter_placeholder), color = JulesTextMuted) },
                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, tint = JulesTextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = JulesSurface,
                    unfocusedContainerColor = JulesSurface,
                    focusedBorderColor = JulesBrandPurple,
                    unfocusedBorderColor = JulesBorder
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (filteredSources.isEmpty()) {
            item {
                EmptyStateCard(
                    title = stringResource(R.string.empty_sources_title),
                    subtitle = stringResource(R.string.empty_sources_subtitle)
                )
            }
        } else {
            items(filteredSources, key = { it.name }) { src ->
                val repoSlug = "${src.owner}_${src.repo}".lowercase(java.util.Locale.ROOT)
                val hasConfiguredEnv = !prefs["env_script_$repoSlug"].isNullOrBlank() ||
                    !prefs["env_vars_$repoSlug"].isNullOrBlank() ||
                    !prefs["repo_memory_$repoSlug"].isNullOrBlank()
                SourceItemCard(
                    source = src,
                    hasConfiguredEnv = hasConfiguredEnv,
                    onRefresh = { viewModel.refreshSource(src.name) },
                    onConfigureEnv = { configTargetSource = src }
                )
            }
        }
    }
}

@Composable
/** 저장소 정보, 브랜치와 설정·새로고침 동작을 표시합니다. */
private fun SourceItemCard(
    source: CachedSourceEntity,
    hasConfiguredEnv: Boolean,
    onRefresh: () -> Unit,
    onConfigureEnv: () -> Unit
) {
    val context = LocalContext.current
    val branches = remember(source.branchesCsv) {
        source.branchesCsv.split(",").filter { it.isNotBlank() }
    }

    Surface(
        color = JulesSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, JulesBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = JulesBrandPurple.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.4f)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = JulesPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "${source.owner}/${source.repo}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = JulesTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    color = (if (source.isPrivate) JulesWarning else JulesSuccess).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, (if (source.isPrivate) JulesWarning else JulesSuccess).copy(alpha = 0.4f))
                ) {
                    Text(
                        text = if (source.isPrivate) "Private" else "Public",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.isPrivate) JulesWarning else JulesSuccess,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Branches:",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = JulesTextMuted
                )
                branches.forEach { b ->
                    val isDefault = b == source.defaultBranch
                    Surface(
                        color = if (isDefault) JulesSecondary.copy(alpha = 0.16f) else JulesSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (isDefault) JulesSecondary.copy(alpha = 0.4f) else JulesBorder)
                    ) {
                        Text(
                            text = if (isDefault) "$b (default)" else b,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDefault) JulesSecondary else JulesTextPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.source_refresh))
                }
                OutlinedButton(
                    onClick = onConfigureEnv,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (hasConfiguredEnv) JulesBrandPurple else JulesBorderBright),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (hasConfiguredEnv) JulesPrimary else JulesTextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (hasConfiguredEnv) stringResource(R.string.source_env_configured) else stringResource(R.string.source_env_configure),
                        fontSize = 11.sp,
                        color = if (hasConfiguredEnv) JulesPrimary else JulesTextPrimary
                    )
                }

                TextButton(
                    onClick = {
                        JulesBrowserUtils.openGitHubRepo(context, "${source.owner}/${source.repo}")
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.source_open_github), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Jules 웹의 Settings 화면(API 키 다중 프로필 관리, 요금제별 API 남은 작업량/한도 설정, 커밋 작성자 귀속 모드)을
 * Room DB 기반으로 구현한 설정 화면입니다.
 */
@Composable
fun SettingsScreen(viewModel: JulesViewModel) {
    val context = LocalContext.current
    val apiKeys by viewModel.apiKeys.collectAsState()
    val activeKey by viewModel.activeApiKey.collectAsState()
    val apiKeyUsages by viewModel.apiKeyUsages.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val prefs by viewModel.preferences.collectAsState()

    var newLabel by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var showNewKey by remember { mutableStateOf(false) }

    val commitAuthorMode = prefs["commit_author_mode"] ?: "CO_AUTHORED"
    val selectedLangCode = prefs["app_language"] ?: "SYSTEM"
    val selectedModelCode = prefs["selected_jules_model"] ?: "GEMINI_3_1_PRO"
    val notifyOnDone = (prefs["notify_on_session_event"] ?: "true") == "true"
    val selectedTierCode = activeKey?.planTier?.ifBlank { prefs["jules_plan_tier"] ?: "FREE" }
        ?: prefs["jules_plan_tier"] ?: "FREE"
    val currentTier = remember(selectedTierCode) { JulesViewModel.getTierLimits(selectedTierCode) }
    val used24h = remember(sessions) { JulesViewModel.countRolling24hSessions(sessions) }
    val remainingDaily = (currentTier.dailyLimit - used24h).coerceAtLeast(0)
    val concurrentUsed = remember(sessions) { JulesViewModel.countConcurrentActiveSessions(sessions) }
    val remainingConcurrent = (currentTier.concurrentLimit - concurrentUsed).coerceAtLeast(0)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 0. 앱 표시 언어(Localization) 설정 섹션 (스마트폰 기본 언어 자동 추적 또는 한국어/영어 수동 고정)
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorderBright),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = JulesPrimary)
                        Column {
                            Text(
                                text = stringResource(R.string.settings_language_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.settings_language_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = JulesTextMuted
                            )
                        }
                    }

                    listOf(
                        "SYSTEM" to stringResource(R.string.lang_option_system),
                        "ko" to stringResource(R.string.lang_option_ko),
                        "en" to stringResource(R.string.lang_option_en)
                    ).forEach { (code, label) ->
                        val isSelected = selectedLangCode == code
                        Surface(
                            color = if (isSelected) JulesBrandPurple.copy(alpha = 0.16f) else JulesSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) JulesBrandPurple else JulesBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updatePreference("app_language", code) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updatePreference("app_language", code) }
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. API 남은 작업량(Quota) 및 요금제 티어 설정 섹션
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorderBright),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = JulesPrimary)
                        Column {
                            Text(
                                text = stringResource(R.string.settings_quota_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.settings_quota_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = JulesTextMuted
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuotaDetailBox(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.quota_box_daily_title),
                            remaining = remainingDaily,
                            total = currentTier.dailyLimit,
                            unit = stringResource(R.string.quota_box_daily_unit),
                            accent = JulesSuccess
                        )
                        QuotaDetailBox(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.quota_box_concurrent_title),
                            remaining = remainingConcurrent,
                            total = currentTier.concurrentLimit,
                            unit = stringResource(R.string.quota_box_concurrent_unit),
                            accent = JulesSecondary
                        )
                    }

                    HorizontalDivider(color = JulesBorder)

                    Text(
                        text = stringResource(R.string.settings_select_tier),
                        style = MaterialTheme.typography.labelLarge,
                        color = JulesTextPrimary
                    )

                    JulesViewModel.PLAN_TIERS.forEach { tier ->
                        val isSelected = tier.code == currentTier.code
                        val tierLabel = when (tier.code) {
                            "PRO" -> stringResource(R.string.tier_pro_label)
                            "ULTRA" -> stringResource(R.string.tier_ultra_label)
                            else -> stringResource(R.string.tier_free_label)
                        }
                        Surface(
                            color = if (isSelected) JulesBrandPurple.copy(alpha = 0.16f) else JulesSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) JulesBrandPurple else JulesBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updatePreference("jules_plan_tier", tier.code) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updatePreference("jules_plan_tier", tier.code) }
                                )
                                Text(
                                    text = tierLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1-B. 기본 AI 모델 선택 및 작업 알림 설정 (Jules 웹 Model Picker & Task Notifications 미러링)
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = JulesPrimary)
                        Column {
                            Text(
                                text = stringResource(R.string.settings_model_notify_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.settings_model_notify_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = JulesTextMuted
                            )
                        }
                    }

                    JulesViewModel.AVAILABLE_MODELS.forEach { m ->
                        val isSelected = m.id == selectedModelCode
                        Surface(
                            color = if (isSelected) JulesBrandPurple.copy(alpha = 0.16f) else JulesSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isSelected) JulesBrandPurple else JulesBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updatePreference("selected_jules_model", m.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updatePreference("selected_jules_model", m.id) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = m.shortName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Surface(
                                            color = JulesSecondary.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = m.badge,
                                                fontSize = 9.sp,
                                                color = JulesSecondary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (m.id == "GEMINI_3_1_PRO") {
                                            stringResource(R.string.model_31_pro_desc)
                                        } else {
                                            stringResource(R.string.model_36_flash_desc)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = JulesTextMuted
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = JulesBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_notify_label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.settings_notify_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = JulesTextMuted
                            )
                        }
                        Switch(
                            checked = notifyOnDone,
                            onCheckedChange = {
                                viewModel.updatePreference("notify_on_session_event", if (it) "true" else "false")
                            }
                        )
                    }
                }
            }
        }

        // 2. API 키 프로필 관리 섹션 (Room DB 내장)
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = JulesPrimary)
                        Column {
                            Text(
                                text = stringResource(R.string.settings_apikey_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.settings_apikey_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = JulesTextMuted
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text(stringResource(R.string.settings_new_key_alias)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text(stringResource(R.string.settings_new_key_input)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = if (showNewKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showNewKey = !showNewKey }) {
                                Icon(
                                    imageVector = if (showNewKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveApiKey(newLabel, newKey)
                                newLabel = ""
                                newKey = ""
                            },
                            enabled = newKey.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = JulesBrandPurple, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.settings_add_activate_key),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.activateDemoMode() },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, JulesBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_switch_demo),
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    HorizontalDivider(color = JulesBorder)

                    Text(
                        text = stringResource(R.string.settings_saved_keys_count, apiKeys.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = JulesTextMuted
                    )

                    apiKeys.forEach { item ->
                        val usage = apiKeyUsages[item.id]
                        val itemTier = usage?.tier ?: JulesViewModel.getTierLimits(item.planTier)
                        val itemUsed24h = usage?.used24h ?: 0
                        val itemConcurrent = usage?.concurrentUsed ?: 0
                        val itemTotal = usage?.totalSessions ?: 0
                        val itemDone = usage?.completedCount ?: 0
                        val itemStalled = usage?.stalledCount ?: 0
                        val dailyRatio = (itemUsed24h.toFloat() / itemTier.dailyLimit.coerceAtLeast(1)).coerceIn(0f, 1f)

                        Surface(
                            color = if (item.isActive) JulesBrandPurple.copy(alpha = 0.14f) else JulesSurfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (item.isActive) JulesBrandPurple else JulesBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = item.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (item.isActive) {
                                                Surface(
                                                    color = JulesSuccess.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "ACTIVE",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = JulesSuccess,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            // 키별 요금제 티어 순환 선택 배지 (클릭 시 FREE -> PRO -> ULTRA 전환)
                                            Surface(
                                                color = JulesBrandPurple.copy(alpha = 0.22f),
                                                shape = RoundedCornerShape(4.dp),
                                                border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.5f)),
                                                modifier = Modifier.clickable {
                                                    val next = when (itemTier.code) {
                                                        "FREE" -> "PRO"
                                                        "PRO" -> "ULTRA"
                                                        else -> "FREE"
                                                    }
                                                    viewModel.updateApiKeyTier(item.id, next)
                                                }
                                            ) {
                                                Text(
                                                    text = itemTier.code,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = JulesPrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        val masked = if (item.apiKey.length > 8) {
                                            "${item.apiKey.take(4)}••••••••${item.apiKey.takeLast(4)}"
                                        } else "••••••••"
                                        Text(
                                            text = masked,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = JulesTextMuted
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!item.isActive) {
                                            TextButton(onClick = { viewModel.switchApiKey(item.id) }) {
                                                Text(stringResource(R.string.settings_switch_btn))
                                            }
                                        }
                                        IconButton(onClick = { viewModel.deleteApiKey(item.id) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = JulesTextMuted)
                                        }
                                    }
                                }

                                // API 키별 실시간 작업 소모량(24h 소모/한도, 동시 실행 슬롯, 누적/완료 세션) 표시 바
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LinearProgressIndicator(
                                        progress = { dailyRatio },
                                        color = if (dailyRatio > 0.8f) JulesDanger else if (dailyRatio > 0.5f) JulesWarning else JulesSuccess,
                                        trackColor = JulesBackground,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.key_usage_stats_format,
                                            itemUsed24h,
                                            itemTier.dailyLimit,
                                            itemConcurrent,
                                            itemTier.concurrentLimit,
                                            itemTotal,
                                            itemDone
                                        ),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = JulesTextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (itemStalled > 0) {
                                        Text(
                                            text = stringResource(R.string.key_stalled_warning_format, itemStalled),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = JulesWarning
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 커밋 작성자 귀속 설정 (웹 Settings -> Commit Authoring Control 미러링)
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_commit_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_commit_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = JulesTextMuted
                    )

                    listOf(
                        "CO_AUTHORED" to stringResource(R.string.commit_co_authored),
                        "JULES_ONLY" to stringResource(R.string.commit_jules_only),
                        "USER_ONLY" to stringResource(R.string.commit_user_only)
                    ).forEach { (mode, desc) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updatePreference("commit_author_mode", mode) }
                        ) {
                            RadioButton(
                                selected = commitAuthorMode == mode,
                                onClick = { viewModel.updatePreference("commit_author_mode", mode) }
                            )
                            Text(text = desc, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // 4. 웹 콘솔 바로가기
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_web_console_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.jules_web_features_desc), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        JulesBrowserUtils.openUrlInDefaultBrowser(context, "https://jules.google.com")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.jules_web_features))
                    }
                    OutlinedButton(
                        onClick = {
                            JulesBrowserUtils.openUrlInDefaultBrowser(context, "https://jules.google.com/settings")
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, JulesBorderBright),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_open_web_btn), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaDetailBox(
    modifier: Modifier = Modifier,
    title: String,
    remaining: Int,
    total: Int,
    unit: String,
    accent: Color
) {
    val fraction = (remaining.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Surface(
        modifier = modifier,
        color = JulesSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, JulesBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                color = JulesTextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "$remaining",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "/ $total $unit",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = JulesTextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                color = accent,
                trackColor = JulesBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
    }
}
