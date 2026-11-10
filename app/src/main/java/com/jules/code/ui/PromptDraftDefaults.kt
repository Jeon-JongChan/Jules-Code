package com.jules.code.ui

/** 작성창을 채우지 않고 전송 시 활성화된 기본 문구를 사용자 입력 뒤에 붙입니다. */
fun promptForSubmission(input: String, preferences: Map<String, String>): String {
    val defaultText = preferences["default_prompt_text"].orEmpty()
    // 빈 입력을 공통 문구만으로 전송하거나 빈 기본 문구로 원문을 바꾸지 않습니다.
    if (input.isBlank() || preferences["default_prompt_enabled"] != "true" || defaultText.isBlank()) return input
    return "$input\n\n$defaultText"
}
