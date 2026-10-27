package com.frop.montash

import android.content.Context
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
                WorkBlock(id = b.getString("id"), photos = (0 until photos.length()).map { k ->
                    val photo = photos.getJSONObject(k)
                    PhotoEntry(photo.getString("path"), photo.getString("id"))
                }, name = b.optionalText("name"), note = if (b.has("note")) b.optionalText("note") else {
                    (0 until photos.length()).map { photos.getJSONObject(it).optionalText("note") }
                        .filter { it.isNotBlank() }.distinct().joinToString("\n\n")
                }, description = if (b.has("description")) b.optionalText("description") else b.optionalText("characteristics"),
                    status = WorkStatus.fromStored(b.optionalText("status")),
                    media = (0 until media.length()).map { k ->
                        val m = media.getJSONObject(k)
                        MediaEntry(m.getString("path"), m.getString("name"), m.getString("mime_type"), m.getString("id"))
                    }, legacyDrawingsJson = b.optJSONArray("drawings")?.toString())
            }, customerId = p.optionalText("customer_id").takeIf { it.isNotBlank() }, description = p.optionalText("description"))
    }
}
internal fun projectsToJson(projects: List<Project>): String = JSONArray().apply {
    projects.forEach { p ->
        val blocks = JSONArray().apply {
            p.blocks.forEach { b ->
                val photos = JSONArray().apply { b.photos.forEach { put(JSONObject().put("id", it.id).put("path", it.path)) } }
                val media = JSONArray().apply { b.media.forEach { put(JSONObject().put("id", it.id).put("path", it.path).put("name", it.name).put("mime_type", it.mimeType)) } }
                put(JSONObject().put("id", b.id).put("name", b.name).put("note", b.note)
                    .put("description", b.description).put("status", b.status.name).put("photos", photos).put("media", media)
                    .apply { if (b.legacyDrawingsJson != null) put("drawings", JSONArray(b.legacyDrawingsJson)) })
            }
        }
        put(JSONObject().put("id", p.id).put("name", p.name).put("customer", p.customer)
            .put("description", p.description).put("blocks", blocks).put("customer_id", p.customerId ?: JSONObject.NULL))
    }
}.toString()

internal class ProjectStore(private val context: Context) {
    private val file get() = File(context.filesDir, "objects.json")
    fun load(): List<Project> = if (file.exists()) projectsFromJson(file.readText()) else emptyList()
    fun save(projects: List<Project>) {
        val temporary = File(context.filesDir, "objects.tmp")
        temporary.writeText(projectsToJson(projects))
        check(temporary.renameTo(file)) { "Не вдалося зберегти об'єкти" }
    }
}
