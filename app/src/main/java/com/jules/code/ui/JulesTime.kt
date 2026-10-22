package com.jules.code.ui

import com.jules.code.data.model.parseActivityTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서버 시각을 기기 시간대의 날짜·시간으로 표시하며 상대 시간과 해석 불가 문자열은 보존합니다. */
fun formatLocalTimestamp(value: String, zoneId: ZoneId = ZoneId.systemDefault()): String {
    if (value.isBlank()) return value
    val timestamp = parseActivityTimestamp(value)
    // 기존 파서의 상대 시간·해석 실패 표식을 실제 날짜로 표시하지 않습니다.
    if (timestamp == Long.MAX_VALUE - 1000L) return value
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(timestamp).atZone(zoneId))
}
