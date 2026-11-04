package com.frop.montash

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.frop.montash.data.ProjectStore
import com.frop.montash.data.RemoteProjects
import com.frop.montash.data.ServerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RemoteProjectsInstrumentedTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun fixture(port: Int): Triple<ProjectStore, RemoteProjects, Project> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "remote-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val project = Project("Локальна зміна", "Замовник")
        val store = ProjectStore(context).also { it.save(listOf(project)) }
        val remote = RemoteProjects(context, "http://127.0.0.1:$port", "", "test-token", "remote-test-${UUID.randomUUID()}")
        remote.markPending(project.id)
        return Triple(store, remote, project)
    }

    private fun respondOnce(server: ServerSocket, status: Int, body: String, count: AtomicInteger) = thread {
        server.use { listener ->
            listener.soTimeout = 10_000
            listener.accept().use { socket ->
                count.incrementAndGet()
                val input = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    @Test fun conflictKeepsLocalChangePending() = runBlocking {
        val server = ServerSocket(0, 1, loopback)
        val (store, remote, project) = fixture(server.localPort)
        val count = AtomicInteger()
        val responder = respondOnce(server, 409, """{"revision":2,"error":"conflict"}""", count)
        try {
            withContext(Dispatchers.IO) { remote.upload(store, ServerConnection.GLOBAL) }
            fail("Expected a revision conflict")
        } catch (_: RemoteProjects.RevisionConflict) { }
        responder.join(10_000)
        assertEquals(1, count.get())
        assertTrue(remote.hasPending())
        assertEquals(project, store.load().single())
    }

    @Test fun reconnectUploadsOnceAndClearsQueue() = runBlocking {
        val reserved = ServerSocket(0, 1, loopback)
        val port = reserved.localPort
        reserved.close()
        val (store, remote, _) = fixture(port)
        try {
            withContext(Dispatchers.IO) { remote.upload(store, ServerConnection.GLOBAL) }
            fail("Expected the offline attempt to fail")
        } catch (_: Exception) { }
        assertTrue(remote.hasPending())

        val server = ServerSocket(port, 1, loopback)
        val count = AtomicInteger()
        val responder = respondOnce(server, 200, """{"revision":1}""", count)
        withContext(Dispatchers.IO) { remote.upload(store, ServerConnection.GLOBAL) }
        responder.join(10_000)
        assertEquals(1, count.get())
        assertFalse(remote.hasPending())
        withContext(Dispatchers.IO) { remote.upload(store, ServerConnection.GLOBAL) }
        assertEquals(1, count.get())
    }
}
