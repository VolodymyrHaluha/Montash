package com.frop.montash

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ObjectProductsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryOpensOnlySelectedProductAndEditsItsData() {
        val project = mutableStateOf(Project("Об'єкт", "Замовник", blocks = listOf(
            WorkBlock(id = "first", name = "Перший", characteristics = "Дані першого"),
            WorkBlock(id = "second", name = "Другий", characteristics = "Дані другого")
        )))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { project.value = it }) }
        }
        compose.onNodeWithText("Характеристики").assertDoesNotExist()
        compose.onNodeWithTag("product-first").assert(hasContentDescription("Фото виробу відсутнє"))
        compose.onNodeWithTag("object-products").performScrollToNode(hasTestTag("product-second"))
        compose.onNodeWithTag("product-second").performClick()
        compose.onNodeWithText("Дані другого").assertIsDisplayed()
        compose.onNodeWithText("Дані першого").assertDoesNotExist()
        compose.onNodeWithTag("product-name").performTextReplacement("Оновлений другий")
        compose.runOnIdle {
            assertEquals("Оновлений другий", project.value.blocks[1].name)
            assertEquals("Перший", project.value.blocks[0].name)
        }
        compose.onNodeWithText("До списку виробів").performClick()
        compose.onNodeWithTag("object-products").performScrollToNode(hasText("Оновлений другий"))
        compose.onNodeWithText("Оновлений другий").assertIsDisplayed()
    }

    @Test fun addActionStaysVisibleAfterScrollingListAndProductDetails() {
        val project = mutableStateOf(Project("Об'єкт", "Замовник", blocks = (0..20).map {
            WorkBlock(id = "p$it", name = "Виріб $it")
        }))
        compose.setContent {
            MontashTheme { ObjectDetail(project.value, {}, { _, _ -> }, { project.value = it }) }
        }
        compose.onNodeWithTag("object-products").performScrollToNode(hasTestTag("product-p20"))
        compose.onNodeWithTag("add-product").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(22, project.value.blocks.size) }
        compose.onNodeWithTag("product-name").performTextReplacement("Новий виріб")
        compose.onNodeWithText("Прикріпити PDF-креслення").performScrollTo()
        compose.onNodeWithTag("add-product").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(23, project.value.blocks.size)
            assertEquals("Новий виріб", project.value.blocks[21].name)
        }
    }
}
