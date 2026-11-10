package com.jules.code

import com.jules.code.data.local.JulesDao
import com.jules.code.data.local.CachedSessionEntity
import com.jules.code.data.model.JulesActivity
import com.jules.code.data.model.MessageEvent
import com.jules.code.ui.timeline.SessionTimelineStore
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 화면을 거치지 않고 타임라인 상태 소유자의 삭제·캐시 계약을 검증합니다. */
class SessionTimelineStoreTest {
    /** 삭제 뒤 도착한 조회 응답이 메모리나 디스크에 세션을 되살리지 못하게 합니다. */
    @Test fun deletedSessionRejectsLateActivities() = runBlocking {
        val writes = mutableListOf<String>()
        val store = SessionTimelineStore(dao(writes), Json)
        store.cacheActivities("one", listOf(activity("old")), "one")
        store.deleteSession("one")
        store.cacheActivities("one", listOf(activity("late")), "one")
        assertTrue(store.isDeleted("one"))
        assertNull(store.cached("one"))
        assertEquals(listOf("upsertCachedActivities", "deleteSessionCache"), writes)
    }

    /** 서버 반영 전의 메시지는 유지하고 확인된 메시지만 중복 없이 병합합니다. */
    @Test fun optimisticMessagesRemainUntilServerConfirmsThem() {
        val store = SessionTimelineStore(dao(mutableListOf()), Json)
        val first = activity("first")
        val second = activity("second").copy(createTime = "2026-11-10T00:01:00Z")
        store.enqueue("one", first)
        store.enqueue("one", second)
        assertEquals(listOf(first, second), store.merge("one", emptyList()))
        val server = first.copy(id = "server-first")
        assertEquals(listOf(server, second), store.merge("one", listOf(server)))
        assertEquals(listOf(second), store.pending("one"))
        store.reject("one", second.id)
        assertTrue(store.pending("one").isEmpty())
    }

    /** 구분자처럼 보이는 사용자 원문도 타임라인 병합에서 절단하지 않습니다. */
    @Test fun mergePreservesUserTextAndExistingPrefixConfirmation() {
        val store = SessionTimelineStore(dao(mutableListOf()), Json)
        val text = "사용자 원문\n\n[Language Setting: user content]"
        store.enqueue("one", activity(text))
        val server = activity("$text\n서버 추가 내용")
        assertEquals(listOf(server), store.merge("one", listOf(server)))
        assertEquals(server.resolvedUserMessage, store.merge("one", listOf(server)).single().resolvedUserMessage)
        assertTrue(store.pending("one").isEmpty())
    }

    /** 다섯 번째 세션을 저장해도 선택 세션과 최신 세션은 남고 전체는 네 개로 제한합니다. */
    @Test fun memoryLimitPreservesSelectedAndNewestSession() {
        val store = SessionTimelineStore(dao(mutableListOf()), Json)
        (1..5).forEach { store.remember("session-$it", listOf(activity("$it")), "session-1") }
        assertNotNull(store.cached("session-1"))
        assertNotNull(store.cached("session-5"))
        assertEquals(4, (1..5).count { store.cached("session-$it") != null })
    }

    /** 문자 수가 작아도 UTF-8 바이트가 큰 한글은 Room에 저장하지 않고 메모리에는 유지합니다. */
    @Test fun oversizedUtf8ActivitySkipsDiskButRemainsVisible() = runBlocking {
        val writes = mutableListOf<String>()
        val store = SessionTimelineStore(dao(writes), Json)
        val items = listOf(activity("가".repeat(180_000)))
        store.cacheActivities("one", items, "one")
        assertTrue(writes.isEmpty())
        assertEquals(items, store.cached("one"))
    }

    /** 선택적 디스크 오류가 정상 수신 활동의 메모리 표시를 중단하지 않게 합니다. */
    @Test fun diskFailureStillPublishesMemory() = runBlocking {
        val store = SessionTimelineStore(dao(mutableListOf()) { _, _ -> error("disk full") }, Json)
        val items = listOf(activity("visible"))
        store.cacheActivities("one", items, "one")
        assertEquals(items, store.cached("one"))
    }

