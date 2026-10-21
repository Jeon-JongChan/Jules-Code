package com.jules.code

import com.jules.code.data.remote.JulesApiClient
import com.jules.code.data.local.*
import com.jules.code.ui.JulesViewModel
import java.net.ServerSocket
import java.net.SocketException
import java.io.Closeable
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test

/** 로컬 HTTP 서버로 페이지 누락, 활동 단건 조회와 취소 전파를 재현합니다. */
class JulesApiWorkflowTest {
    /** 선택 필드가 null인 응답도 없는 값으로 처리하여 가짜 다음 페이지를 요청하지 않습니다. */
    @Test
    fun nullableActivityFieldsDoNotBreakTimeline() {
        val response = JulesApiClient().parseActivitiesFlexible("""{"activities":[{"id":"a1","artifacts":null}],"nextPageToken":null}""")
        assertNull(response.nextPageToken)
        assertTrue(response.activities.single().artifacts.isEmpty())
    }

    /** 반복 토큰과 중간 페이지 실패는 부분 성공으로 반환하지 않습니다. */
    @Test
    fun failedOrRepeatingPagesDoNotLookComplete() = runBlocking {
        var failSecondPage = false
        LocalApiServer { _, uri ->
            if (failSecondPage && uri.contains("pageToken=")) 503 to "{}"
            else 200 to """{"sessions":[{"id":"one"}],"nextPageToken":"repeat"}"""
        }.use { server ->
            val api = JulesApiClient("http://127.0.0.1:${server.port}/v1alpha")
            assertTrue(api.listAllSessions("test").isFailure)
            failSecondPage = true
            assertTrue(api.listAllSessions("test").isFailure)
        }
    }

    /** 세 목록 API가 마지막 페이지까지 읽고 단건 활동도 동일한 파서로 처리하는지 확인합니다. */
    @Test
    fun listsReadEveryPageAndActivityCanBeRetrieved() = runBlocking {
        val requests = mutableListOf<String>()
        val server = LocalApiServer { _, uri ->
            requests.add(uri)
            val path = uri.substringBefore('?')
            val second = uri.contains("pageToken=next%2Bpage")
            val collection = when {
                path.endsWith("/sources") -> "sources"
                path.endsWith("/activities") -> "activities"
                else -> "sessions"
            }
            val body = if (path.endsWith("/activities/a1")) {
                """{"id":"a1","artifacts":[{"changeSet":{"gitPatch":"diff"}}]}"""
            } else {
                val token = if (second) "" else """, "nextPageToken":"next+page""""
                """{"$collection":[{"id":"${if (second) "second" else "first"}"}]$token}"""
            }
            200 to body
        }
        try {
            val api = JulesApiClient("http://127.0.0.1:${server.port}/v1alpha")
            assertEquals(listOf("first", "second"), api.listAllSessions("test").getOrThrow().map { it.id })
            assertEquals(2, api.listAllSources("test").getOrThrow().size)
            assertEquals(2, api.listAllActivities("test", "s1").getOrThrow().size)
            assertEquals("diff", api.getActivity("test", "s1", "a1").getOrThrow()
                .artifacts.single().changeSet?.gitPatch?.unidiffPatch)
            assertEquals(7, requests.size)
        } finally {
            server.close()
        }
    }

    /** 취소된 API 호출이 실패 Result로 삼켜져 폴링을 계속하지 않는지 확인합니다. */
    @Test
    fun cancelledRequestPropagatesCancellation() = runBlocking {
        val job = kotlinx.coroutines.Job()
        var returnedResult = false
        try {
            kotlinx.coroutines.withContext(job) {
                job.cancel()
                JulesApiClient().listSessions("test")
                returnedResult = true
            }
            fail("취소 예외가 전파되어야 합니다.")
        } catch (_: CancellationException) {
            // 취소된 호출은 정상적으로 코루틴을 종료해야 합니다.
        }
        assertFalse(returnedResult)
    }

    /** 원격 삭제 실패 시 캐시를 유지하고 성공한 경우에만 콜백 및 캐시 제거를 허용합니다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deleteFailureKeepsCacheAndSuccessRemovesIt() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        var deleteStatus = 403
        val server = LocalApiServer { method, _ ->
            val status = if (method == "DELETE") deleteStatus else 200
            val body = if (status == 403) """{"error":{"message":"denied"}}""" else "{}"
            status to body
        }
        val store = ViewModelStore()
        try {
            val cached = CachedSessionEntity("s1", "sessions/s1", "테스트", "지시", "COMPLETED", "o/r", "main", "", "", "", "", apiKeyId = 1)
            val rows = MutableStateFlow(listOf(cached))
            val keys = MutableStateFlow(listOf(ApiKeyEntity(1, "테스트 키", "test", true)))
            var deletedActivities = false
            val dao = Proxy.newProxyInstance(JulesDao::class.java.classLoader, arrayOf(JulesDao::class.java)) { _, method, args ->
                when (method.name) {
                    "observeApiKeys" -> keys
                    "observeActiveApiKey" -> MutableStateFlow(keys.value.first())
                    "observeSessions" -> rows
                    "observeTemplates" -> MutableStateFlow(emptyList<ScheduledTemplateEntity>())
                    "observeSources" -> MutableStateFlow(emptyList<CachedSourceEntity>())
                    "observePreferences" -> MutableStateFlow(emptyList<AppPreferenceEntity>())
                    "insertTemplate" -> 1L
                    "getCachedSession" -> rows.value.find { it.sessionId == args!![0] }
                    "deleteSessionCache" -> {
                        rows.value = emptyList()
                        deletedActivities = true
                        Unit
                    }
                    "upsertSessions", "replaceSources", "clearSources" -> Unit
                    else -> error("예상하지 못한 DAO 호출: ${method.name}")
                }
            } as JulesDao
            val vm = JulesViewModel(dao, JulesApiClient("http://127.0.0.1:${server.port}/v1alpha"))
            store.put("test", vm)
            withTimeout(5000) { vm.activeApiKey.first { it != null } }
            var callbackCount = 0
            vm.deleteSession("s1") { callbackCount++ }
            withTimeout(5000) { vm.deletingSessionIds.first { it.isEmpty() } }
            assertEquals(1, rows.value.size)
            assertFalse(deletedActivities)
            assertEquals(0, callbackCount)
            assertTrue(vm.snackbarMessage.value.orEmpty().contains("실패"))

            deleteStatus = 200
            vm.deleteSession("s1") { callbackCount++ }
            withTimeout(5000) { vm.deletingSessionIds.first { it.isEmpty() } }
            assertTrue(rows.value.isEmpty())
            assertTrue(deletedActivities)
            assertEquals(1, callbackCount)
        } finally {
            store.clear()
            server.close()
            Dispatchers.resetMain()
        }
    }

    /** 추가 라이브러리 없이 테스트에 필요한 GET·DELETE 응답만 제공하는 로컬 서버입니다. */
    private class LocalApiServer(private val response: (String, String) -> Pair<Int, String>) : Closeable {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine()?.split(' ') ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) { /* 요청 헤더를 끝까지 소비합니다. */ }
                        val (status, body) = response(request[0], request[1])
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: SocketException) {
                    if (!socket.isClosed) throw error
                }
            }
        }.apply { isDaemon = true; start() }

        /** 서버를 닫고 응답 스레드가 남지 않도록 기다립니다. */
        override fun close() {
            socket.close()
            worker.join(5000)
        }
    }
}
