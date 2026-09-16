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
import java.net.ServerSocket
import java.net.Socket

/** Small HTTP/1.1, one request per connection. Plain HTTP for the MVP. */
class HttpServer(private val identities: IdentityStore, private val controller: DeviceController,
    private val assets: android.content.res.AssetManager,
    private val connected: (String) -> Unit) {
    private val workers = ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var listener: ServerSocket? = null
    fun start(port: Int = 8443) {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port), 16)
        } catch (e: Exception) { socket.close(); throw e }
        listener = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = 10_000
                    clients.add(client)
                    try { workers.execute {
                        val timeout = deadlines.schedule({ runCatching { client.close() } }, 40, TimeUnit.SECONDS)
                        try { client.use { handle(it) } } finally { timeout.cancel(false); clients.remove(client) }
                    } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: Exception) { if (!socket.isClosed) stop() }
            }
        }, "phone-use-http").start()
    }
    fun stop() {
        listener?.close(); listener = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
    private data class Response(val status: Int, val body: JSONObject? = null,
        val text: String? = null, val contentType: String = "application/json; charset=utf-8", val allow: String = "POST",
        val cookie: String? = null)
    private fun cookie(token: String, port: Int) =
        "phoneuse_client_$port=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${if (token.isEmpty()) 0 else 34_560_000}"
    private class HttpError(val status: Int, message: String) : RuntimeException(message)
    private fun handle(socket: Socket) {
        try {
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
            val path = request[1]
            // Android ACTION_VIEW launches can mark the public landing page as cross-site.
            // Only GET / is public navigation; scripts, pairing and API still require same origin.
            if (!BrowserAccess.allowed(headers, socket.localAddress.hostAddress.orEmpty(), socket.localPort,
                    publicPage = request[0] == "GET" && path == "/")) {
                throw HttpError(403, "Use the phone's IPv4 address and port; cross-origin access is not allowed")
            }
            if (request[0] == "GET" && path in setOf("/", "/app.js")) {
                val file = if (path == "/") "index.html" else "app.js"
                val type = if (path == "/") "text/html; charset=utf-8" else "text/javascript; charset=utf-8"
                write(socket, Response(200, text = assets.open("web/$file").bufferedReader().use { it.readText() }, contentType = type))
                return
            }
            val pairingPath = path.removePrefix("/browser")
            val isPairing = pairingPath in setOf("/pair/request", "/pair/status")
            if (!isPairing && path !in setOf("/api", "/api/tools", "/mcp", "/browser/session", "/browser/forget"))
                throw HttpError(404, "Unknown endpoint")
            val cookieToken = headers["cookie"]?.split(';')?.map { it.trim() }
                ?.singleOrNull { it.startsWith("phoneuse_client_${socket.localPort}=") }?.substringAfter('=')
            val auth = headers["authorization"]
            val token = if (auth != null) auth.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") else cookieToken
            val client = if (isPairing) null else token?.let(identities::authenticate)
            if (path == "/browser/session" && request[0] == "GET") {
                write(socket, Response(200, json("paired" to (client != null), "client_id" to client),
                    cookie = cookie(if (client != null) token!! else "", socket.localPort)))
                return
            }
            if (!isPairing && client == null) throw HttpError(401, "Invalid or missing client authorization")
            if (request[0] == "GET" && path == "/api/tools") {
                write(socket, Response(200, json("tools" to ToolCatalog.all())))
                return
            }
            if (path == "/api/tools") {
                write(socket, Response(405, json("error" to "Only GET is supported"), allow = "GET"))
                return
            }
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
            val response = if (isPairing) {
                try {
                    if (pairingPath == "/pair/request") Response(202, identities.pairing.create(payload.string("client_name", 80), socket.inetAddress.hostAddress.orEmpty()))
                    else {
                        val result = identities.pairing.poll(payload.string("pairing_id", 80), payload.string("pairing_secret", 128))
                        if (path.startsWith("/browser/") && result.optString("status") == "approved") {
                            val browserToken = result.remove("token") as String
                            result.remove("token_type")
                            Response(200, result, cookie = cookie(browserToken, socket.localPort))
                        } else Response(200, result)
                    }
                } catch (e: DeviceError) {
                    Response(if (e.code == "PAIRING_BUSY") 429 else 400, errorResult(e))
                }
            } else if (path == "/browser/session") {
                // Migrate a previously saved browser Bearer credential without pairing again.
                Response(200, json("paired" to true, "client_id" to client), cookie = cookie(token!!, socket.localPort))
            } else if (path == "/browser/forget") {
                identities.revoke(client!!)
                identities.pairing.revoked(client)
                controller.revokeClient(client)
                Response(200, json("paired" to false), cookie = cookie("", socket.localPort))
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
        catch (e: Exception) {
            android.util.Log.w("PhoneUseHttp", "HTTP request failed: ${e.javaClass.simpleName}", e)
            runCatching { write(socket, Response(500, json("error" to "Request failed"))) }
        }
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
                "instructions" to "Paired clients can call actions directly. Always supply a unique request_id. Read tool results; Android acceptance is not business success.")
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
    private fun write(socket: Socket, response: Response) {
        val bytes = (response.text ?: response.body?.toString())?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val reason = when (response.status) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val headers = "HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\nReferrer-Policy: no-referrer\r\n" +
            (if (response.status == 405) "Allow: ${response.allow}\r\n" else "") +
            (response.cookie?.let { "Set-Cookie: $it\r\n" } ?: "") + "\r\n"
        socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII))
        socket.outputStream.write(bytes); socket.outputStream.flush()
    }
    companion object { val versions = setOf("2025-03-26", "2025-06-18", "2025-11-25") }
}
