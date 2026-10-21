package com.jules.code.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.jules.code.ui.theme.*
import java.util.Locale

/**
 * OpenAI Codex 태스크 워크스페이스 & Google Jules 세션 상세 화면을 결합한 모바일 에이전트 콘솔입니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    viewModel: JulesViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val session by viewModel.selectedSessionDetail.collectAsState()
    val activities by viewModel.activities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${session?.repoDisplay ?: ""}:${session?.branchDisplay ?: "main"}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = JulesSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
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
                                Text(
                                    text = stateText,
                                    fontSize = 11.sp,
                                    color = JulesPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        // Jules 웹 콘솔 열기 / Repoless 세션을 GitHub 저장소로 Publish하는 바로가기
                        IconButton(
                            onClick = {
                                val webUrl = session?.url?.ifBlank { null }
                                    ?: session?.sessionId?.let { "https://jules.google.com/session/$it" }
                                    ?: "https://jules.google.com"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
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
                        IconButton(onClick = { viewModel.refreshSelectedSession() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
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
                        enabled = messageInput.isNotBlank() && session != null,
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = JulesBrandPurple,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
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
            // 상단 액션 콜아웃 배너 (계획 승인 대기 중이거나 PR이 생성된 경우)
            session?.let { curSession ->
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
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.url)))
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
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
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
            LaunchedEffect(activities.size, activeTab) {
                if (activeTab == 0 && activities.isNotEmpty()) {
                    timelineListState.animateScrollToItem(activities.lastIndex)
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
                        if (activities.isEmpty()) {
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
                                    onCopyPatch = { patchText ->
                                        copyToClipboard(context, "Jules Git Patch", patchText)
                                        viewModel.showMessage("Git patch copied.")
                                    }
                                )
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
                                    canApprove = session?.state.equals("AWAITING_PLAN_APPROVAL", ignoreCase = true),
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
                                    initiallyExpanded = true,
                                    onCopyPatch = { patchText ->
                                        copyToClipboard(context, "Jules Git Patch", patchText)
                                        viewModel.showMessage("Git patch copied.")
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
    onCopyPatch: (String) -> Unit
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
                Text(
                    text = activity.createTime.take(19).replace("T", " "),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = JulesTextMuted
                )
            }

            activity.resolvedUserMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(text = msg, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
            }

            activity.resolvedAgentMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(text = msg, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
            }

            activity.progressUpdated?.let { prog ->
                if (prog.title.isNotBlank()) {
                    Text(
                        text = prog.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = JulesSecondary
                    )
                }
                if (prog.description.isNotBlank()) {
                    Text(
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
                Text(text = activity.description, style = MaterialTheme.typography.bodyMedium)
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
                    Text(
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
                        Text(
                            text = step.displayTitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = JulesTextPrimary
                        )
                        if (step.description.isNotBlank() && step.description != step.displayTitle) {
                            Text(
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

fun parseDiffFileSummaries(unidiffPatch: String): List<DiffFileSummary> {
    if (unidiffPatch.isBlank()) return emptyList()
    val results = mutableListOf<DiffFileSummary>()
    var currentFile = ""
    var addCount = 0
    var remCount = 0

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
    val fileSummaries = remember(patch) { parseDiffFileSummaries(patch) }
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
            .clickable(enabled = patch.isNotBlank()) { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    fileSummaries.forEach { fs ->
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
                val parsedLines = remember(patch) { patch.lines() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF05070B))
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    parsedLines.forEachIndexed { idx, line ->
                        val bgColor = when {
                            line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF102B1E)
                            line.startsWith("-") && !line.startsWith("---") -> Color(0xFF34161B)
                            line.startsWith("@@") -> Color(0xFF142238)
                            line.startsWith("diff --git") -> Color(0xFF171C28)
                            else -> Color.Transparent
                        }
                        val textColor = when {
                            line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF56D364)
                            line.startsWith("-") && !line.startsWith("---") -> Color(0xFFF85149)
                            line.startsWith("@@") -> Color(0xFF79C0FF)
                            line.startsWith("diff --git") -> JulesPrimary
                            else -> Color(0xFFC9D1D9)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = (idx + 1).toString().padStart(3, ' '),
                                color = JulesTextMuted.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                text = line,
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
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
                    Text(
                        text = "$ ${bash.command}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
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
                Text(
                    text = bash.output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFD0D7DE),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Base64 인코딩된 미디어 산출물(스크린샷 등)을 디코딩해 표시하는 카드입니다.
 */
@Composable
fun MediaArtifactCard(media: MediaArtifact) {
    val bitmap = remember(media.data) {
        try {
            val bytes = Base64.decode(media.data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    Surface(
        color = JulesSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, JulesBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "미디어 산출물 (${media.mimeType})",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = JulesTextMuted
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Jules 생성 이미지",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}
