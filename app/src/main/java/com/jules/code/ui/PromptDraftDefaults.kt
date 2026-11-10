package com.jules.code.ui

/** 사용자가 켠 기본 문구만 새 작성창에 복사하며 전송 시 본문에는 개입하지 않습니다. */
fun defaultPromptForDraft(preferences: Map<String, String>): String =
    if (preferences["default_prompt_enabled"] == "true") preferences["default_prompt_text"].orEmpty() else ""
