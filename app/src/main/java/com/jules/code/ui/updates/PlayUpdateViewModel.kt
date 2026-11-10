package com.jules.code.ui.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 버전·진행률·사용자 알림 여부를 화면과 공유하는 업데이트 상태입니다. */
data class PlayUpdateUiState(
    val versionName: String,
    val installedCode: Long,
    val automatic: Boolean,
    val status: PlayUpdateStatus = PlayUpdateStatus.IDLE,
    val availableCode: Int? = null,
    val progress: Float? = null,
    val showOffer: Boolean = false,
    val showReady: Boolean = false,
    val resumeImmediate: Boolean = false
)

/** Play SDK 연결·설치 상태 복구·동의 취소와 설정 저장을 Activity 수명과 분리해 관리합니다. */
class PlayUpdateViewModel internal constructor(
    private val manager: AppUpdateManager,
    initialState: PlayUpdateUiState,
    private val installedFromPlay: Boolean,
    private val storePackage: String,
    private val saveAutomatic: (Boolean) -> Unit,
    private val savePending: (Boolean) -> Unit,
    private var pendingInstall: Boolean
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()
    private var checkedThisSession = false
    private var offerDismissed = false
    private var readyDismissed = false
    private var requestPending = false
    private var closed = false
    private var availableInfo: AppUpdateInfo? = null
    private var installRevision = 0L
    private val installListener = InstallStateUpdatedListener { installation ->
        if (!closed) {
            val status = when (installation.installStatus()) {
                InstallStatus.DOWNLOADED -> PlayUpdateStatus.READY
                InstallStatus.DOWNLOADING, InstallStatus.PENDING -> PlayUpdateStatus.DOWNLOADING
                InstallStatus.INSTALLING -> PlayUpdateStatus.INSTALLING
                InstallStatus.INSTALLED -> PlayUpdateStatus.CURRENT
                InstallStatus.CANCELED -> PlayUpdateStatus.CANCELLED
                InstallStatus.FAILED -> PlayUpdateStatus.ERROR
                else -> return@InstallStateUpdatedListener
            }
            installRevision++
            val total = installation.totalBytesToDownload()
            val progress = if (total > 0) (installation.bytesDownloaded().toFloat() / total).coerceIn(0f, 1f) else null
            updatePending(status in setOf(PlayUpdateStatus.DOWNLOADING, PlayUpdateStatus.READY, PlayUpdateStatus.INSTALLING))
            if (status == PlayUpdateStatus.CANCELLED) offerDismissed = true
            mutableState.value = mutableState.value.copy(status = status, progress = progress,
                showOffer = false, showReady = status == PlayUpdateStatus.READY && !readyDismissed)
        }
    }

    init {
        manager.registerListener(installListener)
    }

    /** 앱 복귀 시 기존 다운로드를 복구하며 일반 새 버전 확인은 앱 실행당 한 번만 수행합니다. */
    fun onResume() {
        if (pendingInstall || (state.value.automatic && !checkedThisSession)) check()
    }

    /** 자동 확인 설정을 즉시 저장하며 새로 켜면 버전을 확인합니다. */
    fun setAutomatic(enabled: Boolean) {
        saveAutomatic(enabled)
        mutableState.value = mutableState.value.copy(automatic = enabled,
            showOffer = enabled && state.value.showOffer)
        if (enabled) check()
    }

    /** 중복 요청을 막고 Play의 원격 버전 코드와 다운로드 상태를 조회합니다. */
    fun check(manual: Boolean = false) {
        if (requestPending || closed) return
        checkedThisSession = true
        if (manual) offerDismissed = false
        if (!installedFromPlay) {
            mutableState.value = state.value.copy(status = PlayUpdateStatus.STORE_REQUIRED, showOffer = false)
            return
        }
        requestPending = true
        // 조회 중 도착한 설치 알림이 더 최신이므로 늦은 조회 응답이 준비 완료 상태를 되돌리지 않게 합니다.
        val revisionAtRequest = installRevision
        mutableState.value = state.value.copy(status = PlayUpdateStatus.CHECKING)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            requestPending = false
            if (!closed && revisionAtRequest == installRevision) {
                availableInfo = info
                // 취소·실패 뒤 사용자가 다시 확인하면 새 Play 정보로 재시도할 수 있게 합니다.
                val installStatus = if (manual && info.installStatus() in setOf(InstallStatus.CANCELED, InstallStatus.FAILED))
                    InstallStatus.UNKNOWN else info.installStatus()
                val status = resolvePlayUpdateStatus(state.value.installedCode, info.availableVersionCode(),
                    info.updateAvailability(), installStatus,
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) || info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE))
                val resume = info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                updatePending(status in setOf(PlayUpdateStatus.DOWNLOADING, PlayUpdateStatus.READY, PlayUpdateStatus.INSTALLING) || resume)
                mutableState.value = state.value.copy(status = status, availableCode = info.availableVersionCode(),
                    showOffer = status == PlayUpdateStatus.AVAILABLE && !offerDismissed && state.value.automatic,
                    showReady = status == PlayUpdateStatus.READY && !readyDismissed, resumeImmediate = resume)
            }
        }.addOnFailureListener {
            requestPending = false
            if (!closed && revisionAtRequest == installRevision) mutableState.value = state.value.copy(status = PlayUpdateStatus.ERROR,
                showOffer = false, resumeImmediate = false)
        }
    }

    /** 한 번 사용한 업데이트 정보를 재사용하지 않고 Play의 동의 화면을 시작합니다. */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (state.value.status != PlayUpdateStatus.AVAILABLE) return
        val info = availableInfo ?: return check(manual = true)
        availableInfo = null
        offerDismissed = true
        val type = if (state.value.resumeImmediate || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE))
            AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        mutableState.value = state.value.copy(status = PlayUpdateStatus.CONSENT, showOffer = false, resumeImmediate = false)
        try {
            updatePending(true)
            if (!manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())) {
                updatePending(false)
                mutableState.value = state.value.copy(status = PlayUpdateStatus.ERROR)
            }
        } catch (_: Exception) {
            updatePending(false)
            mutableState.value = state.value.copy(status = PlayUpdateStatus.ERROR)
        }
    }

    /** 사용자가 동의를 취소하면 앱 사용을 계속 허용하고 자동 재요청을 막습니다. */
    fun onFlowResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK) {
            updatePending(false)
            offerDismissed = true
            mutableState.value = state.value.copy(status = if (resultCode == Activity.RESULT_CANCELED)
                PlayUpdateStatus.CANCELLED else PlayUpdateStatus.ERROR, showOffer = false)
        } else if (state.value.status == PlayUpdateStatus.CONSENT) {
            mutableState.value = state.value.copy(status = PlayUpdateStatus.DOWNLOADING)
        }
    }

    /** 다운로드 후 사용자가 재시작을 선택했을 때만 설치 완료를 요청합니다. */
    fun completeUpdate() {
        if (state.value.status != PlayUpdateStatus.READY) return
        mutableState.value = state.value.copy(status = PlayUpdateStatus.INSTALLING, showReady = false)
        manager.completeUpdate().addOnFailureListener {
            if (!closed) mutableState.value = state.value.copy(status = PlayUpdateStatus.ERROR)
        }
    }

    /** 이번 실행에서는 새 버전 안내를 다시 띄우지 않습니다. */
    fun dismissOffer() {
        offerDismissed = true
        mutableState.value = state.value.copy(showOffer = false)
    }

    /** 재시작을 나중으로 미뤄도 설정에서 설치를 계속할 수 있게 합니다. */
    fun dismissReady() {
        readyDismissed = true
        mutableState.value = state.value.copy(showReady = false)
    }

    /** Play 앱을 먼저 열고 사용할 수 없으면 같은 앱의 공식 웹 스토어 페이지를 엽니다. */
    fun openStore(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$storePackage"))
                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$storePackage"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                mutableState.value = state.value.copy(status = PlayUpdateStatus.ERROR)
            }
        }
    }

    /** 프로세스가 종료되어도 진행 중 다운로드를 다음 실행에서 다시 확인하도록 저장합니다. */
    private fun updatePending(pending: Boolean) {
        pendingInstall = pending
        savePending(pending)
    }

    /** 종료된 ViewModel로 설치 알림이 전달되지 않도록 리스너를 해제합니다. */
    override fun onCleared() {
        closed = true
        manager.unregisterListener(installListener)
        super.onCleared()
    }

    /** 앱 Context만 보관하며 표시 언어 Context나 Activity를 참조하지 않는 팩터리입니다. */
    companion object {
        /** 실제 설치 버전·설치 출처·자동 확인 설정으로 업데이트 관리자를 만듭니다. */
        @Suppress("DEPRECATION")
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                /** Activity 재생성 시 기존 ViewModel을 재사용하는 생성 경계입니다. */
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PlayUpdateViewModel::class.java))
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    val installer = if (Build.VERSION.SDK_INT >= 30)
                        app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
                    else app.packageManager.getInstallerPackageName(app.packageName)
                    val prefs = app.getSharedPreferences("play_updates", Context.MODE_PRIVATE)
                    return PlayUpdateViewModel(AppUpdateManagerFactory.create(app),
                        PlayUpdateUiState(info.versionName.orEmpty(), code, prefs.getBoolean("automatic", true)),
                        installer == "com.android.vending", app.packageName.removeSuffix(".debug"),
                        { prefs.edit().putBoolean("automatic", it).apply() },
                        { prefs.edit().putBoolean("pending", it).apply() }, prefs.getBoolean("pending", false)) as T
                }
            }
        }
    }
}
