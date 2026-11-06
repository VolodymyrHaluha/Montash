package com.frop.montash

import com.frop.montash.data.*
import com.frop.montash.media.*
import com.frop.montash.ui.components.*

import com.frop.montash.R as MontashResources

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics

internal class NavigationTapGate(
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val intervalMs: Long = 1_000L
) {
    private var nextAllowedAt = 0L

    fun tryEnter(): Boolean {
        val time = now()
        if (time < nextAllowedAt) return false
        nextAllowedAt = time + intervalMs
        return true
    }
}
private val Ink = Color(0xFFF0F6F3)
private val Muted = Color(0xFFC0D1C9)
private val Teal = Color(0xFF60D5B6)
private val TealDark = Color(0xFFA4EBD6)

@Composable
internal fun isCompactLayout(): Boolean = LocalConfiguration.current.screenWidthDp < 840
private val Mint = Color(0xCC214B3D)
private val Surface = Color(0xCC16261F)
private val SolidSurface = Color(0xFF16261F)
private val CanvasColor = Color(0xFF17261F)
private val Red = Color(0xFFD96C67)
private val Border = Color(0xFF537265)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MontashTheme { MontashApp() } }
    }
}

@Composable
fun MontashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal, onPrimary = CanvasColor,
            primaryContainer = Mint, onPrimaryContainer = Ink,
            secondary = TealDark, onSecondary = CanvasColor,
            background = CanvasColor, onBackground = Ink,
            surface = SolidSurface, onSurface = Ink,
            surfaceVariant = Color(0xFF243E32), onSurfaceVariant = Muted,
            surfaceContainer = SolidSurface, surfaceContainerHigh = SolidSurface,
            surfaceContainerHighest = SolidSurface,
            outline = Border, error = Red
        ),
        typography = Typography(),
        content = { CompositionLocalProvider(LocalContentColor provides Ink) { InputEditingHost { content() } } }
    )
}

