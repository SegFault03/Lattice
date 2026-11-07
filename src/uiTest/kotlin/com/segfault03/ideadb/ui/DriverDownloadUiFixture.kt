package com.segfault03.ideadb.ui

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Gates opaque HTTPS responses so the native download UI can be inspected during a real transfer. */
internal class DriverDownloadUiFixture(proxyAddress: String?) : Closeable {
    private val upstream = proxyAddress?.takeIf { it.isNotBlank() }?.let(URI::create)
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lattice-ui-download-proxy").apply { isDaemon = true }
    }
    // The IDE downloads from the same base the launcher passes as -Dlattice.maven.central (a mirror when set).
    private val gatedHost = System.getProperty("lattice.maven.central")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI.create(it).host }.getOrNull() } ?: "repo.maven.apache.org"
    private val gate = Object()
    private var paused = false
    private var blocked = CountDownLatch(1)
    @Volatile private var closed = false
    val port: Int get() = listener.localPort

    init {
        workers.execute {
            while (!closed) {
                try {
                    val client = listener.accept()
                    sockets.add(client)
                    workers.execute { tunnel(client) }
                } catch (error: Exception) {
                    if (!closed) throw error
                }
            }
        }
    }

    fun pauseResponses() = synchronized(gate) {
        check(!paused)
        blocked = CountDownLatch(1)
        paused = true
    }

    fun awaitBlockedResponse() {
        check(blocked.await(15, TimeUnit.SECONDS)) { "Expected a real Maven HTTPS response at the download gate" }
    }

    fun resumeResponses() = synchronized(gate) {
        paused = false
        gate.notifyAll()
    }

    private fun tunnel(client: Socket) {
        try {
            val request = headers(client.getInputStream())
            val line = request.substringBefore("\r\n").split(' ')
            check(line[0] == "CONNECT") { "The download fixture accepts HTTPS tunnels" }
            val target = URI.create("https://${line[1]}")
            val server = Socket(upstream?.host ?: target.host,
                upstream?.let { if (it.port < 0) 80 else it.port } ?: target.port)
            sockets.add(server)
            if (upstream != null) {
                val credentials = upstream.userInfo?.let {
                    "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString(it.toByteArray(US_ASCII))}\r\n"
                } ?: ""
                server.getOutputStream().write(("CONNECT ${line[1]} HTTP/1.1\r\nHost: ${line[1]}\r\n$credentials\r\n").toByteArray(US_ASCII))
                val response = headers(server.getInputStream())
                check(response.substringBefore("\r\n").split(' ')[1] == "200") { "Upstream HTTPS tunnel failed" }
            }
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(US_ASCII))
            workers.execute { forward(client, server, false) }
            // TLS stays end-to-end with Maven Central; no certificate or artifact is substituted.
            forward(server, client, target.host == gatedHost)
        } catch (error: Exception) {
            if (!closed) System.err.println("Download fixture tunnel ended: ${error.javaClass.simpleName}")
        } finally {
            client.close()
            sockets.remove(client)
        }
    }

    private fun headers(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16_384) {
            val value = input.read()
            check(value >= 0) { "HTTPS tunnel closed before its headers" }
            bytes.write(value)
            if (value == 10 && bytes.toString(US_ASCII).endsWith("\r\n\r\n")) return bytes.toString(US_ASCII)
        }
        error("HTTPS tunnel headers exceed the fixture limit")
    }

    private fun forward(source: Socket, target: Socket, gated: Boolean) {
        try {
            val bytes = ByteArray(8192)
            val input = source.getInputStream()
            val output = target.getOutputStream()
            while (!closed) {
                val count = input.read(bytes)
                if (count < 0) break
                if (gated) synchronized(gate) {
                    if (paused) blocked.countDown()
                    while (paused && !closed) gate.wait()
                }
                output.write(bytes, 0, count)
                output.flush()
            }
        } catch (ignored: Exception) {
            // Either end can close a completed HTTPS request or the fixture during teardown.
        } finally {
            source.close()
            target.close()
            sockets.remove(source)
            sockets.remove(target)
        }
    }

    override fun close() {
        closed = true
        resumeResponses()
        listener.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
    }
}
