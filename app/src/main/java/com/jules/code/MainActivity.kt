package com.jules.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jules.code.ui.JulesViewModel
import com.jules.code.ui.ProvideJulesLocalization
import com.jules.code.ui.screens.*
import com.jules.code.ui.theme.*

/**
 * Jules Code Android 앱의 진입점 액티비티입니다.
 * 딥링크(`https://jules.google.com/session/{id}`) 수신 및 모바일 반응형 하단 탭 네비게이션을 제공합니다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deepLinkSessionId = intent?.data?.pathSegments?.let { segs ->
            val idx = segs.indexOfFirst { it.equals("session", ignoreCase = true) || it.equals("sessions", ignoreCase = true) }
            if (idx >= 0 && idx + 1 < segs.size) segs[idx + 1] else null
        }

        setContent {
            JulesTheme {
                val vm: JulesViewModel = viewModel(factory = JulesViewModel.factory(applicationContext))
                JulesMobileApp(viewModel = vm, initialSessionId = deepLinkSessionId)
            }
        }
    }
}

/**
 * 웹 UI의 좌측 사이드바 및 멀티 패널을 모바일 친화적인 상단 앱바(남은 작업량 배지 포함) +
 * 하단 네비게이션 바 + 상세 화면 전환으로 구성한 루트 컴포저블입니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JulesMobileApp(
    viewModel: JulesViewModel,
    initialSessionId: String? = null
) {
    val activeKey by viewModel.activeApiKey.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val snackbarMsg by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by remember { mutableIntStateOf(0) } // 0: 대시보드, 1: 세션, 2: 저장소(Sources), 3: 설정
    var openedSessionId by remember { mutableStateOf(initialSessionId) }
    var previousKeyId by remember { mutableStateOf<Long?>(null) }

    // 계정 전환 뒤 이전 계정의 상세 화면이 남지 않도록 닫습니다.
    LaunchedEffect(activeKey?.id) {
        if (previousKeyId != null && previousKeyId != activeKey?.id) openedSessionId = null
        previousKeyId = activeKey?.id
        if (activeKey != null && openedSessionId != null) viewModel.openSession(openedSessionId!!)
    }

    val currentTier = remember(activeKey, prefs) {
        JulesViewModel.getTierLimits(activeKey?.planTier?.ifBlank { prefs["jules_plan_tier"] } ?: prefs["jules_plan_tier"])
    }
    val used24h = remember(sessions) {
        JulesViewModel.countRolling24hSessions(sessions)
    }
    val remainingDaily = (currentTier.dailyLimit - used24h).coerceAtLeast(0)

    LaunchedEffect(initialSessionId) {
        if (!initialSessionId.isNullOrBlank()) {
            viewModel.openSession(initialSessionId)
            openedSessionId = initialSessionId
        }
    }

    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // 첫 실행 시 저장된 활성 API 키가 없으면 즉시 온보딩 화면 노출
    ProvideJulesLocalization(langPref = prefs["app_language"]) {
        if (activeKey == null) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { pad ->
                Box(modifier = Modifier.padding(pad)) {
                    ApiKeyOnboardingScreen(viewModel = viewModel)
                }
            }
            return@ProvideJulesLocalization
        }

        // 세션 상세 화면이 열려 있으면 뒤로가기 핸들링과 함께 이중 Scaffold 인셋 중첩 없이 렌더링
        if (openedSessionId != null) {
            val handleCloseSession = {
                viewModel.closeSession()
                openedSessionId = null
            }
            BackHandler(onBack = handleCloseSession)
            SessionDetailScreen(
                viewModel = viewModel,
                onBack = handleCloseSession,
                snackbarHostState = snackbarHostState
            )
            return@ProvideJulesLocalization
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                JulesOctopusLogo(size = 22.dp, tint = JulesPrimary)
                                Text(
                                    text = stringResource(R.string.app_name),
                                    fontSize = 17.sp,
                                    color = JulesTextPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "•",
                                    color = JulesTextMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = when (currentTab) {
                                        0 -> stringResource(R.string.nav_dashboard)
                                        1 -> stringResource(R.string.nav_sessions)
                                        2 -> stringResource(R.string.nav_sources)
                                        else -> stringResource(R.string.nav_settings)
                                    },
                                    fontSize = 13.sp,
                                    color = JulesTextMuted,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            // 상단 바 직관적 남은 일일 작업량(Quota) 칩
                            Surface(
                                onClick = { currentTab = 3 },
                                color = JulesSurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, JulesBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = stringResource(R.string.top_remaining_quota),
                                        tint = if (remainingDaily > 3) JulesSuccess else JulesWarning,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "${remainingDaily}/${currentTier.dailyLimit}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = JulesTextPrimary
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.refreshAll() }) {
                                Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.top_sync_all), tint = JulesPrimary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = JulesSurface
                        )
                    )
                    HorizontalDivider(color = JulesBorder)
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = JulesBorder)
                    NavigationBar(
                        containerColor = JulesSurface
                    ) {
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            icon = { Icon(Icons.Default.Dashboard, contentDescription = stringResource(R.string.nav_dashboard)) },
                            label = { Text(stringResource(R.string.nav_dashboard), fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = stringResource(R.string.nav_sessions)) },
                            label = { Text(stringResource(R.string.nav_sessions), fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = { Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.nav_sources)) },
                            label = { Text(stringResource(R.string.nav_sources), fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )
                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                            label = { Text(stringResource(R.string.nav_settings_api), fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    0 -> DashboardScreen(
                        viewModel = viewModel,
                        onOpenSession = { id ->
                            viewModel.openSession(id)
                            openedSessionId = id
                        }
                    )
                    1 -> SessionsListScreen(
                        viewModel = viewModel,
                        onOpenSession = { id ->
                            viewModel.openSession(id)
                            openedSessionId = id
                        }
                    )
                    2 -> SourcesScreen(viewModel = viewModel)
                    3 -> SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
