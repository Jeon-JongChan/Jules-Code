package com.jules.code.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.RandomAccessFile

/** UTF-16 원문 파일의 지정 구간만 읽고 서로게이트 쌍이 끊기지 않게 다음 위치를 돌려줍니다. */
fun readTextFilePage(path: String, start: Long): Pair<String, Long> = RandomAccessFile(path, "r").use { file ->
    require(start >= 0 && start <= file.length() / 2) { "원문 위치가 올바르지 않습니다." }
    file.seek(start * 2)
    val bytes = ByteArray(minOf(4002L, file.length() - file.filePointer).toInt())
    file.readFully(bytes)
    val text = bytes.toString(Charsets.UTF_16LE)
    val visible = text.substring(0, textPageEnd(text, 0))
    visible to (start + visible.length)
}

/** 원문 전체를 메모리에 올리지 않고 파일에서 2,000자씩 탐색하고 복사합니다. */
@Composable
fun FileSelectableContent(path: String, diff: Boolean = false) {
    val starts = remember(path) { mutableStateListOf(0L) }
    var page by remember(path) { mutableIntStateOf(0) }
    val start = starts[page]
    val result by produceState<Result<Pair<String, Long>>?>(null, path, start) {
        value = null
        try { value = Result.success(withContext(Dispatchers.IO) { readTextFilePage(path, start) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { value = Result.failure(error) }
    }
    val current = result?.getOrNull()
    Column {
        when {
            result == null -> CircularProgressIndicator()
            current == null -> Text("원문 캐시를 읽지 못했습니다. 활동을 새로고침해 주세요.", color = MaterialTheme.colorScheme.error)
            else -> {
                SelectableContent(current.first, diff = diff, initiallyExpanded = true, copyLabel = "현재 구간 복사")
                Row {
                    IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.Default.ChevronLeft, "원문 이전 구간") }
                    Text("${page + 1}", Modifier.padding(top = 12.dp))
                    IconButton(enabled = current.second * 2 < File(path).length(), onClick = {
                        if (starts.size == page + 1) starts.add(current.second)
                        page++
                    }) { Icon(Icons.Default.ChevronRight, "원문 다음 구간") }
                }
            }
        }
    }
}

/** UTF-16 서로게이트 쌍을 보존하면서 최대 2,000자 페이지의 끝을 구합니다. */
fun textPageEnd(text: String, start: Int): Int {
    require(start in 0..text.length)
    val end = (start.toLong() + 2000).coerceAtMost(text.length.toLong()).toInt()
    return if (end < text.length && Character.isHighSurrogate(text[end - 1]) &&
        Character.isLowSurrogate(text[end])) end - 1 else end
}

/** 긴 글을 기본 접고, 펼쳐도 한 페이지만 배치하여 단일 행의 과대 측정을 방지합니다. */
@Composable
fun SelectableContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    diff: Boolean = false,
    initiallyExpanded: Boolean = false,
    copyLabel: String? = null
) {
    var expanded by remember(text) { mutableStateOf(initiallyExpanded) }
    var overflow by remember(text) { mutableStateOf(false) }
    val starts = remember(text) { mutableStateListOf(0) }
    var page by remember(text) { mutableIntStateOf(0) }
    val start = if (expanded) starts[page] else 0
    val end = textPageEnd(text, start)
    val visible = remember(text, start, end) { text.substring(start, end) }
    val annotated = remember(visible, diff) {
        buildAnnotatedString {
            if (!diff) append(visible) else visible.lineSequence().forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val tint = when {
                    line.startsWith("+") -> Color(0xFF56D364)
                    line.startsWith("-") -> Color(0xFFF85149)
                    line.startsWith("@@") -> Color(0xFF79C0FF)
                    else -> Color(0xFFC9D1D9)
                }
                withStyle(SpanStyle(color = tint)) { append(line) }
            }
        }
    }
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth()) {
        key(start, expanded) {
            SelectionContainer {
                Text(
                    text = annotated, style = style, color = color,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
                    modifier = if (expanded) Modifier.heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()) else Modifier
                )
            }
        }
        Row {
            if (overflow || end < text.length || expanded) {
                TextButton(onClick = { expanded = !expanded; page = 0 }) {
                    Text(if (expanded) "접기" else "펼치기")
                }
            }
            if (expanded && (page > 0 || end < text.length)) {
                IconButton(enabled = page > 0, onClick = { page-- }) {
                    Icon(Icons.Default.ChevronLeft, "이전 구간")
                }
                Text("${page + 1}", modifier = Modifier.padding(top = 12.dp))
                IconButton(enabled = end < text.length, onClick = {
                    if (starts.size == page + 1) starts.add(end)
                    page++
                }) { Icon(Icons.Default.ChevronRight, "다음 구간") }
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(visible)) }) {
                Icon(Icons.Default.ContentCopy, copyLabel ?: if (start == 0 && end == text.length) "전체 글 복사" else "현재 구간 복사")
            }
        }
    }
}

/** 비밀 키 값을 노출하지 않고 등록된 프로필을 상단 메뉴에서 전환합니다. */
@Composable
fun ApiKeyQuickSwitch(viewModel: JulesViewModel, onSwitch: () -> Unit = {}) {
    val keys by viewModel.apiKeys.collectAsState()
    val active by viewModel.activeApiKey.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (keys.size < 2) return
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Key, "API 키 빠른 전환: ${active?.label.orEmpty()}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            keys.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(if (profile.id == active?.id) Icons.Default.CheckCircle else Icons.Default.Key, null) },
                    onClick = {
                        open = false
                        if (profile.id != active?.id) {
                            onSwitch()
                            viewModel.switchApiKey(profile.id)
                        }
                    }
                )
            }
        }
    }
}
