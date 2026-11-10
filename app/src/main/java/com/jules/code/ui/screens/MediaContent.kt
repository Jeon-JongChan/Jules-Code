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
import com.jules.code.R
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
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/** MIME별 미디어를 비동기로 준비하고 영상은 재생 요청 시에만 디코딩합니다. */
@Composable
fun MediaContent(media: MediaArtifact) {
    val context = LocalContext.current
    // 대용량 목록은 미디어 본문을 지연 조회하므로 빈 데이터를 디코딩하지 않습니다.
    if (media.data.isBlank() && media.dataFile.isBlank()) {
        Text(context.getString(R.string.ui_media_reload, media.mimeType))
        return
    }
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
    LaunchedEffect(media, requested, context) {
        if (!image && !(video && requested)) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                // 인라인 첨부에만 상한을 두고 큰 첨부는 파일 스트림으로 처리합니다.
                val limit = (if (image) 16 else 64) * 1024 * 1024
                require(media.dataFile.isNotBlank() || media.data.length <= limit) { context.getString(R.string.ui_attachment_reload) }
                val payload = if (media.data.startsWith("data:")) media.data.substringAfter(',') else media.data
                if (image) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    decodedMediaStream(media).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(R.string.ui_image_format_error) }
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
                        options.inSampleSize *= 2
                    }
                    val decoded = decodedMediaStream(media).use { BitmapFactory.decodeStream(it, null, options) }
                    requireNotNull(decoded) { context.getString(R.string.ui_image_decode_error) }
                    bitmap = decoded.asImageBitmap()
                } else {
                    val digest = MessageDigest.getInstance("SHA-256").digest((media.dataFile.ifBlank { payload }).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val file = File(context.cacheDir, "media-$digest.video")
                    // 중단된 쓰기 파일을 재사용하지 않도록 매 요청마다 완전히 기록합니다.
                    decodedMediaStream(media).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    require(file.length() > 0) { context.getString(R.string.ui_video_data_error) }
                    videoFile = file
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = context.getString(R.string.ui_media_error, failure.message ?: context.getString(R.string.ui_media_load_error))
        }
    }
    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        Text(context.getString(R.string.ui_media_label, media.mimeType), style = MaterialTheme.typography.labelSmall)
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            !image && !video -> Text(context.getString(R.string.ui_media_unsupported))
            image && bitmap == null -> CircularProgressIndicator()
            image -> Image(bitmap!!, context.getString(R.string.ui_image_enlarge), Modifier.fillMaxWidth().height(240.dp).clickable { zoom = true })
            !requested -> Button(onClick = { requested = true }) { Text(context.getString(R.string.ui_video_play)) }
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
                                error = context.getString(R.string.ui_video_codec_error)
                                true
                            }
                            setVideoURI(Uri.fromFile(videoFile))
                        }
                    }
                )
            }
        }
    }
    if (zoom && bitmap != null) ImageZoomDialog(bitmap!!, context.getString(R.string.ui_attached_image), onDismiss = { zoom = false })
}

/** 파일에 저장한 큰 Base64 첨부도 바이트 배열 복사 없이 디코딩합니다. */
private fun decodedMediaStream(media: MediaArtifact): InputStream {
    val source = if (media.dataFile.isNotBlank()) FileInputStream(media.dataFile)
    else ByteArrayInputStream((if (media.data.startsWith("data:")) media.data.substringAfter(',') else media.data).toByteArray(Charsets.US_ASCII))
    return Base64InputStream(source, Base64.DEFAULT)
}
