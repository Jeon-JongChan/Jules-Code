package com.jules.code.ui.timeline

import com.jules.code.data.local.CachedSessionActivitiesEntity
import com.jules.code.data.local.CachedSessionEntity
import com.jules.code.data.local.JulesDao
import com.jules.code.data.model.JulesActivity
import com.jules.code.data.model.MessageEvent
import com.jules.code.data.model.sortActivitiesChronologically
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 세션 타임라인의 메모리·디스크·미반영 메시지를 소유합니다.
 * 삭제와 캐시 쓰기의 순서, Room 행 크기, 메모리 상한을 같은 위치에서 보장합니다.
 */
class SessionTimelineStore(private val dao: JulesDao, private val json: Json) {
    private val cacheMutex = Mutex()
    private val deleted = Collections.synchronizedSet(mutableSetOf<String>())
    private val memory = ConcurrentHashMap<String, List<JulesActivity>>()
    private val optimistic = ConcurrentHashMap<String, CopyOnWriteArrayList<JulesActivity>>()

    /** 삭제 표식이 있는 세션의 늦은 조회나 화면 재진입을 차단합니다. */
    fun isDeleted(sessionId: String): Boolean = sessionId in deleted

    /** 재진입 화면에서 즉시 표시할 활동 스냅샷을 반환합니다. */
    fun cached(sessionId: String): List<JulesActivity>? = memory[sessionId]

    /** 서버 인덱스에 아직 반영되지 않은 사용자 메시지의 스냅샷을 반환합니다. */
    fun pending(sessionId: String): List<JulesActivity> = optimistic[sessionId]?.toList().orEmpty()

    /** 서버 반영 전까지 사용자 메시지를 화면에 유지합니다. */
    @Synchronized
    fun enqueue(sessionId: String, activity: JulesActivity) {
        if (isDeleted(sessionId)) return
        optimistic.getOrPut(sessionId) { CopyOnWriteArrayList() }.add(activity)
    }

    /** 전송 실패한 메시지만 제거하여 다른 미반영 메시지는 유지합니다. */
    fun reject(sessionId: String, activityId: String) {
        optimistic[sessionId]?.removeAll { it.id == activityId }
    }

    /** 디스크 복원과 데모 활동도 현재 화면을 보존하며 최대 네 세션만 유지합니다. */
    @Synchronized
    fun remember(sessionId: String, items: List<JulesActivity>, selectedSessionId: String?) {
        if (isDeleted(sessionId)) return
        memory[sessionId] = items
        memory.keys.filter { it != sessionId && it != selectedSessionId }
            .take((memory.size - 4).coerceAtLeast(0))
            .forEach { memory.remove(it) }
    }

    /** 서버 원문은 보존하고, 서버에 확인된 미반영 메시지만 제거한 뒤 시간순으로 합칩니다. */
    fun merge(sessionId: String, serverActivities: List<JulesActivity>): List<JulesActivity> {
        val normalized = serverActivities.map { activity ->
            activity.resolvedUserMessage?.let { message ->
                activity.copy(userMessaged = MessageEvent(message = message), userMessage = null)
            } ?: activity
        }
        val pendingList = optimistic[sessionId]
        if (pendingList.isNullOrEmpty()) return sortActivitiesChronologically(normalized)
        val serverTexts = normalized.mapNotNull { it.resolvedUserMessage?.trim() }.toSet()
        pendingList.removeAll { activity ->
            val text = activity.resolvedUserMessage?.trim().orEmpty()
            text.isEmpty() || serverTexts.any { it == text || it.startsWith(text) }
        }
        return sortActivitiesChronologically(normalized + pendingList)
    }

    /** 삭제 이후 도착한 목록을 걸러내고, 프로필 전체 교체도 동일한 쓰기 순서를 따르게 합니다. */
    suspend fun cacheSessions(items: List<CachedSessionEntity>, replaceForKey: Long? = null) = cacheMutex.withLock {
        val remaining = items.filterNot { isDeleted(it.sessionId) }
        if (replaceForKey == null) dao.upsertSessions(remaining)
        else dao.replaceSessionsForApiKey(replaceForKey, remaining)
    }

    /** 선택적 디스크 저장 실패는 허용하되 취소와 512KiB 행 제한, 삭제 직렬화는 보존합니다. */
    suspend fun cacheActivities(sessionId: String, items: List<JulesActivity>, selectedSessionId: String?) = cacheMutex.withLock {
        if (isDeleted(sessionId)) return@withLock
        val encoded = withContext(Dispatchers.Default) { json.encodeToString(items) }
        if (encoded.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
            try {
                dao.upsertCachedActivities(CachedSessionActivitiesEntity(sessionId, encoded))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 선택적 캐시 실패가 정상 네트워크 결과와 화면 표시를 중단하지 않게 합니다.
            }
        }
        remember(sessionId, items, selectedSessionId)
    }

    /** 캐시 쓰기와 직렬화된 삭제 표식으로 늦은 응답의 세션 복원을 차단합니다. */
    suspend fun deleteSession(sessionId: String) = cacheMutex.withLock {
        synchronized(this@SessionTimelineStore) { deleted.add(sessionId) }
        dao.deleteSessionCache(sessionId)
        memory.remove(sessionId)
        optimistic.remove(sessionId)
        Unit
    }

    /** 삭제 실패 시 기존 동작처럼 표식을 해제하여 사용자가 재시도할 수 있게 합니다. */
    suspend fun rollbackDeletion(sessionId: String) = cacheMutex.withLock {
        deleted.remove(sessionId)
        Unit
    }
}
