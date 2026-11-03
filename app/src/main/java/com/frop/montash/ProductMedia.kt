package com.frop.montash

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
internal fun ProductMedia(block: WorkBlock, onUpdate: (WorkBlock) -> Unit,
    editableMedia: Boolean, editableAudio: Boolean, canEdit: Boolean, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestBlock by rememberUpdatedState(block)
    val update by rememberUpdatedState(onUpdate)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<MediaEntry?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingPath by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    fun finishRecording() {
        val active = recorder ?: return
        val path = recordingPath
        recorder = null; recordingPath = null
        var saved = false
        try {
            active.stop()
            val file = File(checkNotNull(path))
            check(file.length() > 0) { "Запис порожній. Спробуйте записати ще раз." }
            val entry = MediaEntry(file.absolutePath, "Голосовий запис", "audio/mp4")
            update(latestBlock.copy(media = latestBlock.media + entry))
            saved = true
        } catch (failure: Exception) { error = "Не вдалося зберегти запис. ${failure.message.orEmpty()}" }
        finally { active.release(); if (!saved) path?.let { File(it).delete() } }
    }
    fun beginRecording() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Для запису потрібен дозвіл на мікрофон."; return
        }
        val file = File(context.filesDir, "media/${UUID.randomUUID()}.m4a")
        var active: MediaRecorder? = null
        try {
            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            @Suppress("DEPRECATION")
            val created = MediaRecorder()
            active = created
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setOutputFile(file.absolutePath)
            created.prepare(); created.start()
            recordingPath = file.absolutePath; recorder = created; seconds = 0; error = null
        } catch (failure: Exception) { active?.release(); file.delete(); error = "Не вдалося почати запис: ${failure.message.orEmpty()}" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else error = "Для голосового запису дозвольте доступ до мікрофона в налаштуваннях Montash."
    }
    LaunchedEffect(recorder) {
        if (recorder != null) while (isActive) { delay(1000); seconds++ }
    }
    val stop by rememberUpdatedState(::finishRecording)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            recorder?.release(); recordingPath?.let { File(it).delete() }
        }
    }
    BackHandler(enabled = recorder != null) { finishRecording() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val copied = mutableListOf<MediaEntry>()
                var committed = false
                try {
                    withContext(Dispatchers.IO) {
                        val folder = File(context.filesDir, "media")
                        check(folder.isDirectory || folder.mkdirs()) { "Не вдалося створити папку медіа." }
                        uris.forEach { uri ->
                            ensureActive()
                            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "Медіафайл"
                            val mime = context.contentResolver.getType(uri)
                                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                            require(isSupportedMediaType(mime)) { "«$name» не є зображенням, відео або аудіо." }
                            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[a-zA-Z0-9]+")) } ?: "media"
                            val file = File(folder, "${UUID.randomUUID()}.$extension")
                            try {
                                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                                    ?: error("Файл «$name» недоступний.")
                                copied.add(MediaEntry(file.absolutePath, name, mime))
                            } catch (failure: Exception) { file.delete(); throw failure }
                        }
                    }
                    update(latestBlock.copy(media = latestBlock.media + copied)); committed = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Не вдалося додати медіа." }
                finally {
                    if (!committed) withContext(NonCancellable + Dispatchers.IO) { copied.forEach { File(it.path).delete() } }
                    busy = false
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Медіа", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (canEdit) TextButton(onClick = { onEdit("media") }) { Text("✎") }
            }
            OutlinedButton(enabled = editableMedia && !busy && recorder == null, onClick = { picker.launch(arrayOf("image/*", "video/*")) }) { Text("＋ Додати медіа") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val visuals = block.media.filterNot { it.mimeType.startsWith("audio/") }
            if (visuals.isEmpty()) Text("Медіа ще не додано")
            visuals.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).clickable { viewing = entry }, contentAlignment = Alignment.Center) {
                                if (entry.mimeType.startsWith("image/")) {
                                    val bitmap = remember(entry.path) { BitmapFactory.decodeFile(entry.path) }
                                    if (bitmap != null) Image(bitmap.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else Text("Зображення")
                                } else {
                                    val thumbnail = remember(entry.path) { ThumbnailUtils.createVideoThumbnail(entry.path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND) }
                                    if (thumbnail != null) Image(thumbnail.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Text("▶ Відео")
                                }
                            }
                            if (editableMedia) TextButton(onClick = { update(latestBlock.copy(media = latestBlock.media.filterNot { it.id == entry.id })) }) { Text("Видалити") }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Голосові повідомлення", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (canEdit) TextButton(onClick = { onEdit("audio") }) { Text("✎") }
            }
            Button(enabled = editableAudio && !busy, onClick = {
                    if (recorder != null) finishRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (recorder != null) "Зберегти запис" else "Записати голос") }
            if (recorder != null) Text("Запис голосу: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
            val recordings = block.media.filter { it.mimeType.startsWith("audio/") }
            if (recordings.isEmpty()) Text("Голосових повідомлень ще немає")
            recordings.forEach { entry ->
                AudioControls(entry, onDelete = if (editableAudio) ({
                    update(latestBlock.copy(media = latestBlock.media.filterNot { it.id == entry.id }))
                }) else null)
            }
        }
    }
    viewing?.let { entry -> key(entry.id) { MediaViewer(entry) { viewing = null } } }
}

