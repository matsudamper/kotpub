package net.matsudamper.activitypub.http

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * 渡された [java.net.InetAddress] に TCP で繋ぎ、HTTP/1.1 を 1 往復する。
 *
 * ホスト名では繋がない。名前を引き直すと、検査のあとだけ内部アドレスを返す
 * DNS リバインディングで、検査したアドレスと別の相手に届く。
 * TLS の検査名（SNI と証明書）は URL のホストのままにする。繋ぐ相手と、証明書が名乗る名前は別
 */
internal class SocketPinnedHttpTransport internal constructor(
    private val requestTimeoutMillis: Int,
) : PinnedHttpTransport {
    internal constructor() : this(REQUEST_TIMEOUT_MILLIS)

    override fun exchange(
        request: PinnedHttpRequest,
        readsBody: (status: Int, headers: ResponseHeaders) -> Boolean,
        maxBodyBytes: Int,
    ): PinnedHttpResponse {
        val deadline = RequestDeadline(requestTimeoutMillis)
        try {
            return deadline.use { exchangeWithin(request, readsBody, maxBodyBytes, deadline) }
        } catch (error: IOException) {
            throw error.ifExpired(deadline.deadlineNanos)
        }
    }

    private fun exchangeWithin(
        request: PinnedHttpRequest,
        readsBody: (status: Int, headers: ResponseHeaders) -> Boolean,
        maxBodyBytes: Int,
        deadline: RequestDeadline,
    ): PinnedHttpResponse {
        open(request, deadline).use { socket ->
            writeRequest(socket.getOutputStream(), request)
            val input = socket.getInputStream()
            val (status, headers) = readStatusAndHeaders(input)
            val body =
                if (readsBody(status, headers)) {
                    try {
                        Result.success(readBody(input, headers, maxBodyBytes))
                    } catch (error: IOException) {
                        Result.failure(error.ifExpired(deadline.deadlineNanos))
                    }
                } else {
                    null
                }
            return PinnedHttpResponse(status = status, headers = headers, body = body)
        }
    }

    override fun close() = Unit

    /**
     * 先に IP へ TCP 接続してから TLS を被せる。
     * 被せるときのホスト名で繋ぎ直す実装があり、その順だと検査と別のアドレスに届く
     */
    private fun open(
        request: PinnedHttpRequest,
        deadline: RequestDeadline,
    ): Socket {
        val socket = Socket()
        deadline.bind(socket)
        try {
            socket.connect(
                InetSocketAddress(request.address, request.url.effectivePort()),
                minOf(CONNECT_TIMEOUT_MILLIS, deadline.remainingMillis()),
            )
            if (!request.url.isHttps) return socket
            return startTls(socket, request.url.host, request.url.effectivePort(), deadline)
        } catch (error: IOException) {
            socket.close()
            throw error
        } catch (error: RuntimeException) {
            socket.close()
            throw error
        }
    }

    private fun startTls(
        socket: Socket,
        host: String,
        port: Int,
        deadline: RequestDeadline,
    ): Socket {
        val bareHost = host.removePrefix("[").removeSuffix("]")
        val ssl = SSLContext.getDefault().socketFactory.createSocket(socket, bareHost, port, true) as SSLSocket
        deadline.bind(ssl)
        try {
            ssl.soTimeout = deadline.remainingMillis()
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            val serverName = sniHostName(bareHost)
            if (serverName != null) parameters.serverNames = listOf(serverName)
            ssl.sslParameters = parameters
            ssl.startHandshake()
            return ssl
        } catch (error: IOException) {
            ssl.close()
            throw error
        } catch (error: RuntimeException) {
            ssl.close()
            throw error
        }
    }

    private fun sniHostName(bareHost: String): SNIHostName? {
        if (isIpLiteral(bareHost)) return null
        return runCatching { SNIHostName(bareHost) }.getOrNull()
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val labels = host.split('.')
        if (labels.size != IPV4_LABEL_COUNT) return false
        return labels.all { label -> label.toIntOrNull() in 0..255 }
    }

    private fun writeRequest(
        output: OutputStream,
        request: PinnedHttpRequest,
    ) {
        val text = StringBuilder()
        text.append(request.method).append(' ').append(request.url.requestTarget()).append(" HTTP/1.1\r\n")
        for ((name, value) in wireHeaders(request)) {
            text.append(name).append(": ").append(value).append("\r\n")
        }
        text.append("\r\n")
        output.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        if (request.body.isNotEmpty()) output.write(request.body)
        output.flush()
    }

    /**
     * 呼び出し側が付けたヘッダはそのまま送る。署名に入っている `Host` を付け替えると検証が通らない。
     * 無いときだけ、HTTP/1.1 に要るものだけ足す
     */
    private fun wireHeaders(request: PinnedHttpRequest): List<Pair<String, String>> {
        val provided = request.headers.toList()
        return buildList {
            addAll(provided)
            if (!hasHeader(provided, "Host")) add("Host" to request.url.hostHeader())
            if (!hasHeader(provided, "Content-Length") && sendsContentLength(request)) {
                add("Content-Length" to request.body.size.toString())
            }
            if (!hasHeader(provided, "Connection")) add("Connection" to "close")
        }
    }

    private fun sendsContentLength(request: PinnedHttpRequest): Boolean =
        request.method == "POST" || request.body.isNotEmpty()

    private fun hasHeader(
        headers: List<Pair<String, String>>,
        name: String,
    ): Boolean = headers.any { (headerName, _) -> headerName.equals(name, ignoreCase = true) }

    private fun readStatusAndHeaders(input: InputStream): Pair<Int, ResponseHeaders> {
        var status = readStatus(input)
        var headers = readHeaders(input)
        var informational = 0
        while (status in INFORMATIONAL_STATUS && informational < MAX_INFORMATIONAL) {
            informational += 1
            status = readStatus(input)
            headers = readHeaders(input)
        }
        return status to headers
    }

    private fun readStatus(input: InputStream): Int {
        val line = readLine(input)
        val code = line.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
        return code ?: throw IOException("status を読めない")
    }

    private fun readHeaders(input: InputStream): ResponseHeaders {
        val values =
            buildList {
                repeat(MAX_HEADER_COUNT) {
                    val line = readLine(input)
                    if (line.isEmpty()) return ResponseHeaders(toList())
                    val colon = line.indexOf(':')
                    if (colon <= 0) throw IOException("応答ヘッダを読めない")
                    add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
                }
            }
        throw IOException("応答ヘッダが多すぎる: ${values.size}")
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (line.size() <= MAX_HEADER_LINE_LENGTH) {
            val next = input.read()
            if (next == -1) {
                if (line.size() == 0) throw EOFException("応答が途中で終わった")
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) line.write(next)
        }
        if (line.size() > MAX_HEADER_LINE_LENGTH) throw IOException("応答の行が長すぎる")
        return line.toString(Charsets.ISO_8859_1)
    }

    private fun readBody(
        input: InputStream,
        headers: ResponseHeaders,
        maxBodyBytes: Int,
    ): ByteArray {
        val limit = if (maxBodyBytes == Int.MAX_VALUE) maxBodyBytes else maxBodyBytes + 1
        val encoding = headers.first("Transfer-Encoding")
        if (encoding != null && encoding.contains("chunked", ignoreCase = true)) {
            return readChunked(input, limit)
        }
        val length = headers.first("Content-Length")?.trim()?.toLongOrNull()
        if (length != null) {
            if (length < 0) throw IOException("Content-Length が負")
            return readCounted(input, minOf(length, limit.toLong()).toInt())
        }
        return readUntilEnd(input, limit)
    }

    private fun readChunked(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        while (body.size() < limit) {
            val sizeText = readLine(input).substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16) ?: throw IOException("チャンクの長さを読めない")
            if (size < 0) throw IOException("チャンクの長さを読めない")
            if (size == 0L) {
                readChunkTrailers(input)
                break
            }
            val take = minOf(size, (limit - body.size()).toLong()).toInt()
            body.write(readCounted(input, take))
            if (take.toLong() < size) break
            if (readLine(input).isNotEmpty()) throw IOException("チャンクの区切りを読めない")
        }
        return body.toByteArray()
    }

    private fun readChunkTrailers(input: InputStream) {
        while (readLine(input).isNotEmpty()) {
            continue
        }
    }

    private fun readCounted(
        input: InputStream,
        count: Int,
    ): ByteArray {
        if (count == 0) return ByteArray(0)
        val body = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(body, offset, count - offset)
            if (read == -1) throw EOFException("本文が途中で終わった")
            offset += read
        }
        return body
    }

    private fun readUntilEnd(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(minOf(limit, READ_BUFFER_BYTES))
        while (body.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - body.size()))
            if (read == -1) break
            body.write(buffer, 0, read)
        }
        return body.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val REQUEST_TIMEOUT_MILLIS = 10_000
        const val MAX_HEADER_LINE_LENGTH = 8 * 1024
        const val MAX_HEADER_COUNT = 128
        const val MAX_INFORMATIONAL = 3
        val INFORMATIONAL_STATUS = 100..199
        const val IPV4_LABEL_COUNT = 4
        const val READ_BUFFER_BYTES = 8 * 1024
    }
}

