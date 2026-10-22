package com.jules.code.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.jules.code.R
import com.jules.code.ui.ApiKeySyncStatus
import com.jules.code.ui.theme.JulesDanger
import com.jules.code.ui.theme.JulesTextMuted

/** 조회 전·진행·실패·성공을 구분하고 실패 시 기존 캐시가 표시됨을 알립니다. */
@Composable
fun ApiKeySyncStatusText(status: ApiKeySyncStatus?) {
    val text = when {
        status?.loading == true -> stringResource(R.string.key_sync_loading)
        status?.error != null -> stringResource(R.string.key_sync_error, status.error)
        status?.updatedAt != null -> stringResource(R.string.key_sync_success,
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(status.updatedAt)))
        else -> stringResource(R.string.key_sync_pending)
    }
    Text(text, fontSize = 11.sp, color = if (status?.error != null) JulesDanger else JulesTextMuted,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}
