package com.jules.code.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jules.code.ui.JulesBrowserUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jules.code.R
import com.jules.code.data.model.*
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.SelectableContent
import com.jules.code.ui.ApiKeyQuickSwitch
import com.jules.code.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenAI Codex 태스크 워크스페이스 & Google Jules 세션 상세 화면을 결합한 모바일 에이전트 콘솔입니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    viewModel: JulesViewModel,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    val context = LocalContext.current
    val session by viewModel.selectedSessionDetail.collectAsState()
    val activities by viewModel.activities.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isAwaitingResponse by viewModel.isAwaitingResponse.collectAsState()
    val deletingIds by viewModel.deletingSessionIds.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember(session?.sessionId) { mutableStateOf(false) }
    val deleting = session?.sessionId in deletingIds
    if (confirmDelete) {
        session?.let { target ->
            DeleteSessionDialog(
                title = target.displayTitle,
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    viewModel.deleteSession(target.sessionId) {
                        if (viewModel.selectedSessionId.value == null) onBack()
                    }
                }
            )
        }
    }

    val isKo = remember(prefs) {
        val lang = prefs["app_language"] ?: "SYSTEM"
        !lang.equals("en", ignoreCase = true)
    }
    val healthDiagnostic = remember(session, activities) {
        JulesViewModel.diagnoseSessionHealth(session, activities)
    }

    var activeTab by remember { mutableIntStateOf(0) } // 0: 타임라인, 1: 계획(Plan), 2: 코드 Diff, 3: 터미널(Bash)
    var messageInput by remember { mutableStateOf("") }
    var isSpeaking by remember { mutableStateOf(false) }

    // Android 기본 TextToSpeech(TTS)를 활용한 Jules Audio Changelog 구현
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
            }
        }
        ttsEngine = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    val allPlans = remember(activities) {
        activities.mapNotNull { it.planGenerated?.plan }
    }
    val allChangeSets = remember(activities) {
        activities.flatMap { it.artifacts }.mapNotNull { it.changeSet }
    }
    val allBashOutputs = remember(activities) {
        activities.flatMap { it.artifacts }.mapNotNull { it.bashOutput }
    }

    Scaffold(
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState)
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = session?.displayTitle ?: stringResource(R.string.detail_default_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val curRepoDisp = session?.repoDisplay.orEmpty()
                            val isGithubRepo = curRepoDisp.isNotBlank() && !curRepoDisp.contains("Repoless", ignoreCase = true)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable(enabled = isGithubRepo) {
                                            JulesBrowserUtils.openGitHubRepo(context, curRepoDisp)
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    if (isGithubRepo) {
                                        Icon(
                                            imageVector = Icons.Default.Code,
                                            contentDescription = "GitHub 저장소 열기",
                                            tint = JulesPrimary,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                    Text(
                                        text = "${session?.repoDisplay ?: ""}:${session?.branchDisplay ?: "main"}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (isGithubRepo) JulesPrimary else JulesSecondary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("•", fontSize = 11.sp, color = JulesTextMuted, maxLines = 1, softWrap = false)
                                val stateText = when ((session?.state ?: "").uppercase()) {
                                    "COMPLETED" -> stringResource(R.string.state_completed)
                                    "AWAITING_PLAN_APPROVAL" -> stringResource(R.string.state_awaiting_plan)
                                    "AWAITING_USER_FEEDBACK" -> stringResource(R.string.state_awaiting_feedback)
                                    "FAILED" -> stringResource(R.string.state_failed)
                                    "IN_PROGRESS" -> stringResource(R.string.state_in_progress)
                                    "PLANNING" -> stringResource(R.string.state_planning)
                                    "QUEUED" -> stringResource(R.string.state_queued)
                                    "PAUSED" -> stringResource(R.string.state_paused)
                                    else -> JulesViewModel.translateState(session?.state ?: "")
                                }
                                val isAlive = session?.isAlive == true
                                Surface(
                                    color = if (isAlive) JulesSuccess.copy(alpha = 0.16f) else JulesSurfaceElevated,
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, if (isAlive) JulesSuccess.copy(alpha = 0.45f) else JulesBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            color = if (isAlive) JulesSuccess else JulesTextMuted,
                                            shape = CircleShape,
                                            modifier = Modifier.size(5.dp)
                                        ) {}
                                        Text(
                                            text = if (isAlive) "활성 • $stateText" else "종료 • $stateText",
                                            fontSize = 10.sp,
                                            color = if (isAlive) JulesSuccess else JulesTextMuted,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        ApiKeyQuickSwitch(viewModel, onSwitch = onBack)
                        // GitHub 저장소 열기 버튼 (저장소 연동된 세션인 경우 기본 브라우저로 실행)
                        val repoDisp = session?.repoDisplay.orEmpty()
                        val hasGithubRepo = repoDisp.isNotBlank() && !repoDisp.contains("Repoless", ignoreCase = true)
                        if (hasGithubRepo) {
                            IconButton(
                                onClick = {
                                    JulesBrowserUtils.openGitHubRepo(context, repoDisp)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = "GitHub 저장소 열기",
                                    tint = JulesPrimary
                                )
                            }
                        }

                        // Jules 웹 콘솔 열기 / Repoless 세션을 GitHub 저장소로 Publish하는 바로가기 (기본 브라우저로 실행)
                        IconButton(
                            onClick = {
                                val webUrl = session?.url?.ifBlank { null }
                                    ?: session?.sessionId?.let { "https://jules.google.com/session/$it" }
                                    ?: "https://jules.google.com"
                                JulesBrowserUtils.openUrlInDefaultBrowser(context, webUrl)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "웹에서 열기",
                                tint = JulesSecondary
                            )
                        }
                        // 오디오 체인지로그(Audio Summary) 재생/정지 버튼
                        IconButton(
                            onClick = {
                                val tts = ttsEngine ?: return@IconButton
                                if (isSpeaking) {
                                    tts.stop()
                                    isSpeaking = false
                                } else {
                                    val script = viewModel.buildAudioSummaryScript(session, activities)
                                    tts.speak(script, TextToSpeech.QUEUE_FLUSH, null, "jules_audio_changelog")
                                    isSpeaking = true
                                    viewModel.showMessage("Audio Changelog started.")
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) Icons.Default.StopCircle else Icons.Default.Headphones,
                                contentDescription = null,
                                tint = if (isSpeaking) JulesWarning else JulesPrimary
                            )
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }, enabled = session != null && !deleting) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.session_actions))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_refresh)) },
                                    onClick = { showMenu = false; viewModel.refreshSelectedSession() },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_delete_action)) },
                                    onClick = { showMenu = false; confirmDelete = true },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = JulesSurface)
                )
                HorizontalDivider(color = JulesBorder)
            }
        },
        bottomBar = {
            // Codex & Jules 스타일 하단 프롬프트 독 (`sendMessage` 연동)
            Surface(
                color = JulesSurface,
                border = BorderStroke(1.dp, JulesBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.detail_followup_placeholder),
                                fontSize = 13.sp,
                                color = JulesTextMuted
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = JulesBackground,
                            unfocusedContainerColor = JulesBackground,
                            focusedBorderColor = JulesBrandPurple,
                            unfocusedBorderColor = JulesBorder
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = {
                            val sId = session?.sessionId ?: return@FilledIconButton
                            viewModel.sendMessage(sId, messageInput)
                            messageInput = ""
                        },
                        enabled = messageInput.isNotBlank() && session != null && !isAwaitingResponse && !deleting,
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = JulesBrandPurple,
                            contentColor = Color.White
                        )
                    ) {
                        if (isAwaitingResponse) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            // 상단 액션 콜아웃 배너 (계획 승인 대기 중이거나, 무응답/스턱(Stalled) 감지 시, 또는 PR이 생성된 경우)
            session?.let { curSession ->
                if (healthDiagnostic.isStalled) {
                    Surface(
                        color = JulesWarning.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, JulesWarning.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = JulesWarning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isKo) healthDiagnostic.reasonTitleKo else healthDiagnostic.reasonTitleEn,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = JulesWarning,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = if (isKo) healthDiagnostic.reasonDetailKo else healthDiagnostic.reasonDetailEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = JulesTextPrimary,
                                lineHeight = 18.sp
                            )
                            if (healthDiagnostic.currentStepTitle.isNotBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.stalled_step_label,
                                        healthDiagnostic.currentStepTitle,
                                        healthDiagnostic.completedStepsCount,
                                        healthDiagnostic.totalStepsCount.coerceAtLeast(1)
                                    ),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = JulesSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { viewModel.nudgeStalledSession(curSession.sessionId) },
                                    enabled = !isLoading && !isAwaitingResponse && !deleting,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = JulesWarning, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.btn_nudge_agent), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.recreateStalledSession(curSession)
                                    },
                                    enabled = !isLoading && !deleting,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, JulesPrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = null, tint = JulesPrimary, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.btn_recreate_session), fontSize = 11.sp, color = JulesPrimary, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val webUrl = curSession.url.ifBlank { "https://jules.google.com/session/${curSession.sessionId}" }
                                        JulesBrowserUtils.openUrlInDefaultBrowser(context, webUrl)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, JulesBorderBright),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = JulesSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.btn_open_web_console), fontSize = 11.sp, color = JulesSecondary)
                                }
                            }
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                enabled = !deleting,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.session_delete_action))
                            }
                        }
                    }
                }

                if (curSession.state.equals("AWAITING_PLAN_APPROVAL", ignoreCase = true)) {
                    Surface(
                        color = JulesWarning.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, JulesWarning.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.detail_approval_banner_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = JulesWarning,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.detail_approval_banner_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JulesTextPrimary
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.approvePlan(curSession.sessionId) },
                                enabled = !isLoading,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = JulesWarning, contentColor = Color.Black)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.detail_approve_btn), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                curSession.outputs.firstOrNull { it.pullRequest != null }?.pullRequest?.let { pr ->
                    if (pr.url.isNotBlank()) {
                        Surface(
                            color = JulesSuccess.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, JulesSuccess.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, tint = JulesSuccess)
                                    Column {
                                        Text(
                                            text = stringResource(R.string.detail_pr_banner_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = JulesSuccess,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = pr.title.ifBlank { pr.url },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = JulesTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        JulesBrowserUtils.openUrlInDefaultBrowser(context, pr.url)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, JulesSuccess.copy(alpha = 0.6f))
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.detail_open_pr_btn), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Repoless 세션에서 코드 패치가 생성된 경우 Git 반영 안내 배너 표시
                if (curSession.sourceContext == null && allChangeSets.isNotEmpty()) {
                    Surface(
                        color = JulesBrandPurple.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.detail_repoless_banner),
                                fontSize = 11.sp,
                                color = JulesTextPrimary,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val webUrl = curSession.url.ifBlank { "https://jules.google.com/session/${curSession.sessionId}" }
                                    JulesBrowserUtils.openUrlInDefaultBrowser(context, webUrl)
                                }
                            ) {
                                Text(stringResource(R.string.detail_publish_github_btn), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = JulesSecondary)
                            }
                        }
                    }
                }
            }

            // 모바일 4탭 스위처 (타임라인 / 실행 계획 / 코드 Diff / 터미널 로그)
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = JulesSurface,
                edgePadding = 12.dp,
                divider = { HorizontalDivider(color = JulesBorder) }
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text(stringResource(R.string.detail_tab_timeline, activities.size), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text(stringResource(R.string.detail_tab_plan, allPlans.sumOf { it.steps.size }), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                )
                Tab(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    text = { Text(stringResource(R.string.detail_tab_diff, allChangeSets.size), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                )
                Tab(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    text = { Text(stringResource(R.string.detail_tab_bash, allBashOutputs.size), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                )
            }

            // 탭별 본문 컨텐츠
            val timelineListState = rememberLazyListState()
            LaunchedEffect(activities.size, isAwaitingResponse, activeTab) {
                if (activeTab == 0 && (activities.isNotEmpty() || isAwaitingResponse)) {
                    val targetIndex = if (isAwaitingResponse) activities.size else activities.lastIndex
                    if (targetIndex >= 0) {
                        timelineListState.animateScrollToItem(targetIndex)
                    }
                }
            }

            LazyColumn(
                state = timelineListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        if (activities.isEmpty() && !isAwaitingResponse) {
                            item {
                                EmptyStateCard(
                                    title = stringResource(R.string.empty_timeline_title),
                                    subtitle = stringResource(R.string.empty_timeline_subtitle)
                                )
                            }
                        } else {
                            items(activities, key = { it.id.ifBlank { it.name + it.createTime } }) { act ->
                                ActivityTimelineCard(
                                    activity = act,
                                    onRefresh = if (act.id.startsWith("optimistic-") || act.id.isBlank()) null else {
                                        { session?.sessionId?.let { viewModel.refreshActivity(it, act.id) } }
                                    },
                                    onCopyPatch = { patchText ->
                                        if (copyToClipboard(context, "Jules Git Patch", patchText)) viewModel.showMessage("패치를 복사했습니다.")
                                    }
                                )
                            }
                            if (isAwaitingResponse) {
                                item(key = "agent_responding_loader") {
                                    AgentRespondingCard()
                                }
                            }
                        }
                    }
                    1 -> {
                        if (allPlans.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    title = stringResource(R.string.empty_plan_title),
                                    subtitle = stringResource(R.string.empty_plan_subtitle)
                                )
                            }
                        } else {
                            items(allPlans) { plan ->
                                PlanDetailCard(
                                    plan = plan,
                                    canApprove = plan == allPlans.lastOrNull() && !isLoading && !deleting &&
                                        session?.state.equals("AWAITING_PLAN_APPROVAL", ignoreCase = true),
                                    onApprove = { session?.sessionId?.let { viewModel.approvePlan(it) } }
                                )
                            }
                        }
                    }
                    2 -> {
                        if (allChangeSets.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    title = stringResource(R.string.empty_diff_title),
                                    subtitle = stringResource(R.string.empty_diff_subtitle)
                                )
                            }
                        } else {
                            items(allChangeSets) { cs ->
                                ChangeSetDiffCard(
                                    changeSet = cs,
                                    initiallyExpanded = false,
                                    onCopyPatch = { patchText ->
                                        if (copyToClipboard(context, "Jules Git Patch", patchText)) viewModel.showMessage("패치를 복사했습니다.")
                                    }
                                )
                            }
                        }
                    }
                    3 -> {
                        if (allBashOutputs.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    title = stringResource(R.string.empty_bash_title),
                                    subtitle = stringResource(R.string.empty_bash_subtitle)
                                )
                            }
                        } else {
                            items(allBashOutputs) { bash ->
                                BashOutputCard(bash = bash)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 개별 Activity 이벤트를 타임라인 카드로 렌더링합니다.
 */
@Composable
fun ActivityTimelineCard(
    activity: JulesActivity,
    onCopyPatch: (String) -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    val isUser = activity.originator.equals("user", ignoreCase = true) || activity.resolvedUserMessage != null
    Surface(
        color = if (isUser) JulesBrandPurple.copy(alpha = 0.12f) else JulesSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isUser) JulesBrandPurple.copy(alpha = 0.45f) else JulesBorder
        ),
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isUser) {
                        Surface(
                            color = JulesSecondary.copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = JulesSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        JulesOctopusLogo(size = 22.dp, tint = JulesPrimary)
                    }
                    Text(
                        text = if (isUser) "User Prompt" else "Jules Agent",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) JulesSecondary else JulesPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activity.createTime.take(19).replace("T", " "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = JulesTextMuted
                    )
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.activity_refresh))
                        }
                    }
                }
            }

            activity.resolvedUserMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                SelectableContent(text = msg, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
            }

            activity.resolvedAgentMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                SelectableContent(text = msg, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
            }

            activity.progressUpdated?.let { prog ->
                if (prog.title.isNotBlank()) {
                    SelectableContent(
                        text = prog.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = JulesSecondary
                    )
                }
                if (prog.description.isNotBlank()) {
                    SelectableContent(
                        text = prog.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = JulesTextMuted
                    )
                }
            }

            if (activity.description.isNotBlank() &&
                activity.resolvedUserMessage == null &&
                activity.resolvedAgentMessage == null &&
                activity.progressUpdated == null
            ) {
                SelectableContent(text = activity.description)
            }

            activity.planGenerated?.plan?.let { plan ->
                PlanDetailCard(plan = plan, canApprove = false, onApprove = {})
            }

            activity.sessionFailed?.let { fail ->
                Surface(
                    color = JulesDanger.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, JulesDanger.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectableContent(
                        text = stringResource(R.string.detail_failed_reason, fail.reason),
                        color = JulesDanger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            activity.artifacts.forEach { art ->
                art.bashOutput?.let { BashOutputCard(it) }
                art.changeSet?.let { ChangeSetDiffCard(changeSet = it, initiallyExpanded = false, onCopyPatch = onCopyPatch) }
                art.media?.let { MediaArtifactCard(it) }
            }
        }
    }
}

