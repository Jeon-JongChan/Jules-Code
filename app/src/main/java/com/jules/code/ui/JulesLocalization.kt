package com.jules.code.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/** 지원 언어 해석과 리소스 적용을 담당하며 프로세스 전체 기본 언어는 변경하지 않습니다. */
object JulesLocaleManager {
    /** 설정 화면에서 자신의 언어로 식별할 수 있는 지원 언어 항목입니다. */
    data class LanguageOption(val code: String, val nativeLabel: String)

    val LANGUAGE_OPTIONS = listOf(
        LanguageOption("ko", "한국어"),
        LanguageOption("en", "English"),
        LanguageOption("es", "Español"),
        LanguageOption("ja", "日本語"),
        LanguageOption("pt", "Português"),
        LanguageOption("fr", "Français"),
        LanguageOption("de", "Deutsch"),
        LanguageOption("zh", "简体中文"),
        LanguageOption("hi", "हिन्दी"),
        LanguageOption("ar", "العربية"),
        LanguageOption("ru", "Русский"),
        LanguageOption("id", "Bahasa Indonesia")
    )

    /** 국가별 기기 언어를 지원 언어에 대응시키고 미지원 언어는 영어로 표시합니다. */
    fun resolveLocale(langPref: String?, deviceLocale: Locale = Locale.getDefault()): Locale {
        val requested = langPref?.takeUnless { it.equals("SYSTEM", ignoreCase = true) }
            ?.let { Locale.forLanguageTag(it.replace('_', '-')).language }
            ?: deviceLocale.language
        val code = if (requested == "in") "id" else requested
        return Locale.forLanguageTag(LANGUAGE_OPTIONS.firstOrNull { it.code == code }?.code ?: "en")
    }

    /** API 21~23의 단일 언어 설정과 API 24 이상의 언어 목록을 안전하게 읽습니다. */
    @Suppress("DEPRECATION")
    fun configurationLocale(configuration: Configuration): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (configuration.locales.isEmpty) Locale.ENGLISH else configuration.locales[0]
        } else configuration.locale ?: Locale.ENGLISH

    /** 복사한 Configuration에만 언어와 읽기 방향을 적용하여 SYSTEM 복귀를 보장합니다. */
    fun createLocalizedContext(baseContext: Context, targetLocale: Locale): Pair<Context, Configuration> {
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(targetLocale)
        config.setLayoutDirection(targetLocale)
        return baseContext.createConfigurationContext(config) to config
    }
}

/** 설정한 언어와 RTL 방향을 하위 Compose 화면에 함께 제공하며 시스템 언어와 격리합니다. */
@Composable
fun ProvideJulesLocalization(langPref: String?, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val deviceLocale = remember(baseConfig) { JulesLocaleManager.configurationLocale(baseConfig) }
    val targetLocale = remember(langPref, deviceLocale) { JulesLocaleManager.resolveLocale(langPref, deviceLocale) }
    // 회전·글꼴 크기 변경도 언어 적용 Context에 전달해 이전 화면 설정을 재사용하지 않습니다.
    val (localizedContext, localizedConfig) = remember(baseContext, targetLocale, baseConfig) {
        JulesLocaleManager.createLocalizedContext(baseContext, targetLocale)
    }
    val direction = if (localizedConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
        LocalLayoutDirection provides direction,
        content = content
    )
}
