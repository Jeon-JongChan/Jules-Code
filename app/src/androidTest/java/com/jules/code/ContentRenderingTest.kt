package com.jules.code

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.compose.ui.geometry.Offset
import android.util.Base64
import android.widget.VideoView
import android.content.ClipboardManager
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import com.jules.code.data.local.JulesDatabase
import com.jules.code.ui.ApiKeyQuickSwitch
import com.jules.code.ui.JulesViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import java.io.File
import com.jules.code.data.model.MediaArtifact
import com.jules.code.ui.SelectableContent
import com.jules.code.ui.screens.MediaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Compose 레이아웃에서 긴 단일 행과 잘못된 영상의 안전한 처리를 검증합니다. */
@RunWith(AndroidJUnit4::class)
class ContentRenderingTest {
    @get:Rule
    val rule = createComposeRule()

    /** 길게 눌렀을 때 Android 기본 선택·복사 메뉴가 나타나는지 확인합니다. */
    @Test
    fun longPressOffersNativeCopyMenu() {
        rule.setContent { MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(24.dp)) { SelectableContent("선택 가능한 문장을 길게 눌러 복사합니다.") }
            }
        } }
        rule.onNodeWithText("선택 가능한 문장을 길게 눌러 복사합니다.")
            .performTouchInput { longClick(Offset(12f, 12f)) }
        rule.waitForIdle()
        capture("text-selection")
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
    }

    /** 백만 자를 펼쳐도 제한된 구간만 표시하고 원문의 끝까지 탐색할 수 있습니다. */
    @Test
    fun millionCharacterMessageCanExpandAndPage() {
        rule.setContent { MaterialTheme { SelectableContent("가".repeat(1_000_000) + "끝") } }
        rule.onNodeWithText("펼치기").performClick()
        rule.onNodeWithContentDescription("다음 구간").performClick()
        rule.onNodeWithText("2", useUnmergedTree = true).assertExists()
        capture("long-content")
        rule.onNodeWithContentDescription("현재 구간 복사").performClick()
        var copiedLength = 0
        rule.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            copiedLength = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.text?.length ?: 0
        }
        assertEquals(2000, copiedLength)
        rule.onNodeWithContentDescription("이전 구간").performClick()
        rule.onNodeWithText("접기").performClick()
        rule.onNodeWithText("펼치기").assertExists()
    }

    /** 잘못된 영상은 자동 디코딩하지 않고 사용자가 요청한 후 오류로 표시합니다. */
    @Test
    fun malformedVideoShowsErrorAfterRequest() {
        rule.setContent { MaterialTheme { MediaContent(MediaArtifact("video/mp4", "a")) } }
        rule.onNodeWithText("영상 재생").assertExists().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("미디어 오류:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("미디어 오류:", substring = true).assertExists()
    }

    /** AndroidX 샘플을 실제 VideoView로 디코딩하고 재생 위치가 전진하는지 확인합니다. */
    @Test
    fun attachedMp4ActuallyPlays() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp4").use { it.readBytes() }
        val media = MediaArtifact("video/mp4", Base64.encodeToString(bytes, Base64.NO_WRAP))
        rule.setContent { MaterialTheme { MediaContent(media) } }
        rule.onNodeWithText("영상 재생").performClick()
        rule.waitUntil(15_000) {
            var advanced = false
            try {
                onView(isAssignableFrom(VideoView::class.java)).check { view, error ->
                    if (error != null) throw error
                    val video = view as VideoView
                    advanced = video.currentPosition > 100
                }
            } catch (_: NoMatchingViewException) {
                // 비동기 첨부 준비 중에는 아직 네이티브 뷰가 없습니다.
            }
            advanced
        }
        rule.onAllNodesWithText("미디어 오류:", substring = true).assertCountEquals(0)
        capture("video-playback")
    }

    /** 퀵 메뉴의 선택이 실제 Room 활성 프로필을 변경하는지 확인합니다. */
    @Test
    fun quickSwitchActivatesSelectedProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, JulesDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            runBlocking {
                database.dao().saveAndActivateApiKey("검증 A", JulesViewModel.DEMO_API_KEY)
                database.dao().saveAndActivateApiKey("검증 B", JulesViewModel.DEMO_API_KEY)
            }
            val viewModel = JulesViewModel(database.dao())
            rule.runOnIdle { store.put("quick-switch", viewModel) }
            rule.setContent { MaterialTheme { ApiKeyQuickSwitch(viewModel) } }
            rule.waitUntil(5000) {
                rule.onAllNodesWithContentDescription("API 키 빠른 전환: 검증 B").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("API 키 빠른 전환: 검증 B").performClick()
            capture("quick-key-menu")
            rule.onNodeWithText("검증 A").performClick()
            rule.waitUntil(5000) { runBlocking { database.dao().getActiveApiKey()?.label == "검증 A" } }
            rule.onNodeWithContentDescription("API 키 빠른 전환: 검증 A").assertExists()
        } finally {
            rule.runOnIdle { store.clear() }
            database.close()
        }
    }

    /** 실제 기기 화면을 앱 전용 외부 경로에 저장해 UI 검증 근거를 남깁니다. */
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it).apply { mkdirs() } }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        File(output, "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
