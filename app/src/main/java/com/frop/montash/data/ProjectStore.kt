package com.frop.montash.data

import com.frop.montash.*

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun projectsFromJson(text: String): List<Project> {
    val data = JSONArray(text)
    return (0 until data.length()).map { i ->
        val p = data.getJSONObject(i)
        val blocks = p.optJSONArray("blocks") ?: JSONArray()
        Project(p.optionalText("name"), p.optionalText("customer"), p.getString("id"),
            blocks = (0 until blocks.length()).map { j ->
                val b = blocks.getJSONObject(j)
                val photos = b.optJSONArray("photos") ?: JSONArray()
                val media = b.optJSONArray("media") ?: JSONArray()
                val texts = b.optJSONArray("texts") ?: JSONArray()
                WorkBlock(id = b.getString("id"), photos = (0 until photos.length()).map { k ->
                    val photo = photos.getJSONObject(k)
                    PhotoEntry(photo.getString("path"), photo.getString("id"), photo.optLong("created_at"),
                        photo.optionalText("batch_id").takeIf { it.isNotBlank() })
                }, name = b.optionalText("name"), note = if (b.has("note")) b.optionalText("note") else {
                    (0 until photos.length()).map { photos.getJSONObject(it).optionalText("note") }
                        .filter { it.isNotBlank() }.distinct().joinToString("\n\n")
                }, description = if (b.has("description")) b.optionalText("description") else b.optionalText("characteristics"),
                    status = WorkStatus.fromStored(b.optionalText("status")),
                    media = (0 until media.length()).map { k ->
                        val m = media.getJSONObject(k)
                        MediaEntry(m.getString("path"), m.getString("name"), m.getString("mime_type"), m.getString("id"),
                            m.optLong("created_at"), m.optionalText("batch_id").takeIf { it.isNotBlank() },
                            m.optionalText("storage_section").takeIf { it.isNotBlank() })
                    }, texts = (0 until texts.length()).map { k ->
                        val entry = texts.getJSONObject(k)
                        TextEntry(entry.optString("text"), entry.getString("id"), entry.optLong("created_at"),
                            entry.optionalText("batch_id").takeIf { it.isNotBlank() })
                    }, history = (b.optJSONArray("history") ?: JSONArray()).let { events ->
                        (0 until events.length()).map { k ->
                            val event = events.getJSONObject(k)
                            ProductChange(event.optString("section"), event.optString("description"), event.optLong("timestamp"))
                        }
                    }, legacyDrawingsJson = b.optJSONArray("drawings")?.toString())
            }, customerId = p.optionalText("customer_id").takeIf { it.isNotBlank() }, description = p.optionalText("description"))
    }
}
internal fun projectsToJson(projects: List<Project>): String = JSONArray().apply {
    projects.forEach { p ->
        val blocks = JSONArray().apply {
            p.blocks.forEach { b ->
                val photos = JSONArray().apply { b.photos.forEach { photo -> put(JSONObject().put("id", photo.id).put("path", photo.path)
                    .put("created_at", photo.createdAt).apply { photo.batchId?.let { put("batch_id", it) } }) } }
                val media = JSONArray().apply { b.media.forEach { entry -> put(JSONObject().put("id", entry.id).put("path", entry.path)
                    .put("name", entry.name).put("mime_type", entry.mimeType).put("created_at", entry.createdAt)
                    .apply {
                        entry.batchId?.let { put("batch_id", it) }
                        entry.storageSection?.let { put("storage_section", it) }
                    }) } }
                val history = JSONArray().apply { b.history.forEach { put(JSONObject().put("section", it.section).put("description", it.description).put("timestamp", it.timestamp)) } }
                val texts = JSONArray().apply { b.texts.forEach { entry -> put(JSONObject().put("id", entry.id).put("text", entry.text)
                    .put("created_at", entry.createdAt).apply { entry.batchId?.let { put("batch_id", it) } }) } }
                put(JSONObject().put("id", b.id).put("name", b.name).put("note", b.note)
                    .put("description", b.description).put("status", b.status.name).put("photos", photos).put("media", media)
                    .put("texts", texts).put("history", history)
                    .apply { if (b.legacyDrawingsJson != null) put("drawings", JSONArray(b.legacyDrawingsJson)) })
            }
        }
        put(JSONObject().put("id", p.id).put("name", p.name).put("customer", p.customer)
            .put("description", p.description).put("blocks", blocks).put("customer_id", p.customerId ?: JSONObject.NULL))
    }
}.toString()

internal class ProjectStore(private val context: Context) {
    private val file get() = File(context.filesDir, "objects.json")
    private val atomicFile by lazy { AtomicFile(file) }
    private var cached: List<Project>? = null

    @Synchronized fun load(): List<Project> {
        cached?.let { return it }
        val projects = if (file.exists() || File(file.path + ".bak").exists()) {
            atomicFile.openRead().bufferedReader().use { projectsFromJson(it.readText()) }
        } else emptyList()
        cached = projects
        return projects
    }

    @Synchronized fun save(projects: List<Project>) {
        val bytes = projectsToJson(projects).toByteArray(Charsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
            cached = projects
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    @Synchronized fun mergeRefreshed(snapshot: List<Project>, refreshed: List<Project>): List<Project> {
        val previous = snapshot.associateBy { it.id }
        val current = load()
        val changed = current.filter { it != previous[it.id] }.associateBy { it.id }
        val refreshedIds = refreshed.mapTo(HashSet()) { it.id }
        val merged = refreshed.map { changed[it.id] ?: it } +
            changed.values.filter { it.id !in refreshedIds }
        save(merged)
        return merged
    }

    @Synchronized fun replaceProject(expected: Project, project: Project, beforeWrite: () -> Unit = {}): Pair<List<Project>, Boolean> {
        val current = load()
        val stored = current.firstOrNull { it.id == project.id }
        check(stored == expected) { "Об’єкт змінився на сервері. Перегляньте нові дані перед збереженням." }
        if (stored == project) return current to false
        beforeWrite()
        val updated = current.map { if (it.id == project.id) project else it }
        save(updated)
        return updated to true
    }

    @Synchronized fun addProject(project: Project, beforeWrite: () -> Unit = {}): List<Project> {
        check(load().none { it.id == project.id }) { "Об’єкт із таким ідентифікатором уже існує." }
        beforeWrite()
        val updated = load() + project
        save(updated)
        return updated
    }

    @Synchronized fun deleteProject(expected: Project, beforeWrite: () -> Unit = {}): List<Project> {
        val current = load()
        val stored = current.firstOrNull { it.id == expected.id }
        check(stored == expected) { "Об’єкт змінився на сервері. Перегляньте нові дані перед видаленням." }
        beforeWrite()
        val updated = current.filterNot { it.id == expected.id }
        save(updated)
        return updated
    }
}
