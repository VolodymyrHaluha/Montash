@file:Suppress("DEPRECATION")

package com.frop.montash.media

import com.frop.montash.*

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("DEPRECATION")
private class GifView(context: Context, private val movie: Movie) : View(context) {
    init { require(movie.width() > 0 && movie.height() > 0) { "GIF пошкоджено." } }
    private val started = SystemClock.uptimeMillis()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat() / movie.width(), height.toFloat() / movie.height())
        val count = canvas.save()
        canvas.translate((width - movie.width() * scale) / 2, (height - movie.height() * scale) / 2)
        canvas.scale(scale, scale)
        movie.setTime(((SystemClock.uptimeMillis() - started) % (movie.duration().takeIf { it > 0 } ?: 1000)).toInt())
        movie.draw(canvas, 0f, 0f)
        canvas.restoreToCount(count)
        if (isAttachedToWindow) postInvalidateDelayed(32)
    }
}

private sealed interface LoadedImage {
    data class Still(val drawable: Drawable) : LoadedImage
    data class Gif(val movie: Movie) : LoadedImage
}

@Composable
internal fun MediaViewer(entry: MediaEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    var image by remember { mutableStateOf<View?>(null) }
    val contentUri = remember(entry.path) {
        entry.path.takeIf { it.startsWith("content://") }?.let(Uri::parse)
    }
    val imageResult by produceState<Result<LoadedImage>?>(null, entry.path) {
        if (entry.mimeType.startsWith("image/")) value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    val source = contentUri?.let { ImageDecoder.createSource(context.contentResolver, it) }
                        ?: ImageDecoder.createSource(File(entry.path))
                    val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                        val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                    LoadedImage.Still(drawable)
                } else if (entry.mimeType == "image/gif") {
                    val movie = requireNotNull(contentUri?.let { uri ->
                        context.contentResolver.openInputStream(uri)?.use(Movie::decodeStream)
                    } ?: Movie.decodeFile(entry.path))
                    require(movie.width() > 0 && movie.height() > 0)
                    LoadedImage.Gif(movie)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (contentUri != null) context.contentResolver.openInputStream(contentUri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    else BitmapFactory.decodeFile(entry.path, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bitmap = requireNotNull(if (contentUri != null)
                        context.contentResolver.openInputStream(contentUri)?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                    else BitmapFactory.decodeFile(entry.path, options))
                    LoadedImage.Still(BitmapDrawable(context.resources, bitmap))
                }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching { if (ready) player?.pause() }; playing = false
                video?.pause()
                ((image as? ImageView)?.drawable as? Animatable)?.stop()
            }
            if (event == Lifecycle.Event.ON_RESUME) ((image as? ImageView)?.drawable as? Animatable)?.start()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player?.release(); video?.stopPlayback()
            ((image as? ImageView)?.drawable as? Animatable)?.stop()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row {
                    Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("Закрити") }
                }
                if (contentUri == null && !File(entry.path).isFile) Text("Файл відсутній на пристрої.", color = MaterialTheme.colorScheme.error)
                else when {
                    entry.mimeType.startsWith("video/") -> AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                        VideoView(ctx).apply {
                            video = this
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setOnErrorListener { _, _, _ -> error = "Цей відеоформат не підтримується пристроєм або файл пошкоджено."; true }
                            if (contentUri != null) setVideoURI(contentUri) else setVideoPath(entry.path)
                            setOnPreparedListener { start() }
                        }
                    })
                    entry.mimeType.startsWith("audio/") -> {
                        Button(onClick = {
                            if (ready) {
                                if (playing) player?.pause() else player?.start()
                                playing = !playing
                            } else {
                                player?.release()
                                try {
                                    val created = MediaPlayer()
                                    player = created
                                    if (contentUri != null) created.setDataSource(context, contentUri)
                                    else created.setDataSource(entry.path)
                                    created.setOnPreparedListener { ready = true; playing = true; it.start() }
                                    created.setOnCompletionListener { playing = false; it.seekTo(0) }
                                    created.setOnErrorListener { _, _, _ -> error = "Не вдалося відтворити аудіо на цьому пристрої."; ready = false; playing = false; true }
                                    error = null
                                    created.prepareAsync()
                                } catch (failure: Exception) { error = "Аудіофайл недоступний: ${failure.message.orEmpty()}"; ready = false }
                            }
                        }) { Text(if (playing) "Пауза" else "Відтворити") }
                    }
                    entry.mimeType.startsWith("image/") -> {
                        val decoded = imageResult?.getOrNull()
                        if (decoded == null) {
                            if (imageResult == null) CircularProgressIndicator()
                            else Text("Цей формат зображення не підтримується пристроєм або файл пошкоджено.",
                                color = MaterialTheme.colorScheme.error)
                        } else key(decoded) {
                            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                                val result: View = when (decoded) {
                                    is LoadedImage.Still -> ImageView(ctx).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        setImageDrawable(decoded.drawable)
                                        (decoded.drawable as? Animatable)?.start()
                                    }
                                    is LoadedImage.Gif -> GifView(ctx, decoded.movie)
                                }
                                image = result
                                result
                            })
                        }
                    }
                    else -> Column(Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(entry.mimeType, style = MaterialTheme.typography.bodyMedium)
                        if (contentUri != null) Button(onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(contentUri, entry.mimeType)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.onFailure { error = "Для цього формату немає програми перегляду." }
                        }) { Text("Відкрити для перегляду") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
