package com.jules.code.ui

import android.content.Context
import androidx.annotation.StringRes

/** 화면 표시 시점의 언어로 해석할 문구와 서버·사용자 원문을 구분합니다. */
sealed interface UiText {
    /** 번역 리소스와 동적 인자를 보존하여 ViewModel의 언어 의존성을 없앱니다. */
    data class Resource(@StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiText

    /** 서버 오류나 사용자가 작성한 본문처럼 번역하지 않을 원문입니다. */
    data class Literal(val value: String) : UiText

    /** 현재 화면의 지역화된 Context로 문구를 렌더링합니다. */
    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(id, *arguments.toTypedArray())
        is Literal -> value
    }
}
