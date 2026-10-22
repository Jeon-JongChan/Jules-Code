package com.jules.code.data.remote

import com.jules.code.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

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
    ): Result<SourcesResponse> = apiResult {
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
    suspend fun getSource(apiKey: String, sourceIdOrName: String): Result<JulesSource> = apiResult {
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
    ): Result<SessionsResponse> = apiResult {
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
    suspend fun getSession(apiKey: String, sessionId: String): Result<JulesSession> = apiResult {
        val cleanId = sessionId.substringAfterLast("/")
        val raw = request("GET", "/sessions/$cleanId", apiKey)
        json.decodeFromString<JulesSession>(raw)
    }

    /**
     * 새로운 코딩 세션을 생성합니다. (`POST /v1alpha/sessions`)
     */
    suspend fun createSession(apiKey: String, req: CreateSessionRequest): Result<JulesSession> = apiResult {
        val body = json.encodeToString(req)
        val raw = request("POST", "/sessions", apiKey, body)
        json.decodeFromString<JulesSession>(raw)
    }

    /**
     * 코딩 세션을 삭제합니다. (`DELETE /v1alpha/sessions/{sessionId}`)
     */
    suspend fun deleteSession(apiKey: String, sessionId: String): Result<Unit> = apiResult {
        val cleanId = sessionId.substringAfterLast("/")
        request("DELETE", "/sessions/$cleanId", apiKey)
    }

    /**
     * 대기 중인 실행 계획을 승인합니다. (`POST /v1alpha/sessions/{sessionId}:approvePlan`)
     */
    suspend fun approvePlan(apiKey: String, sessionId: String): Result<Unit> = apiResult {
        val cleanId = sessionId.substringAfterLast("/")
        request("POST", "/sessions/$cleanId:approvePlan", apiKey, "{}")
    }

    /**
     * 진행 중인 세션의 에이전트에게 후속 지시나 피드백 메시지를 전송합니다. (`POST /v1alpha/sessions/{sessionId}:sendMessage`)
     */
    suspend fun sendMessage(apiKey: String, sessionId: String, prompt: String): Result<Unit> = apiResult {
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
    ): Result<ActivitiesResponse> = apiResult {
        val cleanId = sessionId.substringAfterLast("/")
        val params = buildList {
            add("pageSize=$pageSize")
            if (!pageToken.isNullOrBlank()) add("pageToken=${encode(pageToken)}")
        }.joinToString("&")
        val raw = request("GET", "/sessions/$cleanId/activities?$params", apiKey)
        // Base64 미디어가 포함된 큰 JSON은 UI 스레드에서 파싱하지 않습니다.
        withContext(Dispatchers.Default) { parseActivitiesFlexible(raw) }
    }

    /** 모든 세션 페이지를 가져와 이전 작업이 목록에서 누락되지 않게 합니다. */
    suspend fun listAllSessions(apiKey: String): Result<List<JulesSession>> = collectPages { token ->
        listSessions(apiKey, pageSize = 100, pageToken = token).getOrThrow().let {
            it.sessions to it.nextPageToken
        }
    }

    /** 연결된 저장소의 전체 페이지를 가져옵니다. */
    suspend fun listAllSources(apiKey: String): Result<List<JulesSource>> = collectPages { token ->
        listSources(apiKey, pageSize = 100, pageToken = token).getOrThrow().let {
            it.sources to it.nextPageToken
        }
    }

    /** 계획·최신 메시지·산출물이 잘리지 않도록 활동의 전체 페이지를 가져옵니다. */
    suspend fun listAllActivities(apiKey: String, sessionId: String): Result<List<JulesActivity>> = collectPages { token ->
        listActivities(apiKey, sessionId, pageToken = token).getOrThrow().let {
            it.activities to it.nextPageToken
        }
    }

    /** 활동 하나를 다시 조회하며 목록과 동일한 호환 파서를 사용합니다. */
    suspend fun getActivity(apiKey: String, sessionId: String, activityId: String): Result<JulesActivity> = apiResult {
        val raw = request("GET", "/sessions/${sessionId.substringAfterLast('/')}/activities/${activityId.substringAfterLast('/')}", apiKey)
        parseSingleActivity(json.parseToJsonElement(raw).jsonObject)
    }

    /** 실패한 중간 페이지는 부분 성공으로 처리하지 않고 반복 토큰은 차단합니다. */
    private suspend fun <T> collectPages(fetch: suspend (String?) -> Pair<List<T>, String?>): Result<List<T>> = apiResult {
        val items = mutableListOf<T>()
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        do {
            coroutineContext.ensureActive()
            val (page, next) = fetch(token)
            items.addAll(page)
            token = next?.takeIf { it.isNotBlank() }
            check(token == null || seenTokens.add(token)) { "서버가 동일한 페이지 토큰을 반복했습니다." }
        } while (token != null)
        items
    }

    /** 네트워크 오류는 Result로 전달하되 코루틴 취소는 호출자에게 전파합니다. */
    private suspend fun <T> apiResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * JSON 응답에서 `gitPatch`가 문자열이든 객체이든 안전하게 파싱하여 `ActivitiesResponse`로 변환합니다.
     */
    fun parseActivitiesFlexible(rawJson: String): ActivitiesResponse {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val nextToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
        val arr = root["activities"] as? JsonArray ?: JsonArray(emptyList())
        val items = arr.map { elem -> parseSingleActivity(elem.jsonObject) }
        return ActivitiesResponse(activities = items, nextPageToken = nextToken)
    }

    /**
     * 단일 Activity JSON 객체를 `JulesActivity` 모델로 변환합니다.
     */
    private fun parseSingleActivity(obj: JsonObject): JulesActivity {
        val artifactsElem = obj["artifacts"] as? JsonArray ?: JsonArray(emptyList())
        val parsedArtifacts = artifactsElem.map { artElem ->
            val artObj = artElem.jsonObject
            val changeSetObj = artObj["changeSet"] as? JsonObject
            val changeSet = changeSetObj?.let { cs ->
                val gpElem: JsonElement? = cs["gitPatch"]
                val gitPatchData = when (gpElem) {
                    JsonNull -> null
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
            val bashObj = artObj["bashOutput"] as? JsonObject
            val bashOutput = bashObj?.let { bo ->
                BashOutputArtifact(
                    command = bo["command"]?.jsonPrimitive?.content ?: "",
                    output = bo["output"]?.jsonPrimitive?.content ?: "",
                    exitCode = bo["exitCode"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }
            val mediaObj = artObj["media"] as? JsonObject
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

    /** 쿼리 값의 예약 문자와 공백을 인코딩합니다. */
    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")
}

/**
 * Jules API 호출 실패 시 발생하는 예외 클래스입니다.
 */
class JulesApiException(val statusCode: Int, override val message: String) : Exception(message)

