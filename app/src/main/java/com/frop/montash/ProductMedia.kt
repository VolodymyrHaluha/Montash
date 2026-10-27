package com.frop.montash

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
internal fun ProductMedia(block: WorkBlock, onUpdate: (WorkBlock) -> Unit) {
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
            Text("Медіа та голосові повідомлення", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy && recorder == null, onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) }) { Text("Додати медіа") }
                Button(enabled = !busy, onClick = {
                    if (recorder != null) finishRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (recorder != null) "Зберегти запис" else "Записати голос") }
            }
            if (recorder != null) Text("Запис голосу: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (block.media.isEmpty()) Text("Медіа ще не додано")
            block.media.forEach { entry ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.weight(1f), enabled = recorder == null, onClick = { viewing = entry }) { Text(entry.name) }
                    TextButton(enabled = !busy && recorder == null, onClick = {
                        try {
                            update(latestBlock.copy(media = latestBlock.media.filterNot { it.id == entry.id }))
                            if (viewing?.id == entry.id) viewing = null
                            File(entry.path).delete()
                        } catch (failure: Exception) { error = failure.message }
                    }) { Text("Видалити") }
                }
            }
        }
    }
    viewing?.let { entry -> key(entry.id) { MediaViewer(entry) { viewing = null } } }
}
