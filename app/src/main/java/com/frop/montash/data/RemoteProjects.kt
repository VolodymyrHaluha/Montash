package com.frop.montash.data

import com.frop.montash.*

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

/** HTTP API access. All addresses and credentials are injected from the root .env at build time. */
internal class RemoteProjects(
    context: Context,
    globalUrl: String = BuildConfig.MONTASH_GLOBAL_API_URL,
    localUrl: String = BuildConfig.MONTASH_LOCAL_API_URL,
    apiToken: String = BuildConfig.MONTASH_CLIENT_API_TOKEN,
    preferencesName: String = "remote_projects"
) {
    class RevisionConflict(val currentRevision: Long) : IllegalStateException("Конфлікт версій об’єкта. Локальні зміни збережено для ручного узгодження.")
    private val globalEndpoint = globalUrl.trim().trimEnd('/')
    private val localEndpoint = localUrl.trim().trimEnd('/')
    private val token = apiToken.trim()
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val pendingLock = Any()

    val enabled: Boolean get() = globalEndpoint.isNotEmpty() && token.isNotEmpty()

    suspend fun globalHealthy(): Boolean = healthy(globalEndpoint, "global", 2_000)
    suspend fun localHealthy(): Boolean = healthy(localEndpoint, "local", 2_000)

    private suspend fun healthy(endpoint: String, expectedMode: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        if (endpoint.isEmpty()) return@withContext false
        try {
            val connection = (URL("$endpoint/api/montash/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (connection.responseCode != 200) false else {
                    val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    body.optBoolean("ok") && body.optString("mode") == expectedMode
                }
            } finally { connection.disconnect() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun request(endpoint: String, method: String, path: String, body: String? = null): JSONObject {
        val connection = (URL("$endpoint$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val errorBody = runCatching { JSONObject(response) }.getOrNull()
                if (status == 409 && errorBody?.has("revision") == true) {
                    throw RevisionConflict(errorBody.getLong("revision"))
                }
                val detail = errorBody?.optString("error").orEmpty()
                throw IllegalStateException("Сервер повернув $status: $detail")
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadFile(endpoint: String, server: ServerConnection, projectId: String,
                           productId: String, kind: String, id: String, path: String,
                           mimeType: String) {
        val file = File(path)
        check(file.isFile) { "Файл для передачі відсутній: ${file.name}" }
        val fingerprint = "$path:${file.length()}:${file.lastModified()}"
        val key = "uploaded:v2:${server.name}:$kind:$id"
        if (preferences.getString(key, null) == fingerprint) return
        val encode = { value: String -> java.net.URLEncoder.encode(value, "UTF-8") }
        val url = "$endpoint/api/montash/media/${encode(projectId)}/${encode(productId)}/$kind/${encode(id)}"
        val boundary = "Montash${java.util.UUID.randomUUID()}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".toByteArray())
                output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            check(connection.responseCode in 200..299) {
                "Не вдалося передати файл ${file.name}: HTTP ${connection.responseCode}"
            }
            check(preferences.edit().putString(key, fingerprint).commit()) {
                "Не вдалося підтвердити передачу файлу"
            }
        } finally { connection.disconnect() }
    }

    /** Server wins for shared IDs; unsent local edits remain in the cache until uploaded. */
    suspend fun refresh(local: List<Project>, server: ServerConnection): List<Project> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fromGlobal = server == ServerConnection.GLOBAL
            val endpoint = if (fromGlobal) globalEndpoint else localEndpoint
            val response = request(endpoint, "GET", "/api/montash/projects")
            val array = response.getJSONArray("projects")
            val pending = pendingIds()
            val deleted = deletedIds()
            val remote = (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val id = value.getString("id")
                val revisionKey = "revision:${server.name}:$id"
                if (id !in pending) {
                    preferences.edit().putLong(revisionKey, value.getLong("revision")).apply()
                }
                value.remove("revision")
                value
            }.filterNot { it.getString("id") in deleted }
            val remoteProjects = projectsFromJson(JSONArray(remote).toString())
            val remoteIds = remoteProjects.mapTo(mutableSetOf()) { it.id }
            val localOnly = local.filter { it.id !in remoteIds }
            localOnly.forEach { markPending(it.id) }
            val localById = local.associateBy { it.id }
            remoteProjects.map { remoteProject ->
                localById[remoteProject.id]?.takeIf { it.id in pending } ?: remoteProject
            } + localOnly
        }
    }

    fun markPending(id: String) = synchronized(pendingLock) {
        val updated = pendingIds() + id
        val generationKey = "pending_generation:$id"
        check(preferences.edit().putStringSet("pending", updated)
            .putLong(generationKey, preferences.getLong(generationKey, 0L) + 1L).commit()) {
            "Не вдалося зберегти чергу синхронізації"
        }
    }

    fun markDeleted(id: String) = synchronized(pendingLock) {
        check(preferences.edit()
            .putStringSet("pending", pendingIds() - id)
            .putStringSet("deleted", deletedIds() + id)
            .commit()) { "Не вдалося зберегти чергу видалення" }
    }

    private fun pendingIds(): Set<String> = synchronized(pendingLock) {
        preferences.getStringSet("pending", emptySet())?.toSet().orEmpty()
    }

    private fun deletedIds(): Set<String> = synchronized(pendingLock) {
        preferences.getStringSet("deleted", emptySet())?.toSet().orEmpty()
    }

    fun hasPending(): Boolean = pendingIds().isNotEmpty() || deletedIds().isNotEmpty()

    /** Transfer files saved before server media upload was available. */
    suspend fun uploadExistingMedia(store: ProjectStore, server: ServerConnection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled || server !in setOf(ServerConnection.LOCAL, ServerConnection.GLOBAL)) return@withLock
            val scanKey = "media_scan_complete:${server.name}"
            if (preferences.getBoolean(scanKey, false)) return@withLock
            val endpoint = if (server == ServerConnection.LOCAL) localEndpoint else globalEndpoint
            var firstFailure: Exception? = null
            for (project in store.load()) {
                for (block in project.blocks) {
                    for (photo in block.photos) {
                        if (File(photo.path).isFile) {
                            try { uploadFile(endpoint, server, project.id, block.id, "photo", photo.id, photo.path, "image/jpeg") }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                if (firstFailure == null) firstFailure = failure
                            }
                        }
                    }
                    for (media in block.media) {
                        if (File(media.path).isFile) {
                            try { uploadFile(endpoint, server, project.id, block.id, "media", media.id, media.path, media.mimeType) }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                if (firstFailure == null) firstFailure = failure
                            }
                        }
                    }
                }
            }
            firstFailure?.let { throw it }
            check(preferences.edit().putBoolean(scanKey, true).commit()) { "Не вдалося зберегти стан передачі медіа" }
        }
    }

    suspend fun upload(store: ProjectStore, server: ServerConnection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled || server !in setOf(ServerConnection.LOCAL, ServerConnection.GLOBAL)) return@withLock
            val endpoint = if (server == ServerConnection.LOCAL) localEndpoint else globalEndpoint
            val projects = store.load().associateBy { it.id }
            var firstFailure: Exception? = null
            for (id in deletedIds()) {
                try {
                    val encoded = java.net.URLEncoder.encode(id, "UTF-8")
                    val revision = preferences.getLong("revision:${server.name}:$id", 0L)
                    request(endpoint, "DELETE", "/api/montash/projects/$encoded",
                        JSONObject().put("revision", revision).toString())
                    synchronized(pendingLock) {
                        check(preferences.edit().putStringSet("deleted", deletedIds() - id)
                            .remove("revision:${server.name}:$id").commit()) {
                            "Не вдалося підтвердити видалення об’єкта"
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (firstFailure == null) firstFailure = failure
                }
            }
            for (id in pendingIds()) {
                val project = projects[id] ?: continue
                val generationKey = "pending_generation:$id"
                val generation = preferences.getLong(generationKey, 0L)
                val revisionKey = "revision:${server.name}:$id"
                try {
                    val body = JSONArray(projectsToJson(listOf(project))).getJSONObject(0)
                    val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(body.toString().toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val uploadedKey = "project_uploaded:${server.name}:$id"
                    val uploadedMarker = "$generation:$fingerprint"
                    if (preferences.getString(uploadedKey, null) != uploadedMarker) {
                        val path = "/api/montash/projects/${java.net.URLEncoder.encode(id, "UTF-8")}"
                        body.put("revision", preferences.getLong(revisionKey, 0L))
                        val result = request(endpoint, "PUT", path, body.toString())
                        check(preferences.edit().putLong(revisionKey, result.getLong("revision"))
                            .putString(uploadedKey, uploadedMarker).commit()) {
                            "Не вдалося зберегти серверну ревізію"
                        }
                    }
                    var mediaFailure: Exception? = null
                    for (block in project.blocks) {
                        for (photo in block.photos) {
                            try { uploadFile(endpoint, server, id, block.id, "photo", photo.id, photo.path, "image/jpeg") }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                if (mediaFailure == null) mediaFailure = failure
                            }
                        }
                        for (media in block.media) {
                            try { uploadFile(endpoint, server, id, block.id, "media", media.id, media.path, media.mimeType) }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                if (mediaFailure == null) mediaFailure = failure
                            }
                        }
                    }
                    mediaFailure?.let { throw it }
                    val unchanged = store.load().firstOrNull { it.id == id } == project
                    if (unchanged) synchronized(pendingLock) {
                        if (preferences.getLong(generationKey, 0L) == generation) {
                            check(preferences.edit().putStringSet("pending", pendingIds() - id).commit()) {
                                "Не вдалося підтвердити синхронізацію"
                            }
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (firstFailure == null) firstFailure = failure
                }
            }
            firstFailure?.let { throw it }
        }
    }
}
