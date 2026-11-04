package com.frop.montash

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.frop.montash.data.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class ProjectStoreInstrumentedTest {
    @Test fun atomicSaveSurvivesReloadAndInterruptedReplacement() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "project-store-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }
        try {
            val original = Project("Перший", "Замовник")
            ProjectStore(isolated).save(listOf(original))
            assertEquals(listOf(original), ProjectStore(isolated).load())

            val file = File(directory, "objects.json")
            File(directory, "objects.json.bak").writeBytes(file.readBytes())
            file.writeText("broken incomplete write")
            assertEquals(listOf(original), ProjectStore(isolated).load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun deleteProjectRemovesObjectAndAllItsProductsFromStore() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "project-delete-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }
        try {
            val removed = Project("Видалити", "Видалити", blocks = listOf(
                WorkBlock(name = "Виріб")
            ))
            val retained = Project("Залишити", "Залишити")
            val store = ProjectStore(isolated)
            store.save(listOf(removed, retained))

            assertEquals(listOf(retained), store.deleteProject(removed))
            assertEquals(listOf(retained), ProjectStore(isolated).load())
        } finally { directory.deleteRecursively() }
    }
}