/**
 * 에이전트가 생성한 단계별 계획(`ExecutionPlan`) 스테퍼 카드입니다.
 */
@Composable
fun PlanDetailCard(
    plan: ExecutionPlan,
    canApprove: Boolean,
    onApprove: () -> Unit
) {
    Surface(
        color = JulesSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FactCheck,
                        contentDescription = null,
                        tint = JulesTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.detail_plan_steps_format, plan.steps.size),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (canApprove) {
                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JulesBrandPurple, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.detail_approve_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = JulesBorder)

            plan.steps.forEachIndexed { idx, step ->
                val isDone = step.state.equals("COMPLETED", ignoreCase = true)
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = if (isDone) JulesSuccess.copy(alpha = 0.2f) else JulesBrandPurple.copy(alpha = 0.2f),
                        shape = CircleShape,
                        border = BorderStroke(
                            1.dp,
                            if (isDone) JulesSuccess.copy(alpha = 0.6f) else JulesPrimary.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isDone) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = JulesSuccess,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Text(
                                    text = "${idx + 1}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = JulesPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SelectableContent(
                            text = step.displayTitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = JulesTextPrimary
                        )
                        if (step.description.isNotBlank() && step.description != step.displayTitle) {
                            SelectableContent(
                                text = step.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = JulesTextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unified Diff 문자열에서 변경된 파일명과 파일별 `+추가 / -삭제` 줄 수 요약을 추출합니다.
 */
data class DiffFileSummary(
    val filePath: String,
    val shortName: String,
    val added: Int,
    val removed: Int
)

/** 전체 줄 목록을 만들지 않고 파일별 변경 통계를 집계합니다. */
fun parseDiffFileSummaries(unidiffPatch: String): List<DiffFileSummary> {
    if (unidiffPatch.isBlank()) return emptyList()
    val results = mutableListOf<DiffFileSummary>()
    var currentFile = ""
    var addCount = 0
    var remCount = 0

    /** 현재 파일의 집계를 결과에 추가합니다. */
    fun flushCurrent() {
        if (currentFile.isNotBlank() || addCount > 0 || remCount > 0) {
            val path = currentFile.ifBlank { "changed_file" }
            results.add(
                DiffFileSummary(
                    filePath = path,
                    shortName = path.substringAfterLast("/"),
                    added = addCount,
                    removed = remCount
                )
            )
        }
    }

    unidiffPatch.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> {
                flushCurrent()
                currentFile = line.substringAfter(" b/", line.substringAfterLast(" ")).trim()
                addCount = 0
                remCount = 0
            }
            line.startsWith("+++ b/") -> {
                currentFile = line.removePrefix("+++ b/").trim()
            }
            line.startsWith("+") && !line.startsWith("+++") -> addCount++
            line.startsWith("-") && !line.startsWith("---") -> remCount++
        }
    }
    flushCurrent()
    return results
}

/**
 * GitHub / Codex 스타일로 파일명과 `+N` / `-M` 바뀐 줄 수 요약을 보여주고,
 * 타임라인에서는 기본 접힘(`initiallyExpanded = false`) 상태로 표시하다가 클릭 시 전체 Diff가 펼쳐지는 컴포넌트입니다.
 */
@Composable
fun ChangeSetDiffCard(
    changeSet: ChangeSetArtifact,
    initiallyExpanded: Boolean = false,
    onCopyPatch: (String) -> Unit
) {
    val context = LocalContext.current
    val patch = changeSet.gitPatch?.unidiffPatch ?: ""
    val commitMsg = changeSet.gitPatch?.suggestedCommitMessage?.ifBlank { changeSet.commitMessage }
        ?: changeSet.commitMessage
    val baseCommit = changeSet.gitPatch?.baseCommitId?.ifBlank { changeSet.baseCommit } ?: changeSet.baseCommit

    var isExpanded by remember(patch, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val fileSummaries by produceState<List<DiffFileSummary>>(emptyList(), patch) {
        value = withContext(Dispatchers.Default) { parseDiffFileSummaries(patch) }
    }
    val totalAdded = remember(fileSummaries) { fileSummaries.sumOf { it.added } }
    val totalRemoved = remember(fileSummaries) { fileSummaries.sumOf { it.removed } }
    val primaryHeader = remember(fileSummaries, commitMsg) {
        when {
            fileSummaries.size == 1 -> fileSummaries.first().filePath
            fileSummaries.size > 1 -> "${fileSummaries.first().shortName} 외 ${fileSummaries.size - 1}개 파일"
            commitMsg.isNotBlank() -> commitMsg
            else -> "코드 변경 패치 (Git Diff)"
        }
    }

    Surface(
        color = Color(0xFF0B0E14),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (isExpanded) JulesBrandPurple.copy(alpha = 0.6f) else JulesBorder),
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = patch.isNotBlank()) { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = JulesSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = primaryHeader,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = JulesPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "+$totalAdded",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF56D364),
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = "-$totalRemoved",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF85149),
                                maxLines = 1,
                                softWrap = false
                            )
                            if (baseCommit.isNotBlank()) {
                                Text(
                                    text = "• ${baseCommit.take(7)}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = JulesTextMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                if (patch.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                // Binder 전송 한계를 넘는 원문은 구간별 복사로 안내합니다.
                                if (patch.length > 100_000) {
                                    android.widget.Toast.makeText(context, "긴 패치는 펼친 뒤 구간별 복사를 사용해 주세요.", android.widget.Toast.LENGTH_LONG).show()
                                    return@IconButton
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/x-patch"
                                    putExtra(Intent.EXTRA_SUBJECT, commitMsg.ifBlank { "jules-changes.patch" })
                                    putExtra(Intent.EXTRA_TEXT, patch)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Git Patch (.patch) 내보내기 / 공유"))
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = ".patch 내보내기/공유",
                                tint = JulesSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        IconButton(onClick = { onCopyPatch(patch) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "패치 복사",
                                tint = JulesTextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // 여러 파일이 변경된 경우 접힌 상태에서도 파일별 +N / -M 요약 칩을 간결하게 표시
            if (fileSummaries.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(fileSummaries) { fs ->
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
                                Text(
                                    text = fs.shortName,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = JulesTextPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "+${fs.added}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF56D364),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "-${fs.removed}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF85149),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // 클릭하여 펼쳤을 때만(`isExpanded == true`) 전체 Unified Diff 본문 렌더링
            if (isExpanded && patch.isNotBlank()) {
                SelectableContent(
                    text = patch,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    diff = true,
                    initiallyExpanded = true
                )
            }
        }
    }
}

/**
 * 클라우드 VM 내 Bash 명령어 및 표준 출력/종료 코드를 보여주는 터미널 콘솔 카드입니다.
 */
@Composable
fun BashOutputCard(bash: BashOutputArtifact) {
    val isSuccess = bash.exitCode == 0
    Surface(
        color = Color(0xFF07090E),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, JulesBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // macOS / Cloud VM 터미널 헤더 바
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFF85149).copy(alpha = 0.7f)))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFD29922).copy(alpha = 0.7f)))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF3FB950).copy(alpha = 0.7f)))
                    }
                    Spacer(Modifier.width(4.dp))
                    SelectableContent(
                        text = "$ ${bash.command}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = JulesSecondary
                    )
                }
                Surface(
                    color = (if (isSuccess) JulesSuccess else JulesDanger).copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, (if (isSuccess) JulesSuccess else JulesDanger).copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "exit ${bash.exitCode}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSuccess) JulesSuccess else JulesDanger,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (bash.output.isNotBlank()) {
                HorizontalDivider(color = JulesBorder.copy(alpha = 0.6f))
                SelectableContent(
                    text = bash.output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = Color(0xFFD0D7DE)
                )
            }
        }
    }
}