@Composable
internal fun MontashApp() {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val remote = remember { RemoteProjects(context) }
    val monitor = remember { ServerConnectionMonitor(context, remote) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val syncRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    var serverConnection by remember { mutableStateOf(ServerConnection.CHECKING) }
    var refreshNeeded by remember { mutableStateOf(true) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val directoryStore = remember { LocalDirectoryStore(context) }
    var clients by remember { mutableStateOf<List<Client>>(emptyList()) }
    LaunchedEffect(store) {
        try {
            val initial = withContext(Dispatchers.IO) {
                val saved = store.load()
                saved to directoryStore.loadClients(saved)
            }
            projects = initial.first
            clients = initial.second
            loaded = true
        } catch (failure: Exception) {
            loadError = failure.message ?: "Не вдалося прочитати локальні дані."
        }
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loadError == null) CircularProgressIndicator()
            else Text("Не вдалося відкрити локальні дані: $loadError", color = Red)
        }
        return
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val navigationTapGate = remember { NavigationTapGate() }
    fun localError(error: Exception) {
        scope.launch { snackbar.showSnackbar(error.message ?: "Не вдалося зберегти дані на пристрої.", actionLabel="Закрити") }
    }
    LaunchedEffect(Unit) {
        if (remote.enabled && BuildConfig.MONTASH_LOCAL_API_URL.isNotBlank() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(monitor, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var firstCheck = true
            ConnectionPolling(SystemClock::elapsedRealtime, ::delay, intervalMs = 5_000L).run {
                val current = monitor.check()
                val previous = serverConnection
                if (current != previous) {
                    serverConnection = current
                }
                if ((firstCheck || current != previous) &&
                    (current == ServerConnection.LOCAL || current == ServerConnection.GLOBAL)) {
                    refreshNeeded = true
                    syncRequests.trySend(Unit)
                }
                firstCheck = false
            }
        }
    }
    LaunchedEffect(remote) {
        for (request in syncRequests) {
            var retryDelay = 2_000L
            var attempts = 0
            while (true) {
                val current = serverConnection
                if (current != ServerConnection.LOCAL && current != ServerConnection.GLOBAL) break
                try {
                    if (refreshNeeded) {
                        val refreshed = withContext(Dispatchers.IO) {
                            val snapshot = store.load()
                            val updated = remote.refresh(snapshot, current)
                            store.mergeRefreshed(snapshot, updated)
                        }
                        projects = refreshed
                        refreshNeeded = false
                    }
                    if (remote.hasPending()) remote.upload(store, current)
                    remote.uploadExistingMedia(store, current)
                    break
                } catch (conflict: RemoteProjects.RevisionConflict) {
                    localError(conflict)
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (++attempts >= 5) { localError(error); break }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                }
            }
        }
    }
    fun sendProject() {
        syncRequests.trySend(Unit)
    }
    fun addClient(value: String): Client? {
        val name = cleanName(value)
        if(name.isBlank()) return null
        clients.find { nameKey(it.name) == nameKey(name) }?.let { return it }
        val client = Client(UUID.randomUUID().toString(), name)
        return try {
            val updated = clients + client
            directoryStore.saveClients(updated)
            clients = updated
            client
        } catch(error: Exception) { localError(error); null }
    }
    var screen by rememberSaveable { mutableStateOf(Screen.OBJECTS) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProductId = rememberSaveable { mutableStateOf<String?>(null) }
    var objectReturnScreen by rememberSaveable { mutableStateOf(Screen.OBJECTS) }
    var detailBackSignal by remember { mutableIntStateOf(0) }
    var photoSelection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    suspend fun update(expected: Project, project: Project) {
        val (updated, changed) = withContext(Dispatchers.IO) {
            store.replaceProject(expected, project) {
                if (remote.enabled) remote.markPending(project.id)
            }
        }
        projects = updated
        if (changed) sendProject()
    }
    suspend fun deleteProject(expected: Project) {
        val removedFiles = expected.blocks.flatMap { block ->
            block.photos.map { it.path } + block.media.map { it.path }
        }
        val updated = withContext(Dispatchers.IO) {
            store.deleteProject(expected) {
                if (remote.enabled) remote.markDeleted(expected.id)
            }
        }
        projects = updated
        selectedId = null
        selectedProductId.value = null
        screen = Screen.OBJECTS
        val retainedFiles = updated.flatMap { project -> project.blocks.flatMap { block ->
            block.photos.map { it.path } + block.media.map { it.path }
        } }.toSet()
        withContext(Dispatchers.IO) {
            removedFiles.filterNot { it in retainedFiles }.distinct().forEach { File(it).delete() }
        }
        sendProject()
    }
    fun open(project: Project) {
        if (!navigationTapGate.tryEnter()) return
        selectedId = project.id; selectedProductId.value = null
        objectReturnScreen = Screen.OBJECTS; screen = Screen.OBJECT_DETAIL
    }
    fun openProduct(project: Project, product: WorkBlock) {
        if (!navigationTapGate.tryEnter()) return
        selectedId = project.id; selectedProductId.value = product.id
        objectReturnScreen = Screen.PRODUCTS; screen = Screen.OBJECT_DETAIL
    }
    fun openPhoto(project: Project, block: WorkBlock, photo: PhotoEntry) {
        photoSelection = listOf(project.id, block.id, photo.id)
    }
    suspend fun changePhoto(newPath: String?) {
        val selection = checkNotNull(photoSelection)
        val project = checkNotNull(projects.find { it.id == selection[0] })
        val block = checkNotNull(project.blocks.find { it.id == selection[1] })
        val oldPhoto = checkNotNull(block.photos.find { it.id == selection[2] })
        val photos = if (newPath == null) block.photos.filterNot { it.id == oldPhoto.id }
        else block.photos.map { if (it.id == oldPhoto.id) it.copy(path = newPath) else it }
        update(project, project.copy(blocks = project.blocks.map { if (it.id == block.id) it.copy(photos = photos) else it }))
        // Remove the old file only after the changed object has been saved.
        val stillUsed = projects.any { p -> p.blocks.any { b -> b.photos.any { it.path == oldPhoto.path } } }
        if (!stillUsed) withContext(Dispatchers.IO) { runCatching { File(oldPhoto.path).delete() } }
        if (newPath == null) photoSelection = null
    }
    BackHandler(enabled = screen == Screen.PRODUCTS) { screen = Screen.OBJECTS }
    @Composable fun ScreenContent(current: Screen) {
        when (current) {
            Screen.OBJECTS -> ObjectsScreen(projects, ::addClient, ::open) { project ->
                scope.launch {
                    try {
                        projects = withContext(Dispatchers.IO) {
                            store.addProject(project) {
                                if (remote.enabled) remote.markPending(project.id)
                            }
                        }
                        sendProject()
                        open(project)
                    } catch (failure: Exception) { localError(failure) }
                }
            }
            Screen.PRODUCTS -> {
                val compact = isCompactLayout()
                Column(Modifier.fillMaxSize().padding(
                    horizontal = if (compact) 16.dp else 28.dp,
                    vertical = if (compact) 16.dp else 22.dp
                )) {
                    var searchText by rememberSaveable { mutableStateOf("") }
                    var appliedSearch by rememberSaveable { mutableStateOf("") }
                    PageHeader("Вироби", "Всього: ${projects.sumOf { it.blocks.size }}",
                        search = { ListSearch(searchText, { searchText = it },
                            { appliedSearch = searchText.trim() },
                            alignment = if (compact) Alignment.CenterStart else Alignment.Center) })
                    Spacer(Modifier.height(16.dp))
                    ProductsTable(projects, ::openProduct, Modifier.weight(1f).fillMaxWidth(),
                        appliedSearch, compactTable = compact)
                }
            }
            Screen.OBJECT_DETAIL -> projects.find { it.id == selectedId }?.let { project ->
                key(project.id) {
                    ObjectDetail(project, { screen = objectReturnScreen },
                        { block, photo -> openPhoto(project, block, photo) }, ::update,
                        initialProductId = selectedProductId.value,
                        selectedProductId = selectedProductId,
                        backSignal = detailBackSignal,
                        canOpenProduct = navigationTapGate::tryEnter,
                        onDeleteProject = { target ->
                            scope.launch {
                                try { deleteProject(target) }
                                catch (failure: Exception) { localError(failure) }
                            }
                        })
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(CanvasColor)) {
        Image(
            painter = painterResource(MontashResources.drawable.frop_logo_preview_01_1),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        if (isCompactLayout()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(targetState = screen, label = "screen",
                    modifier = Modifier.weight(1f)) { ScreenContent(it) }
                CompactNavigationBar(screen, { screen = it }, onBack = { detailBackSignal++ })
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(screen, { screen = it }, onBack = { detailBackSignal++ },
                    connection = serverConnection)
                AnimatedContent(targetState = screen, label = "screen",
                    modifier = Modifier.weight(1f)) { ScreenContent(it) }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment=Alignment.BottomCenter) {
        SnackbarHost(snackbar, modifier=Modifier.padding(16.dp))
    }
    photoSelection?.let { selection ->
        val photo = projects.find { it.id == selection[0] }?.blocks
            ?.find { it.id == selection[1] }?.photos?.find { it.id == selection[2] }
        if (photo != null) key(selection) {
            PhotoViewer(photo, onClose = { photoSelection = null },
                onReplace = { path -> scope.launch { runCatching { changePhoto(path) }.onFailure { localError(it as? Exception ?: Exception(it)) } } },
                onDelete = { scope.launch { runCatching { changePhoto(null) }.onFailure { localError(it as? Exception ?: Exception(it)) } } })
        }
    }
}

@Composable
private fun CompactNavigationBar(active: Screen, onSelect: (Screen) -> Unit, onBack: () -> Unit) {
    NavigationBar(Modifier.fillMaxWidth().testTag("compact-navigation"), containerColor = Surface) {
        if (active == Screen.OBJECT_DETAIL) {
            NavigationBarItem(selected = false, onClick = onBack,
                modifier = Modifier.testTag("navigation-back")
                    .semantics { contentDescription = "Назад" },
                icon = { Text("‹", fontSize = 28.sp) }, label = { Text("Назад") })
        }
        listOf(Screen.OBJECTS to "Об'єкти", Screen.PRODUCTS to "Вироби").forEach { (screen, label) ->
            val selected = active == screen || (screen == Screen.OBJECTS && active == Screen.OBJECT_DETAIL)
            NavigationBarItem(selected = selected, onClick = { onSelect(screen) },
                modifier = Modifier.testTag("navigation-${screen.name.lowercase()}"),
                icon = { NavGlyph(screen, if (selected) Teal else Muted) },
                label = { Text(label) })
        }
    }
}

@Composable
private fun NavigationRail(active: Screen, onSelect: (Screen) -> Unit, onBack: () -> Unit,
    connection: ServerConnection) {
    val items = listOf(Screen.OBJECTS to "Об'єкти", Screen.PRODUCTS to "Вироби")
    Column(
        Modifier.width(116.dp).fillMaxHeight().background(Surface).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (active == Screen.OBJECT_DETAIL) IconButton(onClick = onBack,
            modifier = Modifier.testTag("navigation-back").semantics { contentDescription = "Назад" }) {
            Canvas(Modifier.size(24.dp)) {
                val stroke = 2.5.dp.toPx()
                drawLine(Teal, Offset(size.width * .62f, size.height * .15f),
                    Offset(size.width * .32f, size.height * .5f), stroke, cap = StrokeCap.Round)
                drawLine(Teal, Offset(size.width * .32f, size.height * .5f),
                    Offset(size.width * .62f, size.height * .85f), stroke, cap = StrokeCap.Round)
            }
        }
        if (active == Screen.OBJECT_DETAIL) Spacer(Modifier.weight(1f))
        items.forEach { (screen, label) ->
            val selected = active == screen || (screen == Screen.OBJECTS && active in listOf(Screen.OBJECT_DETAIL))
            Column(
                Modifier.fillMaxWidth().testTag("navigation-${screen.name.lowercase()}").clickable { onSelect(screen) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Mint else Color.Transparent), contentAlignment = Alignment.Center) {
                    NavGlyph(screen, if (selected) Teal else Muted)
                }
                Text(label, color = if (selected) TealDark else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (active != Screen.OBJECT_DETAIL) Spacer(Modifier.weight(1f))
        val connected = connection == ServerConnection.LOCAL || connection == ServerConnection.GLOBAL
        val statusColor = when (connection) {
            ServerConnection.LOCAL, ServerConnection.GLOBAL -> Teal
            ServerConnection.CHECKING -> Color(0xFFE0AC53)
            else -> Red
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("server-connection-status"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.height(5.dp))
            Text(connection.label, color = statusColor, fontSize = 9.sp,
                lineHeight = 11.sp, textAlign = TextAlign.Center,
                fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
}

@Composable
private fun NavGlyph(screen: Screen, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.minDimension
        when (screen) {
            Screen.PRODUCTS -> { drawRect(color, Offset(s*.2f,s*.1f), Size(s*.6f,s*.8f), style=Stroke(2.5f)); repeat(3) { drawLine(color, Offset(s*.32f,s*(.32f+it*.18f)), Offset(s*.68f,s*(.32f+it*.18f)), strokeWidth=2f) } }
            Screen.OBJECTS, Screen.OBJECT_DETAIL -> { drawRoundRect(color, Offset(s*.13f,s*.1f), Size(s*.74f,s*.8f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(2.5f)); repeat(2){r->repeat(2){c->drawRect(color,Offset(s*(.26f+c*.31f),s*(.27f+r*.29f)),Size(s*.16f,s*.14f))}} }

        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String,
    search: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    if (isCompactLayout()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column { Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(3.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
            if (search != null || action != null) Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (search != null) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { search() }
                action?.invoke()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Spacer(Modifier.height(3.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
            if (search != null) Box(Modifier.weight(1.5f)) { search() }
            if (search != null && action != null) Spacer(Modifier.width(12.dp))
            action?.invoke()
        }
    }
}

@Composable
private fun ListSearch(value: String, onValueChange: (String) -> Unit, onApply: () -> Unit,
    modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    Box(modifier.fillMaxWidth(), contentAlignment = alignment) {
        Row(Modifier.width(220.dp).height(40.dp)) {
            BasicTextField(value, onValueChange, singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Ink),
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .border(1.dp, Border, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .padding(horizontal = 14.dp).testTag("list-search-input"),
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("Пошук...", color = Muted)
                        input()
                    }
                })
            Button(onClick = onApply,
                modifier = Modifier.size(width = 48.dp, height = 40.dp).testTag("list-search-apply"),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)) {
                Icon(painterResource(MontashResources.drawable.ic_search),
                    contentDescription = "Пошук", tint = CanvasColor,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable internal fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Surface(modifier, shape=RoundedCornerShape(20.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) { Column(Modifier.padding(18.dp), content=content) }

@Composable
private fun ObjectsScreen(projects: List<Project>, onAddClient: (String) -> Client?,
    onProject: (Project) -> Unit, onCreate: (Project) -> Unit) {
    val compact = isCompactLayout()
    var searchText by rememberSaveable { mutableStateOf("") }
    var appliedSearch by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var objectName by rememberSaveable { mutableStateOf("") }
    var objectDescription by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(
        horizontal = if (compact) 16.dp else 28.dp,
        vertical = if (compact) 16.dp else 22.dp
    )) {
        PageHeader("Об'єкти", "Всього: ${projects.size}",
            search = { ListSearch(searchText, { searchText = it },
                { appliedSearch = searchText.trim() },
                alignment = if (compact) Alignment.CenterStart else Alignment.Center) },
            action = {
                Button(onClick={ objectName="";objectDescription="";creating=true }) { Text("Створити об'єкт") }
            })
        Spacer(Modifier.height(16.dp))
        if (projects.isEmpty()) Text("Створіть перший об'єкт: вкажіть його назву.", color=Muted)
        ObjectsTable(projects, onProject, Modifier.weight(1f).fillMaxWidth(), appliedSearch,
            compactTable = compact)
    }
    if (creating) AlertDialog(
        modifier=Modifier.dismissInputOnOutsideTouch(),
        onDismissRequest={creating=false}, title={Text("Новий об'єкт")},
        text={ Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(objectName, { objectName=it }, label={Text("Назва об'єкта")}, singleLine=true)
            OutlinedTextField(objectDescription, { objectDescription=it }, label={Text("Опис об'єкта")}, minLines=2)
        } },
        confirmButton={ TextButton(enabled=cleanName(objectName).isNotBlank(), onClick={
            onAddClient(objectName)?.let { client ->
                onCreate(Project(name=client.name, customer=client.name,
                    customerId=client.id, description=objectDescription))
                creating=false
            }
        }) {Text("Створити")} },
        dismissButton={TextButton(onClick={creating=false}) {Text("Скасувати")}}
    )
}

@Composable
internal fun ObjectDetail(project: Project, onBack: () -> Unit, onPhoto: (WorkBlock, PhotoEntry) -> Unit,
    onUpdate: suspend (Project, Project) -> Unit,
    initialProductId: String? = null,
    selectedProductId: MutableState<String?>? = null,
    backSignal: Int = 0,
    canOpenProduct: () -> Boolean = { true }, onDeleteProject: (Project) -> Unit = {}) {
    val compact = isCompactLayout()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val localProductId = rememberSaveable(project.id, initialProductId) { mutableStateOf(initialProductId) }
    val productIdState = selectedProductId ?: localProductId
    var productId by productIdState
    var draft by remember(project.id) { mutableStateOf<WorkBlock?>(null) }
    var activeEdit by remember(project.id) { mutableStateOf<Pair<String, String?>?>(null) }
    var editBusy by remember(project.id) { mutableStateOf(false) }
    var creating by remember(project.id) { mutableStateOf(false) }
    var viewingPhoto by remember { mutableStateOf<PhotoEntry?>(null) }
    var editingObject by remember { mutableStateOf(false) }
    var pendingAddition by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var productSearchText by rememberSaveable(project.id) { mutableStateOf("") }
    var appliedProductSearch by rememberSaveable(project.id) { mutableStateOf("") }
    val savedProduct = project.blocks.find { it.id == productId }
    val product = savedProduct

    fun cancelProduct() {
        val originalPaths = savedProduct?.let { old ->
            (old.photos.map { it.path } + old.media.map { it.path }).toSet()
        }.orEmpty()
        draft?.let { current ->
            val discarded = (current.photos.map { it.path } + current.media.map { it.path })
                .filterNot { it in originalPaths }
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    discarded.forEach { File(it).delete() }
                }
            }
        }
        draft = null
        activeEdit = null
        editBusy = false
        pendingAddition = null
        viewingPhoto = null
        if (creating) productId = null
        creating = false
        error = null
    }
    fun leaveProduct() {
        if (draft != null) cancelProduct() else productId = null
    }
    fun beginBlockEdit(section: String, batchId: String? = null, addition: String? = null) {
        val original = savedProduct ?: return
        val targetBatch = if (addition != null) UUID.randomUUID().toString() else batchId
        draft = if (addition == "text") original.copy(texts = original.texts + TextEntry("",
            createdAt = System.currentTimeMillis(), batchId = targetBatch)) else original.copy()
        activeEdit = section to targetBatch
        editBusy = false
        pendingAddition = addition
        error = null
    }
    fun saveProduct() {
        val snapshot = draft ?: return
        if (saving || editBusy) return
        val isCreating = creating
        val changed = snapshot.copy(texts = snapshot.texts.filter { it.text.isNotBlank() })
            .withRecordedChanges(savedProduct)
        saving = true
        scope.launch {
            try {
                val blocks = if (isCreating) project.blocks + changed
                    else project.blocks.map { if (it.id == changed.id) changed else it }
                onUpdate(project, project.copy(blocks = blocks))
                if (draft == snapshot) {
                    draft = null
                    activeEdit = null
                    editBusy = false
                }
                if (isCreating) productId = null
                creating = false
                error = null
            } catch (failure: Exception) {
                error = failure.message ?: "Не вдалося зберегти виріб."
            } finally { saving = false }
        }
    }
    var handledBackSignal by remember(project.id) { mutableIntStateOf(backSignal) }
    LaunchedEffect(backSignal) {
        if (backSignal != handledBackSignal) {
            handledBackSignal = backSignal
            if (product != null || draft != null) leaveProduct() else onBack()
        }
    }
    BackHandler(enabled = product != null) { leaveProduct() }

    Column(Modifier.fillMaxSize().padding(
        horizontal = if (compact) 16.dp else if (product == null) 28.dp else 20.dp,
        vertical = if (compact) 14.dp else 20.dp
    )) {
        if (product == null) {
            val title: @Composable () -> Unit = {
                Column {
                    Text(project.displayName, fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 23.sp else 25.sp)
                    Text("Виробів: ${project.blocks.size}", color = Muted, fontSize = 12.sp)
                }
            }
            val actions: @Composable () -> Unit = {
                TextButton(onClick = { editingObject = true }) { Text("Редагувати об’єкт") }
                Button(onClick = {
                    if (draft != null) cancelProduct()
                    draft = WorkBlock()
                    creating = true
                    error = null
                }, modifier = Modifier.testTag("add-product")) { Text("Додати виріб") }
            }
            if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                title()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ListSearch(productSearchText, { productSearchText = it },
                        { appliedProductSearch = productSearchText.trim() }, Modifier.weight(1f),
                        alignment = Alignment.CenterStart)
                    TextButton(onClick = { editingObject = true },
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Редагувати об’єкт", fontSize = 13.sp, maxLines = 1)
                    }
                    Button(onClick = {
                        if (draft != null) cancelProduct()
                        draft = WorkBlock()
                        creating = true
                        error = null
                    }, modifier = Modifier.testTag("add-product"),
                        contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("Додати виріб", fontSize = 13.sp, maxLines = 1)
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { title() }
                ListSearch(productSearchText, { productSearchText = it },
                    { appliedProductSearch = productSearchText.trim() }, Modifier.weight(1.4f))
                Spacer(Modifier.width(12.dp))
                actions()
            }
        }
        error?.let { Text(it, color = Red) }
        if (product == null) Spacer(Modifier.height(18.dp))
        if (product == null) {
            if (project.blocks.isEmpty()) Text("Додайте перший виріб до об'єкта.", color = Muted)
            ProductsTable(listOf(project), { _, block -> if (canOpenProduct()) productId = block.id },
                Modifier.weight(1f).fillMaxWidth(), appliedProductSearch, compactTable = compact)
        } else key(product.id) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("product-details"),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    ProductBlock(product, { photo -> viewingPhoto = photo }, { updated -> if (draft?.id == updated.id) draft = updated },
                        editableInfo = false, editablePhotos = false,
                        editableNote = false, canEdit = true,
                        onEdit = { beginBlockEdit("info") },
                        addition = null,
                        onAdditionHandled = { pendingAddition = null },
                        showAdditions = true, onAdd = { requested ->
                            beginBlockEdit(if (requested == "photos") "photos" else when (requested) {
                                "media-gallery", "media-photo", "media-video" -> "media"
                                "documents" -> "documents"
                                "audio" -> "audio"
                                else -> "text"
                            }, addition = if (requested == "photos") "photos-empty" else requested)
                        })
                    ProductMedia(product, {}, { photo -> viewingPhoto = photo },
                        editableMedia = false, editableAudio = false,
                        editableDocs = false, editablePhotos = false,
                        editableText = false, canEdit = true,
                        onEdit = { section, batchId -> beginBlockEdit(section, batchId) },
                        addition = null, onAdditionHandled = {})
                }
            }
        }
    }
    if (creating && draft != null) {
        val added = checkNotNull(draft)
        Dialog(onDismissRequest = { if (!saving && !editBusy) cancelProduct() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SurfaceCard(Modifier.fillMaxWidth(.96f).fillMaxHeight(.96f).systemBarsPadding()
                .testTag("add-product-dialog")) {
                Text("Новий виріб", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                error?.let { Text(it, color = Red) }
                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    ProductBlock(added, { photo -> viewingPhoto = photo },
                        { updated -> if (draft?.id == updated.id) draft = updated },
                        editableInfo = true, editablePhotos = true, editableNote = true,
                        canEdit = false, onEdit = {}, addition = pendingAddition,
                        onAdditionHandled = { pendingAddition = null }, showAdditions = false,
                        onAdd = { pendingAddition = it }, statusOnNextRow = true,
                        onBusyChange = { editBusy = it })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = ::cancelProduct, enabled = !saving && !editBusy,
                        modifier = Modifier.testTag("cancel-product")
                            .semantics { contentDescription = "Скасувати" }) {
                        EditActionGlyph("xmark")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = ::saveProduct, enabled = !saving && !editBusy,
                        modifier = Modifier.testTag("save-product")
                            .semantics { contentDescription = "Зберегти" }) {
                        EditActionGlyph("check")
                    }
                }
            }
        }
    }
    if (!creating && activeEdit != null && draft != null) {
        val section = checkNotNull(activeEdit)
        val edited = checkNotNull(draft)
        val title = when (section.first) {
            "info" -> "Основна інформація"
            "photos" -> "Фото виробу"
            "media" -> "Медіа"
            "audio" -> "Голосові повідомлення"
            "text" -> "Опис і примітки"
            else -> "Документи"
        }
        Dialog(onDismissRequest = { if (!saving && !editBusy) cancelProduct() },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SurfaceCard(Modifier.fillMaxSize().systemBarsPadding().padding(6.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(14.dp))
                if (section.first == "info") {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        ProductBlock(edited, { photo -> viewingPhoto = photo },
                            { updated -> if (draft?.id == updated.id) draft = updated },
                            editableInfo = true, editablePhotos = true,
                            editableNote = false, canEdit = false, onEdit = {},
                            addition = pendingAddition, onAdditionHandled = { pendingAddition = null },
                            showAdditions = false, onAdd = { pendingAddition = it },
                            photosOnly = false, nameFieldTag = "product-name-editor",
                            newBatchId = section.second, statusOnNextRow = section.first == "info",
                            onBusyChange = { editBusy = it })
                    }
                } else {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState())) {
                    if (section.first == "photos") {
                        ProductBlock(edited, { photo -> viewingPhoto = photo },
                            { updated -> if (draft?.id == updated.id) draft = updated },
                            editableInfo = false, editablePhotos = true,
                            editableNote = false, canEdit = false, onEdit = {},
                            addition = pendingAddition, onAdditionHandled = { pendingAddition = null },
                            showAdditions = false, onAdd = { pendingAddition = it },
                            photosOnly = true, nameFieldTag = "product-name-editor",
                            newBatchId = section.second, statusOnNextRow = false,
                            onBusyChange = { editBusy = it })
                    } else {
                        ProductMedia(edited, { updated -> if (draft?.id == updated.id) draft = updated },
                            { photo -> viewingPhoto = photo },
                            editableMedia = section.first == "media", editableAudio = section.first == "audio",
                            editableDocs = section.first == "documents", editablePhotos = section.first == "photos",
                            editableText = section.first == "text", canEdit = false,
                            onEdit = { _, _ -> }, addition = pendingAddition,
                            onAdditionHandled = { pendingAddition = null }, visibleGroup = section,
                            newBatchId = section.second, onBusyChange = { editBusy = it })
                    }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = ::cancelProduct, enabled = !saving && !editBusy,
                        modifier = Modifier.testTag("cancel-product").semantics { contentDescription = "Скасувати" }) {
                        EditActionGlyph("xmark")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = ::saveProduct, enabled = !saving && !editBusy,
                        modifier = Modifier.testTag("save-product").semantics { contentDescription = "Зберегти" }) {
                        EditActionGlyph("check")
                    }
                }
            }
        }
    }
    viewingPhoto?.let { photo ->
        PhotoViewer(photo, onClose = { viewingPhoto = null }, editable = draft != null,
            onReplace = { path ->
                draft = draft?.copy(photos = draft!!.photos.map { if (it.id == photo.id) it.copy(path = path) else it })
                viewingPhoto = viewingPhoto?.copy(path = path)
            },
            onDelete = {
                draft = draft?.copy(photos = draft!!.photos.filterNot { it.id == photo.id })
                viewingPhoto = null
            })
    }
    if (editingObject) ObjectEditDialog(project, { editingObject = false }, { onDeleteProject(project) }) { updated ->
        scope.launch {
            try { onUpdate(project, updated); editingObject = false } catch (failure: Exception) { error = failure.message }
        }
    }
}

@Composable
internal fun ProductPlaceholder(modifier: Modifier = Modifier) {
    Image(painterResource(MontashResources.drawable.icon), contentDescription = "Фото виробу відсутнє",
        contentScale = ContentScale.Fit, modifier = modifier.padding(24.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductAdditions(onAdd: (String) -> Unit) {
    val compact = isCompactLayout()
    var mediaMenu by remember { mutableStateOf(false) }
    val kinds = listOf("photos" to "Фото", "media" to "Медіа", "audio" to "Аудіо",
        "text" to "Текст", "documents" to "Документи")

    @Composable fun AdditionButton(section: String, label: String, modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (section == "photos") {
                OutlinedButton(onClick = { onAdd("photos") },
                    modifier = Modifier.weight(1f).testTag("add-photo-camera"),
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(label, maxLines = 1, fontSize = 13.sp)
                    Spacer(Modifier.width(5.dp))
                    CameraGlyph()
                }
            } else {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = {
                        if (section == "media") mediaMenu = true else onAdd(section)
                    }, modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(label, maxLines = 1, fontSize = 13.sp)
                        Spacer(Modifier.width(5.dp))
                        ProductActionIcon(section)
                    }
                    if (section == "media") DropdownMenu(mediaMenu, { mediaMenu = false }) {
                        DropdownMenuItem(text = { Text("Галерея телефона") }, onClick = {
                            mediaMenu = false; onAdd("media-gallery")
                        })
                        DropdownMenuItem(text = { Text("Сфотографувати") }, onClick = {
                            mediaMenu = false; onAdd("media-photo")
                        })
                        DropdownMenuItem(text = { Text("Зняти відео") }, onClick = {
                            mediaMenu = false; onAdd("media-video")
                        })
                    }
                }
            }
        }
    }

    SurfaceCard(Modifier.fillMaxWidth().testTag("product-additions")) {
        Text("Додати нові дані", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(12.dp))
        if (compact) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { (section, label) ->
                    AdditionButton(section, label, Modifier.width(126.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { (section, label) ->
                    AdditionButton(section, label, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun CameraGlyph() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.7.dp.toPx()
        drawRoundRect(color, Offset(size.width * .08f, size.height * .28f),
            Size(size.width * .84f, size.height * .58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        drawRect(color, Offset(size.width * .29f, size.height * .17f), Size(size.width * .32f, size.height * .12f))
        drawCircle(color, size.width * .18f, Offset(size.width * .5f, size.height * .56f), style = Stroke(stroke))
    }
}

@Composable
private fun ProductActionIcon(section: String) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val line = 1.7.dp.toPx()
        when (section) {
            "photos" -> {
                drawRoundRect(color, Offset(w * .08f, h * .2f), Size(w * .84f, h * .65f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(line))
                drawCircle(color, w * .12f, Offset(w * .67f, h * .4f))
                drawLine(color, Offset(w * .18f, h * .75f), Offset(w * .43f, h * .5f), line)
                drawLine(color, Offset(w * .43f, h * .5f), Offset(w * .69f, h * .76f), line)
            }
            "media" -> {
                drawRoundRect(color, Offset(w * .08f, h * .18f), Size(w * .84f, h * .64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(line))
                val triangle = Path().apply { moveTo(w * .42f, h * .35f); lineTo(w * .68f, h * .5f);
                    lineTo(w * .42f, h * .65f); close() }
                drawPath(triangle, color)
            }
            "audio" -> {
                drawRoundRect(color, Offset(w * .35f, h * .12f), Size(w * .3f, h * .54f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .15f))
                drawArc(color, 0f, 180f, false, Offset(w * .2f, h * .3f), Size(w * .6f, h * .56f), style = Stroke(line))
                drawLine(color, Offset(w * .5f, h * .78f), Offset(w * .5f, h * .95f), line)
            }
            "text" -> repeat(3) { index ->
                val y = h * (.28f + index * .23f)
                drawLine(color, Offset(w * .12f, y), Offset(w * .88f, y), line, cap = StrokeCap.Round)
            }
            "documents" -> {
                drawRoundRect(color, Offset(w * .2f, h * .08f), Size(w * .6f, h * .84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()), style = Stroke(line))
                drawLine(color, Offset(w * .3f, h * .48f), Offset(w * .7f, h * .48f), line)
                drawLine(color, Offset(w * .3f, h * .65f), Offset(w * .7f, h * .65f), line)
            }
        }
    }
}

@Composable
internal fun ProductEntryCard(title: String, date: Long?, canEdit: Boolean, onEdit: () -> Unit,
    showHeader: Boolean = true, embedded: Boolean = false, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val compact = isCompactLayout()
    val body: @Composable ColumnScope.() -> Unit = {
        if (showHeader) {
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (canEdit) IconButton(onClick = onEdit,
                        modifier = Modifier.semantics { contentDescription = "Редагувати $title" }) {
                        EditActionGlyph("pen")
                    }
                }
                Text(if (date == null) "Дата невідома" else java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(date)),
                    color = Muted, fontSize = 12.sp)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(if (date == null) "Дата невідома" else java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(date)),
                        color = Muted, fontSize = 12.sp)
                    if (canEdit) IconButton(onClick = onEdit,
                        modifier = Modifier.semantics { contentDescription = "Редагувати $title" }) {
                        EditActionGlyph("pen")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
    if (embedded) {
        Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .22f),
            border = androidx.compose.foundation.BorderStroke(1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = .55f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), content = body)
        }
    } else SurfaceCard(modifier.fillMaxWidth(), content = body)
}

@Composable
internal fun EditActionGlyph(kind: String) {
    val resource = when (kind) {
        "pen" -> MontashResources.drawable.fa_pen
        "check" -> MontashResources.drawable.fa_check
        else -> MontashResources.drawable.fa_xmark
    }
    Icon(painterResource(resource), contentDescription = null, modifier = Modifier.size(20.dp))
}

@Composable
private fun ProductBlock(block: WorkBlock, onPhoto: (PhotoEntry) -> Unit, onUpdate: (WorkBlock) -> Unit,
    editableInfo: Boolean, editablePhotos: Boolean, editableNote: Boolean, canEdit: Boolean, onEdit: (String) -> Unit,
    addition: String?, onAdditionHandled: () -> Unit, showAdditions: Boolean, onAdd: (String) -> Unit,
    photosOnly: Boolean = false, nameFieldTag: String = "product-name", newBatchId: String? = null,
    statusOnNextRow: Boolean = false,
    onBusyChange: (Boolean) -> Unit = {}) {
    val compact = isCompactLayout()
    val currentBlock by rememberUpdatedState(block)
    val update by rememberUpdatedState(onUpdate)
    var photoPickerSource by remember { mutableStateOf<PhotoBatchSource?>(null) }
    var photoSourceMenu by remember { mutableStateOf(false) }
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(addition, editablePhotos) {
        if ((addition == "photos" || addition == "camera") && editablePhotos) {
            onAdditionHandled()
            photoSourceMenu = true
        } else if (addition == "text" && editableNote) onAdditionHandled()
    }
    photoPickerSource?.let { source -> PhotoBatchPicker(source = source,
        onClose = { photoPickerSource = null }, onFinished = { paths ->
        val batchId = newBatchId ?: UUID.randomUUID().toString()
        update(currentBlock.copy(photos = currentBlock.photos + photoBatchEntries(paths, batchId)))
        error = null
        photoPickerSource = null
    }, onBusyChange = onBusyChange) }
    SurfaceCard(Modifier.fillMaxWidth()) {
            if (photosOnly) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val visiblePhotos = if (newBatchId == null) block.photos
                        else block.photos.filter { it.batchId == newBatchId }
                    val slotCount = visiblePhotos.size + if (editablePhotos) 1 else 0
                    val columns = if (compact) 2 else 4
                    (0 until slotCount).chunked(columns).forEach { slots ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            slots.forEach { index ->
                                if (index < visiblePhotos.size) {
                                    val photo = visiblePhotos[index]
                                    PhotoImage(photo.path, Modifier.size(120.dp).clickable { onPhoto(photo) },
                                        maxDimension = 320)
                                } else Box {
                                    PhotoAddTile(Modifier.size(120.dp)) { photoSourceMenu = true }
                                    PhotoSourceMenu(photoSourceMenu, { photoSourceMenu = false },
                                        { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.GALLERY },
                                        { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.CAMERA })
                                }
                            }
                        }
                    }
                    if (slotCount == 0) ProductPlaceholder(Modifier.size(90.dp))
                }
            } else {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    if (compact) {
                        Column(Modifier.fillMaxWidth().padding(top = if (canEdit) 42.dp else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Фото", fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth().height(120.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (editablePhotos) Box {
                                    IconButton(onClick = { photoSourceMenu = true },
                                        modifier = Modifier.size(44.dp).testTag("product-photo-camera")) {
                                        CameraGlyph()
                                    }
                                    PhotoSourceMenu(photoSourceMenu, { photoSourceMenu = false },
                                        { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.GALLERY },
                                        { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.CAMERA })
                                }
                                val first = block.photos.firstOrNull()
                                val photoModifier = Modifier.weight(1f).fillMaxHeight()
                                    .testTag("product-photo-preview")
                                Box(if (first != null) photoModifier.clickable { onPhoto(first) } else photoModifier) {
                                    if (first == null) ProductPlaceholder(Modifier.fillMaxSize())
                                    else PhotoImage(first.path, Modifier.fillMaxSize(), maxDimension = 320)
                                }
                            }
                            Text("Назва", fontWeight = FontWeight.Bold)
                            if (editableInfo) OutlinedTextField(block.name,
                                { update(currentBlock.copy(name = it)) },
                                modifier = Modifier.fillMaxWidth().testTag(nameFieldTag)
                                    .finishEditingOnOutsideTouch(), singleLine = true)
                            else Text(block.name.ifBlank { "—" }, Modifier.fillMaxWidth()
                                .testTag("product-name").semantics { disabled() })
                            Text("Опис", fontWeight = FontWeight.Bold)
                            if (editableInfo) OutlinedTextField(block.description,
                                { update(currentBlock.copy(description = it)) },
                                modifier = Modifier.fillMaxWidth().testTag("product-description")
                                    .finishEditingOnOutsideTouch(), minLines = 2)
                            else Text(block.description.ifBlank { "—" }, Modifier.fillMaxWidth()
                                .testTag("product-description"))
                            Text("Статус", fontWeight = FontWeight.Bold)
                            if (editableInfo) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                StatusPicker(block.status, fillWidth = false) {
                                    update(currentBlock.copy(status = it))
                                }
                            } else StatusBadge(block.status)
                        }
                    } else if (statusOnNextRow) {
                        Row(Modifier.fillMaxWidth().height(178.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Column(Modifier.weight(1.35f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Фото", fontWeight = FontWeight.Bold)
                                Box(Modifier.fillMaxWidth().height(152.dp)) {
                                    val first = block.photos.firstOrNull()
                                    val photoModifier = Modifier.fillMaxSize()
                                        .testTag("product-photo-preview")
                                    val interactivePhoto = when {
                                        editablePhotos -> photoModifier.clickable { photoSourceMenu = true }
                                        first != null -> photoModifier.clickable { onPhoto(first) }
                                        else -> photoModifier
                                    }
                                    Surface(interactivePhoto,
                                        shape = RoundedCornerShape(18.dp), color = Color.Transparent,
                                        border = androidx.compose.foundation.BorderStroke(1.dp,
                                            MaterialTheme.colorScheme.outline)) {
                                        if (first == null && editablePhotos) {
                                            Column(Modifier.fillMaxSize(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center) {
                                                CameraGlyph()
                                                Spacer(Modifier.height(8.dp))
                                                Text("Додати фото", color = Muted,
                                                    style = MaterialTheme.typography.labelMedium)
                                            }
                                        } else if (first == null) {
                                            ProductPlaceholder(Modifier.fillMaxSize())
                                        } else PhotoImage(first.path, Modifier.fillMaxSize(), maxDimension = 512)
                                    }
                                    if (editablePhotos) Box(Modifier.align(Alignment.TopStart)
                                        .testTag("product-photo-camera")) {
                                        PhotoSourceMenu(photoSourceMenu, { photoSourceMenu = false },
                                            { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.GALLERY },
                                            { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.CAMERA })
                                    }
                                }
                            }
                            Column(Modifier.weight(2.65f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Назва", fontWeight = FontWeight.Bold)
                                if (editableInfo) OutlinedTextField(block.name,
                                    { update(currentBlock.copy(name = it)) },
                                    modifier = Modifier.fillMaxWidth().testTag(nameFieldTag)
                                        .finishEditingOnOutsideTouch(), singleLine = true)
                                else Text(block.name.ifBlank { "—" }, Modifier.fillMaxWidth()
                                    .testTag("product-name").semantics { disabled() })
                                Text("Опис", fontWeight = FontWeight.Bold)
                                if (editableInfo) OutlinedTextField(block.description,
                                    { update(currentBlock.copy(description = it)) },
                                    modifier = Modifier.fillMaxWidth().height(64.dp)
                                        .testTag("product-description")
                                        .finishEditingOnOutsideTouch(), minLines = 2, maxLines = 2)
                                else Text(block.description.ifBlank { "—" }, Modifier.fillMaxWidth()
                                    .height(64.dp).testTag("product-description"))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Статус", fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterHorizontally))
                            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                contentAlignment = Alignment.Center) {
                                if (editableInfo) StatusPicker(block.status, fillWidth = false) {
                                    update(currentBlock.copy(status = it))
                                } else StatusBadge(block.status)
                            }
                        }
                    } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Фото", Modifier.weight(1.5f), fontWeight = FontWeight.Bold)
                        Text("Назва", Modifier.weight(2.6f), fontWeight = FontWeight.Bold)
                        Text("Опис", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                        if (!statusOnNextRow) Text("Статус", Modifier.weight(1.8f), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(48.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.5f).height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (editablePhotos) Box {
                                IconButton(onClick = { photoSourceMenu = true },
                                    modifier = Modifier.size(34.dp).testTag("product-photo-camera")) {
                                    CameraGlyph()
                                }
                                PhotoSourceMenu(photoSourceMenu, { photoSourceMenu = false },
                                    { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.GALLERY },
                                    { photoSourceMenu = false; photoPickerSource = PhotoBatchSource.CAMERA })
                            }
                            val first = block.photos.firstOrNull()
                            val photoModifier = Modifier.weight(1f).fillMaxHeight().testTag("product-photo-preview")
                            Box(if (first != null) photoModifier.clickable { onPhoto(first) } else photoModifier) {
                                if (first == null) ProductPlaceholder(Modifier.fillMaxSize())
                                else PhotoImage(first.path, Modifier.fillMaxSize(), maxDimension = 320)
                            }
                        }
                        if (editableInfo) OutlinedTextField(block.name, { update(currentBlock.copy(name = it)) }, modifier = Modifier.weight(2.6f).testTag(nameFieldTag).finishEditingOnOutsideTouch(), singleLine = true)
                        else Text(block.name.ifBlank { "—" }, Modifier.weight(2.6f).testTag("product-name").semantics { disabled() })
                        if (editableInfo) OutlinedTextField(block.description, { update(currentBlock.copy(description = it)) }, modifier = Modifier.weight(2f).finishEditingOnOutsideTouch())
                        else Text(block.description.ifBlank { "—" }, Modifier.weight(2f))
                        if (!statusOnNextRow) Box(Modifier.weight(1.8f)) {
                            if (editableInfo) StatusPicker(block.status) { update(currentBlock.copy(status = it)) }
                            else StatusBadge(block.status)
                        }
                        Spacer(Modifier.width(48.dp))
                    }
                    }
                }
                if (canEdit) IconButton(onClick = { onEdit("info") },
                    modifier = Modifier.align(Alignment.TopEnd).testTag("edit-product")
                        .semantics { contentDescription = "Редагувати" }) { EditActionGlyph("pen") }
            }
            }
    }
    if (showAdditions) ProductAdditions(onAdd = onAdd)
    error?.let { Text(it, color = Red) }
}

@Composable
private fun PhotoAddTile(modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.testTag("product-photo-camera").clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp), color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("+", color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.displaySmall)
        }
    }
}

@Composable
private fun PhotoSourceMenu(expanded: Boolean, onDismiss: () -> Unit,
    onGallery: () -> Unit, onCamera: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Вибрати з галереї") }, onClick = onGallery,
            modifier = Modifier.testTag("choose-photo-gallery"))
        DropdownMenuItem(text = { Text("Сфотографувати") }, onClick = onCamera,
            modifier = Modifier.testTag("choose-photo-camera"))
    }
}

@Composable
private fun PhotoPreview(path: String, height: androidx.compose.ui.unit.Dp = 260.dp) {
    PhotoImage(path, Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(CanvasColor), maxDimension = 800)
}

private object PhotoBitmapCache {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    @Synchronized fun get(key: String): android.graphics.Bitmap? = cache.get(key)
    @Synchronized fun put(key: String, value: android.graphics.Bitmap) { cache.put(key, value) }
}

@Composable
internal fun PhotoImage(path: String, modifier: Modifier, maxDimension: Int = 1600, placeholder: (@Composable () -> Unit)? = null) {
    var bitmap by remember(path, maxDimension) {mutableStateOf<android.graphics.Bitmap?>(null)}
    var failed by remember(path, maxDimension) {mutableStateOf(false)}
    LaunchedEffect(path, maxDimension) {
        val key = "$path:$maxDimension:${File(path).lastModified()}"
        bitmap=PhotoBitmapCache.get(key) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(path,bounds)
                var sample=1
                while(bounds.outWidth/sample>maxDimension || bounds.outHeight/sample>maxDimension) sample*=2
                BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
            }.getOrNull()
        }?.also { PhotoBitmapCache.put(key, it) }
        failed=bitmap==null
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it.asImageBitmap(),contentDescription="Фото об'єкта",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
            ?: if (placeholder != null) placeholder() else Text(if(failed) "Не вдалося відкрити фото" else "Завантаження фото…",color=Muted)
    }
}

@Composable
private fun PhotoViewer(photo: PhotoEntry, onClose: () -> Unit, onReplace: (String) -> Unit, onDelete: () -> Unit, editable: Boolean = true) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val replace by rememberUpdatedState(onReplace)
    var pendingPath by rememberSaveable {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(pendingPath!=null)}
    var error by remember {mutableStateOf<String?>(null)}
    var chooseSource by remember {mutableStateOf(false)}
    fun acceptReplacement(path: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeFile(path,bounds)
                    check(bounds.outWidth>0 && bounds.outHeight>0)
                }
                replace(path)
                error=null
            } catch(_: Exception) {
                withContext(Dispatchers.IO) { File(path).delete() }
                error="Не вдалося замінити фото. Спробуйте ще раз."
            } finally { busy=false }
        }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPath?.let {path ->
            if(success) acceptReplacement(path)
            else { scope.launch(Dispatchers.IO) { File(path).delete() }; busy=false }
        }
        pendingPath=null
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) {
            busy=true
            error=null
            scope.launch {
                var copiedPath: String? = null
                var committed=false
                try {
                    val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg")
                    copiedPath=file.absolutePath
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        file.parentFile?.mkdirs()
                        try {
                            context.contentResolver.openInputStream(uri)?.use {input ->
                                file.outputStream().use {output -> input.copyTo(output)}
                            } ?: error("Фото недоступне")
                            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                            BitmapFactory.decodeFile(file.absolutePath,bounds)
                            check(bounds.outWidth>0 && bounds.outHeight>0)
                            file.absolutePath
                        } catch(e: Exception) {file.delete();throw e}
                    }
                    replace(checkNotNull(copiedPath))
                    committed=true
                } catch(e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch(_: Exception) {
                    error="Не вдалося замінити фото. Спробуйте ще раз."
                } finally {
                    if(!committed) copiedPath?.let {File(it).delete()}
                    busy=false
                }
            }
        }
    }
    Dialog(onDismissRequest={if(!busy) onClose()}, properties=DialogProperties(
        usePlatformDefaultWidth=false, decorFitsSystemWindows=false,
        dismissOnBackPress=!busy, dismissOnClickOutside=false
    )) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            PhotoImage(photo.path, Modifier.fillMaxSize(), maxDimension=2048)
            IconButton(onClick=onClose, enabled=!busy,
                modifier=Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha=.7f),CircleShape)
                    .semantics {contentDescription="Закрити фото"}) {
                Canvas(Modifier.size(22.dp)) {
                    drawLine(Color.White,Offset(size.width*.2f,size.height*.2f),Offset(size.width*.8f,size.height*.8f),3.dp.toPx(),StrokeCap.Round)
                    drawLine(Color.White,Offset(size.width*.8f,size.height*.2f),Offset(size.width*.2f,size.height*.8f),3.dp.toPx(),StrokeCap.Round)
                }
            }
            if (editable) Row(Modifier.align(Alignment.TopEnd).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box {
                    IconButton(onClick={chooseSource=true},enabled=!busy,
                        modifier=Modifier.background(Color.Black.copy(alpha=.7f),CircleShape).semantics {contentDescription="Змінити фото"}) {
                        PhotoActionGlyph(delete=false)
                    }
                    DropdownMenu(chooseSource,{chooseSource=false}) {
                        DropdownMenuItem(text={Text("Вибрати з галереї")},onClick={chooseSource=false;gallery.launch("image/*")})
                        DropdownMenuItem(text={Text("Сфотографувати")},onClick={
                            chooseSource=false
                            try {
                                val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg").also {it.parentFile?.mkdirs();it.createNewFile()}
                                pendingPath=file.absolutePath
                                busy=true
                                camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.photos",file))
                            } catch(_: Exception) {
                                pendingPath?.let {File(it).delete()};pendingPath=null;busy=false
                                error="Камера недоступна. Виберіть фото з галереї."
                            }
                        })
                    }
                }
                IconButton(onClick={
                    try {onDelete()} catch(_: Exception) {error="Не вдалося видалити фото. Спробуйте ще раз."}
                },enabled=!busy,modifier=Modifier.background(Color.Black.copy(alpha=.7f),CircleShape)
                    .semantics {contentDescription="Видалити фото"}) {
                    PhotoActionGlyph(delete=true)
                }
            }
            if(busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            error?.let {Text(it,color=Red,modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .background(Color.Black.copy(alpha=.8f),RoundedCornerShape(12.dp)).padding(12.dp))}
        }
    }
}

@Composable
private fun PhotoActionGlyph(delete: Boolean) {
    if (!delete) {
        EditActionGlyph("pen")
        return
    }
    Canvas(Modifier.size(24.dp)) {
        val w=size.width
        val h=size.height
        val stroke=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round)
        drawLine(Color.White,Offset(w*.18f,h*.25f),Offset(w*.82f,h*.25f),stroke.width,StrokeCap.Round)
        drawLine(Color.White,Offset(w*.38f,h*.12f),Offset(w*.62f,h*.12f),stroke.width,StrokeCap.Round)
        val outline=Path().apply {moveTo(w*.28f,h*.32f);lineTo(w*.32f,h*.85f);lineTo(w*.68f,h*.85f);lineTo(w*.72f,h*.32f)}
        drawPath(outline,Color.White,style=stroke)
        drawLine(Color.White,Offset(w*.43f,h*.42f),Offset(w*.44f,h*.73f),stroke.width,StrokeCap.Round)
        drawLine(Color.White,Offset(w*.57f,h*.42f),Offset(w*.56f,h*.73f),stroke.width,StrokeCap.Round)
    }
}
