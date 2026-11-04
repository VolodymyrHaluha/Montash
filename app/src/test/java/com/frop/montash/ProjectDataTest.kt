package com.frop.montash

import com.frop.montash.data.projectsFromJson
import com.frop.montash.data.projectsToJson

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProjectDataTest {
    @Test fun richTextHtmlTagsArePersistedUnchanged() {
        val html = "<h2>Заголовок</h2><p><strong>Жирний</strong> і <em>курсивний</em></p><ul><li>Пункт</li></ul>"
        val original = Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(texts = listOf(TextEntry(html, id = "rich-text", batchId = "text-batch")))
        ))

        val restored = projectsFromJson(projectsToJson(listOf(original))).single().blocks.single()

        assertEquals(html, restored.texts.single().text)
    }

    @Test fun anyMimeTypeCanRemainInDocumentsSectionAfterPersistence() {
        val attachment = MediaEntry("/private/reference.jpg", "reference.jpg", "image/jpeg",
            id = "document-image", createdAt = 99L, batchId = "document-batch",
            storageSection = "documents")
        val original = Project("Об'єкт", "Об'єкт", blocks = listOf(WorkBlock(media = listOf(attachment))))

        val restored = projectsFromJson(projectsToJson(listOf(original))).single().blocks.single()

        assertEquals("documents", restored.media.single().contentSection())
        assertEquals("documents", restored.media.single().storageSection)
        assertEquals(listOf("documents"), restored.contentGroups().map { it.section })
    }

    @Test fun capturedPhotosStayInOneBatch() {
        val entries = photoBatchEntries(listOf("one.jpg", "two.jpg", "three.jpg"),
            batchId = "capture-session", createdAt = 42L)

        assertEquals(3, entries.size)
        assertEquals(setOf("capture-session"), entries.map { it.batchId }.toSet())
        assertEquals(setOf(42L), entries.map { it.createdAt }.toSet())
    }
    @Test fun laterMediaAdditionCreatesItsOwnPersistedBlock() {
        val first = MediaEntry("/first.jpg", "Перше", "image/jpeg", "first", 1_000L, "batch-14")
        val second = MediaEntry("/second.mp4", "Друге", "video/mp4", "second", 2_000L, "batch-15")
        val block = WorkBlock(media = listOf(first, second))
        assertEquals(listOf("batch-15", "batch-14"), block.contentGroups().map { it.batchId })
        val restored = projectsFromJson(projectsToJson(listOf(Project("Об'єкт", "Клієнт", blocks = listOf(block)))))
            .single().blocks.single()
        assertEquals(block, restored)
        assertEquals(2, restored.contentGroups().size)
    }

    @Test fun contentGroupsUseNewestItemAndMoveEditedGroupToTop() {
        val block = WorkBlock(
            photos = listOf(PhotoEntry("/photo.jpg", "photo", 100L)),
            media = listOf(MediaEntry("/first.jpg", "Перше", "image/jpeg", "first", 200L),
                MediaEntry("/second.mp4", "Друге", "video/mp4", "second", 300L)),
            texts = listOf(TextEntry("Текст", "text", 150L))
        )
        assertEquals(listOf("media", "text", "photos"), block.contentGroups().map { it.section })
        assertEquals(300L, block.contentGroups().first().updatedAt)
        val edited = block.copy(history = listOf(ProductChange("photos", "Замінено фото", 400L)))
        assertEquals("photos", edited.contentGroups().first().section)
        assertEquals(400L, edited.contentGroups().first().updatedAt)
    }
    @Test fun additionHistorySurvivesSaveReload() {
        val original = WorkBlock(id = "product")
        val changed = original.copy(
            name = "Шафа",
            photos = listOf(PhotoEntry("/private/photo.jpg", "photo", 1_700_000_000_000L)),
            note = "Розміри уточнено",
            texts = listOf(TextEntry("Окремий запис", "text", 1_700_000_001_000L)),
            media = listOf(MediaEntry("/private/manual.pdf", "Інструкція.pdf", "application/pdf", "document", 1_700_000_002_000L))
        ).withRecordedChanges(original)
        assertEquals(setOf("info", "photos", "text", "documents"), changed.history.map { it.section }.toSet())
        val restored = projectsFromJson(projectsToJson(listOf(Project("Об'єкт", "Клієнт", blocks = listOf(changed)))))
            .single().blocks.single()
        assertEquals(changed.history, restored.history)
        assertEquals(changed.media, restored.media)
        assertEquals(changed.texts, restored.texts)
        assertEquals(changed.photos, restored.photos)
    }
    @Test fun projectStatusUsesMostCommonProductStatus() {
        val products = listOf(WorkStatus.DONE, WorkStatus.DONE, WorkStatus.DRAFT, WorkStatus.IN_PROGRESS, WorkStatus.DONE).map { WorkBlock(status = it) }
        assertEquals(WorkStatus.DONE, aggregateStatus(products))
        assertEquals(WorkStatus.IN_PROGRESS, aggregateStatus(listOf(WorkBlock(status = WorkStatus.IN_PROGRESS), WorkBlock(status = WorkStatus.IN_PROGRESS), WorkBlock(status = WorkStatus.DONE))))
    }
    @Test fun emptyAndTiedProjectsPreferTheLessCompletedStatus() {
        assertEquals(WorkStatus.DRAFT, aggregateStatus(emptyList()))
        assertEquals(WorkStatus.DRAFT, aggregateStatus(listOf(WorkBlock(status = WorkStatus.DONE), WorkBlock())))
        assertEquals(WorkStatus.IN_PROGRESS, aggregateStatus(listOf(WorkBlock(status = WorkStatus.DONE), WorkBlock(status = WorkStatus.IN_PROGRESS))))
    }
    @Test fun oldObjectsMigrateDescriptionsAndKeepPhotosAndAttachmentMetadata() {
        val old = """[{"id":"object","name":"Об'єкт","customer":"Замовник","blocks":[{"id":"product","name":"Виріб","characteristics":"Старі розміри","photos":[{"id":"photo","path":"/private/photo.jpg","note":"Стара примітка"}],"drawings":[{"id":"drawing","path":"/private/old.pdf","name":"Старе"}]}]}]"""
        val project = projectsFromJson(old).single()
        val block = project.blocks.single()
        assertEquals("Старі розміри", block.description)
        assertEquals("Стара примітка", block.note)
        assertEquals(WorkStatus.DRAFT, block.status)
        assertEquals(PhotoEntry("/private/photo.jpg", "photo"), block.photos.single())
        assertEquals("drawing", JSONArray(block.legacyDrawingsJson).getJSONObject(0).getString("id"))
        assertEquals(project, projectsFromJson(projectsToJson(listOf(project))).single())
    }
    @Test fun statusDescriptionAndMediaSurviveSaveReload() {
        val block = WorkBlock(id = "product", name = "Двері", description = "Дуб", status = WorkStatus.IN_PROGRESS,
            media = listOf(MediaEntry("/media/voice.m4a", "Голос", "audio/mp4", "voice"), MediaEntry("/media/movie.mp4", "Відео", "video/mp4", "video"), MediaEntry("/media/image.gif", "GIF", "image/gif", "gif")))
        val project = Project("Монтаж", "Замовник", id = "object", blocks = listOf(block), description = "Перший поверх")
        assertEquals(project, projectsFromJson(projectsToJson(listOf(project))).single())
        assertEquals("Монтаж", project.displayName)
    }
    @Test fun missingOrUnknownStatusBecomesDraftAndMediaPickerExcludesPdf() {
        assertEquals(WorkStatus.DRAFT, WorkBlock().status)
        assertEquals(WorkStatus.DRAFT, WorkStatus.fromStored("unknown"))
        assertEquals(WorkStatus.IN_PROGRESS, WorkStatus.fromStored("В процесі"))
        listOf("image/gif", "image/webp", "video/mp4", "video/x-matroska", "audio/ogg").forEach { assertTrue(isSupportedMediaType(it)) }
        assertFalse(isSupportedMediaType("application/pdf"))
        assertFalse(isSupportedMediaType("application/octet-stream"))
    }
}
