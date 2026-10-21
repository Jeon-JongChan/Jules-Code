package com.jules.code.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 웹 브라우저 및 GitHub 연동 인텐트 유틸리티입니다.
 * 앱 내부 인텐트 가로채기를 방지하고, 기기의 기본 웹 브라우저를 통해 URL을 안전하게 실행합니다.
 */
object JulesBrowserUtils {

    /**
     * 지정된 웹 URL을 기기의 기본 브라우저를 사용하여 안전하게 엽니다.
     *
     * @param context 안드로이드 컨텍스트
     * @param rawUrl 열고자 하는 대상 URL 문자열 (스킴 누락 시 https:// 자동 적용)
     * @return 브라우저 실행 성공 여부
     */
    fun openUrlInDefaultBrowser(context: Context, rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false

        // URL 스킴 자동 보정
        val url = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val uri = Uri.parse(url)
        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 기본 웹 브라우저 패키지를 탐색하여 앱 내부 인텐트 필터(jules.google.com 등)의 루프를 방지
        val pm = context.packageManager
        val testBrowserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val resolveInfo = pm.resolveActivity(testBrowserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val browserPackage = resolveInfo?.activityInfo?.packageName

        if (!browserPackage.isNullOrBlank() && browserPackage != context.packageName) {
            browserIntent.setPackage(browserPackage)
        }

        return try {
            context.startActivity(browserIntent)
            true
        } catch (_: Exception) {
            // 패키지 지정 실행 실패 시 범용 브라우저 인텐트로 재시도
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                true
            } catch (_: Exception) {
                // 시스템 선택기(Chooser)로 최종 폴백
                try {
                    val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    /**
     * GitHub 저장소 전체 이름(owner/repo)을 기반으로 GitHub 웹 페이지를 엽니다.
     *
     * @param context 안드로이드 컨텍스트
     * @param repoFullName owner/repo 형태의 저장소 이름 (예: Jeon-JongChan/Jules-Code)
     */
    fun openGitHubRepo(context: Context, repoFullName: String): Boolean {
        val cleanRepo = repoFullName.removePrefix("sources/github/").removePrefix("sources/").trim()
        if (cleanRepo.isEmpty() || cleanRepo.equals("repoless", ignoreCase = true) ||
            cleanRepo.contains("repoless", ignoreCase = true)
        ) {
            return false
        }
        return openUrlInDefaultBrowser(context, "https://github.com/$cleanRepo")
    }
}

