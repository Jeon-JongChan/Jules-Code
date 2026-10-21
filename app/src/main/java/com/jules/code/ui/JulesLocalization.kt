package com.jules.code.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * 스마트폰의 시스템 기본 국가/언어 설정을 자동으로 따라가거나(`SYSTEM`),
 * 사용자가 설정 탭에서 직접 지정한 언어(`ko` 한국어 / `en` 영어)로
 * `res/values/strings.xml` 및 `res/values-ko/strings.xml` 리소스를 실시간 전환하는 헬퍼입니다.
 */
object JulesLocaleManager {

    /** 지원되는 앱 언어 설정 옵션 */
    data class LanguageOption(
        val code: String,
        val displayLabelKo: String,
        val displayLabelEn: String
    )

    val LANGUAGE_OPTIONS = listOf(
        LanguageOption("SYSTEM", "스마트폰 기본 언어 따라가기 (자동)", "Follow Device Default (Auto)"),
        LanguageOption("ko", "한국어 (Korean)", "한국어 (Korean)"),
        LanguageOption("en", "영어 (English)", "English")
    )

    /**
     * 설정 코드(`SYSTEM`, `ko`, `en`)와 스마트폰 기본 Locale을 바탕으로 실제 적용할 `Locale`을 반환합니다.
     */
    fun resolveLocale(langPref: String?, deviceLocale: Locale = Locale.getDefault()): Locale {
        return when (langPref?.trim()) {
            "ko" -> Locale.KOREAN
            "en" -> Locale.ENGLISH
            else -> {
                if (deviceLocale.language.lowercase().startsWith("ko")) {
                    Locale.KOREAN
                } else {
                    Locale.ENGLISH
                }
            }
        }
    }

    /**
     * 현재 적용된 언어가 한국어(`ko`)인지 여부를 반환합니다.
     */
    fun isKorean(langPref: String?, deviceLocale: Locale = Locale.getDefault()): Boolean =
        resolveLocale(langPref, deviceLocale).language == "ko"

    /**
     * 지정된 `Locale`이 반영된 새로운 Android `Context`와 `Configuration`을 생성합니다.
     */
    fun createLocalizedContext(baseContext: Context, targetLocale: Locale): Pair<Context, Configuration> {
        Locale.setDefault(targetLocale)
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(targetLocale)
        val localizedCtx = baseContext.createConfigurationContext(config)
        return localizedCtx to config
    }
}

/**
 * 하위 Compose 트리 전체의 `stringResource(R.string.*)` 호출이
 * 스마트폰 기본 언어 또는 사용자가 선택한 언어(`ko`/`en`) 파일(`strings.xml`)을 참조하도록 감싸는 Provider입니다.
 */
@Composable
fun ProvideJulesLocalization(
    langPref: String?,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val deviceLocale = remember(baseConfig) {
        if (baseConfig.locales.isEmpty) Locale.getDefault() else baseConfig.locales[0]
    }
    val targetLocale = remember(langPref, deviceLocale) {
        JulesLocaleManager.resolveLocale(langPref, deviceLocale)
    }
    val (localizedContext, localizedConfig) = remember(baseContext, targetLocale) {
        JulesLocaleManager.createLocalizedContext(baseContext, targetLocale)
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig
    ) {
        content()
    }
}

