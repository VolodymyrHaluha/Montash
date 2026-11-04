package com.frop.montash

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ResponsiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newProductFormUsesSingleColumnInPortraitWidth() {
        val project = Project("Об'єкт", "Об'єкт")
        compose.setContent {
            val portrait = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 400
                screenHeightDp = 800
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides portrait) {
                MontashTheme {
                    ObjectDetail(project, {}, { _, _ -> }, { _, _ -> })
                }
            }
        }

        compose.onNodeWithTag("add-product").performClick()
        compose.onNodeWithTag("add-product-dialog").assertIsDisplayed()
        val photo = compose.onNodeWithTag("product-photo-preview").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("product-name").fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithTag("product-description").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("product-status").fetchSemanticsNode().boundsInRoot

        assertTrue(name.top >= photo.bottom)
        assertTrue(description.top >= name.bottom)
        assertTrue(status.top >= description.bottom)
    }

    @Test fun newProductFormUsesPhotoBesideStackedFieldsInLandscape() {
        val project = Project("Об'єкт", "Об'єкт")
        compose.setContent {
            val landscape = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1000
                screenHeightDp = 700
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides landscape) {
                MontashTheme {
                    ObjectDetail(project, {}, { _, _ -> }, { _, _ -> })
                }
            }
        }

        compose.onNodeWithTag("add-product").performClick()
        val photo = compose.onNodeWithTag("product-photo-preview").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("product-name").fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithTag("product-description").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("product-status").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("cancel-product").fetchSemanticsNode().boundsInRoot

        assertTrue(photo.right <= name.left)
        assertTrue(description.top >= name.bottom)
        assertTrue(status.top >= photo.bottom)
        assertTrue(status.top >= description.bottom)
        assertTrue(status.left > photo.left)
        assertTrue(status.right < description.right)
        assertTrue(abs((status.left - photo.left) - (description.right - status.right)) < 5f)
        assertTrue(status.height >= 40f)
        assertTrue(status.bottom <= cancel.top)
    }

    @Test fun productInfoEditorUsesSameLandscapeLayout() {
        val project = Project("Об'єкт", "Об'єкт", blocks = listOf(
            WorkBlock(id = "product", name = "Виріб", description = "Опис")
        ))
        compose.setContent {
            val landscape = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1000
                screenHeightDp = 700
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides landscape) {
                MontashTheme {
                    ObjectDetail(project, {}, { _, _ -> }, { _, _ -> })
                }
            }
        }

        compose.onNodeWithTag("product-product").performClick()
        compose.onNodeWithTag("edit-product").performClick()
        val photo = compose.onNodeWithTag("product-photo-preview").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("product-name-editor").fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithTag("product-description").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("product-status").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("cancel-product").fetchSemanticsNode().boundsInRoot

        assertTrue(photo.right <= name.left)
        assertTrue(description.top >= name.bottom)
        assertTrue(status.top >= photo.bottom)
        assertTrue(status.top >= description.bottom)
        assertTrue(status.height >= 40f)
        assertTrue(status.bottom <= cancel.top)
    }
}
