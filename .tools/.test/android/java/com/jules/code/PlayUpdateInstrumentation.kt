package com.jules.code

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.jules.code.ui.updates.PlayUpdateStatus
import com.jules.code.ui.updates.PlayUpdateUiState
import com.jules.code.ui.updates.PlayUpdateViewModel

/** 공식 FakeAppUpdateManager로 실제 Android에서 동의·취소·다운로드·설치 복구를 검증합니다. */
class PlayUpdateInstrumentation : Instrumentation() {
    /** 외부 계정이나 실제 설치 작업 없이 검증 스레드를 시작합니다. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    /** 검증 결과만 출력하며 실제 Play 스토어나 앱 사용자 데이터를 변경하지 않습니다. */
    override fun onStart() {
        super.onStart()
        val results = Bundle()
        try {
            verifyFlexibleFlow()
            results.putString("result", "PASS: consent, cancellation, download progress, restart, cleanup")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            results.putString("location", error.stackTrace.take(5).joinToString("\n"))
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    /** 메인 스레드 검사 실패를 검증 결과로 반환하여 프로세스 종료나 대기가 발생하지 않게 합니다. */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    /** 동의 취소 후 새 정보로 재시도하고 준비된 업데이트만 재시작 설치하는지 확인합니다. */
    private fun verifyFlexibleFlow() {
        val fake = FakeAppUpdateManager(targetContext)
        fake.setUpdateAvailable(6, AppUpdateType.FLEXIBLE)
        var pending = false
        var automatic = true
        val updates = PlayUpdateViewModel(fake, PlayUpdateUiState("fixture", 5, true), true,
            "com.jules.code", { automatic = it }, { pending = it }, false)
        // 실제 ViewModel 종료 경로에서 SDK 리스너도 해제되도록 소유자를 연결합니다.
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            /** 리스너 해제를 포함한 실제 ViewModel 수명 종료를 검증할 소유자에 연결합니다. */
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = updates as T
        }
        ViewModelProvider(owner, factory)[PlayUpdateViewModel::class.java]
        val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
            /** 가짜 SDK가 실제 Play 화면이나 설치자를 실행하지 않음을 보장합니다. */
            override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {
                error("실제 설치 화면을 실행하면 안 됩니다.")
            }

            /** 실제 Activity에 등록하지 않은 테스트 런처이므로 해제할 자원이 없습니다. */
            override fun unregister() = Unit

            /** 제품 코드와 동일한 Activity Result 계약으로 SDK 진입점을 검사합니다. */
            override val contract: ActivityResultContract<IntentSenderRequest, *> =
                ActivityResultContracts.StartIntentSenderForResult()
        }
        try {
            onMain {
                updates.setAutomatic(false)
                updates.onResume()
            }
            check(!automatic && updates.state.value.status == PlayUpdateStatus.IDLE) { "자동 확인 끄기 실패" }
            onMain { updates.check() }
            waitForIdleSync()
            check(updates.state.value.status == PlayUpdateStatus.AVAILABLE) { "새 버전 확인 실패" }
            onMain {
                updates.startUpdate(launcher)
                check(fake.isConfirmationDialogVisible) { "Play 동의 화면 연결 실패" }
                fake.userRejectsUpdate()
                updates.onFlowResult(Activity.RESULT_CANCELED)
            }
            check(updates.state.value.status == PlayUpdateStatus.CANCELLED && !pending) { "취소 복구 실패" }
            onMain { updates.check(manual = true) }
            waitForIdleSync()
            onMain {
                updates.startUpdate(launcher)
                fake.userAcceptsUpdate()
                updates.onFlowResult(Activity.RESULT_OK)
                fake.downloadStarts()
                fake.setTotalBytesToDownload(100)
                fake.setBytesDownloaded(25)
            }
            waitForIdleSync()
            check(updates.state.value.progress == 0.25f && pending) { "진행률 또는 복구 상태 저장 실패" }
            onMain { fake.downloadCompletes() }
            waitForIdleSync()
            check(updates.state.value.status == PlayUpdateStatus.READY && updates.state.value.showReady) { "설치 준비 안내 실패" }
            onMain {
                updates.dismissReady()
                check(updates.state.value.status == PlayUpdateStatus.READY) { "나중에 설치 상태 유실" }
                updates.completeUpdate()
            }
            waitForIdleSync()
            check(fake.isInstallSplashScreenVisible) { "재시작 설치 요청 실패" }
            onMain { fake.installCompletes() }
            waitForIdleSync()
            check(updates.state.value.status == PlayUpdateStatus.CURRENT && !pending) { "설치 완료 복구 실패" }
        } finally {
            onMain { owner.viewModelStore.clear() }
        }
    }
}
