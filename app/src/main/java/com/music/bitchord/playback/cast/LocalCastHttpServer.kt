package com.music.bitchord.playback.cast

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Narrow, session-scoped HTTP bridge for one user-selected local audio item.
 * It exposes no filesystem paths and accepts only the random media URL returned
 * by [start]. Call [close] when the Cast session ends.
 */
internal class LocalCastHttpServer(
    context: Context,
    private val mediaUri: Uri,
    private val contentType: String,
) : Closeable {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val responseContentType = contentType
        .takeIf { it.length <= 127 && MIME_TYPE_PATTERN.matches(it) }
        ?: "application/octet-stream"
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    // Bound both active readers and queued sockets so another LAN client cannot
    // turn this temporary endpoint into an unbounded thread or fd consumer.
    private val workers = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4),
    ) { runnable -> Thread(runnable, "cast-local-file-client").apply { isDaemon = true } }
    @Volatile private var serverSocket: ServerSocket? = null

    @Synchronized
    @Throws(IOException::class)
    fun start(): String {
        check(!closed.get()) { "Cast media server is closed" }
        if (serverSocket == null) {
            val length = mediaLength()
            if (length <= 0L) throw IOException("Local media has no readable length")
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 4)
            }
            serverSocket = socket
            thread(name = "cast-local-file-server", isDaemon = true) { acceptClients(socket) }
        }
        val address = localIpv4Address()
            ?: throw IOException("No local IPv4 address is available for Cast")
        return "http://$address:${serverSocket!!.localPort}/media/$token"
    }

    private fun acceptClients(socket: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                socket.accept().apply { soTimeout = SOCKET_TIMEOUT_MS }
            } catch (_: IOException) {
                if (!closed.get()) close()
                return
            }
            clients += client
            try {
                workers.execute {
                    try {
                        serve(client)
                    } finally {
                        clients -= client
                        runCatching { client.close() }
                    }
                }
            } catch (_: RuntimeException) {
                clients -= client
                runCatching { client.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        var headerBytes = 0
        val requestLine = readAsciiLine(input, MAX_HTTP_LINE_BYTES)?.also {
            headerBytes += it.length + 2
        }?.split(' ') ?: return
        if (requestLine.size != 3) return
        val method = requestLine[0]
        val requestPath = requestLine[1]
        if (method != "GET" && method != "HEAD") {
            respond(socket, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n")
            return
        }
        if (requestPath != "/media/$token") {
            respond(socket, 404, "Not Found")
            return
        }

        val headers = mutableMapOf<String, String>()
        var headerCount = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HTTP_LINE_BYTES) ?: return
            headerBytes += line.length + 2
            if (headerBytes > MAX_HTTP_HEADER_BYTES || ++headerCount > MAX_HTTP_HEADER_COUNT) {
                respond(socket, 431, "Request Header Fields Too Large")
                return
            }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                line.substring(colon + 1).trim()
        }

        val length = try {
            mediaLength()
        } catch (_: Exception) {
            respond(socket, 404, "Not Found")
            return
        }
        if (length <= 0L) {
            respond(socket, 404, "Not Found")
            return
        }
        val range = headers["range"]
        val bounds = if (range == null) null else parseRange(range, length)
        if (range != null && bounds == null) {
            respond(socket, 416, "Range Not Satisfiable", "Content-Range: bytes */$length\r\n")
            return
        }
        val start = bounds?.first ?: 0L
        val end = bounds?.last ?: (length - 1L)
        val responseLength = end - start + 1L
        val status = if (bounds == null) "200 OK" else "206 Partial Content"
        val output = socket.getOutputStream()
        val responseHeaders = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $responseContentType\r\n")
            append("Content-Length: $responseLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            if (bounds != null) append("Content-Range: bytes $start-$end/$length\r\n")
            append("\r\n")
        }
        output.write(responseHeaders.toByteArray(Charsets.US_ASCII))
        output.flush()
        if (method == "HEAD") return

        val stream = openStreamAt(start) ?: return
        stream.use { source ->
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            var remaining = responseLength
            while (remaining > 0L && !closed.get()) {
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    private fun mediaLength(): Long = when (mediaUri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> File(mediaUri.path ?: throw IOException("Invalid file URI"))
            .takeIf { it.isFile }
            ?.length()
            ?: throw IOException("File is not available")
        "content" -> resolver.openAssetFileDescriptor(mediaUri, "r")?.use { descriptor ->
            val declared = descriptor.length
            if (declared >= 0L) declared else {
                val statSize = runCatching { descriptor.parcelFileDescriptor.statSize }
                    .getOrDefault(-1L)
                (statSize - descriptor.startOffset).takeIf { statSize >= descriptor.startOffset }
                    ?: throw IOException("Content length is unknown")
            }
        } ?: throw IOException("Content URI is not available")
        else -> throw IOException("Only local file and content URIs can be shared")
    }

    private fun openStreamAt(offset: Long): InputStream? = when (mediaUri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> {
            val file = File(mediaUri.path ?: return null)
            if (!file.isFile) return null
            FileInputStream(file).also { skipFully(it, offset) }
        }
        "content" -> {
            val descriptor = resolver.openAssetFileDescriptor(mediaUri, "r") ?: return null
            try {
                FileInputStream(descriptor.fileDescriptor).also { stream ->
                    skipFully(stream, descriptor.startOffset + offset)
                }.let { input ->
                    object : InputStream() {
                        override fun read(): Int = input.read()
                        override fun read(buffer: ByteArray, off: Int, len: Int): Int = input.read(buffer, off, len)
                        override fun close() {
                            runCatching { input.close() }
                            descriptor.close()
                        }
                    }
                }
            } catch (failure: Exception) {
                runCatching { descriptor.close() }
                throw failure
            }
        }
        else -> null
    }

    private fun localIpv4Address(): Inet4Address? {
        val connectivity = resolverContextConnectivityManager() ?: return null
        val network = connectivity.activeNetwork ?: return null
        val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
        return addresses.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
            ?: addresses.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    // ContentResolver intentionally holds the application context; recover the
    // same service from the small context reference captured by construction.
    private fun resolverContextConnectivityManager(): ConnectivityManager? =
        connectivityManager

    private val connectivityManager = contextConnectivityManager(context)

    private fun respond(socket: Socket, code: Int, reason: String, extraHeaders: String = "") {
        runCatching {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $code $reason\r\n$extraHeaders" + "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
    }

    private fun readAsciiLine(input: BufferedInputStream, maxBytes: Int): String? {
        val line = ByteArrayOutputStream(minOf(maxBytes, 256))
        while (line.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII.name())
            if (next == '\n'.code) {
                val bytes = line.toByteArray()
                val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            line.write(next)
        }
        throw IOException("HTTP line is too long")
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        workers.shutdownNow()
    }

    private fun parseRange(header: String, length: Long): LongRange? {
        if (!header.startsWith("bytes=", ignoreCase = true) || header.contains(',')) return null
        val spec = header.substringAfter('=').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val firstText = spec.substring(0, dash).trim()
        val lastText = spec.substring(dash + 1).trim()
        if (firstText.isEmpty()) {
            val suffixLength = lastText.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val start = (length - suffixLength).coerceAtLeast(0L)
            return start until length
        }
        val start = firstText.toLongOrNull()?.takeIf { it >= 0L && it < length } ?: return null
        val end = if (lastText.isEmpty()) length - 1L else lastText.toLongOrNull()?.takeIf { it >= start } ?: return null
        return start..end.coerceAtMost(length - 1L)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() < 0) {
                throw IOException("Could not seek to requested byte range")
            } else {
                remaining--
            }
        }
    }

    companion object {
        private const val TOKEN_BYTES = 24
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val TRANSFER_BUFFER_BYTES = 64 * 1024
        private const val MAX_HTTP_LINE_BYTES = 8 * 1024
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_HTTP_HEADER_COUNT = 64
        private val MIME_TYPE_PATTERN = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")

        private fun contextConnectivityManager(context: Context): ConnectivityManager? =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
}