@Composable
private fun AudioControls(entry: MediaEntry, onDelete: (() -> Unit)?) {
    var player by remember(entry.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(entry.id) { mutableStateOf(false) }
    var position by remember(entry.id) { mutableFloatStateOf(0f) }
    var totalDuration by remember(entry.id) { mutableFloatStateOf(1f) }
    var volume by remember(entry.id) { mutableFloatStateOf(1f) }
    var volumeOpen by remember(entry.id) { mutableStateOf(false) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    DisposableEffect(entry.id, player) {
        val active = player
        onDispose { active?.release() }
    }
    LaunchedEffect(player, playing) {
        while (playing) { position = player?.currentPosition?.toFloat() ?: 0f; delay(250) }
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.name, Modifier.widthIn(max = 130.dp), maxLines = 1)
            TextButton(onClick = {
                try {
                    val active = player ?: MediaPlayer().apply {
                        setDataSource(entry.path); prepare(); setVolume(volume, volume)
                        totalDuration = duration.toFloat().coerceAtLeast(1f)
                        setOnCompletionListener { playing = false; position = 0f; seekTo(0) }
                    }.also { player = it }
                    if (playing) active.pause() else active.start()
                    playing = !playing
                } catch (failure: Exception) { error = "Не вдалося відтворити запис"; player?.release(); player = null; playing = false }
            }) { Text(if (playing) "Пауза" else "▶") }
            TextButton(onClick = { player?.pause(); player?.seekTo(0); position = 0f; playing = false }) { Text("■") }
            Slider(position, { value -> position = value; player?.seekTo(value.toInt()) }, Modifier.weight(1f), valueRange = 0f..totalDuration)
            Box {
                TextButton(onClick = { volumeOpen = true }) { SoundIcon(volume > 0f) }
                DropdownMenu(expanded = volumeOpen, onDismissRequest = { volumeOpen = false }) {
                    VerticalVolumeLine(volume) { volume = it; player?.setVolume(it, it) }
                }
            }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("✕") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SoundIcon(audible: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val speaker = Path().apply {
            moveTo(size.width * .12f, size.height * .38f)
            lineTo(size.width * .34f, size.height * .38f)
            lineTo(size.width * .62f, size.height * .17f)
            lineTo(size.width * .62f, size.height * .83f)
            lineTo(size.width * .34f, size.height * .62f)
            lineTo(size.width * .12f, size.height * .62f)
            close()
        }
        drawPath(speaker, color)
        if (audible) {
            drawArc(color, -55f, 110f, false, topLeft = Offset(size.width * .38f, size.height * .17f),
                size = androidx.compose.ui.geometry.Size(size.width * .5f, size.height * .66f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        } else {
            drawLine(color, Offset(size.width * .72f, size.height * .35f), Offset(size.width * .94f, size.height * .65f), 2.dp.toPx())
        }
    }
}

@Composable
private fun VerticalVolumeLine(volume: Float, onChange: (Float) -> Unit) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outline
    Canvas(Modifier.width(48.dp).height(150.dp)
        .pointerInput(onChange) { detectTapGestures { onChange((1f - it.y / size.height).coerceIn(0f, 1f)) } }
        .pointerInput(onChange) { detectDragGestures { change, _ ->
            onChange((1f - change.position.y / size.height).coerceIn(0f, 1f))
            change.consume()
        } }) {
        val x = size.width / 2f
        val stroke = 3.dp.toPx()
        drawLine(inactive, Offset(x, 8.dp.toPx()), Offset(x, size.height - 8.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(active, Offset(x, size.height - 8.dp.toPx()),
            Offset(x, size.height - 8.dp.toPx() - (size.height - 16.dp.toPx()) * volume), stroke, cap = StrokeCap.Round)
    }
}
