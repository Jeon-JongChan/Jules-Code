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
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * Google Jules REST API (`https://jules.googleapis.com/v1alpha`) 통신을 담당하는 클라이언트입니다.
 * 외부 무거운 HTTP 라이브러리 대신 표준 `HttpURLConnection`과 `kotlinx.serialization`을 사용합니다.
 */
class JulesApiClient(
    private val baseUrl: String = "https://jules.googleapis.com/v1alpha",
    private val contentDirectory: File = File(System.getProperty("java.io.tmpdir"), "jules-activity-content")
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
     * 세션 활동 요약을 조회하며 본문과 첨부는 선택 시 getActivity로 불러옵니다.
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
        val summaryFields = "activities(name,id,originator,description,createTime,planGenerated,planApproved,userMessaged,agentMessaged,progressUpdated,sessionCompleted,sessionFailed,artifacts(changeSet(source),bashOutput(command,exitCode),media(mimeType))),nextPageToken"
        val raw = request("GET", "/sessions/$cleanId/activities?$params&fields=${encode(summaryFields)}", apiKey, activityResponse = true)
        // Base64 미디어가 포함된 큰 JSON은 UI 스레드에서 파싱하지 않습니다.
        withContext(Dispatchers.Default) {
            val parsed = parseActivitiesFlexible(raw)
            parsed.copy(activities = parsed.activities.map { activity ->
                activity.copy(detailsLoaded = false, contentFiles = emptyMap(), description = activity.description.take(160),
                    agentMessaged = activity.agentMessaged?.copy(message = "", agentMessage = "", text = "", prompt = ""),
                    agentMessage = activity.agentMessage?.copy(message = "", agentMessage = "", text = "", prompt = ""),
                    progressUpdated = activity.progressUpdated?.copy(description = ""),
                    artifacts = activity.artifacts.map { it.copy(
                        changeSet = it.changeSet?.copy(gitPatch = null),
                        bashOutput = it.bashOutput?.copy(output = ""),
                        media = it.media?.copy(data = "", dataFile = "")) })
            })
        }
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

    /** 이전 기록이 빠지지 않도록 활동 요약의 마지막 페이지까지 가져옵니다. */
    suspend fun listAllActivities(apiKey: String, sessionId: String): Result<List<JulesActivity>> = collectPages { token ->
        listActivities(apiKey, sessionId, pageToken = token).getOrThrow().let {
            it.activities to it.nextPageToken
        }
    }

    /** 선택한 활동의 전체 본문을 조회하고 큰 원문·첨부는 파일에 보존합니다. */
    suspend fun getActivity(apiKey: String, sessionId: String, activityId: String): Result<JulesActivity> = apiResult {
        val raw = request("GET", "/sessions/${sessionId.substringAfterLast('/')}/activities/${activityId.substringAfterLast('/')}", apiKey,
            activityResponse = true, saveContent = true)
        withContext(Dispatchers.Default) {
            parseSingleActivity(json.parseToJsonElement(raw).jsonObject).copy(detailsLoaded = true)
        }
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
                    data = mo["data"]?.jsonPrimitive?.content ?: "",
                    dataFile = trustedContentFile(mo["dataFile"]?.jsonPrimitive?.content)?.absolutePath.orEmpty()
                )
            }
            ActivityArtifact(changeSet = changeSet, bashOutput = bashOutput, media = media)
        }

        // artifacts 필드를 제외한 나머지는 기본 역직렬화 후 결합
        val mutableMap = obj.toMutableMap()
        mutableMap.remove("artifacts")
        val stripped = json.decodeFromJsonElement(JulesActivity.serializer(), JsonObject(mutableMap))
        return stripped.copy(artifacts = parsedArtifacts,
            contentFiles = stripped.contentFiles.filterValues { trustedContentFile(it) != null })
    }

    /** API 응답이 앱의 원문 캐시 밖 파일을 참조하지 못하게 합니다. */
    private fun trustedContentFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path).canonicalFile
        return file.takeIf { it.parentFile == contentDirectory.canonicalFile && it.isFile }
    }

    /**
     * `X-Goog-Api-Key` 헤더를 포함한 저수준 HTTP 요청을 수행합니다.
     */
    private suspend fun request(
        method: String,
        path: String,
        apiKey: String,
        body: String? = null,
        activityResponse: Boolean = false,
        saveContent: Boolean = false
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
            val responseText = stream?.use { s ->
                if (activityResponse && code in 200..299) readActivitiesBounded(s, saveContent)
                else {
                    // 단건 첨부를 포함한 응답도 무제한 문자열로 확장하지 않습니다.
                    val bytes = readAtMost(s, 8 * 1024 * 1024 + 1)
                    require(bytes.size <= 8 * 1024 * 1024) { "목록 응답이 너무 큽니다. 새로고침해 다시 시도해 주세요." }
                    bytes.toString(Charsets.UTF_8)
                }
            } ?: ""

            if (code !in 200..299) {
                throw JulesApiException(code, extractErrorMessage(code, responseText))
            }
            responseText.ifBlank { "{}" }
        } finally {
            conn.disconnect()
        }
    }

    /** 목록은 작게 유지하고 개별 원문은 파일로 흘려보내며 표시 구간만 반환합니다. */
    internal fun readActivitiesBounded(input: InputStream, saveContent: Boolean = false): String {
        val prefix = readAtMost(input, 512 * 1024 + 1)
        if (prefix.size <= 512 * 1024 && !saveContent) return prefix.toString(Charsets.UTF_8)
        val combined = SequenceInputStream(ByteArrayInputStream(prefix), input)
        return compactActivityJson(InputStreamReader(combined, Charsets.UTF_8).buffered(), saveContent)
    }

    /** Android 8에서도 지정한 바이트까지만 버퍼링합니다. */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** JSON 문자열을 토큰 경계에서 제한해 큰 패치·로그도 전체 문자열로 할당하지 않습니다. */
    private fun compactActivityJson(reader: java.io.Reader, saveContent: Boolean): String {
        val output = StringBuilder()
        val files = mutableMapOf<String, String>()
        var previous = ' '
        var lastToken = ""
        while (true) {
            val next = reader.read()
            if (next < 0) break
            val char = next.toChar()
            if (char != '"') {
                output.append(char)
                if (!char.isWhitespace()) previous = char
            } else {
                val omit = previous == ':' && lastToken == "data"
                val field = lastToken
                val persist = saveContent && previous == ':'
                var file: File? = null
                var writer: java.io.BufferedWriter? = null
                val pending = StringBuilder()
                /** 실제로 큰 값만 파일로 전환하고 앞서 읽은 원문도 함께 보존합니다. */
                fun openContentFile() {
                    contentDirectory.mkdirs()
                    val created = File.createTempFile("content-", ".${if (omit) "base64" else "text"}", contentDirectory)
                    file = created
                    writer = created.bufferedWriter(if (omit) Charsets.UTF_8 else Charsets.UTF_16LE)
                    writer!!.append(pending)
                    pending.setLength(0)
                }
                /** 작은 값은 짧은 버퍼에 두고 큰 원문은 고정 크기 쓰기 버퍼로 흘려보냅니다. */
                fun persistChar(char: Char) {
                    if (!persist) return
                    if (writer != null) writer!!.append(char)
                    else {
                        pending.append(char)
                        if (omit || pending.length > 64 * 1024) openContentFile()
                    }
                }
                val token = StringBuilder()
                var length = 0
                var truncated = false
                val previewLimit = if (saveContent) 2000 else 64 * 1024
                output.append('"')
                try { while (true) {
                    val value = reader.read()
                    require(value >= 0) { "활동 응답이 문자열 중간에 끊겼습니다." }
                    val current = value.toChar()
                    if (current == '"') break
                    if (current != '\\') {
                        persistChar(current)
                        if (token.length < 64) token.append(current)
                        if (!omit && !truncated && length < previewLimit) {
                            output.append(current)
                            length++
                        } else if (!omit) truncated = true
                        continue
                    }
                    // 이스케이프와 유니코드 시퀀스는 통째로 유지하거나 통째로 생략합니다.
                    val unit = StringBuilder().append(current)
                    if (current == '\\') {
                        val escaped = reader.read()
                        require(escaped >= 0) { "활동 응답의 이스케이프가 끊겼습니다." }
                        unit.append(escaped.toChar())
                        if (escaped.toChar() == 'u') repeat(4) {
                            val digit = reader.read()
                            require(digit >= 0) { "활동 응답의 유니코드가 끊겼습니다." }
                            unit.append(digit.toChar())
                        }
                    }
                    if (token.length < 64) token.append(unit)
                    if (persist) {
                        val decoded = when (unit[1]) {
                            'u' -> unit.substring(2).toInt(16).toChar()
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'
                            else -> unit[1]
                        }
                        persistChar(decoded)
                    }
                    if (!omit && !truncated && length + unit.length <= previewLimit) {
                        output.append(unit)
                        length += unit.length
                    } else if (!omit) truncated = true
                }
                    if (persist && truncated && writer == null) openContentFile()
                } finally { writer?.close() }
                if (truncated) output.append("\\n[긴 내용은 원문 보기에서 계속 읽을 수 있습니다.]")
                output.append('"')
                if (file != null && (truncated || omit)) {
                    val path = file!!.absolutePath
                    output.append(",").append(json.encodeToString(field + "File")).append(":").append(json.encodeToString(path))
                    if (!omit) files["$field ${files.size + 1}"] = path
                }
                lastToken = token.toString()
                previous = '"'
            }
            require(output.length <= 8 * 1024 * 1024) { "활동 목록이 너무 큽니다. 항목을 나누어 다시 조회해 주세요." }
        }
        if (files.isNotEmpty()) {
            val end = output.lastIndexOf("}")
            output.insert(end, ",\"contentFiles\":" + json.encodeToString(files))
        }
        return output.toString()
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

