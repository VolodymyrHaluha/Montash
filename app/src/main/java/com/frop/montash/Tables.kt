package com.frop.montash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
private fun TableLayout(headers: List<Pair<String, Float>>, modifier: Modifier, rows: LazyListScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val tableWidth = maxWidth.coerceAtLeast(820.dp)
        Column(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())) {
            Row(Modifier.width(tableWidth).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                headers.forEach { (title, weight) -> Text(title, Modifier.weight(weight).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold) }
            }
            LazyColumn(Modifier.width(tableWidth).weight(1f), content = rows)
        }
    }
}
@Composable
internal fun StatusBadge(status: WorkStatus) {
    val color = when (status) { WorkStatus.DRAFT -> Color(0xFFC0D1C9); WorkStatus.IN_PROGRESS -> Color(0xFFFFD180); WorkStatus.DONE -> Color(0xFF60D5B6) }
    Text(status.label, color = color, style = MaterialTheme.typography.labelLarge)
}
@Composable
internal fun StatusPicker(status: WorkStatus, onChange: (WorkStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("product-status")) { Text("Статус: ${status.label}") }
        DropdownMenu(expanded, { expanded = false }) {
            WorkStatus.entries.forEach { value ->
                DropdownMenuItem(text = { Text(value.label) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}
@Composable
internal fun ObjectsTable(projects: List<Project>, onOpen: (Project) -> Unit, modifier: Modifier = Modifier) {
    TableLayout(listOf("Назва" to 2.2f, "Опис" to 3.5f, "К-сть виробів" to 1.3f, "Статус" to 1.8f), modifier.testTag("objects-table")) {
        if (projects.isEmpty()) item { Text("Об'єктів поки немає", Modifier.padding(20.dp)) }
        items(projects, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(p) }.testTag("object-${p.id}").padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayName, Modifier.weight(2.2f).padding(8.dp), fontWeight = FontWeight.SemiBold)
                Text(p.description.ifBlank { "—" }, Modifier.weight(3.5f).padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(p.blocks.size.toString(), Modifier.weight(1.3f).padding(8.dp))
                Box(Modifier.weight(1.8f).padding(8.dp)) { StatusBadge(p.status) }
            }
            HorizontalDivider()
        }
    }
}
@Composable
internal fun ProductsTable(projects: List<Project>, onOpen: (Project, WorkBlock) -> Unit, modifier: Modifier = Modifier) {
    val products = projects.flatMap { p -> p.blocks.map { p to it } }
    TableLayout(listOf("Фото виробу" to 1.5f, "Назва" to 2.4f, "Опис" to 3.5f, "Статус" to 1.8f), modifier.testTag("object-products")) {
        if (products.isEmpty()) item { Text("Виробів поки немає", Modifier.padding(20.dp)) }
        items(products, key = { "${it.first.id}/${it.second.id}" }) { (p, b) ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(p, b) }.testTag("product-${b.id}").padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.5f).padding(8.dp)) { ProductThumbnail(b, Modifier.fillMaxWidth().height(80.dp)) }
                Column(Modifier.weight(2.4f).padding(8.dp)) {
                    Text(b.name.ifBlank { "Виріб без назви" }, fontWeight = FontWeight.SemiBold)
                    if (projects.size > 1) Text(p.displayName, style = MaterialTheme.typography.bodySmall)
                }
                Text(b.description.ifBlank { "—" }, Modifier.weight(3.5f).padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1.8f).padding(8.dp)) { StatusBadge(b.status) }
            }
            HorizontalDivider()
        }
    }
}
@Composable
private fun ProductThumbnail(block: WorkBlock, modifier: Modifier) {
    val path = block.photos.firstOrNull()?.path ?: block.media.firstOrNull { it.mimeType.startsWith("image/") }?.path
    if (path == null) ProductPlaceholder(modifier)
    else PhotoImage(path, modifier, placeholder = { ProductPlaceholder(modifier) })
}

@Composable
internal fun ObjectEditDialog(project: Project, onClose: () -> Unit, onSave: (Project) -> Unit) {
    var name by remember(project.id) { mutableStateOf(project.displayName) }
    var description by remember(project.id) { mutableStateOf(project.description) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Редагування об'єкта") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Назва") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Опис") }, minLines = 3)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(project.copy(name = name.trim(), description = description)) }) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Скасувати") } })
}
