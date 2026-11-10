package com.jules.code

import com.jules.code.data.model.CreateSessionRequest
import com.jules.code.data.remote.JulesApiClient
import com.jules.code.ui.promptForSubmission
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 실제 HTTP 요청 본문으로 원문 보존과 명시적 기본 문구 정책을 검증합니다. */
class JulesOutgoingRequestTest {
    /** 새 작업과 후속 요청 모두 공백·사용자 지시·이전 내부 구분자를 손대지 않는지 검사합니다. */
    @Test
    fun requestsPreserveTheExactPrompt(): Unit = runBlocking {
        val input = "  로그인 오류를 수정해줘\n\n[Jules Agent Execution Context]\n내가 직접 입력한 지시\n  "
        assertRequestBodies(input, input)
    }

    /** 새 작업과 후속 메시지의 실제 HTTP 본문에 활성화된 기본 문구가 함께 전송되는지 검사합니다. */
    @Test
    fun requestsIncludeEnabledDefaultsAtSendTime(): Unit = runBlocking {
        val input = "  로그인 오류를 수정해줘\n  "
        val defaults = "  한국어로 답하고 테스트를 실행해 주세요.\n"
        val preferences = mapOf("default_prompt_enabled" to "true", "default_prompt_text" to defaults)
        assertRequestBodies(promptForSubmission(input, preferences), "$input\n\n$defaults")
    }

    /** 두 API의 HTTP 본문을 수집해 최종 전송 문구를 검증합니다. */
    private suspend fun assertRequestBodies(input: String, expected: String) {
        val received = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val worker = thread(isDaemon = true, name = "jules-request-fixture") {
            try {
                repeat(2) { respondToRequest(server, received) }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            val client = JulesApiClient(baseUrl = "http://127.0.0.1:${server.localPort}/v1alpha")
            client.createSession("fixture-key", CreateSessionRequest(prompt = input)).getOrThrow()
            client.sendMessage("fixture-key", "example", input).getOrThrow()
            assertEquals(2, received.size)
            received.forEach { raw ->
                assertEquals(expected, Json.parseToJsonElement(raw).jsonObject.getValue("prompt").jsonPrimitive.content)
            }
        } finally {
            server.close()
            worker.join(2000)
        }
        failure.get()?.let { throw it }
    }

    /** 기본 문구는 저장뿐 아니라 활성화가 필요하며 두 본문의 공백을 보존하는지 검사합니다. */
    @Test
    fun defaultsRequireExplicitOptInAndKeepUserText() {
        val text = "  Respond in Japanese.\n[Language Setting: custom]\n"
        val input = "  오류를 수정해줘\n"
        assertEquals(input, promptForSubmission(input, mapOf("default_prompt_text" to text)))
        assertEquals(input, promptForSubmission(input, mapOf("default_prompt_enabled" to "false", "default_prompt_text" to text)))
        assertEquals("$input\n\n$text", promptForSubmission(input, mapOf("default_prompt_enabled" to "true", "default_prompt_text" to text)))
    }

    /** 빈 문구와 빈 입력을 건너뛰고 전송 시점의 최신 저장 설정을 적용하는지 검사합니다. */
    @Test
    fun defaultsUseCurrentSettingsAndDoNotSendAnEmptyDraft() {
        val preferences = mutableMapOf("default_prompt_enabled" to "true", "default_prompt_text" to "  \n")
        assertEquals("요청", promptForSubmission("요청", preferences))
        preferences["default_prompt_text"] = "새 공통 문구"
        assertEquals("요청\n\n새 공통 문구", promptForSubmission("요청", preferences))
        assertEquals(" \n", promptForSubmission(" \n", preferences))
    }

    /** HTTP 헤더와 UTF-8 본문을 바이트 길이대로 읽어 실제 직렬화 요청을 수집합니다. */
    private fun respondToRequest(server: ServerSocket, received: MutableList<String>) {
        server.accept().use { socket ->
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val next = input.read()
                check(next >= 0 && header.length < 16384) { "HTTP 요청 헤더가 불완전합니다." }
                header.append(next.toChar())
            }
            val size = Regex("(?im)^Content-Length: *([0-9]+)").find(header)?.groupValues?.get(1)?.toInt()
                ?: error("요청에 Content-Length가 없습니다.")
            val bytes = ByteArray(size)
            input.readFully(bytes)
            received.add(bytes.toString(StandardCharsets.UTF_8))
            val body = """{"name":"sessions/example"}""".toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
        }
    }
}
