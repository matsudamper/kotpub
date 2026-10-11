package net.matsudamper.activitypub.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * 渡したアドレスへ TCP で繋ぐことと、HTTP/1.1 の読み書き。名前解決はしない
 */
class SocketPinnedHttpTransportTest {
    @Test
    fun `指定したアドレスに繋いでHostは渡した値のまま送る`() {
        val body = "hello"
        val captured =
            requestAgainstLocalServer(body = body.encodeToByteArray()) { port ->
                val url = checkNotNull(HttpUrl.parse("http://example.com:$port/users/alice?x=1"))
                val response =
                    SocketPinnedHttpTransport().exchange(
                        request =
                        PinnedHttpRequest(
                            method = "GET",
                            url = url,
                            address = InetAddress.getByName("127.0.0.1"),
                            headers = mapOf("Accept" to "application/activity+json", "Host" to "remote.example"),
                            body = ByteArray(0),
                        ),
                        readsBody = { _, _ -> true },
                        maxBodyBytes = 100,
                    )
                assertEquals(200, response.status)
                assertEquals(body, response.body?.getOrThrow()?.decodeToString())
            }

        assertEquals(true, captured.text.startsWith("GET /users/alice?x=1 HTTP/1.1\r\n"))
        assertEquals(true, captured.text.contains("Host: remote.example\r\n"))
        assertEquals(false, captured.text.contains("127.0.0.1"))
    }

    @Test
    fun `Hostが無ければURLのホストを足す`() {
        val captured =
            requestAgainstLocalServer { port ->
                val url = checkNotNull(HttpUrl.parse("http://example.com:$port/inbox"))
                SocketPinnedHttpTransport().exchange(
                    request =
                    PinnedHttpRequest(
                        method = "GET",
                        url = url,
                        address = InetAddress.getByName("127.0.0.1"),
                        headers = mapOf("Accept" to "application/activity+json"),
                        body = ByteArray(0),
                    ),
                    readsBody = { _, _ -> false },
                    maxBodyBytes = 0,
                )
            }

        assertEquals(true, captured.text.contains("Host: example.com:${captured.port}\r\n"))
        assertEquals(true, captured.text.contains("Accept: application/activity+json\r\n"))
    }

    @Test
    fun `POSTの本文と署名対象のHostをそのまま送る`() {
        val payload = """{"type":"Accept"}""".encodeToByteArray()
        val captured =
            requestAgainstLocalServer(readBody = true) { port ->
                val url = checkNotNull(HttpUrl.parse("http://example.com:$port/inbox"))
                SocketPinnedHttpTransport().exchange(
                    request =
                    PinnedHttpRequest(
                        method = "POST",
                        url = url,
                        address = InetAddress.getByName("127.0.0.1"),
                        headers = mapOf("Host" to "remote.example", "Digest" to "SHA-256=abc"),
                        body = payload,
                    ),
                    readsBody = { _, _ -> false },
                    maxBodyBytes = 0,
                )
            }

        assertEquals(true, captured.text.startsWith("POST /inbox HTTP/1.1\r\n"))
        assertEquals(true, captured.text.contains("Host: remote.example\r\n"))
        assertEquals(true, captured.text.contains("Digest: SHA-256=abc\r\n"))
        assertEquals(true, captured.text.contains("Content-Length: ${payload.size}\r\n"))
        assertEquals(true, captured.text.endsWith("""{"type":"Accept"}"""))
    }

    @Test
    fun `本文は上限を1バイト超えるところまで読む`() {
        val payload = "0123456789".encodeToByteArray()
        requestAgainstLocalServer(body = payload) { port ->
            val url = checkNotNull(HttpUrl.parse("http://example.com:$port/a"))
            val response =
                SocketPinnedHttpTransport().exchange(
                    request =
                    PinnedHttpRequest(
                        method = "GET",
                        url = url,
                        address = InetAddress.getByName("127.0.0.1"),
                        headers = mapOf(),
                        body = ByteArray(0),
                    ),
                    readsBody = { _, _ -> true },
                    maxBodyBytes = 4,
                )
            assertEquals(5, response.body?.getOrThrow()?.size)
        }
    }

    @Test
    fun `httpsでも指定したアドレスへ先にTCP接続する`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicReference(false)
        val worker =
            thread(name = "pinned-https-test") {
                server.accept().use { socket -> socket.close() }
                accepted.set(true)
            }
        val failure =
            try {
                val url = checkNotNull(HttpUrl.parse("https://example.com:${server.localPort}/a"))
                runCatching {
                    SocketPinnedHttpTransport().exchange(
                        request =
                        PinnedHttpRequest(
                            method = "GET",
                            url = url,
                            address = InetAddress.getByName("127.0.0.1"),
                            headers = mapOf(),
                            body = ByteArray(0),
                        ),
                        readsBody = { _, _ -> false },
                        maxBodyBytes = 0,
                    )
                }.exceptionOrNull()
            } finally {
                server.close()
                worker.join(5_000)
            }

        assertNotNull(failure)
        assertEquals(true, accepted.get())
    }

    @Test
    fun `チャンクされた本文を読む`() {
        requestAgainstLocalServer(
            rawResponse = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",
        ) { port ->
            val url = checkNotNull(HttpUrl.parse("http://example.com:$port/a"))
            val response =
                SocketPinnedHttpTransport().exchange(
                    request =
                    PinnedHttpRequest(
                        method = "GET",
                        url = url,
                        address = InetAddress.getByName("127.0.0.1"),
                        headers = mapOf(),
                        body = ByteArray(0),
                    ),
                    readsBody = { _, _ -> true },
                    maxBodyBytes = 100,
                )
            assertEquals("hello", response.body?.getOrThrow()?.decodeToString())
        }
    }

    private class CapturedRequest(
        val port: Int,
        val text: String,
    )

    private fun requestAgainstLocalServer(
        body: ByteArray = ByteArray(0),
        rawResponse: String? = null,
        readBody: Boolean = false,
        block: (port: Int) -> Unit,
    ): CapturedRequest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val seen = AtomicReference<String>()
        val worker =
            thread(name = "pinned-http-test") {
                server.accept().use { socket ->
                    seen.set(readRequest(socket, readBody))
                    val headers =
                        rawResponse
                            ?: "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray(Charsets.ISO_8859_1))
                    if (rawResponse == null) socket.getOutputStream().write(body)
                    socket.getOutputStream().flush()
                }
            }
        try {
            block(port)
        } finally {
            server.close()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        return CapturedRequest(port = port, text = assertNotNull(seen.get()))
    }

    private fun readRequest(
        socket: Socket,
        readBody: Boolean,
    ): String {
        val headers = readUntilHeaderEnd(socket.getInputStream()).toString(Charsets.ISO_8859_1)
        if (!readBody) return headers
        val length =
            headers.split("\r\n")
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toInt()
                ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = socket.getInputStream().read(body, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return headers + body.decodeToString(endIndex = offset)
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) break
            out.write(next)
            val bytes = out.toByteArray()
            val size = bytes.size
            if (size >= 4 &&
                bytes[size - 4] == '\r'.code.toByte() &&
                bytes[size - 3] == '\n'.code.toByte() &&
                bytes[size - 2] == '\r'.code.toByte() &&
                bytes[size - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return out.toByteArray()
    }
}