/**
 * 接続開始から応答完了までの期限。
 * `SO_TIMEOUT` は 1 回の read が無通信で待つ時間なので、1 バイトずつ返されると待ち直してしまう。
 * 期限が来たらソケットを閉じ、読み取りも書き込みも握手もそこまでにする
 */
private class RequestDeadline(
    timeoutMillis: Int,
) : AutoCloseable {
    val deadlineNanos: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())

    private val current = AtomicReference<Socket?>(null)

    @Volatile
    private var closed = false

    private val watcher =
        Thread(::watch, "activitypub-http-deadline").also { thread ->
            thread.isDaemon = true
            thread.start()
        }

    fun bind(socket: Socket) {
        current.set(socket)
        if (closed || System.nanoTime() >= deadlineNanos) socket.close()
    }

    fun remainingMillis(): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) throw SocketTimeoutException("応答の期限を超えた")
        val millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
        return millis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        closed = true
        watcher.interrupt()
    }

    private fun watch() {
        try {
            while (!closed) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    current.get()?.close()
                    return
                }
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1))
            }
        } catch (_: InterruptedException) {
            // 応答が終わった
        }
    }
}

private fun IOException.ifExpired(deadlineNanos: Long): IOException {
    if (System.nanoTime() < deadlineNanos) return this
    if (this is SocketTimeoutException) return this
    return SocketTimeoutException("応答の期限を超えた").also { it.initCause(this) }
}
