package com.frop.montash

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ObjectProductsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newProductIsSavedOnlyAfterConfirmation() {
        val project = mutableStateOf(Project("Об'єкт", "Замовник"))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { project.value = it }) }
        }
        compose.onNodeWithTag("add-product").performClick()
        compose.onNodeWithTag("product-name").performTextReplacement("Чернетка виробу")
        compose.runOnIdle { assertEquals(0, project.value.blocks.size) }
        compose.onNodeWithTag("cancel-product").performClick()
        compose.runOnIdle { assertEquals(0, project.value.blocks.size) }
        compose.onNodeWithTag("add-product").performClick()
        compose.onNodeWithTag("product-name").performTextReplacement("Збережений виріб")
        compose.onNodeWithTag("save-product").performClick()
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
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { project.value = it }) }
        }
        compose.onNodeWithTag("product-first").performClick()
        compose.onNodeWithTag("product-name").assertIsNotEnabled()
        compose.onNodeWithTag("edit-product").performClick()
        compose.onNodeWithTag("product-name").performTextReplacement("Зміна")
        compose.runOnIdle { assertEquals("Перший", project.value.blocks[0].name) }
        compose.onNodeWithTag("cancel-product").performClick()
        compose.onNodeWithTag("product-name").assertTextContains("Перший")
        compose.onNodeWithTag("edit-product").performClick()
        compose.onNodeWithTag("product-name").performTextReplacement("Змінений")
        compose.onNodeWithTag("save-product").performClick()
        compose.runOnIdle { assertEquals("Змінений", project.value.blocks[0].name) }
    }
}
