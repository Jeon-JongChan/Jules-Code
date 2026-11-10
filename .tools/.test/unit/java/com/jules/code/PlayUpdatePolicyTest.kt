package com.jules.code

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.jules.code.ui.updates.PlayUpdateStatus
import com.jules.code.ui.updates.resolvePlayUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 스토어 갱신 지연·다운로드 복구·미지원 설치의 경계에서 잘못된 업데이트 안내를 방지합니다. */
class PlayUpdatePolicyTest {
    /** 서버가 업데이트 가능으로 표시해도 동일하거나 낮은 버전 코드는 권하지 않습니다. */
    @Test
    fun neverOffersTheInstalledOrAnOlderVersion() {
        for (remote in listOf(1, 4, 5)) {
            assertEquals(PlayUpdateStatus.CURRENT, resolvePlayUpdateStatus(5, remote,
                UpdateAvailability.UPDATE_AVAILABLE, InstallStatus.UNKNOWN, true))
        }
        assertEquals(PlayUpdateStatus.AVAILABLE, resolvePlayUpdateStatus(5, 6,
            UpdateAvailability.UPDATE_AVAILABLE, InstallStatus.UNKNOWN, true))
    }

    /** 버전 목록이 불완전해도 이미 다운로드한 업데이트의 설치 버튼을 복구합니다. */
    @Test
    fun downloadedUpdateSurvivesAnUnknownAvailability() {
        assertEquals(PlayUpdateStatus.READY, resolvePlayUpdateStatus(5, 0,
            UpdateAvailability.UNKNOWN, InstallStatus.DOWNLOADED, false))
    }

    /** Play가 앱 내 설치를 허용하지 않는 새 버전은 스토어로 안내합니다. */
    @Test
    fun restrictedUpdateUsesTheStoreInsteadOfReportingCurrent() {
        assertEquals(PlayUpdateStatus.STORE_REQUIRED, resolvePlayUpdateStatus(5, 6,
            UpdateAvailability.UPDATE_AVAILABLE, InstallStatus.UNKNOWN, false))
    }

    /** 확인할 수 없는 응답을 최신 버전으로 오인하지 않습니다. */
    @Test
    fun unknownResponseNeverReportsUpToDate() {
        assertEquals(PlayUpdateStatus.ERROR, resolvePlayUpdateStatus(5, 0,
            UpdateAvailability.UNKNOWN, InstallStatus.UNKNOWN, false))
    }

    /** 중단된 즉시 업데이트는 일반 다운로드 허용 값과 관계없이 복구할 수 있습니다. */
    @Test
    fun resumesAnAlreadyAcceptedImmediateUpdate() {
        assertEquals(PlayUpdateStatus.AVAILABLE, resolvePlayUpdateStatus(5, 6,
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, InstallStatus.UNKNOWN, false))
    }

    /** 다운로드 취소 직후 새 버전 팝업을 다시 띄우지 않습니다. */
    @Test
    fun cancellationTakesPriorityOverAnAvailableVersion() {
        assertEquals(PlayUpdateStatus.CANCELLED, resolvePlayUpdateStatus(5, 6,
            UpdateAvailability.UPDATE_AVAILABLE, InstallStatus.CANCELED, true))
    }
}
