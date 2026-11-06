package com.frop.montash.ui.components

import com.frop.montash.*
import com.frop.montash.media.PhotoBatchPicker
import com.frop.montash.media.PhotoBatchSource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.UUID

private enum class SortDirection { DESCENDING, ASCENDING }

@Composable
private fun CompactSortBar(labels: List<String>, sortColumn: Int?, sortDirection: SortDirection?,
    onSort: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Сортування:", style = MaterialTheme.typography.labelLarge)
        labels.forEachIndexed { index, label ->
            val direction = if (sortColumn == index) sortDirection else null
            FilterChip(selected = sortColumn == index, onClick = { onSort(index) },
                modifier = Modifier.semantics { contentDescription = "$label: " + when (direction) {
                    SortDirection.DESCENDING -> "від найбільшого до найменшого"
                    SortDirection.ASCENDING -> "від найменшого до найбільшого"
                    null -> "без сортування"
                } },
                label = { Text(label, maxLines = 1) })
        }
    }
}

@Composable
private fun TableLayout(headers: List<Pair<String, Float>>, modifier: Modifier,
    sortColumn: Int?, sortDirection: SortDirection?, onSort: (Int) -> Unit,
    rows: LazyListScope.() -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
            headers.forEachIndexed { index, (title, weight) ->
                val direction = if (sortColumn == index) sortDirection else null
                Row(Modifier.weight(weight).clickable { onSort(index) }
                    .semantics { contentDescription = "$title: " + when (direction) {
                        SortDirection.DESCENDING -> "від найбільшого до найменшого"
                        SortDirection.ASCENDING -> "від найменшого до найбільшого"
                        null -> "без сортування"
                    } }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    if (direction != null) {
                        Spacer(Modifier.width(5.dp))
                        val color = MaterialTheme.colorScheme.primary
                        Canvas(Modifier.size(12.dp)) {
                            val top = if (direction == SortDirection.ASCENDING) .7f else .3f
                            val tip = if (direction == SortDirection.ASCENDING) .3f else .7f
                            drawLine(color, Offset(size.width * .15f, size.height * top),
                                Offset(size.width * .5f, size.height * tip), 2.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(color, Offset(size.width * .5f, size.height * tip),
                                Offset(size.width * .85f, size.height * top), 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), content = rows)
    }
}

private data class StatusTagColors(val background: Color, val border: Color)
private fun WorkStatus.tagColors(): StatusTagColors = when (this) {
    WorkStatus.DRAFT -> StatusTagColors(Color(0xFF52625B), Color(0xFF91A69C))
    WorkStatus.IN_PROGRESS -> StatusTagColors(Color(0xFF694A19), Color(0xFFE0AC53))
    WorkStatus.DONE -> StatusTagColors(Color(0xFF1E6652), Color(0xFF60D5B6))
}

@Composable
internal fun StatusBadge(status: WorkStatus) {
    val colors = status.tagColors()
    Surface(shape = RoundedCornerShape(10.dp), color = colors.background, contentColor = Color.White,
        border = BorderStroke(1.dp, colors.border)) {
        Text(status.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun StatusPicker(status: WorkStatus, enabled: Boolean = true, fillWidth: Boolean = true,
    onChange: (WorkStatus) -> Unit) {
    val rowModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
    Row(rowModifier.height(44.dp).testTag("product-status"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkStatus.entries.forEach { value ->
            val selected = status == value
            val activeColors = value.tagColors()
            val itemModifier = if (fillWidth) Modifier.weight(1f) else Modifier.wrapContentWidth()
            Surface(itemModifier.fillMaxHeight().testTag("status-${value.name.lowercase()}")
                .clickable(enabled = enabled) { onChange(value) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) activeColors.background else Color(0xFF46514C),
                border = BorderStroke(1.dp, if (selected) activeColors.border else Color(0xFF75817B))) {
                Box(Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .padding(horizontal = if (fillWidth) 8.dp else 18.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center) {
                    Text(value.label, color = Color.White, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1)
                }
            }
        }
    }
}
@Composable
internal fun ObjectsTable(projects: List<Project>, onOpen: (Project) -> Unit,
    modifier: Modifier = Modifier, query: String = "", compactTable: Boolean = false) {
    val compact = isCompactLayout()
    var sortColumn by rememberSaveable { mutableStateOf<Int?>(null) }
    var sortDirection by rememberSaveable { mutableStateOf<SortDirection?>(null) }
    fun changeSort(index: Int) {
        if (sortColumn != index) { sortColumn = index; sortDirection = SortDirection.DESCENDING }
        else when (sortDirection) {
            SortDirection.DESCENDING -> sortDirection = SortDirection.ASCENDING
            SortDirection.ASCENDING -> { sortColumn = null; sortDirection = null }
            null -> sortDirection = SortDirection.DESCENDING
        }
    }
    val visibleProjects = remember(projects, query, sortColumn, sortDirection) {
        val filteredProjects = projects.filter { project ->
            query.isBlank() || listOf(project.displayName, project.customer, project.description).any { it.contains(query, ignoreCase = true) }
        }
        if (sortColumn == null) filteredProjects else {
            val comparator = when (sortColumn) {
                0 -> compareBy<Project> { it.displayName.lowercase() }
                1 -> compareBy { it.description.lowercase() }
                2 -> compareBy { it.blocks.size }
                else -> compareBy { it.status.ordinal }
            }
            filteredProjects.sortedWith(if (sortDirection == SortDirection.DESCENDING) comparator.reversed() else comparator)
        }
    }
    if (compact && !compactTable) {
        Column(modifier.testTag("objects-table")) {
            CompactSortBar(listOf("Назва", "Опис", "Кількість", "Статус"),
                sortColumn, sortDirection, ::changeSort)
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visibleProjects.isEmpty()) item { Text(if (query.isBlank()) "Об'єктів поки немає" else "За запитом нічого не знайдено", Modifier.padding(20.dp)) }
                items(visibleProjects, key = { it.id }) { p ->
                    Surface(Modifier.fillMaxWidth().clickable { onOpen(p) }
                        .testTag("object-${p.id}"), shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.displayName, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            if (p.description.isNotBlank()) Text(p.description)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Виробів: ${p.blocks.size}", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                StatusBadge(p.status)
                            }
                        }
                    }
                }
            }
        }
    } else {
        TableLayout(listOf("Назва" to 2.6f, "Опис" to 2f, "К-сть виробів" to 1.3f, "Статус" to 1.8f),
            modifier.testTag("objects-table"), sortColumn, sortDirection, ::changeSort) {
            if (visibleProjects.isEmpty()) item { Text(if (query.isBlank()) "Об'єктів поки немає" else "За запитом нічого не знайдено", Modifier.padding(20.dp)) }
            items(visibleProjects, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(p) }.testTag("object-${p.id}").padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.displayName, Modifier.weight(2.6f).padding(8.dp), fontWeight = FontWeight.SemiBold)
                    Text(p.description.ifBlank { "—" }, Modifier.weight(2f).padding(8.dp), softWrap = true)
                    Text(p.blocks.size.toString(), Modifier.weight(1.3f).padding(8.dp))
                    Box(Modifier.weight(1.8f).padding(8.dp)) { StatusBadge(p.status) }
                }
                HorizontalDivider()
            }
        }
    }
}
@Composable
internal fun ProductsTable(projects: List<Project>, onOpen: (Project, WorkBlock) -> Unit,
    modifier: Modifier = Modifier, query: String = "", compactTable: Boolean = false) {
    val compact = isCompactLayout()
    var sortColumn by rememberSaveable { mutableStateOf<Int?>(null) }
    var sortDirection by rememberSaveable { mutableStateOf<SortDirection?>(null) }
    fun changeSort(index: Int) {
        if (sortColumn != index) { sortColumn = index; sortDirection = SortDirection.DESCENDING }
        else when (sortDirection) {
            SortDirection.DESCENDING -> sortDirection = SortDirection.ASCENDING
            SortDirection.ASCENDING -> { sortColumn = null; sortDirection = null }
            null -> sortDirection = SortDirection.DESCENDING
        }
    }
    val products = remember(projects, query, sortColumn, sortDirection) {
        val filteredProducts = projects.flatMap { p -> p.blocks.map { p to it } }.filter { (project, block) ->
            query.isBlank() || listOf(block.name, block.description, block.note, project.displayName).any { it.contains(query, ignoreCase = true) }
        }
        if (sortColumn == null) filteredProducts else {
            val comparator = when (sortColumn) {
                0 -> compareBy<Pair<Project, WorkBlock>> { it.second.photos.size }
                1 -> compareBy { it.second.name.lowercase() }
                2 -> compareBy { it.second.description.lowercase() }
                else -> compareBy { it.second.status.ordinal }
            }
            filteredProducts.sortedWith(if (sortDirection == SortDirection.DESCENDING) comparator.reversed() else comparator)
        }
    }
    if (compact && !compactTable) {
        Column(modifier.testTag("object-products")) {
            CompactSortBar(listOf("Фото", "Назва", "Опис", "Статус"),
                sortColumn, sortDirection, ::changeSort)
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (products.isEmpty()) item { Text(if (query.isBlank()) "Виробів поки немає" else "За запитом нічого не знайдено", Modifier.padding(20.dp)) }
                items(products, key = { "${it.first.id}/${it.second.id}" }) { (p, b) ->
                    Surface(Modifier.fillMaxWidth().clickable { onOpen(p, b) }
                        .testTag("product-${b.id}"), shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProductThumbnail(b, Modifier.fillMaxWidth().height(150.dp))
                            Text(b.name.ifBlank { "Виріб без назви" }, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            if (projects.size > 1) Text(p.displayName, style = MaterialTheme.typography.bodySmall)
                            if (b.description.isNotBlank()) Text(b.description)
                            StatusBadge(b.status)
                        }
                    }
                }
            }
        }
    } else {
        TableLayout(listOf("Фото виробу" to 1.5f, "Назва" to 2.6f, "Опис" to 2f, "Статус" to 1.8f),
            modifier.testTag("object-products"), sortColumn, sortDirection, ::changeSort) {
            if (products.isEmpty()) item { Text(if (query.isBlank()) "Виробів поки немає" else "За запитом нічого не знайдено", Modifier.padding(20.dp)) }
            items(products, key = { "${it.first.id}/${it.second.id}" }) { (p, b) ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(p, b) }.testTag("product-${b.id}").padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1.5f).padding(8.dp)) { ProductThumbnail(b, Modifier.fillMaxWidth().height(80.dp)) }
                    Column(Modifier.weight(2.6f).padding(8.dp)) {
                        Text(b.name.ifBlank { "Виріб без назви" }, fontWeight = FontWeight.SemiBold)
                        if (projects.size > 1) Text(p.displayName, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(b.description.ifBlank { "—" }, Modifier.weight(2f).padding(8.dp), softWrap = true)
                    Box(Modifier.weight(1.8f).padding(8.dp)) { StatusBadge(b.status) }
                }
                HorizontalDivider()
            }
        }
    }
}
@Composable
private fun ProductThumbnail(block: WorkBlock, modifier: Modifier) {
    val path = block.photos.firstOrNull()?.path ?: block.media.firstOrNull { it.mimeType.startsWith("image/") }?.path
    if (path == null) ProductPlaceholder(modifier)
    else PhotoImage(path, modifier, maxDimension = 320, placeholder = { ProductPlaceholder(modifier) })
}

