package com.jules.code

import com.jules.code.ui.textPageEnd
import com.jules.code.ui.JulesViewModel
import com.jules.code.data.remote.JulesApiClient
import org.junit.Assert.*
import org.junit.Test

/** 긴 단일 행과 이모지의 페이지 경계 및 미디어 요청 지시어를 검증합니다. */
class ContentPagingTest {
    /** 개행 없는 백만 자도 원문 손실 없이 제한된 크기로 나눕니다. */
    @Test
    fun longContentIsBoundedAndLossless() {
        val text = "가".repeat(1_000_000) + "😀끝"
        var offset = 0
        val restored = StringBuilder()
        while (offset < text.length) {
            val end = textPageEnd(text, offset)
            assertTrue(end > offset)
            assertTrue(end - offset <= 2000)
            assertFalse(Character.isHighSurrogate(text[end - 1]))
            restored.append(text.substring(offset, end))
            offset = end
        }
        assertEquals(text, restored.toString())
        assertEquals(0, textPageEnd("", 0))
        assertEquals(4, textPageEnd("짧은 글", 0))
        assertEquals(1999, textPageEnd("가".repeat(1999) + "😀", 0))
    }

    /** 최초 요청과 후속 요청 모두 실제 API 미디어 산출물을 요구합니다. */
    @Test
    fun mediaDirectiveAppliesToBothPromptPaths() {
        val prompt = JulesViewModel.buildEnrichedPrompt("영상 보여줘", "", false, false, "", "", "")
        val followUp = JulesViewModel.buildLocalizedFollowUpMessage("이미지 보여줘")
        listOf(prompt, followUp).forEach {
            assertTrue(it.contains("artifacts.media"))
            assertTrue(it.contains("mimeType"))
            assertTrue(it.contains("내부 검증"))
        }
        assertEquals("이미지 보여줘", JulesViewModel.stripInternalDirectives(followUp))
    }

    /** 이미지와 영상의 MIME 및 Base64 데이터를 파서가 그대로 보존합니다. */
    @Test
    fun mediaArtifactsPreserveImageAndVideoPayloads() {
        val response = JulesApiClient().parseActivitiesFlexible("""
            {"activities":[{"id":"media","artifacts":[
              {"media":{"mimeType":"image/png","data":"aGVsbG8="}},
              {"media":{"mimeType":"video/mp4","data":"dmlkZW8="}}
            ]}]}
        """.trimIndent())
        val artifacts = response.activities.single().artifacts
        assertEquals("image/png", artifacts[0].media?.mimeType)
        assertEquals("aGVsbG8=", artifacts[0].media?.data)
        assertEquals("video/mp4", artifacts[1].media?.mimeType)
        assertEquals("dmlkZW8=", artifacts[1].media?.data)
    }
}
