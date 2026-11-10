package com.jules.code.ui.updates

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/** 업데이트 확인·동의·다운로드·설치의 사용자 표시 상태입니다. */
enum class PlayUpdateStatus {
    IDLE, CHECKING, CURRENT, AVAILABLE, CONSENT, DOWNLOADING, READY, INSTALLING, CANCELLED, STORE_REQUIRED, ERROR
}

/** 진행 중인 설치를 먼저 복구하고 실제로 더 높은 버전 코드만 새 업데이트로 취급합니다. */
internal fun resolvePlayUpdateStatus(
    installedCode: Long,
    availableCode: Int,
    availability: Int,
    installStatus: Int,
    updateAllowed: Boolean
): PlayUpdateStatus = when {
    installStatus == InstallStatus.DOWNLOADED -> PlayUpdateStatus.READY
    installStatus == InstallStatus.INSTALLING -> PlayUpdateStatus.INSTALLING
    installStatus == InstallStatus.DOWNLOADING || installStatus == InstallStatus.PENDING -> PlayUpdateStatus.DOWNLOADING
    installStatus == InstallStatus.FAILED -> PlayUpdateStatus.ERROR
    installStatus == InstallStatus.CANCELED -> PlayUpdateStatus.CANCELLED
    availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> PlayUpdateStatus.AVAILABLE
    availability == UpdateAvailability.UPDATE_AVAILABLE && availableCode.toLong() > installedCode ->
        if (updateAllowed) PlayUpdateStatus.AVAILABLE else PlayUpdateStatus.STORE_REQUIRED
    availability == UpdateAvailability.UNKNOWN -> PlayUpdateStatus.ERROR
    else -> PlayUpdateStatus.CURRENT
}
