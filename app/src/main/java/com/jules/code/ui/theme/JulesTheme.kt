package com.jules.code.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Google Jules & OpenAI Codex 스타일 딥 옵시디언 & 퍼플/인디고 팔레트
val JulesBrandPurple = Color(0xFF715CD7)
val JulesPrimary = Color(0xFF9D8DF1)
val JulesSecondary = Color(0xFF79C0FF)
val JulesTertiary = Color(0xFFD2A8FF)
val JulesBackground = Color(0xFF090B10)
val JulesSurface = Color(0xFF11151F)
val JulesSurfaceElevated = Color(0xFF171C29)
val JulesSurfaceVariant = Color(0xFF1E2536)
val JulesBorder = Color(0xFF283044)
val JulesBorderBright = Color(0xFF433978)
val JulesSuccess = Color(0xFF3FB950)
val JulesWarning = Color(0xFFD29922)
val JulesDanger = Color(0xFFF85149)
val JulesTextPrimary = Color(0xFFF0F4F8)
val JulesTextMuted = Color(0xFF8B95A7)

private val JulesDarkColorScheme = darkColorScheme(
    primary = JulesPrimary,
    secondary = JulesSecondary,
    tertiary = JulesTertiary,
    background = JulesBackground,
    surface = JulesSurface,
    surfaceVariant = JulesSurfaceVariant,
    outline = JulesBorder,
    onPrimary = Color(0xFF0D0924),
    onBackground = JulesTextPrimary,
    onSurface = JulesTextPrimary,
    onSurfaceVariant = JulesTextMuted
)

/**
 * Google Jules & OpenAI Codex 웹 대시보드의 하이콘트라스트 다크 코딩 에이전트 감성을
 * 모바일에 맞게 최적화한 Material3 테마입니다.
 */
@Composable
fun JulesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JulesDarkColorScheme,
        content = content
    )
}

/**
 * Google Jules 공식 문어(Octopus) 마스코트를 벡터 캔버스로 그리는 커스텀 아이콘 컴포넌트입니다.
 */
@Composable
fun JulesOctopusLogo(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    tint: Color = JulesBrandPurple
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // 돔 형태의 머리 + 몸통
        val headPath = Path().apply {
            moveTo(w * 0.24f, h * 0.58f)
            lineTo(w * 0.24f, h * 0.42f)
            cubicTo(
                w * 0.24f, h * 0.14f,
                w * 0.76f, h * 0.14f,
                w * 0.76f, h * 0.42f
            )
            lineTo(w * 0.76f, h * 0.58f)
            close()
        }
        drawPath(path = headPath, color = tint)

        val legStroke = Stroke(width = w * 0.115f, cap = StrokeCap.Round)

        // 왼쪽 바깥 말린 다리
        val leftCurl = Path().apply {
            moveTo(w * 0.295f, h * 0.54f)
            lineTo(w * 0.295f, h * 0.74f)
            cubicTo(
                w * 0.295f, h * 0.87f,
                w * 0.12f, h * 0.87f,
                w * 0.12f, h * 0.73f
            )
        }
        drawPath(path = leftCurl, color = tint, style = legStroke)

        // 오른쪽 바깥 말린 다리
        val rightCurl = Path().apply {
            moveTo(w * 0.705f, h * 0.54f)
            lineTo(w * 0.705f, h * 0.74f)
            cubicTo(
                w * 0.705f, h * 0.87f,
                w * 0.88f, h * 0.87f,
                w * 0.88f, h * 0.73f
            )
        }
        drawPath(path = rightCurl, color = tint, style = legStroke)

        // 안쪽 두 다리
        drawLine(
            color = tint,
            start = Offset(w * 0.435f, h * 0.54f),
            end = Offset(w * 0.435f, h * 0.76f),
            strokeWidth = w * 0.115f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.565f, h * 0.54f),
            end = Offset(w * 0.565f, h * 0.76f),
            strokeWidth = w * 0.115f,
            cap = StrokeCap.Round
        )

        // 흰색 눈 두 개
        drawCircle(
            color = Color.White,
            radius = w * 0.065f,
            center = Offset(w * 0.39f, h * 0.42f)
        )
        drawCircle(
            color = Color.White,
            radius = w * 0.065f,
            center = Offset(w * 0.61f, h * 0.42f)
        )
    }
}
