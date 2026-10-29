package com.frop.montash

import com.frop.montash.R as MontashResources

import android.os.Bundle
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
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private val Ink = Color(0xFFF0F6F3)
private val Muted = Color(0xFFC0D1C9)
private val Teal = Color(0xFF60D5B6)
private val TealDark = Color(0xFFA4EBD6)
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
    var projects by remember { mutableStateOf(store.load()) }
    val remote = remember { RemoteProjects(context) }
    val monitor = remember { ServerConnectionMonitor(context, remote) }
    var serverConnection by remember { mutableStateOf(ServerConnection.CHECKING) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val directoryStore = remember { LocalDirectoryStore(context) }
    var clients by remember { mutableStateOf(directoryStore.loadClients(projects)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun localError(error: Exception) {
        scope.launch { snackbar.showSnackbar(error.message ?: "Не вдалося зберегти дані на пристрої.", actionLabel="Закрити") }
    }
    LaunchedEffect(Unit) {
        if (remote.enabled && BuildConfig.MONTASH_LOCAL_API_URL.isNotBlank() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    LaunchedEffect(monitor) {
        val current = monitor.check()
        serverConnection = current
        if (current == ServerConnection.LOCAL || current == ServerConnection.GLOBAL) {
            try {
                val refreshed = remote.refresh(store.load(), current)
                store.save(refreshed)
                projects = refreshed
                remote.upload(store, current)
                remote.uploadExistingMedia(store, current)
            } catch (error: Exception) { localError(error) }
        }
    }
    fun sendProject(id: String) {
        if (remote.enabled) {
            try {
                remote.markPending(id)
                scope.launch {
                    try {
                        val current = monitor.check()
                        serverConnection = current
                        if (current == ServerConnection.LOCAL || current == ServerConnection.GLOBAL) {
                            remote.upload(store, current)
                        }
                    } catch (error: Exception) { localError(error) }
                }
            } catch (error: Exception) { localError(error) }
        }
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
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var objectReturnScreen by rememberSaveable { mutableStateOf(Screen.OBJECTS) }
    var photoSelection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    fun update(project: Project) {
        val updated = projects.map { if (it.id == project.id) project else it }
        store.save(updated)
        projects = updated
        sendProject(project.id)
    }
    fun open(project: Project) { selectedId = project.id; initialProductId = null; objectReturnScreen = Screen.OBJECTS; screen = Screen.OBJECT_DETAIL }
    fun openProduct(project: Project, product: WorkBlock) { selectedId = project.id; initialProductId = product.id; objectReturnScreen = Screen.PRODUCTS; screen = Screen.OBJECT_DETAIL }
    fun openPhoto(project: Project, block: WorkBlock, photo: PhotoEntry) {
        photoSelection = listOf(project.id, block.id, photo.id)
    }
    fun changePhoto(newPath: String?) {
        val selection = checkNotNull(photoSelection)
        val project = checkNotNull(projects.find { it.id == selection[0] })
        val block = checkNotNull(project.blocks.find { it.id == selection[1] })
        val oldPhoto = checkNotNull(block.photos.find { it.id == selection[2] })
        val photos = if (newPath == null) block.photos.filterNot { it.id == oldPhoto.id }
        else block.photos.map { if (it.id == oldPhoto.id) it.copy(path = newPath) else it }
        update(project.copy(blocks = project.blocks.map { if (it.id == block.id) it.copy(photos = photos) else it }))
        // Remove the old file only after the changed object has been saved.
        val stillUsed = projects.any { p -> p.blocks.any { b -> b.photos.any { it.path == oldPhoto.path } } }
        if (!stillUsed) runCatching { File(oldPhoto.path).delete() }
        if (newPath == null) photoSelection = null
    }
    BackHandler(enabled = screen == Screen.OBJECTS || screen == Screen.PRODUCTS) { screen = Screen.HOME }
    Box(Modifier.fillMaxSize().background(CanvasColor)) {
        Image(
            painter = painterResource(MontashResources.drawable.frop_logo_preview_01_1),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        Row(Modifier.fillMaxSize()) {
            NavigationRail(screen) { screen = it }
            AnimatedContent(targetState = screen, label = "screen", modifier = Modifier.weight(1f)) { current ->
                when (current) {
                    Screen.HOME -> HomeScreen(projects, serverConnection, ::open, { screen = Screen.OBJECTS })
                    Screen.OBJECTS -> ObjectsScreen(projects, clients, ::addClient, ::open) { project ->
                        val updated = projects + project
                        store.save(updated)
                        projects = updated
                        sendProject(project.id)
                        open(project)
                    }
                    Screen.PRODUCTS -> Column(Modifier.fillMaxSize().padding(28.dp, 22.dp)) {
                        PageHeader("Вироби", "Всього: ${projects.sumOf { it.blocks.size }}")
                        Spacer(Modifier.height(20.dp))
                        ProductsTable(projects, ::openProduct, Modifier.weight(1f).fillMaxWidth())
                    }
                    Screen.OBJECT_DETAIL -> projects.find { it.id == selectedId }?.let { project ->
                        key(project.id) {
                            ObjectDetail(project, { screen = objectReturnScreen }, { block, photo -> openPhoto(project, block, photo) }, ::update, initialProductId)
                        }
                    }
                }
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
                onReplace = { changePhoto(it) }, onDelete = { changePhoto(null) })
        }
    }
}

@Composable
private fun NavigationRail(active: Screen, onSelect: (Screen) -> Unit) {
    val items = listOf(Screen.HOME to "Головна", Screen.OBJECTS to "Об'єкти", Screen.PRODUCTS to "Вироби")
    Column(
        Modifier.width(116.dp).fillMaxHeight().background(Surface).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
}

@Composable
private fun NavGlyph(screen: Screen, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.minDimension
        when (screen) {
            Screen.HOME -> { drawRoundRect(color, Offset(s*.15f,s*.42f), Size(s*.7f,s*.48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); val p=Path().apply{moveTo(s*.1f,s*.46f);lineTo(s*.5f,s*.1f);lineTo(s*.9f,s*.46f)};drawPath(p,color,style=Stroke(2.5f,cap=StrokeCap.Round)) }
            Screen.PRODUCTS -> { drawRect(color, Offset(s*.2f,s*.1f), Size(s*.6f,s*.8f), style=Stroke(2.5f)); repeat(3) { drawLine(color, Offset(s*.32f,s*(.32f+it*.18f)), Offset(s*.68f,s*(.32f+it*.18f)), strokeWidth=2f) } }
            Screen.OBJECTS, Screen.OBJECT_DETAIL -> { drawRoundRect(color, Offset(s*.13f,s*.1f), Size(s*.74f,s*.8f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(2.5f)); repeat(2){r->repeat(2){c->drawRect(color,Offset(s*(.26f+c*.31f),s*(.27f+r*.29f)),Size(s*.16f,s*.14f))}} }

        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Spacer(Modifier.height(3.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
        action?.invoke()
    }
}

@Composable
private fun HomeScreen(projects: List<Project>, connection: ServerConnection, onProject: (Project) -> Unit, onObjects: () -> Unit) {
    var visibleProjectCount by rememberSaveable { mutableIntStateOf(5) }
    val visibleProjects = projects.take(visibleProjectCount)
    Column(Modifier.fillMaxSize().padding(28.dp, 22.dp, 28.dp, 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Text("Об’єктів: ${projects.size}", color=Muted, fontSize=13.sp, modifier=Modifier.weight(1f))
            Text(connection.label, color=if (connection == ServerConnection.LOCAL || connection == ServerConnection.GLOBAL) Teal else Muted,
                fontSize=12.sp, modifier=Modifier.testTag("server-connection"))
            Spacer(Modifier.width(14.dp))
            Surface(shape=RoundedCornerShape(13.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) {
                Text("Дані на пристрої", color=Muted, fontSize=12.sp, modifier=Modifier.padding(14.dp,9.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SurfaceCard(Modifier.fillMaxSize()) {
                SectionTitle("Об'єкти", "Всі об'єкти", onObjects)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (projects.isEmpty()) item {
                        Text("Додайте об’єкт у вкладці «Об’єкти»", color=Muted)
                    }
                    items(visibleProjects, key={it.id}) { project ->
                        ProjectRow(project) { onProject(project) }
                        if (project.id != visibleProjects.last().id) HorizontalDivider(color=Border)
                    }
                    if (visibleProjectCount < projects.size) item {
                        TextButton(
                            onClick={ visibleProjectCount = (visibleProjectCount + 5).coerceAtMost(projects.size) },
                            modifier=Modifier.fillMaxWidth()
                        ) { Text("Показати більше") }
                    }
                }
            }

        }
    }
}

@Composable private fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Surface(modifier, shape=RoundedCornerShape(20.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) { Column(Modifier.padding(18.dp), content=content) }

@Composable private fun SectionTitle(title:String, action:String, click:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f));Text(action,color=Teal,fontWeight=FontWeight.SemiBold,fontSize=12.sp,modifier=Modifier.clickable(onClick=click).padding(6.dp))}}

@Composable private fun ProjectRow(project: Project, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(project.displayName, fontWeight=FontWeight.Bold, fontSize=16.sp)
            Text("Виробів: ${project.blocks.size}", color=Muted, fontSize=12.sp)
        }
        Text(" ›", fontSize=25.sp, color=Muted)
    }
}

@Composable
private fun ObjectsScreen(projects: List<Project>, clients: List<Client>, onAddClient: (String) -> Client?, onProject: (Project) -> Unit, onCreate: (Project) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var objectName by rememberSaveable { mutableStateOf("") }
    var objectDescription by rememberSaveable { mutableStateOf("") }
    var customer by rememberSaveable { mutableStateOf("") }
    var chosenClientId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenClient = clients.find { it.id == chosenClientId }
    val matches = clients.filter { nameKey(it.name).contains(nameKey(customer)) }
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Об'єкти", "Всього: ${projects.size}") {
            Button(onClick={ objectName="";objectDescription="";customer="";chosenClientId=null;creating=true }) { Text("Створити об'єкт") }
        }
        Spacer(Modifier.height(20.dp))
        if (projects.isEmpty()) Text("Створіть перший об'єкт: вкажіть замовника.", color=Muted)
        ObjectsTable(projects, onProject, Modifier.weight(1f).fillMaxWidth())
    }
    if (creating) AlertDialog(
        modifier=Modifier.dismissInputOnOutsideTouch(),
        onDismissRequest={creating=false}, title={Text("Новий об'єкт")},
        text={ Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(objectName, { objectName=it }, label={Text("Назва об'єкта")}, singleLine=true)
            OutlinedTextField(objectDescription, { objectDescription=it }, label={Text("Опис об'єкта")}, minLines=2)
            OutlinedTextField(customer, {customer=it;chosenClientId=null}, label={Text("Замовник")}, singleLine=true, modifier=Modifier.finishEditingOnOutsideTouch())
            if(chosenClient!=null) Text("Обрано: ${chosenClient.name}",color=Teal)
            else {
                if(matches.isEmpty()) {
                    Text("Замовників не знайдено",color=Muted)
                    if(cleanName(customer).isNotBlank()) TextButton(onClick={
                        onAddClient(customer)?.let { client -> chosenClientId=client.id; customer=client.name }
                    }) {Text("Додати замовника")}
                }
                LazyColumn(Modifier.heightIn(max=220.dp)) {
                    items(matches,key={it.id}) {client ->
                        Text(client.name,modifier=Modifier.fillMaxWidth().clickable {
                            chosenClientId=client.id;customer=client.name
                        }.padding(vertical=12.dp))
                    }
                }
            }
        } },
        confirmButton={ TextButton(enabled=chosenClient!=null, onClick={
            chosenClient?.let {client -> onCreate(Project(name=objectName.trim().ifBlank { client.name },customer=client.name,customerId=client.id,description=objectDescription))}
            customer="";chosenClientId=null;creating=false
        }) {Text("Створити")} },
        dismissButton={TextButton(onClick={creating=false}) {Text("Скасувати")}}
    )
}

@Composable
internal fun ObjectDetail(project: Project, onBack: () -> Unit, onPhoto: (WorkBlock, PhotoEntry) -> Unit, onUpdate: (Project) -> Unit, initialProductId: String? = null) {
    var productId by rememberSaveable(project.id, initialProductId) { mutableStateOf(initialProductId) }
    var editingObject by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val product = project.blocks.find { it.id == productId }
    BackHandler { if (product != null) productId = null else onBack() }
    Column(Modifier.fillMaxSize().padding(28.dp, 20.dp)) {
        // Keep the object header and add action outside both scrolling views.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton { if (product != null) productId = null else onBack() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(project.displayName, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                Text("Виробів: ${project.blocks.size}", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = { editingObject = true }) { Text("Редагувати об’єкт") }
            Button(onClick = {
                val added = WorkBlock()
                try {
                    onUpdate(project.copy(blocks = project.blocks + added))
                    productId = added.id
                    error = null
                } catch (failure: Exception) { error = failure.message ?: "Не вдалося додати виріб." }
            }, modifier = Modifier.testTag("add-product")) { Text("Додати виріб") }
        }
        error?.let { Text(it, color = Red) }
        Spacer(Modifier.height(18.dp))
        if (product == null) {
            if (project.blocks.isEmpty()) Text("Додайте перший виріб до об'єкта.", color = Muted)
            ProductsTable(listOf(project), { _, block -> productId = block.id }, Modifier.weight(1f).fillMaxWidth())
        } else key(product.id) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                TextButton(onClick = { productId = null }) { Text("До списку виробів") }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("product-details"),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    ProductBlock(product, { photo -> onPhoto(product, photo) }) { updated ->
                        onUpdate(project.copy(blocks = project.blocks.map { if (it.id == updated.id) updated else it }))
                    }
                    ProductMedia(product) { updated ->
                        onUpdate(project.copy(blocks = project.blocks.map { if (it.id == updated.id) updated else it }))
                    }
                }
            }
        }
    }
    if (editingObject) ObjectEditDialog(project, { editingObject = false }) { updated ->
        try { onUpdate(updated); editingObject = false } catch (failure: Exception) { error = failure.message }
    }

}

@Composable
internal fun ProductPlaceholder(modifier: Modifier = Modifier) {
    Image(painterResource(MontashResources.drawable.icon), contentDescription = "Фото виробу відсутнє",
        contentScale = ContentScale.Fit, modifier = modifier.padding(24.dp))
}

@Composable
private fun ProductBlock(block: WorkBlock, onPhoto: (PhotoEntry) -> Unit, onUpdate: (WorkBlock) -> Unit) {
    val context=LocalContext.current
    val currentBlock by rememberUpdatedState(block)
    val update by rememberUpdatedState(onUpdate)
    var pendingPath by rememberSaveable {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var importing by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPath?.let {path ->
            if(success) update(currentBlock.copy(photos=currentBlock.photos+PhotoEntry(path))) else File(path).delete()
        }
        pendingPath=null
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if(uris.isNotEmpty()) {
            importing=true
            error=null
            scope.launch {
                try {
                    val imported=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            var target: File? = null
                            try {
                                val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg")
                                    .also {it.parentFile?.mkdirs()}
                                target=file
                                context.contentResolver.openInputStream(uri)?.use {input ->
                                    file.outputStream().use {output -> input.copyTo(output)}
                                } ?: error("Фото недоступне")
                                PhotoEntry(file.absolutePath)
                            } catch(_: Exception) {
                                target?.delete()
                                null
                            }
                        }
                    }
                    if(imported.isNotEmpty()) update(currentBlock.copy(photos=currentBlock.photos+imported))
                    if(imported.size<uris.size) error="Не вдалося додати ${uris.size-imported.size} фото. Спробуйте ще раз."
                } finally {
                    importing=false
                }
            }
        }
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(
                value=block.name,
                onValueChange={name -> update(currentBlock.copy(name=name))},
                label={Text("Назва виробу")},
                placeholder={Text("Наприклад, шафа")},
                singleLine=true,
                modifier=Modifier.weight(1f).testTag("product-name").finishEditingOnOutsideTouch()
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(enabled=!importing, onClick={gallery.launch("image/*")}) {Text(if(importing) "Додаємо фото…" else "Додати фото")}
            Spacer(Modifier.width(10.dp))
            Button(onClick={
                try {
                    val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg").also {it.parentFile?.mkdirs();it.createNewFile()}
                    pendingPath=file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.photos",file))
                } catch(_: Exception) {pendingPath?.let {File(it).delete()};pendingPath=null;error="Камера недоступна. Додайте фото з галереї."}
            }) {Text("Сфотографувати")}
        }
        Spacer(Modifier.height(12.dp))
        StatusPicker(block.status) { update(currentBlock.copy(status = it)) }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value=block.description,
            onValueChange={text -> update(currentBlock.copy(description=text))},
            label={Text("Опис")},
            placeholder={Text("Наприклад: розміри, матеріал, колір — у довільній формі")},
            modifier=Modifier.fillMaxWidth().finishEditingOnOutsideTouch(), minLines=3
        )
        error?.let {Text(it,color=Red)}
        Spacer(Modifier.height(12.dp))
        if(block.photos.isEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                repeat(3) {
                    Box(Modifier.weight(1f).height(200.dp).clip(RoundedCornerShape(14.dp))
                        .background(CanvasColor).clickable(enabled=!importing) { gallery.launch("image/*") },
                        contentAlignment=Alignment.Center) {
                        Text("Додати фото", color=Muted)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        block.photos.chunked(3).forEach { rowPhotos ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                rowPhotos.forEach {photo ->
                    key(photo.id) {
                        Box(Modifier.weight(1f).clickable {onPhoto(photo)}) {
                            PhotoPreview(photo.path,height=200.dp)
                        }
                    }
                }
                repeat(3-rowPhotos.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(18.dp))
        }
        OutlinedTextField(
            value=block.note,
            onValueChange={note -> update(currentBlock.copy(note=note))},
            label={Text("Примітка до виробу")},
            placeholder={Text("Примітка для всіх фото виробу")},
            modifier=Modifier.fillMaxWidth().finishEditingOnOutsideTouch(), minLines=3
        )
    }
}

@Composable
private fun PhotoPreview(path: String, height: androidx.compose.ui.unit.Dp = 260.dp) {
    PhotoImage(path, Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(CanvasColor))
}

@Composable
internal fun PhotoImage(path: String, modifier: Modifier, maxDimension: Int = 1600, placeholder: (@Composable () -> Unit)? = null) {
    var bitmap by remember(path) {mutableStateOf<android.graphics.Bitmap?>(null)}
    var failed by remember(path) {mutableStateOf(false)}
    LaunchedEffect(path, maxDimension) {
        bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(path,bounds)
                var sample=1
                while(bounds.outWidth/sample>maxDimension || bounds.outHeight/sample>maxDimension) sample*=2
                BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
            }.getOrNull()
        }
        failed=bitmap==null
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it.asImageBitmap(),contentDescription="Фото об'єкта",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
            ?: if (placeholder != null) placeholder() else Text(if(failed) "Не вдалося відкрити фото" else "Завантаження фото…",color=Muted)
    }
}

@Composable
private fun PhotoViewer(photo: PhotoEntry, onClose: () -> Unit, onReplace: (String) -> Unit, onDelete: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val replace by rememberUpdatedState(onReplace)
    var pendingPath by rememberSaveable {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(pendingPath!=null)}
    var error by remember {mutableStateOf<String?>(null)}
    var chooseSource by remember {mutableStateOf(false)}
    fun acceptReplacement(path: String) {
        try {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            check(bounds.outWidth>0 && bounds.outHeight>0)
            replace(path)
            error=null
        } catch(_: Exception) {
            File(path).delete()
            error="Не вдалося замінити фото. Спробуйте ще раз."
        }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPath?.let {path -> if(success) acceptReplacement(path) else File(path).delete()}
        pendingPath=null
        busy=false
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
            PhotoImage(photo.path, Modifier.fillMaxSize(), maxDimension=3200)
            IconButton(onClick=onClose, enabled=!busy,
                modifier=Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha=.7f),CircleShape)
                    .semantics {contentDescription="Закрити фото"}) {
                Canvas(Modifier.size(22.dp)) {
                    drawLine(Color.White,Offset(size.width*.2f,size.height*.2f),Offset(size.width*.8f,size.height*.8f),3.dp.toPx(),StrokeCap.Round)
                    drawLine(Color.White,Offset(size.width*.8f,size.height*.2f),Offset(size.width*.2f,size.height*.8f),3.dp.toPx(),StrokeCap.Round)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
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
    Canvas(Modifier.size(24.dp)) {
        val w=size.width
        val h=size.height
        val stroke=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round)
        if(delete) {
            drawLine(Color.White,Offset(w*.18f,h*.25f),Offset(w*.82f,h*.25f),stroke.width,StrokeCap.Round)
            drawLine(Color.White,Offset(w*.38f,h*.12f),Offset(w*.62f,h*.12f),stroke.width,StrokeCap.Round)
            val outline=Path().apply {moveTo(w*.28f,h*.32f);lineTo(w*.32f,h*.85f);lineTo(w*.68f,h*.85f);lineTo(w*.72f,h*.32f)}
            drawPath(outline,Color.White,style=stroke)
            drawLine(Color.White,Offset(w*.43f,h*.42f),Offset(w*.44f,h*.73f),stroke.width,StrokeCap.Round)
            drawLine(Color.White,Offset(w*.57f,h*.42f),Offset(w*.56f,h*.73f),stroke.width,StrokeCap.Round)
        } else {
            val pencil=Path().apply {
                moveTo(w*.2f,h*.8f);lineTo(w*.25f,h*.59f);lineTo(w*.68f,h*.16f)
                lineTo(w*.84f,h*.32f);lineTo(w*.41f,h*.75f);close()
            }
            drawPath(pencil,Color.White,style=stroke)
            drawLine(Color.White,Offset(w*.59f,h*.25f),Offset(w*.75f,h*.41f),stroke.width,StrokeCap.Round)
        }
    }
}

@Composable private fun BackButton(onClick:()->Unit){Surface(Modifier.size(42.dp).clickable(onClick=onClick),shape=RoundedCornerShape(12.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Box(contentAlignment=Alignment.Center){Text("‹",fontSize=28.sp,color=Ink)}}}
