package com.jules.code.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Base64InputStream
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jules.code.data.model.MediaArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** MIME별 미디어를 비동기로 준비하고 영상은 재생 요청 시에만 디코딩합니다. */
@Composable
fun MediaContent(media: MediaArtifact) {
    val context = LocalContext.current
    val video = media.mimeType.startsWith("video/", ignoreCase = true)
    val image = media.mimeType.startsWith("image/", ignoreCase = true)
    var requested by remember(media) { mutableStateOf(false) }
    var zoom by remember(media) { mutableStateOf(false) }
    var error by remember(media) { mutableStateOf<String?>(null) }
    var bitmap by remember(media) { mutableStateOf<ImageBitmap?>(null) }
    var videoFile by remember(media) { mutableStateOf<File?>(null) }
    var player by remember(media) { mutableStateOf<VideoView?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, media) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player?.stopPlayback()
        }
    }
    LaunchedEffect(media, requested) {
        if (!image && !(video && requested)) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                // ponytail: 첨부당 인코딩 64MiB 상한. 더 큰 미디어는 스트리밍 API 도입 시 지원합니다.
                val limit = (if (image) 16 else 64) * 1024 * 1024
                require(media.data.length <= limit) { "첨부가 너무 큽니다. 더 작은 이미지·영상으로 요청해 주세요." }
                val payload = if (media.data.startsWith("data:")) media.data.substringAfter(',') else media.data
                if (image) {
                    val bytes = Base64.decode(payload, Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지 형식을 읽을 수 없습니다." }
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
                        options.inSampleSize *= 2
                    }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    requireNotNull(decoded) { "이미지를 디코딩할 수 없습니다." }
                    bitmap = decoded.asImageBitmap()
                } else {
                    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
                        .joinToString("") { "%02x".format(it) }
                    val file = File(context.cacheDir, "media-$digest.video")
                    // 중단된 쓰기 파일을 재사용하지 않도록 매 요청마다 완전히 기록합니다.
                    Base64InputStream(ByteArrayInputStream(payload.toByteArray(Charsets.US_ASCII)), Base64.DEFAULT).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    require(file.length() > 0) { "영상 데이터가 비어 있거나 잘못된 Base64입니다." }
                    videoFile = file
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "미디어 오류: ${failure.message ?: "미디어를 불러오지 못했습니다."}"
        }
    }
    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        Text("미디어 첨부 · ${media.mimeType}", style = MaterialTheme.typography.labelSmall)
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            !image && !video -> Text("지원하지 않는 미디어 형식입니다.")
            image && bitmap == null -> CircularProgressIndicator()
            image -> Image(bitmap!!, "첨부 이미지 확대", Modifier.fillMaxWidth().height(240.dp).clickable { zoom = true })
            !requested -> Button(onClick = { requested = true }) { Text("영상 재생") }
            videoFile == null -> CircularProgressIndicator()
            else -> key(videoFile) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            player = this
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setOnPreparedListener {
                                // 준비 완료 전에 앱이 백그라운드로 이동했으면 자동 재생하지 않습니다.
                                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
                            }
                            setOnErrorListener { _, _, _ ->
                                error = "미디어 오류: 이 영상의 코덱을 기기에서 재생할 수 없습니다. MP4(H.264)로 다시 요청해 주세요."
                                true
                            }
                            setVideoURI(Uri.fromFile(videoFile))
                        }
                    }
                )
            }
        }
    }
    if (zoom && bitmap != null) ImageZoomDialog(bitmap!!, "첨부 이미지", onDismiss = { zoom = false })
}
