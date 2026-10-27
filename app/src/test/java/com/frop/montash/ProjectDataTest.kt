package com.frop.montash

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProjectDataTest {
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
