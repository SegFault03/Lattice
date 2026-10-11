package com.segfault03.ideadb.ui

import org.hsqldb.Server
import org.hsqldb.server.ServerConstants
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real HSQLDB server. Only network responses are gated to inspect otherwise brief busy states. */
internal class HsqlUiFixture : AutoCloseable {
    private val address = InetAddress.getLoopbackAddress()
    private val serverPort = ServerSocket(0, 50, address).use { it.localPort }
    private val server = Server()
    private val proxy = ServerSocket(0, 50, address)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lattice-ui-jdbc-fixture").apply { isDaemon = true }
    }
    private val gate = Object()
    private var paused = false
    private var blockedResponse = CountDownLatch(1)
    @Volatile private var closed = false
    val jdbcUrl = "jdbc:hsqldb:hsql://127.0.0.1:${proxy.localPort}/lattice"

    init {
        server.setAddress("127.0.0.1")
        server.setPort(serverPort)
        server.setDatabaseName(0, "lattice")
        server.setDatabasePath(0, "mem:lattice_ui_review")
        server.setDaemon(true)
        server.setSilent(true)
        server.setLogWriter(null)
        server.setErrWriter(null)
        server.start()
        check(server.state == ServerConstants.SERVER_STATE_ONLINE) { "HSQLDB fixture failed to start: ${server.serverError}" }
        workers.execute {
            while (!closed) {
                try {
                    val client = proxy.accept()
                    val database = Socket(address, serverPort)
                    sockets.add(client)
                    sockets.add(database)
                    workers.execute { forward(client, database, false) }
                    workers.execute { forward(database, client, true) }
                } catch (error: Exception) {
                    if (!closed) throw error
                }
            }
        }
    }

    fun pauseResponses() = synchronized(gate) {
        check(!paused)
        blockedResponse = CountDownLatch(1)
        paused = true
    }

    fun awaitBlockedResponse() {
        check(blockedResponse.await(15, TimeUnit.SECONDS)) { "Expected a real JDBC response at the fixture gate" }
    }

    fun resumeResponses() = synchronized(gate) {
        paused = false
        gate.notifyAll()
    }

    private fun forward(source: Socket, target: Socket, response: Boolean) {
        try {
            val input = source.getInputStream()
            val output = target.getOutputStream()
            val buffer = ByteArray(8192)
            while (!closed) {
                val count = input.read(buffer)
                if (count < 0) break
                if (response) synchronized(gate) {
                    if (paused) blockedResponse.countDown()
                    while (paused && !closed) gate.wait()
                }
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (error: Exception) {
            if (!closed && error !is java.io.IOException && error !is InterruptedException) throw error
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
        proxy.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
        server.stop()
        workers.awaitTermination(2, TimeUnit.SECONDS)
    }
}
