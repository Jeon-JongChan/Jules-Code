package com.jules.code.ui.updates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jules.code.R

/** Play 동의 결과를 연결하고 새 버전·다운로드 완료 안내를 어느 화면에서도 제공합니다. */
@Composable
fun PlayUpdateHost(updates: PlayUpdateViewModel) {
    val state by updates.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        updates.onFlowResult(it.resultCode)
    }
    LaunchedEffect(state.resumeImmediate) {
        if (state.resumeImmediate) updates.startUpdate(launcher)
    }
    if (state.showOffer && !state.resumeImmediate) {
        AlertDialog(
            onDismissRequest = updates::dismissOffer,
            title = { Text(stringResource(R.string.update_available)) },
            text = { Text(stringResource(R.string.update_consent_description)) },
            confirmButton = {
                TextButton(onClick = { updates.startUpdate(launcher) }) { Text(stringResource(R.string.update_download)) }
            },
            dismissButton = {
                TextButton(onClick = updates::dismissOffer) { Text(stringResource(R.string.update_later)) }
            }
        )
    }
    if (state.showReady) {
        AlertDialog(
            onDismissRequest = updates::dismissReady,
            title = { Text(stringResource(R.string.update_ready)) },
            text = { Text(stringResource(R.string.update_restart_description)) },
            confirmButton = {
                TextButton(onClick = updates::completeUpdate) { Text(stringResource(R.string.update_restart)) }
            },
            dismissButton = {
                TextButton(onClick = updates::dismissReady) { Text(stringResource(R.string.update_later)) }
            }
        )
    }
}

/** 버전 확인·자동 확인 설정·설치 진행 상태와 스토어 이동을 한 설정 카드에 제공합니다. */
@Composable
fun PlayUpdateSettings(updates: PlayUpdateViewModel) {
    val context = LocalContext.current
    val state by updates.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        updates.onFlowResult(it.resultCode)
    }
    val busy = state.status in setOf(PlayUpdateStatus.CHECKING, PlayUpdateStatus.CONSENT,
        PlayUpdateStatus.DOWNLOADING, PlayUpdateStatus.INSTALLING)
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.update_current_version, state.versionName))
            Text(stringResource(R.string.update_play_description), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.update_automatic), modifier = Modifier.weight(1f))
                Switch(checked = state.automatic, onCheckedChange = updates::setAutomatic)
            }
            Text(stringResource(when (state.status) {
                PlayUpdateStatus.IDLE -> R.string.update_idle
                PlayUpdateStatus.CHECKING -> R.string.update_checking
                PlayUpdateStatus.CURRENT -> R.string.update_current
                PlayUpdateStatus.AVAILABLE -> R.string.update_available
                PlayUpdateStatus.CONSENT -> R.string.update_consent
                PlayUpdateStatus.DOWNLOADING -> R.string.update_downloading
                PlayUpdateStatus.READY -> R.string.update_ready
                PlayUpdateStatus.INSTALLING -> R.string.update_installing
                PlayUpdateStatus.CANCELLED -> R.string.update_cancelled
                PlayUpdateStatus.STORE_REQUIRED -> R.string.update_store_required
                PlayUpdateStatus.ERROR -> R.string.update_error
            }), style = MaterialTheme.typography.bodyMedium)
            if (state.status == PlayUpdateStatus.DOWNLOADING && state.progress != null) {
                LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            } else if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.status == PlayUpdateStatus.READY) {
                Button(onClick = updates::completeUpdate) { Text(stringResource(R.string.update_restart)) }
            } else if (state.status == PlayUpdateStatus.AVAILABLE) {
                Button(onClick = { updates.startUpdate(launcher) }) { Text(stringResource(R.string.update_download)) }
            } else {
                Button(onClick = { updates.check(manual = true) }, enabled = !busy) {
                    Text(stringResource(R.string.update_check))
                }
            }
            TextButton(onClick = { updates.openStore(context) }) { Text(stringResource(R.string.update_open_store)) }
        }
    }
}
