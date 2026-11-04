package com.frop.montash

import java.util.UUID
import java.io.File

enum class WorkStatus(val label: String) {
    DRAFT("Чернетка"), IN_PROGRESS("В процесі"), DONE("Виконано");
    companion object {
        fun fromStored(value: String): WorkStatus = entries.firstOrNull { it.name == value || it.label == value } ?: DRAFT
    }
}
data class PhotoEntry(val path: String, val id: String = UUID.randomUUID().toString(), val createdAt: Long = 0L,
    val batchId: String? = null)
internal fun photoBatchEntries(paths: List<String>, batchId: String = UUID.randomUUID().toString(),
    createdAt: Long = System.currentTimeMillis()): List<PhotoEntry> =
    paths.map { path -> PhotoEntry(path, createdAt = createdAt, batchId = batchId) }
data class MediaEntry(val path: String, val name: String, val mimeType: String, val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = 0L, val batchId: String? = null, val storageSection: String? = null)
data class TextEntry(val text: String, val id: String = UUID.randomUUID().toString(), val createdAt: Long = 0L,
    val batchId: String? = null)
data class ProductChange(val section: String, val description: String, val timestamp: Long = System.currentTimeMillis())
data class WorkBlock(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<PhotoEntry> = emptyList(),
    val name: String = "",
    val note: String = "",
    val description: String = "",
    val status: WorkStatus = WorkStatus.DRAFT,
    val media: List<MediaEntry> = emptyList(),
    val texts: List<TextEntry> = emptyList(),
    val history: List<ProductChange> = emptyList(),
    // Retain old attachment metadata without exposing the removed PDF feature.
    val legacyDrawingsJson: String? = null
)
data class Project(
    val name: String, val customer: String,
    val id: String = UUID.randomUUID().toString(),
    val blocks: List<WorkBlock> = emptyList(),
    val customerId: String? = null,
    val description: String = ""
) {
    val displayName: String get() = name.ifBlank { customer }
    val status: WorkStatus get() = aggregateStatus(blocks)
}
// Ties prefer the less completed state, so an equal split never implies completion.
internal fun aggregateStatus(blocks: List<WorkBlock>): WorkStatus =
    WorkStatus.entries.maxByOrNull { status -> blocks.count { it.status == status } } ?: WorkStatus.DRAFT

enum class Screen { OBJECTS, PRODUCTS, OBJECT_DETAIL }

internal fun isSupportedMediaType(mime: String): Boolean =
    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

internal fun MediaEntry.contentSection(): String = when {
    storageSection == "documents" -> "documents"
    mimeType.startsWith("audio/") -> "audio"
    mimeType.startsWith("image/") || mimeType.startsWith("video/") -> "media"
    else -> "documents"
}

data class ProductContentGroup(val section: String, val updatedAt: Long, val batchId: String? = null)

internal fun WorkBlock.contentGroups(): List<ProductContentGroup> {
    val sections = listOf("photos", "media", "audio", "text", "documents")
    return sections.flatMap { section ->
        val entries: List<Pair<String?, Long>> = when (section) {
            "photos" -> photos.map { it.batchId to (it.createdAt.takeIf { time -> time > 0 } ?: File(it.path).lastModified()) }
            "text" -> texts.map { it.batchId to it.createdAt } + if (note.isNotBlank()) listOf(null to 0L) else emptyList()
            else -> media.filter { it.contentSection() == section }
                .map { it.batchId to (it.createdAt.takeIf { time -> time > 0 } ?: File(it.path).lastModified()) }
        }
        entries.groupBy { it.first }.map { (batchId, items) ->
            val latest = items.maxOf { it.second }
            ProductContentGroup(section,
                if (batchId == null) maxOf(latest, history.filter { it.section == section }.maxOfOrNull { it.timestamp } ?: 0L)
                else latest, batchId)
        }
    }.sortedByDescending { it.updatedAt }
}

internal fun WorkBlock.withRecordedChanges(previous: WorkBlock?): WorkBlock {
    val before = previous ?: WorkBlock(id = id)
    val changes = mutableListOf<ProductChange>()
    fun record(section: String, message: String) { changes += ProductChange(section, message) }
    if (name != before.name) record("info", "Змінено назву виробу")
    if (description != before.description) record("info", "Змінено опис виробу")
    if (status != before.status) record("info", "Статус: ${before.status.label} → ${status.label}")
    val oldPhotos = before.photos.associateBy { it.id }
    photos.filter { it.id !in oldPhotos }.forEach { record("photos", "Додано фото") }
    photos.filter { oldPhotos[it.id]?.path != null && oldPhotos[it.id]?.path != it.path }
        .forEach { record("photos", "Замінено фото") }
    before.photos.filter { old -> photos.none { it.id == old.id } }.forEach { record("photos", "Видалено фото") }
    val oldMedia = before.media.associateBy { it.id }
    media.filter { it.id !in oldMedia }.forEach { record(it.contentSection(), "Додано: ${it.name}") }
    before.media.filter { old -> media.none { it.id == old.id } }.forEach { old ->
        record(old.contentSection(), "Видалено: ${old.name}")
    }
    if (note != before.note) record("text", if (before.note.isBlank()) "Додано примітку" else "Змінено примітку")
    val oldTexts = before.texts.associateBy { it.id }
    texts.filter { it.id !in oldTexts }.forEach { record("text", "Додано текст") }
    texts.filter { oldTexts[it.id]?.text != null && oldTexts[it.id]?.text != it.text }
        .forEach { record("text", "Змінено текст") }
    before.texts.filter { old -> texts.none { it.id == old.id } }.forEach { record("text", "Видалено текст") }
    return copy(history = before.history + changes)
}