/**
 * 에이전트 응답 생성 및 작업 진행 대기 중일 때 표시되는 로딩 카드입니다.
 */
@Composable
fun AgentRespondingCard() {
    Surface(
        color = JulesSurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, JulesBrandPurple.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = JulesBrandPurple,
                strokeWidth = 2.5.dp
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Jules 에이전트가 응답을 생성하고 있습니다...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = JulesTextPrimary
                )
                Text(
                    text = "작업 계획 수립 및 변경사항 검토 중",
                    style = MaterialTheme.typography.bodySmall,
                    color = JulesTextMuted
                )
            }
        }
    }
}

/**
 * 이미지 산출물을 전체 화면으로 띄우고 핀치 줌, 패닝, 더블탭 리셋, 플로팅 줌 컨트롤을 제공하는 팝업 다이얼로그입니다.
 */
@Composable
fun ImageZoomDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    title: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
        ) {
            // 상단 바 (제목, 줌 배율 배지, 닫기 버튼)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "핀치 또는 버튼으로 확대/축소, 더블탭 리셋",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.12f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        tint = Color.White
                    )
                }
            }

            // 이미지 표시 영역 (핀치 줌, 패닝, 더블탭 토글)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = androidx.compose.ui.geometry.Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 5.0f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = androidx.compose.ui.geometry.Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "확대된 이미지 산출물",
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }

            // 하단 플로팅 줌 컨트롤 바 (+, -, 1:1 리셋)
            Surface(
                color = JulesSurfaceElevated.copy(alpha = 0.9f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, JulesBorder),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = {
                        scale = (scale - 0.5f).coerceAtLeast(1f)
                        if (scale <= 1f) offset = androidx.compose.ui.geometry.Offset.Zero
                    }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "축소", tint = Color.White)
                    }
                    TextButton(onClick = {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    }) {
                        Text("1:1 리셋", color = JulesPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = {
                        scale = (scale + 0.5f).coerceAtMost(5f)
                    }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "확대", tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Base64 인코딩된 미디어 산출물(스크린샷 등)을 디코딩해 표시하고,
 * 클릭 시 핀치 줌 및 확대 팝업 화면으로 연결하는 카드입니다.
 */
@Composable
fun MediaArtifactCard(media: MediaArtifact) {
    MediaContent(media)
}

/** 클립보드에 텍스트를 복사합니다. */

private fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (text.length > 100_000) {
        android.widget.Toast.makeText(context, "긴 글은 펼친 뒤 구간별 복사를 사용해 주세요.", android.widget.Toast.LENGTH_LONG).show()
        return false
    }
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    return cm != null
}
