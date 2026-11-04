package com.frop.montash

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ObjectProductsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newProductIsSavedOnlyAfterConfirmation() {
        val project = mutableStateOf(Project("Об'єкт", "Замовник"))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated }) }
        }
        compose.onNodeWithTag("add-product").performClick()
        compose.onNodeWithTag("add-product-dialog").assertIsDisplayed()
        compose.onNodeWithTag("add-product").assertExists()
        compose.onNodeWithTag("product-additions").assertDoesNotExist()
        compose.onNodeWithTag("product-photo-camera").assertExists()
        compose.onNodeWithTag("product-photo-preview").assertExists()
        compose.onNodeWithTag("product-photo-preview").performClick()
        compose.onNodeWithTag("choose-photo-gallery").assertIsDisplayed()
        compose.onNodeWithTag("product-status").assertExists()
        val nameBounds = compose.onNodeWithTag("product-name").fetchSemanticsNode().boundsInRoot
        val statusBounds = compose.onNodeWithTag("product-status").fetchSemanticsNode().boundsInRoot
        assertTrue(statusBounds.top >= nameBounds.bottom)
        compose.onNodeWithTag("product-name").performTextReplacement("Чернетка виробу")
        compose.runOnIdle { assertEquals(0, project.value.blocks.size) }
        compose.onNodeWithTag("cancel-product").performClick()
        compose.onNodeWithTag("add-product-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, project.value.blocks.size) }
        compose.onNodeWithTag("add-product").performClick()
        compose.onNodeWithTag("product-name").performTextReplacement("Збережений виріб")
        compose.onNodeWithTag("save-product").performClick()
        compose.onNodeWithTag("add-product-dialog").assertDoesNotExist()
        compose.onNodeWithTag("add-product").assertExists()
        compose.onNodeWithText("Збережений виріб").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, project.value.blocks.size)
            assertEquals("Збережений виріб", project.value.blocks[0].name)
        }
    }

    @Test fun existingProductIsReadOnlyUntilEditAndCancelRestoresIt() {
        val project = mutableStateOf(Project("Об'єкт", "Замовник", blocks = listOf(
            WorkBlock(id = "first", name = "Перший", description = "Початковий опис")
        )))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated }) }
        }
        compose.onNodeWithTag("product-first").performClick()
        compose.onNodeWithTag("product-name").assertIsNotEnabled()
        compose.onNodeWithTag("edit-product").performClick()
        compose.onNodeWithTag("product-name-editor").performTextReplacement("Зміна")
        compose.runOnIdle { assertEquals("Перший", project.value.blocks[0].name) }
        compose.onNodeWithTag("cancel-product").performClick()
        compose.onNodeWithTag("product-name").assertTextContains("Перший")
        compose.onNodeWithTag("edit-product").performClick()
        compose.onNodeWithTag("product-name-editor").performTextReplacement("Змінений")
        compose.onNodeWithTag("save-product").performClick()
        compose.runOnIdle { assertEquals("Змінений", project.value.blocks[0].name) }
    }

    @Test fun statusUsesExclusiveCheckboxSelectionInsideEditDialog() {
        val project = mutableStateOf(Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(id = "first", name = "Виріб")
        )))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated }) }
        }
        compose.onNodeWithTag("product-first").performClick()
        compose.onNodeWithTag("edit-product").performClick()
        compose.onNodeWithTag("status-in_progress").performClick()
        compose.runOnIdle { assertEquals(WorkStatus.DRAFT, project.value.blocks[0].status) }
        compose.onNodeWithTag("save-product").performClick()
        compose.runOnIdle { assertEquals(WorkStatus.IN_PROGRESS, project.value.blocks[0].status) }
    }

    @Test fun editingTextBlockDoesNotExposeOtherTextBlocks() {
        val project = mutableStateOf(Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(id = "first", name = "Виріб", texts = listOf(
                TextEntry("Перший текст", id = "first-text", batchId = "first-batch"),
                TextEntry("Другий текст", id = "second-text", batchId = "second-batch")
            ))
        )))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated }) }
        }
        compose.onNodeWithTag("product-first").performClick()
        compose.onAllNodesWithContentDescription("Редагувати Опис і примітки")[0].performClick()
        compose.onNodeWithTag("text-entry-first-text").assertExists()
        compose.onNodeWithTag("text-entry-second-text").assertDoesNotExist()
        compose.onNodeWithTag("text-entry-first-text").performTextReplacement("Змінено")
        compose.onNodeWithTag("save-product").performClick()
        compose.runOnIdle {
            assertEquals("Змінено", project.value.blocks[0].texts[0].text)
            assertEquals("Другий текст", project.value.blocks[0].texts[1].text)
        }
    }

    @Test fun photoTileShowsInlineSourceChoiceWithoutAnotherDialog() {
        val project = mutableStateOf(Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(id = "first", name = "Виріб")
        )))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated }) }
        }
        compose.onNodeWithTag("product-first").performClick()
        compose.onNodeWithTag("add-photo-camera").performClick()
        compose.onNodeWithTag("product-photo-camera").assertIsDisplayed()
        compose.onNodeWithTag("choose-photo-gallery").assertDoesNotExist()
        compose.onNodeWithTag("product-photo-camera").performClick()
        compose.onNodeWithTag("choose-photo-gallery").assertIsDisplayed()
        compose.onNodeWithTag("choose-photo-camera").assertIsDisplayed()
    }

    @Test fun blockEditorsExposeAdditionalItemTilesWithoutDuplicateTitles() {
        val project = mutableStateOf(Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(id = "first", name = "Виріб",
                media = listOf(
                    MediaEntry("/missing/image.jpg", "Фото", "image/jpeg", batchId = "media-batch"),
                    MediaEntry("/missing/audio.m4a", "Голос", "audio/mp4", batchId = "audio-batch"),
                    MediaEntry("/missing/file.pdf", "Файл", "application/pdf", batchId = "docs-batch",
                        storageSection = "documents")
                ), texts = listOf(TextEntry("Текст", batchId = "text-batch")))
        )))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated }) }
        }
        compose.onNodeWithTag("product-first").performClick()
        compose.onNodeWithTag("product-content-block").assertIsDisplayed()
        compose.onNodeWithTag("product-section-media-media-batch").assertExists()
        compose.onNodeWithTag("product-section-audio-audio-batch").assertExists()
        compose.onNodeWithTag("product-section-documents-docs-batch").assertExists()
        compose.onNodeWithTag("product-section-text-text-batch").assertExists()
        val contentBounds = compose.onNodeWithTag("product-content-block").fetchSemanticsNode().boundsInRoot
        val mediaBounds = compose.onNodeWithTag("product-section-media-media-batch")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(mediaBounds.left > contentBounds.left)
        assertTrue(mediaBounds.right < contentBounds.right)

        compose.onNodeWithContentDescription("Редагувати Медіа").performClick()
        compose.onNodeWithTag("add-додати-медіа").assertIsDisplayed()
        compose.onNodeWithTag("cancel-product").performClick()

        compose.onNodeWithContentDescription("Редагувати Голосові повідомлення").performClick()
        compose.onNodeWithTag("add-додати-голосове").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cancel-product").performClick()

    }

    @Test fun objectEditorUpdatesObjectAndProductMainInformationAndSupportsSorting() {
        val project = mutableStateOf(Project("Старий об'єкт", "Старий об'єкт",
            description = "Старий опис", blocks = listOf(
                WorkBlock(id = "first", name = "Перший", description = "Опис 1"),
                WorkBlock(id = "second", name = "Другий", description = "Опис 2")
            )))
        compose.setContent {
            MontashTheme {
                ObjectDetail(project.value, {}, { _, _ -> }, { _, updated -> project.value = updated })
            }
        }

        compose.onNodeWithText("Редагувати об’єкт").performClick()
        compose.onNodeWithTag("object-edit-dialog").assertIsDisplayed()
        compose.onNodeWithTag("object-products-editor").assertIsDisplayed()
        compose.onNode(hasContentDescription("Назва: без сортування") and
            hasAnyAncestor(hasTestTag("object-products-editor"))).performClick()

        compose.onNodeWithTag("object-name-editor").performTextReplacement("Новий об'єкт")
        compose.onNodeWithTag("object-description-editor").performTextReplacement("Новий опис")
        compose.onNodeWithTag("object-product-name-first").performTextReplacement("Оновлений виріб")
        compose.onNodeWithTag("object-product-description-first").performTextReplacement("Оновлений опис")
        compose.onNode(hasTestTag("status-done") and
            hasAnyAncestor(hasTestTag("object-product-status-first"))).performClick()
        compose.onNodeWithTag("save-object-editor").performClick()

        compose.runOnIdle {
            assertEquals("Новий об'єкт", project.value.displayName)
            assertEquals("Новий опис", project.value.description)
            val updated = project.value.blocks.first { it.id == "first" }
            assertEquals("Оновлений виріб", updated.name)
            assertEquals("Оновлений опис", updated.description)
            assertEquals(WorkStatus.DONE, updated.status)
        }
    }

    @Test fun objectEditorRequiresConfirmationBeforeDeletingWholeObject() {
        val project = Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(id = "first", name = "Виріб")
        ))
        var deleted: Project? = null
        compose.setContent {
            MontashTheme {
                ObjectDetail(project, {}, { _, _ -> }, { _, _ -> },
                    onDeleteProject = { deleted = it })
            }
        }

        compose.onNodeWithText("Редагувати об’єкт").performClick()
        compose.onNodeWithTag("delete-object").performClick()
        compose.onNodeWithText("Об’єкт і всі вироби та файли, які входять до нього, буде видалено безповоротно.")
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithTag("confirm-delete-object").performClick()
        compose.runOnIdle { assertEquals(project, deleted) }
    }
}
