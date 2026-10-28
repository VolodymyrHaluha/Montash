package com.frop.montash

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** HTTP API access. All addresses and credentials are injected from the root .env at build time. */
internal class RemoteProjects(context: Context) {
    private val globalEndpoint = BuildConfig.MONTASH_GLOBAL_API_URL.trim().trimEnd('/')
    private val localEndpoint = BuildConfig.MONTASH_LOCAL_API_URL.trim().trimEnd('/')
    private val token = BuildConfig.MONTASH_CLIENT_API_TOKEN.trim()
    private val preferences = context.getSharedPreferences("remote_projects", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val enabled: Boolean get() = globalEndpoint.isNotEmpty() && token.isNotEmpty()

    suspend fun globalHealthy(): Boolean = healthy(globalEndpoint, "global", 8_000)
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
                if (fromGlobal && id !in pending) {
                    preferences.edit().putLong("revision:$id", value.getLong("revision")).apply()
                }
                value.remove("revision")
                value
            }
            val remoteProjects = projectsFromJson(JSONArray(remote).toString())
            val remoteIds = remoteProjects.mapTo(mutableSetOf()) { it.id }
            val localOnly = local.filter { it.id !in remoteIds }
            if (fromGlobal) localOnly.forEach { markPending(it.id) }
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

    suspend fun upload(store: ProjectStore) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled) return@withLock
            for (id in pendingIds()) {
                val project = store.load().firstOrNull { it.id == id } ?: continue
                val body = JSONArray(projectsToJson(listOf(project))).getJSONObject(0)
                    .put("revision", preferences.getLong("revision:$id", 0L))
                val result = request(globalEndpoint, "PUT", "/api/montash/projects/${java.net.URLEncoder.encode(id, "UTF-8")}", body.toString())
                preferences.edit().putLong("revision:$id", result.getLong("revision")).apply()
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
