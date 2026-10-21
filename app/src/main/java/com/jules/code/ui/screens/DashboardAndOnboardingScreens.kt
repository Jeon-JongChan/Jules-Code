package com.jules.code.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jules.code.ui.JulesBrowserUtils
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jules.code.R
import com.jules.code.data.local.CachedSessionEntity
import com.jules.code.data.local.ScheduledTemplateEntity
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.theme.*

/**
 * 첫 실행 시(또는 API 키 미등록 시) 표시되는 Google Jules & Codex 스타일의 API 키 온보딩 화면입니다.
 */
@Composable
fun ApiKeyOnboardingScreen(viewModel: JulesViewModel) {
    val context = LocalContext.current
    val defaultAlias = stringResource(R.string.onboarding_default_key_label)
    var label by remember(defaultAlias) { mutableStateOf(defaultAlias) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF141129), JulesBackground, JulesBackground)
                )
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = JulesSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, JulesBorderBright)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        color = JulesBrandPurple.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.5f)),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            JulesOctopusLogo(size = 34.dp, tint = JulesPrimary)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Surface(
                                color = JulesBrandPurple.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "AGENT",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = JulesPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.onboarding_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = JulesBorder)

                Text(
                    text = stringResource(R.string.onboarding_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JulesTextPrimary.copy(alpha = 0.9f),
                    lineHeight = 20.sp
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.onboarding_label_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.onboarding_key_hint)) },
                    placeholder = { Text("AQ.Ab8RN6... / AIza...", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { viewModel.saveApiKey(label, apiKey) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JulesBrandPurple,
                        contentColor = Color.White
                    ),
                    enabled = apiKey.isNotBlank()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_save_start),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                ) {
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_demo_start),
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = {
                        JulesBrowserUtils.openUrlInDefaultBrowser(context, "https://jules.google.com/settings")
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_get_key_link),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * OpenAI Codex 웹 & Google Jules 웹의 통합 프롬프트 컴포저(Composer Dock) 및 태스크 큐 디자인을
 * 모바일에 맞게 다듬은 메인 대시보드 화면입니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: JulesViewModel,
    onOpenSession: (String) -> Unit
) {
    val sources by viewModel.sources.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val templates by viewModel.templates.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isDemo by viewModel.isDemoMode.collectAsState()

    val currentTier = remember(prefs) {
        JulesViewModel.getTierLimits(prefs["jules_plan_tier"])
    }
    val used24h = remember(sessions) {
        JulesViewModel.countRolling24hSessions(sessions)
    }
    val remainingDaily = (currentTier.dailyLimit - used24h).coerceAtLeast(0)
    val concurrentUsed = remember(sessions) {
        JulesViewModel.countConcurrentActiveSessions(sessions)
    }
    val remainingConcurrent = (currentTier.concurrentLimit - concurrentUsed).coerceAtLeast(0)

    var selectedSourceName by remember(sources) {
        mutableStateOf(sources.firstOrNull()?.name ?: "")
    }
    val selectedSource = sources.find { it.name == selectedSourceName }
    val availableBranches = remember(selectedSource) {
        selectedSource?.branchesCsv?.split(",")?.filter { it.isNotBlank() } ?: listOf("main")
    }
    var selectedBranch by remember(selectedSource) {
        mutableStateOf(selectedSource?.defaultBranch ?: "main")
    }

    var prompt by remember { mutableStateOf("") }
    var taskTitle by remember { mutableStateOf("") }
    var showTitleField by remember { mutableStateOf(false) }
    var requireApproval by remember { mutableStateOf(false) }
    var autoCreatePr by remember { mutableStateOf(true) }
    var enableCritic by remember(prefs) {
        mutableStateOf(prefs["enable_critic_agent"] != "false")
    }
    var enableCiFixer by remember(prefs) {
        mutableStateOf(prefs["enable_ci_fixer"] == "true")
    }
    var selectedModelId by remember(prefs) {
        mutableStateOf(prefs["jules_model_id"] ?: "GEMINI_3_1_PRO")
    }
    val selectedModel = remember(selectedModelId) {
        JulesViewModel.AVAILABLE_MODELS.find { it.id == selectedModelId } ?: JulesViewModel.AVAILABLE_MODELS.first()
    }
    var dashboardTab by remember { mutableIntStateOf(0) } // 0: 최근 세션, 1: 예약 작업 & 템플릿

    var repoDropdownExpanded by remember { mutableStateOf(false) }
    var branchDropdownExpanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var showNewTemplateDialog by remember { mutableStateOf(false) }
    var showFilePinDialog by remember { mutableStateOf(false) }

    val activeCount = remember(sessions) {
        sessions.count { it.state.uppercase() in listOf("IN_PROGRESS", "PLANNING", "QUEUED") }
    }
    val approvalCount = remember(sessions) {
        sessions.count { it.state.uppercase() in listOf("AWAITING_PLAN_APPROVAL", "AWAITING_USER_FEEDBACK") }
    }
    val completedCount = remember(sessions) {
        sessions.count { it.state.equals("COMPLETED", ignoreCase = true) }
    }

    val quickPrompts = listOf(
        stringResource(R.string.quick_bug_title) to stringResource(R.string.quick_bug_prompt),
        stringResource(R.string.quick_test_title) to stringResource(R.string.quick_test_prompt),
        stringResource(R.string.quick_refactor_title) to stringResource(R.string.quick_refactor_prompt),
        stringResource(R.string.quick_pr_title) to stringResource(R.string.quick_pr_prompt)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 데모 모드 안내 배너
        if (isDemo) {
            item {
                Surface(
                    color = JulesWarning.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, JulesWarning.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null, tint = JulesWarning, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.demo_banner_text),
                            style = MaterialTheme.typography.labelMedium,
                            color = JulesTextPrimary
                        )
                    }
                }
            }
        }

        // 상단 API 남은 작업량(Quota) & 동시 실행 슬롯 대시보드 위젯
        item {
            QuotaRemainingBanner(
                tierCode = currentTier.code,
                remainingDaily = remainingDaily,
                dailyLimit = currentTier.dailyLimit,
                remainingConcurrent = remainingConcurrent,
                concurrentLimit = currentTier.concurrentLimit,
                onCycleTier = {
                    val nextTier = when (currentTier.code) {
                        "FREE" -> "PRO"
                        "PRO" -> "ULTRA"
                        else -> "FREE"
                    }
                    viewModel.updatePreference("jules_plan_tier", nextTier)
                }
            )
        }

        // 상단 워크로드 메트릭 스트립 (Codex & Jules 상태 요약 바)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.metric_active_vm),
                    count = activeCount,
                    accent = JulesPrimary
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.metric_awaiting_plan),
                    count = approvalCount,
                    accent = JulesWarning
                )
                MetricPill(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.metric_completed_pr),
                    count = completedCount,
                    accent = JulesSuccess
                )
            }
        }

        // 1. Jules & Codex 스타일 통합 프롬프트 컴포저 독 (Unified Composer Box)
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (prompt.isNotBlank()) JulesBrandPurple else JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 헤더: 마스코트 + 질문 타이틀 + 로딩 인디케이터
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            JulesOctopusLogo(size = 24.dp, tint = JulesPrimary)
                            Text(
                                text = stringResource(R.string.composer_headline),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = JulesPrimary
                            )
                        }
                    }

                    // 상단 컨텍스트 툴바: [저장소 Pill] + [브랜치 Pill] + [제목 토글]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 저장소 선택 Pill
                        Box {
                            Surface(
                                color = JulesSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, JulesBorder),
                                modifier = Modifier.clickable { repoDropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (selectedSource?.isCustom == true) Icons.Default.FolderShared else Icons.Default.FolderSpecial,
                                        contentDescription = null,
                                        tint = if (selectedSource?.isCustom == true) JulesPrimary else JulesSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    if (selectedSource?.isCustom == true) {
                                        Surface(
                                            color = JulesBrandPurple.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "외부",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = JulesPrimary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = selectedSource?.let { "${it.owner}/${it.repo}" } ?: "Repoless (Cloud VM)",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = JulesTextMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = repoDropdownExpanded,
                                onDismissRequest = { repoDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_repoless_dropdown), fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                    onClick = {
                                        selectedSourceName = ""
                                        repoDropdownExpanded = false
                                    }
                                )
                                sources.forEach { src ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (src.isCustom) {
                                                    Surface(
                                                        color = JulesBrandPurple.copy(alpha = 0.18f),
                                                        shape = RoundedCornerShape(4.dp),
                                                        border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.4f))
                                                    ) {
                                                        Text(
                                                            text = "외부",
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 10.sp,
                                                            color = JulesPrimary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = "${src.owner}/${src.repo}",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (src.isCustom) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedSourceName = src.name
                                            selectedBranch = src.defaultBranch
                                            repoDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // 브랜치 선택 Pill
                        Box {
                            Surface(
                                color = JulesSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, JulesBorder),
                                modifier = Modifier.clickable(enabled = selectedSource != null) {
                                    branchDropdownExpanded = true
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountTree,
                                        contentDescription = null,
                                        tint = JulesTertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = selectedBranch,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = if (selectedSource != null) JulesTextPrimary else JulesTextMuted
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = JulesTextMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = branchDropdownExpanded,
                                onDismissRequest = { branchDropdownExpanded = false }
                            ) {
                                availableBranches.forEach { branch ->
                                    DropdownMenuItem(
                                        text = { Text(branch, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                        onClick = {
                                            selectedBranch = branch
                                            branchDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // 선택적 작업 제목 입력 토글 Pill
                        Surface(
                            color = if (showTitleField || taskTitle.isNotBlank()) JulesBrandPurple.copy(alpha = 0.2f) else JulesSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (showTitleField || taskTitle.isNotBlank()) JulesBrandPurple else JulesBorder),
                            modifier = Modifier.clickable { showTitleField = !showTitleField }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Title,
                                    contentDescription = null,
                                    tint = JulesPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (taskTitle.isBlank()) stringResource(R.string.composer_set_title) else taskTitle.take(12),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 모델 선택기(Model Selector) Pill (Gemini 3.1 Pro / Gemini 3 Flash / Gemini 2.5 Pro)
                        Box {
                            Surface(
                                color = JulesBrandPurple.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.65f)),
                                modifier = Modifier.clickable { modelDropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = JulesPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = selectedModel.shortName,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = JulesPrimary,
                                        maxLines = 1
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = JulesPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false }
                            ) {
                                JulesViewModel.AVAILABLE_MODELS.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = m.shortName,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp
                                                    )
                                                    Surface(
                                                        color = JulesBrandPurple.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = m.badge,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 9.sp,
                                                            color = JulesPrimary,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = if (m.id == "GEMINI_3_1_PRO") {
                                                        stringResource(R.string.model_31_pro_desc)
                                                    } else {
                                                        stringResource(R.string.model_36_flash_desc)
                                                    },
                                                    fontSize = 11.sp,
                                                    color = JulesTextMuted
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedModelId = m.id
                                            viewModel.updatePreference("jules_model_id", m.id)
                                            modelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // @파일 컨텍스트 호출 (File Context Pinning) Pill
                        Surface(
                            color = JulesSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, JulesBorder),
                            modifier = Modifier.clickable { showFilePinDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    tint = JulesSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.composer_pin_file),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = JulesSecondary
                                )
                            }
                        }
                    }

                    if (showTitleField) {
                        OutlinedTextField(
                            value = taskTitle,
                            onValueChange = { taskTitle = it },
                            label = { Text(stringResource(R.string.composer_title_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 메인 프롬프트 입력 에디터
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.composer_prompt_placeholder),
                                color = JulesTextMuted,
                                fontSize = 14.sp
                            )
                        },
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = JulesBackground,
                            unfocusedContainerColor = JulesBackground,
                            focusedBorderColor = JulesBrandPurple,
                            unfocusedBorderColor = JulesBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Codex 스타일 원터치 빠른 프롬프트 제안 칩
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickPrompts.forEach { (chipTitle, chipPrompt) ->
                            SuggestionChip(
                                onClick = {
                                    if (taskTitle.isBlank()) taskTitle = chipTitle
                                    prompt = chipPrompt
                                },
                                label = { Text(chipTitle, fontSize = 11.sp) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoFixHigh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = JulesBorder)

                    // 하단 액션 바: [Give me a plan] + [Auto PR] + [Critic Agent] + [CI Auto-Fixer]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = requireApproval,
                            onClick = { requireApproval = !requireApproval },
                            label = { Text(stringResource(R.string.chip_plan_review), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (requireApproval) Icons.Default.CheckCircle else Icons.Default.Checklist,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = autoCreatePr,
                            onClick = { autoCreatePr = !autoCreatePr },
                            label = { Text(stringResource(R.string.chip_auto_pr), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MergeType,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = enableCritic,
                            onClick = {
                                enableCritic = !enableCritic
                                viewModel.updatePreference("enable_critic_agent", enableCritic.toString())
                            },
                            label = { Text(stringResource(R.string.chip_critic_agent), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = enableCiFixer,
                            onClick = {
                                enableCiFixer = !enableCiFixer
                                viewModel.updatePreference("enable_ci_fixer", enableCiFixer.toString())
                            },
                            label = { Text(stringResource(R.string.chip_ci_fixer), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.BuildCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.createSession(
                                prompt = prompt,
                                title = taskTitle,
                                sourceName = selectedSourceName.ifBlank { null },
                                branchName = selectedBranch,
                                requirePlanApproval = requireApproval,
                                autoCreatePr = autoCreatePr,
                                modelId = selectedModelId,
                                enableCritic = enableCritic,
                                enableCiFixer = enableCiFixer,
                                onCreated = { newId ->
                                    prompt = ""
                                    taskTitle = ""
                                    showTitleField = false
                                    onOpenSession(newId)
                                }
                            )
                        },
                        enabled = prompt.isNotBlank() && !isLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JulesBrandPurple,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (requireApproval) stringResource(R.string.btn_launch_with_plan) else stringResource(R.string.btn_launch_agent),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. 대시보드 하단 세그먼트 탭 (최근 세션 vs 예약 작업 & 템플릿)
        item {
            Surface(
                color = JulesSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SegmentTabButton(
                        modifier = Modifier.weight(1f),
                        selected = dashboardTab == 0,
                        text = stringResource(R.string.tab_recent_sessions, sessions.size),
                        onClick = { dashboardTab = 0 }
                    )
                    SegmentTabButton(
                        modifier = Modifier.weight(1f),
                        selected = dashboardTab == 1,
                        text = stringResource(R.string.tab_scheduled_templates, templates.size),
                        onClick = { dashboardTab = 1 }
                    )
                }
            }
        }

        if (dashboardTab == 0) {
            if (sessions.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = stringResource(R.string.empty_sessions_title),
                        subtitle = stringResource(R.string.empty_sessions_subtitle)
                    )
                }
            } else {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionSummaryCard(
                        session = session,
                        onClick = { onOpenSession(session.sessionId) },
                        onPinToggle = { viewModel.togglePinSession(session.sessionId, session.isPinned) },
                        onDelete = { viewModel.deleteSession(session.sessionId) }
                    )
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.scheduled_header),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = { showNewTemplateDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.scheduled_add_btn), fontSize = 12.sp)
                    }
                }
            }
            items(templates, key = { it.id }) { tmpl ->
                ScheduledTemplateCard(
                    template = tmpl,
                    onRunNow = {
                        viewModel.runTemplateNow(tmpl, onCreated = onOpenSession)
                    },
                    onFillPrompt = {
                        taskTitle = tmpl.title
                        prompt = tmpl.prompt
                        requireApproval = tmpl.requirePlanApproval
                        autoCreatePr = tmpl.autoCreatePr
                        dashboardTab = 0
                    },
                    onDelete = { viewModel.deleteTemplate(tmpl.id) }
                )
            }
        }
    }

    if (showNewTemplateDialog) {
        NewTemplateDialog(
            defaultSource = selectedSourceName,
            defaultBranch = selectedBranch,
            onDismiss = { showNewTemplateDialog = false },
            onSave = { tTitle, tPrompt, tCadence, tApproval ->
                viewModel.saveTemplate(
                    title = tTitle,
                    prompt = tPrompt,
                    sourceName = selectedSourceName,
                    branchName = selectedBranch,
                    cadence = tCadence,
                    requirePlanApproval = tApproval,
                    autoCreatePr = true
                )
                showNewTemplateDialog = false
            }
        )
    }

    if (showFilePinDialog) {
        var filePathInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFilePinDialog = false },
            containerColor = JulesSurface,
            title = { Text(stringResource(R.string.dialog_file_pin_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.dialog_file_pin_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = JulesTextMuted
                    )
                    OutlinedTextField(
                        value = filePathInput,
                        onValueChange = { filePathInput = it },
                        placeholder = { Text(stringResource(R.string.dialog_file_pin_hint), fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = filePathInput.trim().removePrefix("@")
                        if (clean.isNotEmpty()) {
                            prompt = if (prompt.isBlank()) "@$clean " else "$prompt @$clean "
                        }
                        showFilePinDialog = false
                    },
                    enabled = filePathInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = JulesBrandPurple, contentColor = Color.White)
                ) {
                    Text(stringResource(R.string.dialog_file_pin_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFilePinDialog = false }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }
}

@Composable
fun QuotaRemainingBanner(
    tierCode: String,
    remainingDaily: Int,
    dailyLimit: Int,
    remainingConcurrent: Int,
    concurrentLimit: Int,
    onCycleTier: () -> Unit
) {
    val dailyFraction = (remainingDaily.toFloat() / dailyLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
    val barColor = when {
        dailyFraction > 0.4f -> JulesSuccess
        dailyFraction > 0.15f -> JulesWarning
        else -> JulesDanger
    }

    Surface(
        color = JulesSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, JulesBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.quota_banner_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = JulesTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 클릭 시 Free / Pro / Ultra 티어 즉시 전환 가능
                Surface(
                    color = JulesBrandPurple.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.6f)),
                    modifier = Modifier.clickable(onClick = onCycleTier)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "$tierCode TIER",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = JulesPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = JulesPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { dailyFraction },
                color = barColor,
                trackColor = JulesSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            // PRO(100회/15개), ULTRA(300회/60개) 등 3자리 숫자에서도 절대 두 줄로 꺾이지 않도록 단일 행 고정
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.quota_daily_format, remainingDaily, dailyLimit),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = barColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.quota_concurrent_format, remainingConcurrent, concurrentLimit),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = JulesSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    modifier: Modifier = Modifier,
    label: String,
    count: Int,
    accent: Color
) {
    Surface(
        modifier = modifier,
        color = JulesSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, JulesBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = JulesTextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = JulesTextPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun SegmentTabButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) JulesBrandPurple.copy(alpha = 0.25f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        border = if (selected) BorderStroke(1.dp, JulesBrandPurple) else null
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) JulesTextPrimary else JulesTextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Codex 태스크 리스트 & Jules 세션 카드를 결합한 고밀도 세션 요약 카드입니다.
 */
@Composable
fun SessionSummaryCard(
    session: CachedSessionEntity,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        color = JulesSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (session.isPinned) JulesBrandPurple.copy(alpha = 0.7f) else JulesBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StateBadge(state = session.state)
                    if (session.updateTime.isNotBlank()) {
                        Text(
                            text = session.updateTime.take(16).replace("T", " "),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = JulesTextMuted
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPinToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (session.isPinned) Icons.Default.PushPin else Icons.Default.PinInvoke,
                            contentDescription = "상단 고정",
                            tint = if (session.isPinned) JulesPrimary else JulesTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "삭제",
                            tint = JulesTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = session.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (session.prompt.isNotBlank() && session.prompt != session.title) {
                Text(
                    text = session.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = JulesTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 하단 모노스페이스 저장소/브랜치 메타칩 + PR 배지 (좁은 화면에서도 main 등이 세로로 깨지지 않도록 1줄 고정 + 가로 스크롤)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isGithubRepo = session.repoDisplay.isNotBlank() && !session.repoDisplay.contains("Repoless", ignoreCase = true)
                Surface(
                    onClick = {
                        if (isGithubRepo) {
                            JulesBrowserUtils.openGitHubRepo(context, session.repoDisplay)
                        }
                    },
                    enabled = isGithubRepo,
                    color = JulesSurfaceElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, JulesBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = if (isGithubRepo) "GitHub 저장소 열기" else null,
                            modifier = Modifier.size(12.dp),
                            tint = if (isGithubRepo) JulesPrimary else JulesSecondary
                        )
                        Text(
                            text = session.repoDisplay,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (isGithubRepo) JulesPrimary else JulesSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    color = JulesSurfaceElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, JulesBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = JulesTertiary
                        )
                        Text(
                            text = session.branchDisplay,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = JulesTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (session.prUrl.isNotBlank()) {
                    Surface(
                        onClick = {
                            JulesBrowserUtils.openUrlInDefaultBrowser(context, session.prUrl)
                        },
                        color = JulesSuccess.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, JulesSuccess.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MergeType,
                                contentDescription = "PR 열기",
                                tint = JulesSuccess,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "PR Open",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = JulesSuccess,
                                maxLines = 1,
                                softWrap = false
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = JulesSuccess,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 상태 인디케이터 도트와 레이블을 함께 표시하는 Codex/Jules 스타일 상태 배지입니다.
 */
@Composable
fun StateBadge(state: String) {
    val isAlive = when (state.uppercase()) {
        "IN_PROGRESS", "PLANNING", "QUEUED", "AWAITING_PLAN_APPROVAL", "AWAITING_USER_FEEDBACK" -> true
        else -> false
    }
    val (color, label) = when (state.uppercase()) {
        "COMPLETED" -> JulesSuccess to stringResource(R.string.state_completed)
        "AWAITING_PLAN_APPROVAL" -> JulesWarning to stringResource(R.string.state_awaiting_plan)
        "AWAITING_USER_FEEDBACK" -> JulesWarning to stringResource(R.string.state_awaiting_feedback)
        "FAILED" -> JulesDanger to stringResource(R.string.state_failed)
        "IN_PROGRESS" -> JulesPrimary to stringResource(R.string.state_in_progress)
        "PLANNING" -> JulesPrimary to stringResource(R.string.state_planning)
        "QUEUED" -> JulesPrimary to stringResource(R.string.state_queued)
        "PAUSED" -> JulesTextMuted to stringResource(R.string.state_paused)
        else -> JulesTextMuted to JulesViewModel.translateState(state)
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = if (isAlive) "활성 • $label" else label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 예약 작업(Scheduled Task) 및 템플릿 카드 컴포넌트입니다.
 */
@Composable
fun ScheduledTemplateCard(
    template: ScheduledTemplateEntity,
    onRunNow: () -> Unit,
    onFillPrompt: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = JulesSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, JulesBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = JulesTertiary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, JulesTertiary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = JulesTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = when (template.cadence) {
                                "DAILY" -> stringResource(R.string.cadence_badge_daily)
                                "WEEKLY" -> stringResource(R.string.cadence_badge_weekly)
                                else -> stringResource(R.string.cadence_badge_manual)
                            },
                            color = JulesTertiary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp), tint = JulesTextMuted)
                }
            }

            Text(
                text = template.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = template.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = JulesTextMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onFillPrompt) {
                    Text(stringResource(R.string.template_fill_composer), fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = onRunNow,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.template_run_now), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 새 스케줄/프롬프트 템플릿 등록 다이얼로그입니다.
 */
@Composable
fun NewTemplateDialog(
    defaultSource: String,
    defaultBranch: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf("DAILY") }
    var requireApproval by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JulesSurface,
        title = { Text(stringResource(R.string.dialog_new_template_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.dialog_new_template_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.dialog_new_template_prompt)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "DAILY" to stringResource(R.string.cadence_daily),
                        "WEEKLY" to stringResource(R.string.cadence_weekly),
                        "MANUAL" to stringResource(R.string.cadence_manual)
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = cadence == code,
                            onClick = { cadence = code },
                            label = { Text(label) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = requireApproval, onCheckedChange = { requireApproval = it })
                    Text(stringResource(R.string.dialog_new_template_require_approval), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, prompt, cadence, requireApproval) },
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = JulesBrandPurple, contentColor = Color.White)
            ) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        }
    )
}

/**
 * 데이터가 비어있을 때 표시하는 공통 안내 카드입니다.
 */
@Composable
fun EmptyStateCard(title: String, subtitle: String) {
    Surface(
        color = JulesSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, JulesBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = JulesTextMuted,
                modifier = Modifier.size(32.dp)
            )
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = JulesTextMuted
            )
        }
    }
}
