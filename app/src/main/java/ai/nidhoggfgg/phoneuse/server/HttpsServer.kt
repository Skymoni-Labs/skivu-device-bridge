package ai.nidhoggfgg.phoneuse.server

import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Small HTTP/1.1, one request per TLS connection. No disk body spooling or public plaintext listener. */
class HttpsServer(private val identities: IdentityStore, private val controller: DeviceController,
    private val connected: (String) -> Unit) {
    private val workers = ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val clients = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var listener: SSLServerSocket? = null
    fun start(port: Int = 8443) {
        val socket = identities.sslContext().serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            socket.reuseAddress = true
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.bind(InetSocketAddress("0.0.0.0", port), 16)
        } catch (e: Exception) { socket.close(); throw e }
        listener = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept() as SSLSocket
                    client.soTimeout = 10_000
                    clients.add(client)
                    try { workers.execute {
                        val timeout = deadlines.schedule({ runCatching { client.close() } }, 40, TimeUnit.SECONDS)
                        try { client.use { handle(it) } } finally { timeout.cancel(false); clients.remove(client) }
                    } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: Exception) { if (!socket.isClosed) stop() }
            }
        }, "phone-use-https").start()
    }
    fun stop() {
        listener?.close(); listener = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
    private data class Response(val status: Int, val body: JSONObject? = null)
    private class HttpError(val status: Int, message: String) : RuntimeException(message)
    private fun handle(socket: SSLSocket) {
        try {
            socket.startHandshake()
            val input = BufferedInputStream(socket.inputStream)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    if (System.nanoTime() > deadline) throw HttpError(408, "Request read deadline exceeded")
                    val b = input.read()
                    if (b < 0) throw HttpError(400, "Unexpected end of request")
                    if (b == 10) break
                    if (bytes.size() >= 8192) throw HttpError(431, "Header too large")
                    bytes.write(b)
                }
                return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            }
            val request = line().split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1") throw HttpError(400, "HTTP/1.1 required")
            val headers = HashMap<String, String>()
            var total = 0
            while (true) {
                val header = line(); if (header.isEmpty()) break
                total += header.length
                if (total > 16_384 || headers.size >= 64) throw HttpError(431, "Headers too large")
                val index = header.indexOf(':')
                if (index <= 0) throw HttpError(400, "Malformed header")
                val key = header.substring(0, index).lowercase()
                if (headers.put(key, header.substring(index + 1).trim()) != null) throw HttpError(400, "Duplicate header")
            }
            // Browser origins are not supported; this prevents browser-to-LAN cross-origin access.
            if (headers.containsKey("origin")) throw HttpError(403, "Browser origins are not allowed")
            val path = request[1]
            if (path !in setOf("/pair", "/api", "/mcp")) throw HttpError(404, "Unknown endpoint")
            val client = if (path != "/pair") {
                val auth = headers["authorization"] ?: throw HttpError(401, "Bearer token required")
                if (!auth.startsWith("Bearer ")) throw HttpError(401, "Bearer token required")
                identities.authenticate(auth.removePrefix("Bearer ")) ?: throw HttpError(401, "Invalid or revoked token")
            } else null
            if (request[0] != "POST") throw HttpError(405, "Only POST is supported")
            if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                throw HttpError(415, "Content-Type application/json required")
            if (path == "/mcp") {
                val version = headers["mcp-protocol-version"]
                if (version != null && version !in versions) throw HttpError(400, "Unsupported MCP protocol version")
            }
            val body = ByteArrayOutputStream()
            fun readBytes(size: Int) {
                if (size < 0 || size > 65_536 - body.size()) throw HttpError(413, "Body exceeds 64 KiB")
                var remaining = size
                val buffer = ByteArray(4096)
                while (remaining > 0) {
                    if (System.nanoTime() > deadline) throw HttpError(408, "Request read deadline exceeded")
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw HttpError(400, "Incomplete body")
                    body.write(buffer, 0, count); remaining -= count
                }
            }
            val transfer = headers["transfer-encoding"]
            if (transfer != null) {
                if (!transfer.equals("chunked", true) || headers.containsKey("content-length")) throw HttpError(400, "Invalid transfer framing")
                var chunks = 0
                while (true) {
                    if (++chunks > 1024) throw HttpError(413, "Too many chunks")
                    val size = line().substringBefore(';').toIntOrNull(16) ?: throw HttpError(400, "Invalid chunk")
                    if (size == 0) { if (line().isNotEmpty()) throw HttpError(400, "Trailers unsupported"); break }
                    readBytes(size)
                    if (line().isNotEmpty()) throw HttpError(400, "Invalid chunk delimiter")
                }
            } else readBytes(headers["content-length"]?.toIntOrNull() ?: throw HttpError(411, "Content-Length required"))
            val raw = body.toString(Charsets.UTF_8.name())
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { char ->
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) { '"' -> quoted = true; '{', '[' -> { if (++depth > 64) throw HttpError(400, "JSON nesting too deep") }; '}', ']' -> depth-- }
            }
            val payload = try { JSONObject(raw) } catch (_: Exception) { throw HttpError(400, "Invalid JSON object") }
            val response = if (path == "/pair") {
                try { Response(200, identities.pair(payload)) } catch (e: DeviceError) { Response(403, errorResult(e)) }
            } else {
                connected(client!!)
                if (path == "/mcp") mcp(client, payload)
                else {
                    val name = payload.string("tool")
                    if (payload.has("arguments") && payload.opt("arguments") !is JSONObject) throw HttpError(400, "arguments must be an object")
                    val args = payload.optJSONObject("arguments") ?: JSONObject()
                    Response(200, controller.call(client, name, args))
                }
            }
            write(socket, response)
        } catch (e: HttpError) { runCatching { write(socket, Response(e.status, json("error" to e.message))) } }
        catch (e: DeviceError) { runCatching { write(socket, Response(400, errorResult(e))) } }
        catch (_: Exception) { runCatching { write(socket, Response(500, json("error" to "Request failed"))) } }
    }
    private fun mcp(client: String, request: JSONObject): Response {
        val id = request.opt("id")
        fun rpcError(code: Int, message: String) = Response(200, json("jsonrpc" to "2.0", "id" to id,
            "error" to json("code" to code, "message" to message)))
        if (request.optString("jsonrpc") != "2.0" || request.opt("method") !is String) return rpcError(-32600, "Invalid Request")
        val method = request.getString("method")
        if (!request.has("id")) {
            // Notifications cannot execute actions. No implicit control cancellation for JSON-RPC cancellation IDs.
            return Response(202)
        }
        if (id !is String && id !is Number) return rpcError(-32600, "Invalid request id")
        if (request.has("params") && request.opt("params") !is JSONObject) return rpcError(-32602, "params must be an object")
        val params = request.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> json("protocolVersion" to params.optString("protocolVersion").takeIf { it in versions }.orEmpty().ifEmpty { "2025-11-25" },
                "capabilities" to json("tools" to json("listChanged" to false)),
                "serverInfo" to json("name" to "phone-use", "version" to "0.1.0"),
                "instructions" to "Acquire a lease before actions. Always supply a unique request_id. Read tool results; Android acceptance is not business success.")
            "ping" -> JSONObject()
            "tools/list" -> json("tools" to ToolCatalog.all())
            "tools/call" -> {
                val name = params.opt("name") as? String ?: return rpcError(-32602, "Tool name required")
                if (params.has("arguments") && params.opt("arguments") !is JSONObject) return rpcError(-32602, "arguments must be an object")
                if ((0 until ToolCatalog.all().length()).none { ToolCatalog.all().getJSONObject(it).getString("name") == name })
                    return rpcError(-32602, "Unknown tool")
                val value = controller.call(client, name, params.optJSONObject("arguments") ?: JSONObject())
                val content = JSONArray()
                // Images are also emitted as MCP image blocks for clients with visual models.
                val copy = JSONObject(value.toString())
                val observation = if (name == "observe") copy else copy.optJSONObject("observation")
                val screenshot = observation?.optJSONObject("screenshot")
                if (screenshot?.optBoolean("available") == true) {
                    content.put(json("type" to "image", "mimeType" to screenshot.getString("mime_type"), "data" to screenshot.remove("data_base64")))
                }
                content.put(json("type" to "text", "text" to copy.toString()))
                json("content" to content, "isError" to value.has("error"))
            }
            else -> return rpcError(-32601, "Method not found")
        }
        return Response(200, json("jsonrpc" to "2.0", "id" to id, "result" to result))
    }
    private fun write(socket: SSLSocket, response: Response) {
        val bytes = response.body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val reason = when (response.status) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val headers = "HTTP/1.1 ${response.status} $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            (if (response.status == 405) "Allow: POST\r\n" else "") + "\r\n"
        socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII))
        socket.outputStream.write(bytes); socket.outputStream.flush()
    }
    companion object { val versions = setOf("2025-03-26", "2025-06-18", "2025-11-25") }
}