@Composable
internal fun ObjectEditDialog(project: Project, onClose: () -> Unit, onDelete: () -> Unit,
    onSave: (Project) -> Unit) {
    val compact = isCompactLayout()
    var draft by remember(project.id) { mutableStateOf(project.copy()) }
    var sortColumn by rememberSaveable(project.id) { mutableStateOf<Int?>(null) }
    var sortDirection by rememberSaveable(project.id) { mutableStateOf<SortDirection?>(null) }
    var photoMenuFor by remember { mutableStateOf<String?>(null) }
    var photoTarget by remember { mutableStateOf<String?>(null) }
    var photoSource by remember { mutableStateOf<PhotoBatchSource?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val originalPhotoPaths = remember(project.id) {
        project.blocks.flatMap { block -> block.photos.map { it.path } }.toSet()
    }

    fun changeSort(index: Int) {
        if (sortColumn != index) {
            sortColumn = index
            sortDirection = SortDirection.DESCENDING
        } else when (sortDirection) {
            SortDirection.DESCENDING -> sortDirection = SortDirection.ASCENDING
            SortDirection.ASCENDING -> { sortColumn = null; sortDirection = null }
            null -> sortDirection = SortDirection.DESCENDING
        }
    }
    fun saveChanges() {
        val name = draft.displayName.trim()
        val recordedBlocks = draft.blocks.map { current ->
            current.withRecordedChanges(project.blocks.firstOrNull { it.id == current.id })
        }
        onSave(draft.copy(name = name, customer = name, blocks = recordedBlocks))
    }

    fun updateProduct(id: String, transform: (WorkBlock) -> WorkBlock) {
        draft = draft.copy(blocks = draft.blocks.map { block ->
            if (block.id == id) transform(block) else block
        })
    }

    fun discardAndClose() {
        draft.blocks.flatMap { block -> block.photos.map { it.path } }
            .filterNot { it in originalPhotoPaths }
            .forEach { File(it).delete() }
        onClose()
    }

    val sortedProducts = remember(draft.blocks, sortColumn, sortDirection) {
        if (sortColumn == null) draft.blocks else {
            val comparator = when (sortColumn) {
                0 -> compareBy<WorkBlock> { it.photos.size }
                1 -> compareBy { it.name.lowercase() }
                2 -> compareBy { it.description.lowercase() }
                else -> compareBy { it.status.ordinal }
            }
            draft.blocks.sortedWith(
                if (sortDirection == SortDirection.DESCENDING) comparator.reversed() else comparator
            )
        }
    }

    photoSource?.let { source ->
        PhotoBatchPicker(source = source, onClose = {
            photoSource = null
            photoTarget = null
        }, onFinished = { paths ->
            val target = photoTarget
            if (target != null && paths.isNotEmpty()) updateProduct(target) { block ->
                val first = block.photos.firstOrNull()
                val batchId = first?.batchId ?: UUID.randomUUID().toString()
                val replacement = if (first == null) {
                    photoBatchEntries(paths, batchId)
                } else {
                    listOf(first.copy(path = paths.first(), createdAt = System.currentTimeMillis())) +
                        block.photos.drop(1) + photoBatchEntries(paths.drop(1), batchId)
                }
                block.copy(photos = replacement)
            }
            photoSource = null
            photoTarget = null
        }, onBusyChange = { photoBusy = it })
    }

    Dialog(onDismissRequest = { if (!photoBusy) discardAndClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !photoBusy,
        dismissOnClickOutside = false
    )) {
        SurfaceCard(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)
            .testTag("object-edit-dialog")) {
            if (compact) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Редагування об'єкта", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { confirmDelete = true }, enabled = !photoBusy,
                            modifier = Modifier.testTag("delete-object")) {
                            Text("Видалити об’єкт", color = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = ::discardAndClose, enabled = !photoBusy,
                            modifier = Modifier.semantics { contentDescription = "Скасувати" }) {
                            EditActionGlyph("xmark")
                        }
                        IconButton(enabled = draft.displayName.isNotBlank() && !photoBusy,
                            onClick = ::saveChanges, modifier = Modifier.testTag("save-object-editor")
                                .semantics { contentDescription = "Зберегти" }) {
                            EditActionGlyph("check")
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Редагування об'єкта", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }, enabled = !photoBusy,
                        modifier = Modifier.testTag("delete-object")) {
                        Text("Видалити об’єкт", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = ::discardAndClose, enabled = !photoBusy,
                        modifier = Modifier.semantics { contentDescription = "Скасувати" }) {
                        EditActionGlyph("xmark")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(enabled = draft.displayName.isNotBlank() && !photoBusy,
                        onClick = ::saveChanges, modifier = Modifier.testTag("save-object-editor")
                            .semantics { contentDescription = "Зберегти" }) {
                        EditActionGlyph("check")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(draft.displayName, { value ->
                    draft = draft.copy(name = value, customer = value)
                }, label = { Text("Назва об'єкта") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("object-name-editor"))
                OutlinedTextField(draft.description, { value -> draft = draft.copy(description = value) },
                    label = { Text("Опис об'єкта") }, minLines = 2, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("object-description-editor"))
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(draft.displayName, { value ->
                    draft = draft.copy(name = value, customer = value)
                }, label = { Text("Назва об'єкта") }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("object-name-editor"))
                OutlinedTextField(draft.description, { value -> draft = draft.copy(description = value) },
                    label = { Text("Опис об'єкта") }, minLines = 2, maxLines = 3,
                    modifier = Modifier.weight(1.5f).testTag("object-description-editor"))
            }
            Spacer(Modifier.height(16.dp))
            Text("Вироби", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (compact) Column(Modifier.fillMaxWidth().weight(1f)
                .testTag("object-products-editor")) {
                CompactSortBar(listOf("Фото", "Назва", "Опис", "Статус"),
                    sortColumn, sortDirection, ::changeSort)
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sortedProducts.isEmpty()) item {
                        Text("Виробів поки немає", Modifier.padding(20.dp))
                    }
                    items(sortedProducts, key = { it.id }) { product ->
                        Surface(Modifier.fillMaxWidth().testTag("object-editor-product-${product.id}"),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.fillMaxWidth()) {
                                    Surface(Modifier.fillMaxWidth().height(140.dp).clickable {
                                        photoMenuFor = product.id
                                    }.testTag("object-product-photo-${product.id}"),
                                        shape = RoundedCornerShape(10.dp), color = Color.Transparent,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                        ProductThumbnail(product, Modifier.fillMaxSize())
                                    }
                                    DropdownMenu(expanded = photoMenuFor == product.id,
                                        onDismissRequest = { photoMenuFor = null }) {
                                        DropdownMenuItem(text = { Text("Вибрати з галереї") }, onClick = {
                                            photoMenuFor = null
                                            photoTarget = product.id
                                            photoSource = PhotoBatchSource.GALLERY
                                        })
                                        DropdownMenuItem(text = { Text("Сфотографувати") }, onClick = {
                                            photoMenuFor = null
                                            photoTarget = product.id
                                            photoSource = PhotoBatchSource.CAMERA
                                        })
                                    }
                                }
                                OutlinedTextField(product.name, { value ->
                                    updateProduct(product.id) { it.copy(name = value) }
                                }, label = { Text("Назва") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                        .testTag("object-product-name-${product.id}"))
                                OutlinedTextField(product.description, { value ->
                                    updateProduct(product.id) { it.copy(description = value) }
                                }, label = { Text("Опис") }, modifier = Modifier.fillMaxWidth()
                                    .testTag("object-product-description-${product.id}"),
                                    minLines = 2, maxLines = 4)
                                Text("Статус", fontWeight = FontWeight.Bold)
                                Box(Modifier.fillMaxWidth().testTag("object-product-status-${product.id}")) {
                                    StatusPicker(product.status) { status ->
                                        updateProduct(product.id) { it.copy(status = status) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else TableLayout(listOf("Фото виробу" to 1.5f, "Назва" to 2.6f,
                "Опис" to 2f, "Статус" to 1.8f), Modifier.fillMaxWidth().weight(1f)
                .testTag("object-products-editor"), sortColumn, sortDirection, ::changeSort) {
                if (sortedProducts.isEmpty()) item {
                    Text("Виробів поки немає", Modifier.padding(20.dp))
                }
                items(sortedProducts, key = { it.id }) { product ->
                    Row(Modifier.fillMaxWidth().testTag("object-editor-product-${product.id}")
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1.5f).padding(8.dp)) {
                            Surface(Modifier.fillMaxWidth().height(80.dp).clickable {
                                photoMenuFor = product.id
                            }.testTag("object-product-photo-${product.id}"),
                                shape = RoundedCornerShape(10.dp), color = Color.Transparent,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                ProductThumbnail(product, Modifier.fillMaxSize())
                            }
                            DropdownMenu(expanded = photoMenuFor == product.id,
                                onDismissRequest = { photoMenuFor = null }) {
                                DropdownMenuItem(text = { Text("Вибрати з галереї") }, onClick = {
                                    photoMenuFor = null
                                    photoTarget = product.id
                                    photoSource = PhotoBatchSource.GALLERY
                                })
                                DropdownMenuItem(text = { Text("Сфотографувати") }, onClick = {
                                    photoMenuFor = null
                                    photoTarget = product.id
                                    photoSource = PhotoBatchSource.CAMERA
                                })
                            }
                        }
                        OutlinedTextField(product.name, { value ->
                            updateProduct(product.id) { it.copy(name = value) }
                        }, singleLine = true, modifier = Modifier.weight(2.6f).padding(8.dp)
                            .testTag("object-product-name-${product.id}"))
                        OutlinedTextField(product.description, { value ->
                            updateProduct(product.id) { it.copy(description = value) }
                        }, modifier = Modifier.weight(2f).padding(8.dp)
                            .testTag("object-product-description-${product.id}"), minLines = 1, maxLines = 3)
                        Box(Modifier.weight(1.8f).padding(8.dp)
                            .testTag("object-product-status-${product.id}")) {
                            StatusPicker(product.status) { status ->
                                updateProduct(product.id) { it.copy(status = status) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Видалити об’єкт?") },
        text = { Text("Об’єкт і всі вироби та файли, які входять до нього, буде видалено безповоротно.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                draft.blocks.flatMap { block -> block.photos.map { it.path } }
                    .filterNot { it in originalPhotoPaths }
                    .forEach { File(it).delete() }
                onDelete()
            }, modifier = Modifier.testTag("confirm-delete-object")) {
                Text("Видалити", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") }
        })
}
