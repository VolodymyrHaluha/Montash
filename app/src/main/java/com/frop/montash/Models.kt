package com.frop.montash

import java.util.UUID

enum class WorkStatus(val label: String) {
    DRAFT("Чернетка"), IN_PROGRESS("В процесі"), DONE("Виконано");
    companion object {
        fun fromStored(value: String): WorkStatus = entries.firstOrNull { it.name == value || it.label == value } ?: DRAFT
    }
}
data class PhotoEntry(val path: String, val id: String = UUID.randomUUID().toString())
data class MediaEntry(val path: String, val name: String, val mimeType: String, val id: String = UUID.randomUUID().toString())
data class WorkBlock(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<PhotoEntry> = emptyList(),
    val name: String = "",
    val note: String = "",
    val description: String = "",
    val status: WorkStatus = WorkStatus.DRAFT,
    val media: List<MediaEntry> = emptyList(),
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

enum class Screen { HOME, OBJECTS, PRODUCTS, OBJECT_DETAIL }

internal fun isSupportedMediaType(mime: String): Boolean =
    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
