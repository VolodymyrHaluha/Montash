package com.frop.montash

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DrawingEntry(val id: String, val name: String, val path: String)

internal fun readDrawings(block: JSONObject): List<DrawingEntry> {
    val values = block.optJSONArray("drawings") ?: return emptyList()
    return (0 until values.length()).map {
        val value = values.getJSONObject(it)
        DrawingEntry(value.getString("id"), value.getString("name"), value.getString("path"))
    }
}

internal fun drawingsJson(drawings: List<DrawingEntry>): JSONArray = JSONArray().apply {
    drawings.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path)) }
}

@Composable
internal fun ProductDrawings(block: WorkBlock, onUpdate: (WorkBlock) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestBlock by rememberUpdatedState(block)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var drawingId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val selection = target
        target = null
        if (selection != null && uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val copied = mutableListOf<DrawingEntry>()
                try {
                    withContext(Dispatchers.IO) {
                        val folder = File(context.filesDir, "drawings")
                        check(folder.isDirectory || folder.mkdirs()) { "Не вдалося створити папку креслень." }
                        uris.forEach { uri ->
                            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "Креслення.pdf"
                            val id = UUID.randomUUID().toString()
                            val file = File(folder, "$id.pdf")
                            try {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val header = ByteArray(5)
                                    var count = 0
                                    while (count < header.size) {
                                        val read = input.read(header, count, header.size - count)
                                        if (read < 0) break
                                        count += read
                                    }
                                    require(count == 5 && header.contentEquals("%PDF-".toByteArray())) { "Файл «$displayName» не є PDF." }
                                    file.outputStream().use { output -> output.write(header); input.copyTo(output) }
                                } ?: error("Не вдалося прочитати «$displayName».")
                                copied.add(DrawingEntry(id, displayName, file.absolutePath))
                            } catch (failure: Exception) { file.delete(); throw failure }
                        }
                    }
                    check(latestBlock.id == selection) { "Виріб не знайдено." }
                    latestUpdate(latestBlock.copy(drawings = latestBlock.drawings + copied))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { copied.forEach { File(it.path).delete() } }
                    throw cancelled
                } catch (failure: Exception) {
                    withContext(Dispatchers.IO) { copied.forEach { File(it.path).delete() } }
                    error = failure.message ?: "Не вдалося прикріпити креслення."
                } finally { busy = false }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PDF-креслення", style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (block.drawings.isEmpty()) Text("Креслення ще не прикріплено")
            block.drawings.forEach { drawing ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { drawingId = drawing.id }) { Text(drawing.name) }
                    TextButton(enabled = !busy, onClick = {
                        try {
                            latestUpdate(latestBlock.copy(drawings = latestBlock.drawings.filterNot { it.id == drawing.id }))
                            File(drawing.path).delete()
                        } catch (failure: Exception) { error = failure.message }
                    }) { Text("Відкріпити") }
                }
            }
            TextButton(enabled = !busy && target == null, onClick = {
                target = block.id
                picker.launch(arrayOf("application/pdf"))
            }) { Text("Прикріпити PDF-креслення") }
        }
    }
    block.drawings.find { it.id == drawingId }?.let { drawing ->
        key(drawing.id) { PdfViewer(drawing, onClose = { drawingId = null }) }
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Креслення") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Закрити") } })
    }
}