    /** 디스크 쓰기 중 코루틴 취소는 일반 캐시 실패로 삼키지 않으며 잠금도 해제합니다. */
    @Test fun cancellationPropagatesAndReleasesCacheLock() = runBlocking {
        val cancellation = CancellationException("cancel cache")
        var failDisk = true
        val store = SessionTimelineStore(dao(mutableListOf()) { _, _ ->
            if (failDisk) throw cancellation
            Unit
        }, Json)
        try {
            store.cacheActivities("one", listOf(activity("cancelled")), "one")
            fail("취소가 호출자에게 전달되어야 합니다.")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertNull(store.cached("one"))
        failDisk = false
        store.cacheActivities("one", listOf(activity("retry")), "one")
        assertNotNull(store.cached("one"))
    }

    /** 이미 시작한 캐시 쓰기 뒤에 삭제를 직렬화하여 쓰기 완료가 삭제를 취소하지 않게 합니다. */
    @Test(timeout = 10_000) fun deletionWaitsForInFlightDiskWrite() = runBlocking {
        val started = CompletableDeferred<Continuation<Unit>>()
        val writes = mutableListOf<String>()
        val store = SessionTimelineStore(dao(writes) { name, args ->
            if (name == "upsertCachedActivities") {
                @Suppress("UNCHECKED_CAST")
                started.complete(args.last() as Continuation<Unit>)
                COROUTINE_SUSPENDED
            } else Unit
        }, Json)
        val writing = launch { store.cacheActivities("one", listOf(activity("old")), "one") }
        val continuation = started.await()
        val deleting = launch(start = CoroutineStart.UNDISPATCHED) { store.deleteSession("one") }
        assertFalse(deleting.isCompleted)
        continuation.resume(Unit)
        writing.join()
        deleting.join()
        assertTrue(store.isDeleted("one"))
        assertNull(store.cached("one"))
        assertEquals(listOf("upsertCachedActivities", "deleteSessionCache"), writes)
    }

    /** 삭제 정리 시 미반영 메시지도 사라지고 실패 표식 해제 후에는 재시도할 수 있습니다. */
    @Test fun deletionClearsPendingAndRollbackAllowsRetry() = runBlocking {
        val store = SessionTimelineStore(dao(mutableListOf()), Json)
        store.enqueue("one", activity("pending"))
        store.deleteSession("one")
        store.enqueue("one", activity("late"))
        assertTrue(store.pending("one").isEmpty())
        store.rollbackDeletion("one")
        store.cacheActivities("one", listOf(activity("retry")), "one")
        assertFalse(store.isDeleted("one"))
        assertNotNull(store.cached("one"))
    }

    /** 증분 쓰기와 프로필 전체 교체 모두 삭제된 세션을 디스크에 재삽입하지 않게 합니다. */
    @Test fun sessionWritesFilterDeletedIdsForBothPersistenceModes() = runBlocking {
        val stored = mutableListOf<List<CachedSessionEntity>>()
        val writes = mutableListOf<String>()
        val store = SessionTimelineStore(dao(writes) { name, args ->
            @Suppress("UNCHECKED_CAST")
            when (name) {
                "upsertSessions" -> stored.add(args[0] as List<CachedSessionEntity>)
                "replaceSessionsForApiKey" -> stored.add(args[1] as List<CachedSessionEntity>)
            }
            Unit
        }, Json)
        store.deleteSession("deleted")
        val items = listOf(session("deleted"), session("live"))
        store.cacheSessions(items)
        store.cacheSessions(items, replaceForKey = 7)
        assertEquals(listOf(listOf(session("live")), listOf(session("live"))), stored)
        assertEquals(listOf("deleteSessionCache", "upsertSessions", "replaceSessionsForApiKey"), writes)
    }

    /** 테스트에서 필요한 쓰기만 기록하여 Android Room 없이 저장 계약을 검증합니다. */
    private fun dao(
        writes: MutableList<String>,
        result: (String, Array<out Any?>) -> Any? = { _, _ -> Unit }
    ): JulesDao = Proxy.newProxyInstance(
        JulesDao::class.java.classLoader, arrayOf(JulesDao::class.java)
    ) { _, method, args ->
        writes.add(method.name)
        result(method.name, args ?: emptyArray())
    } as JulesDao

    /** 순서와 내용이 구분되는 작은 사용자 활동을 만듭니다. */
    private fun activity(text: String) = JulesActivity(
        id = text, originator = "user", createTime = "2026-11-10T00:00:00Z",
        userMessaged = MessageEvent(message = text)
    )

    /** 목록 캐시의 삭제 필터를 확인할 최소 세션을 만듭니다. */
    private fun session(id: String) = CachedSessionEntity(
        sessionId = id, name = "sessions/$id", title = id, prompt = id, state = "COMPLETED",
        repoDisplay = "", branchDisplay = "", prUrl = "", prTitle = "",
        createTime = "2026-11-10T00:00:00Z", updateTime = "2026-11-10T00:00:00Z", apiKeyId = 7
    )
}
