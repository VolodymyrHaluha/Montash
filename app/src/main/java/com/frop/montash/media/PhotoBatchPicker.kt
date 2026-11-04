package com.frop.montash.media

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.frop.montash.EditActionGlyph
import com.frop.montash.PhotoImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal enum class PhotoBatchSource { GALLERY, CAMERA }

@Composable
internal fun PhotoBatchPicker(
    source: PhotoBatchSource,
    onClose: () -> Unit,
    onFinished: (List<String>) -> Unit,
    onBusyChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }

    fun discardAndClose() {
        capturedPaths.forEach { File(it).delete() }
        capturedPaths = emptyList()
        onClose()
    }

    fun finish(paths: List<String> = capturedPaths) {
        if (paths.isEmpty()) return
        capturedPaths = emptyList()
        onFinished(paths)
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) {
            onClose()
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val copied = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val folder = File(context.filesDir, "photos")
                    check(folder.isDirectory || folder.mkdirs())
                    uris.forEach { uri ->
                        val file = File(folder, "${UUID.randomUUID()}.jpg")
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            } ?: error("Фото недоступне")
                            copied += file.absolutePath
                        } catch (failure: Exception) {
                            file.delete()
                            throw failure
                        }
                    }
                }
                finish(copied)
            } catch (_: Exception) {
                copied.forEach { File(it).delete() }
                error = "Не вдалося додати вибрані фото."
            } finally { busy = false }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) error = "Для зйомки дозвольте Montash доступ до камери."
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(context.filesDir, "photos/${UUID.randomUUID()}.jpg")
        file.parentFile?.mkdirs()
        busy = true
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedPaths = capturedPaths + file.absolutePath
                    busy = false
                    error = null
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    busy = false
                    error = "Не вдалося зробити фото."
                }
            })
    }

    LaunchedEffect(busy) { onBusyChange(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }

    LaunchedEffect(source) {
        when (source) {
            PhotoBatchSource.GALLERY -> gallery.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            PhotoBatchSource.CAMERA -> if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) permission.launch(Manifest.permission.CAMERA)
        }
    }

    if (source == PhotoBatchSource.GALLERY) return

    Dialog(onDismissRequest = { if (!busy) discardAndClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = false
    )) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("photo-camera-mode")) {
                if (cameraPermissionGranted) {
                    BatchCameraPreview(onReady = { imageCapture = it },
                        onError = { error = "Камера недоступна." })
                }

                IconButton(onClick = ::discardAndClose, enabled = !busy,
                    modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(16.dp)
                        .size(52.dp).background(Color.Black.copy(alpha = .65f), CircleShape)
                        .testTag("exit-photo-camera")) { EditActionGlyph("xmark") }

                if (capturedPaths.isNotEmpty()) {
                    Surface(Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(16.dp),
                        color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(20.dp)) {
                        Text("Фото: ${capturedPaths.size}", color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }

                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                        capturedPaths.lastOrNull()?.let { path ->
                            PhotoImage(path, Modifier.fillMaxSize().background(Color.DarkGray, CircleShape),
                                maxDimension = 160)
                        }
                    }
                    IconButton(onClick = ::takePhoto, enabled = !busy && imageCapture != null,
                        modifier = Modifier.size(78.dp).background(Color.White, CircleShape)
                            .testTag("take-batch-photo")) {
                        Box(Modifier.size(62.dp).background(Color.Black, CircleShape))
                    }
                    IconButton(onClick = { finish() }, enabled = !busy && capturedPaths.isNotEmpty(),
                        modifier = Modifier.size(60.dp).background(Color.Black.copy(alpha = .65f), CircleShape)
                            .testTag("confirm-photo-batch")) { EditActionGlyph("check") }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .75f),
                            RoundedCornerShape(12.dp)).padding(16.dp))
                }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
    }
}

@Composable
private fun BatchCameraPreview(onReady: (ImageCapture?) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(lifecycleOwner, previewView) {
        var active = true
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!active) return@addListener
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                onReady(capture)
            }.onFailure { onError() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active = false
            onReady(null)
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
