package com.frop.montash

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

/** HTTP API access. All addresses and credentials are injected from the root .env at build time. */
internal class RemoteProjects(context: Context) {
    private val globalEndpoint = BuildConfig.MONTASH_GLOBAL_API_URL.trim().trimEnd('/')
    private val localEndpoint = BuildConfig.MONTASH_LOCAL_API_URL.trim().trimEnd('/')
    private val token = BuildConfig.MONTASH_CLIENT_API_TOKEN.trim()
    private val preferences = context.getSharedPreferences("remote_projects", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val enabled: Boolean get() = globalEndpoint.isNotEmpty() && token.isNotEmpty()

    suspend fun globalHealthy(): Boolean {
        if (healthy(globalEndpoint, "global", 8_000)) return true
        delay(500)
        return healthy(globalEndpoint, "global", 8_000)
    }
    suspend fun localHealthy(): Boolean = healthy(localEndpoint, "local", 2_000)

    private suspend fun healthy(endpoint: String, expectedMode: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        if (endpoint.isEmpty()) return@withContext false
        runCatching {
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
        }.getOrDefault(false)
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
                val detail = runCatching { JSONObject(response).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException("Сервер повернув $status: $detail")
            }
            return JSONObject(response)
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
            val remote = (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val id = value.getString("id")
                if (id !in pending) {
                    preferences.edit().putLong("revision:${server.name}:$id", value.getLong("revision")).apply()
                }
                value.remove("revision")
                value
            }
            val remoteProjects = projectsFromJson(JSONArray(remote).toString())
            val remoteIds = remoteProjects.mapTo(mutableSetOf()) { it.id }
            val localOnly = local.filter { it.id !in remoteIds }
            localOnly.forEach { markPending(it.id) }
            remoteProjects.map { remoteProject ->
                local.firstOrNull { it.id == remoteProject.id && it.id in pending } ?: remoteProject
            } + localOnly
        }
    }

    fun markPending(id: String) {
        val updated = pendingIds() + id
        check(preferences.edit().putStringSet("pending", updated).commit()) {
            "Не вдалося зберегти чергу синхронізації"
        }
    }

    private fun pendingIds(): Set<String> = preferences.getStringSet("pending", emptySet())?.toSet().orEmpty()

    /** Transfer files saved before server media upload was available. */
    suspend fun uploadExistingMedia(store: ProjectStore, server: ServerConnection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled || server !in setOf(ServerConnection.LOCAL, ServerConnection.GLOBAL)) return@withLock
            val endpoint = if (server == ServerConnection.LOCAL) localEndpoint else globalEndpoint
            for (project in store.load()) {
                for (block in project.blocks) {
                    for (photo in block.photos) {
                        if (File(photo.path).isFile) {
                            uploadFile(endpoint, server, project.id, block.id, "photo", photo.id, photo.path, "image/jpeg")
                        }
                    }
                    for (media in block.media) {
                        if (File(media.path).isFile) {
                            uploadFile(endpoint, server, project.id, block.id, "media", media.id, media.path, media.mimeType)
                        }
                    }
                }
            }
        }
    }

    suspend fun upload(store: ProjectStore, server: ServerConnection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled || server !in setOf(ServerConnection.LOCAL, ServerConnection.GLOBAL)) return@withLock
            val endpoint = if (server == ServerConnection.LOCAL) localEndpoint else globalEndpoint
            for (id in pendingIds()) {
                val project = store.load().firstOrNull { it.id == id } ?: continue
                val body = JSONArray(projectsToJson(listOf(project))).getJSONObject(0)
                    .put("revision", preferences.getLong("revision:${server.name}:$id", 0L))
                val result = request(endpoint, "PUT", "/api/montash/projects/${java.net.URLEncoder.encode(id, "UTF-8")}", body.toString())
                preferences.edit().putLong("revision:${server.name}:$id", result.getLong("revision")).apply()
                for (block in project.blocks) {
                    for (photo in block.photos) {
                        uploadFile(endpoint, server, id, block.id, "photo", photo.id, photo.path, "image/jpeg")
                    }
                    for (media in block.media) {
                        uploadFile(endpoint, server, id, block.id, "media", media.id, media.path, media.mimeType)
                    }
                }
                // A newer edit may have arrived while this request was in flight.
                val unchanged = store.load().firstOrNull { it.id == id } == project
                if (unchanged) {
                    check(preferences.edit().putStringSet("pending", pendingIds() - id).commit()) {
                        "Не вдалося підтвердити синхронізацію"
                    }
                }
            }
        }
    }
}
