package com.jules.code.data.remote

import com.jules.code.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Jules REST API (`https://jules.googleapis.com/v1alpha`) 통신을 담당하는 클라이언트입니다.
 * 외부 무거운 HTTP 라이브러리 대신 표준 `HttpURLConnection`과 `kotlinx.serialization`을 사용합니다.
 */
class JulesApiClient(
    private val baseUrl: String = "https://jules.googleapis.com/v1alpha"
) {
    /**
     * 알 수 없는 필드나 스키마 확장에 유연하게 대응하는 JSON 파서 인스턴스입니다.
     */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
    }

    /**
     * 연결된 GitHub 소스(저장소) 목록을 조회합니다. (`GET /v1alpha/sources`)
     */
    suspend fun listSources(
        apiKey: String,
        pageSize: Int = 50,
        pageToken: String? = null,
        filter: String? = null
    ): Result<SourcesResponse> = runCatching {
        val params = buildList {
            add("pageSize=$pageSize")
            if (!pageToken.isNullOrBlank()) add("pageToken=${encode(pageToken)}")
            if (!filter.isNullOrBlank()) add("filter=${encode(filter)}")
        }.joinToString("&")
        val raw = request("GET", "/sources?$params", apiKey)
        json.decodeFromString<SourcesResponse>(raw)
    }

    /**
     * 특정 소스 상세 정보를 조회합니다. (`GET /v1alpha/{sourceName}`)
     */
    suspend fun getSource(apiKey: String, sourceIdOrName: String): Result<JulesSource> = runCatching {
        val path = if (sourceIdOrName.startsWith("sources/")) "/$sourceIdOrName" else "/sources/$sourceIdOrName"
        val raw = request("GET", path, apiKey)
        json.decodeFromString<JulesSource>(raw)
    }

    /**
     * 코딩 세션 목록을 조회합니다. (`GET /v1alpha/sessions`)
     */
    suspend fun listSessions(
        apiKey: String,
        pageSize: Int = 50,
        pageToken: String? = null
    ): Result<SessionsResponse> = runCatching {
        val params = buildList {
            add("pageSize=$pageSize")
            if (!pageToken.isNullOrBlank()) add("pageToken=${encode(pageToken)}")
        }.joinToString("&")
        val raw = request("GET", "/sessions?$params", apiKey)
        json.decodeFromString<SessionsResponse>(raw)
    }

    /**
     * 특정 코딩 세션의 최신 상태와 산출물(PR 등)을 조회합니다. (`GET /v1alpha/sessions/{sessionId}`)
     */
    suspend fun getSession(apiKey: String, sessionId: String): Result<JulesSession> = runCatching {
        val cleanId = sessionId.substringAfterLast("/")
        val raw = request("GET", "/sessions/$cleanId", apiKey)
        json.decodeFromString<JulesSession>(raw)
    }

    /**
     * 새로운 코딩 세션을 생성합니다. (`POST /v1alpha/sessions`)
     */
    suspend fun createSession(apiKey: String, req: CreateSessionRequest): Result<JulesSession> = runCatching {
        val body = json.encodeToString(req)
        val raw = request("POST", "/sessions", apiKey, body)
        json.decodeFromString<JulesSession>(raw)
    }

    /**
     * 코딩 세션을 삭제합니다. (`DELETE /v1alpha/sessions/{sessionId}`)
     */
    suspend fun deleteSession(apiKey: String, sessionId: String): Result<Unit> = runCatching {
        val cleanId = sessionId.substringAfterLast("/")
        request("DELETE", "/sessions/$cleanId", apiKey)
    }

    /**
     * 대기 중인 실행 계획을 승인합니다. (`POST /v1alpha/sessions/{sessionId}:approvePlan`)
     */
    suspend fun approvePlan(apiKey: String, sessionId: String): Result<Unit> = runCatching {
        val cleanId = sessionId.substringAfterLast("/")
        request("POST", "/sessions/$cleanId:approvePlan", apiKey, "{}")
    }

    /**
     * 진행 중인 세션의 에이전트에게 후속 지시나 피드백 메시지를 전송합니다. (`POST /v1alpha/sessions/{sessionId}:sendMessage`)
     */
    suspend fun sendMessage(apiKey: String, sessionId: String, prompt: String): Result<Unit> = runCatching {
        val cleanId = sessionId.substringAfterLast("/")
        val body = json.encodeToString(SendMessageRequest(prompt = prompt))
        request("POST", "/sessions/$cleanId:sendMessage", apiKey, body)
    }

    /**
     * 세션 내 액티비티(계획, 메시지, 코드 패치, 명령 실행 로그) 목록을 조회합니다.
     * (`GET /v1alpha/sessions/{sessionId}/activities`)
     */
    suspend fun listActivities(
        apiKey: String,
        sessionId: String,
        pageSize: Int = 100,
        pageToken: String? = null
    ): Result<ActivitiesResponse> = runCatching {
        val cleanId = sessionId.substringAfterLast("/")
        val params = buildList {
            add("pageSize=$pageSize")
            if (!pageToken.isNullOrBlank()) add("pageToken=${encode(pageToken)}")
        }.joinToString("&")
        val raw = request("GET", "/sessions/$cleanId/activities?$params", apiKey)
        // Base64 미디어가 포함된 큰 JSON은 UI 스레드에서 파싱하지 않습니다.
        withContext(Dispatchers.Default) { parseActivitiesFlexible(raw) }
    }

    /**
     * JSON 응답에서 `gitPatch`가 문자열이든 객체이든 안전하게 파싱하여 `ActivitiesResponse`로 변환합니다.
     */
    fun parseActivitiesFlexible(rawJson: String): ActivitiesResponse {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val nextToken = root["nextPageToken"]?.jsonPrimitive?.content
        val arr = root["activities"]?.jsonArray ?: JsonArray(emptyList())
        val items = arr.map { elem -> parseSingleActivity(elem.jsonObject) }
        return ActivitiesResponse(activities = items, nextPageToken = nextToken)
    }

    /**
     * 단일 Activity JSON 객체를 `JulesActivity` 모델로 변환합니다.
     */
    private fun parseSingleActivity(obj: JsonObject): JulesActivity {
        val artifactsElem = obj["artifacts"]?.jsonArray ?: JsonArray(emptyList())
        val parsedArtifacts = artifactsElem.map { artElem ->
            val artObj = artElem.jsonObject
            val changeSetObj = artObj["changeSet"]?.jsonObject
            val changeSet = changeSetObj?.let { cs ->
                val gpElem: JsonElement? = cs["gitPatch"]
                val gitPatchData = when (gpElem) {
                    is JsonPrimitive -> GitPatchData(
                        unidiffPatch = gpElem.content,
                        baseCommitId = cs["baseCommit"]?.jsonPrimitive?.content ?: "",
                        suggestedCommitMessage = cs["commitMessage"]?.jsonPrimitive?.content ?: ""
                    )
                    is JsonObject -> GitPatchData(
                        baseCommitId = gpElem["baseCommitId"]?.jsonPrimitive?.content
                            ?: cs["baseCommit"]?.jsonPrimitive?.content ?: "",
                        unidiffPatch = gpElem["unidiffPatch"]?.jsonPrimitive?.content ?: "",
                        suggestedCommitMessage = gpElem["suggestedCommitMessage"]?.jsonPrimitive?.content
                            ?: cs["commitMessage"]?.jsonPrimitive?.content ?: ""
                    )
                    else -> null
                }
                ChangeSetArtifact(
                    source = cs["source"]?.jsonPrimitive?.content ?: cs["name"]?.jsonPrimitive?.content ?: "",
                    gitPatch = gitPatchData,
                    baseCommit = cs["baseCommit"]?.jsonPrimitive?.content ?: "",
                    commitMessage = cs["commitMessage"]?.jsonPrimitive?.content ?: ""
                )
            }
            val bashObj = artObj["bashOutput"]?.jsonObject
            val bashOutput = bashObj?.let { bo ->
                BashOutputArtifact(
                    command = bo["command"]?.jsonPrimitive?.content ?: "",
                    output = bo["output"]?.jsonPrimitive?.content ?: "",
                    exitCode = bo["exitCode"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }
            val mediaObj = artObj["media"]?.jsonObject
            val media = mediaObj?.let { mo ->
                MediaArtifact(
                    mimeType = mo["mimeType"]?.jsonPrimitive?.content ?: "",
                    data = mo["data"]?.jsonPrimitive?.content ?: ""
                )
            }
            ActivityArtifact(changeSet = changeSet, bashOutput = bashOutput, media = media)
        }

        // artifacts 필드를 제외한 나머지는 기본 역직렬화 후 결합
        val mutableMap = obj.toMutableMap()
        mutableMap.remove("artifacts")
        val stripped = json.decodeFromJsonElement(JulesActivity.serializer(), JsonObject(mutableMap))
        return stripped.copy(artifacts = parsedArtifacts)
    }

    /**
     * `X-Goog-Api-Key` 헤더를 포함한 저수준 HTTP 요청을 수행합니다.
     */
    private suspend fun request(
        method: String,
        path: String,
        apiKey: String,
        body: String? = null
    ): String = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "API 키가 설정되지 않았습니다." }

        val url = URL("$baseUrl$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("X-Goog-Api-Key", trimmedKey)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""

            if (code !in 200..299) {
                throw JulesApiException(code, extractErrorMessage(code, responseText))
            }
            responseText.ifBlank { "{}" }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Google API 표준 에러 JSON에서 사용자 친화적인 메시지를 추출합니다.
     */
    private fun extractErrorMessage(code: Int, raw: String): String {
        return try {
            val errObj = json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
            val msg = errObj?.get("message")?.jsonPrimitive?.content
            val status = errObj?.get("status")?.jsonPrimitive?.content
            if (!msg.isNullOrBlank()) "[$code ${status ?: ""}] $msg".trim()
            else "HTTP 오류 $code: ${raw.take(200)}"
        } catch (_: Exception) {
            "HTTP 오류 $code: ${raw.take(200)}"
        }
    }

    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")
}

/**
 * Jules API 호출 실패 시 발생하는 예외 클래스입니다.
 */
class JulesApiException(val statusCode: Int, override val message: String) : Exception(message)

