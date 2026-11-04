package com.frop.montash

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.frop.montash.media.ProductMedia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AudioUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addingAudioWaitsForExplicitStart() {
        compose.setContent {
            MontashTheme {
                ProductMedia(WorkBlock(), {}, {}, editableMedia = true, editableAudio = true,
                    editableDocs = true, editablePhotos = true, editableText = true,
                    canEdit = false, onEdit = { _, _ -> }, addition = "audio", onAdditionHandled = {})
            }
        }
        compose.onNodeWithTag("start-audio-recording").assertIsDisplayed()
    }

    @Test fun eachVoiceMessageShowsPlaybackTime() {
        val block = WorkBlock(media = listOf(
            MediaEntry("/missing/first.m4a", "Перший", "audio/mp4"),
            MediaEntry("/missing/second.m4a", "Другий", "audio/mp4")
        ))
        compose.setContent {
            MontashTheme {
                ProductMedia(block, {}, {}, editableMedia = false, editableAudio = false,
                    editableDocs = false, editablePhotos = false, editableText = false,
                    canEdit = false, onEdit = { _, _ -> }, addition = null, onAdditionHandled = {})
            }
        }
        compose.onAllNodesWithTag("audio-position").assertCountEquals(2)
    }

    @Test fun documentEditorShowsAdditionalFileTile() {
        val block = WorkBlock(media = listOf(MediaEntry("/missing/file.pdf", "Файл", "application/pdf",
            batchId = "docs", storageSection = "documents")))
        compose.setContent {
            MontashTheme {
                ProductMedia(block, {}, {}, editableMedia = false, editableAudio = false,
                    editableDocs = true, editablePhotos = false, editableText = false,
                    canEdit = false, onEdit = { _, _ -> }, addition = null, onAdditionHandled = {},
                    visibleGroup = "documents" to "docs")
            }
        }
        compose.onNodeWithTag("add-додати-документ").assertIsDisplayed()
    }

    @Test fun textEditorShowsRichEditorAndAdditionalTextTile() {
        val block = WorkBlock(texts = listOf(TextEntry("<strong>Текст</strong>", id = "rich",
            batchId = "text")))
        compose.setContent {
            MontashTheme {
                ProductMedia(block, {}, {}, editableMedia = false, editableAudio = false,
                    editableDocs = false, editablePhotos = false, editableText = true,
                    canEdit = false, onEdit = { _, _ -> }, addition = null, onAdditionHandled = {},
                    visibleGroup = "text" to "text")
            }
        }
        compose.onNodeWithTag("text-entry-rich").assertExists()
        compose.onNodeWithTag("add-додати-текст").assertIsDisplayed()
        compose.onNodeWithTag("rich-text-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("rich-text-input").assertIsDisplayed()
        val editor = compose.onNodeWithTag("text-entry-rich").fetchSemanticsNode().boundsInRoot
        val addTile = compose.onNodeWithTag("add-додати-текст").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(editor.left - addTile.left) < 1f)
        assertTrue(kotlin.math.abs(editor.right - addTile.right) < 1f)
        assertTrue(addTile.top > editor.bottom)
    }
}
