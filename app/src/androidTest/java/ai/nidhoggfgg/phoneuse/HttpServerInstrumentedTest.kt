package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.device.AndroidBackend
import ai.nidhoggfgg.phoneuse.server.HttpServer
import ai.nidhoggfgg.phoneuse.server.IdentityStore
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/** Real production HTTP server running on Android. */
@RunWith(AndroidJUnit4::class)
class HttpServerInstrumentedTest {
    @Test fun httpServesWebPairingApiAndMcp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = IdentityStore(context)
        val controller = DeviceController(AndroidBackend(context), SystemClock::elapsedRealtime, identity::exists) {}
        val server = HttpServer(identity, controller, context.assets) {}
        fun request(path: String, body: JSONObject? = null, token: String? = null, site: String = "same-origin"): Pair<Int, String> {
            return Socket("127.0.0.1", 18443).use { socket ->
                socket.soTimeout = 10_000
                val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val header = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: 127.0.0.1:18443\r\n" +
                    "Origin: http://127.0.0.1:18443\r\nSec-Fetch-Site: $site\r\nConnection: close\r\n" +
                    (token?.let { "Authorization: Bearer $it\r\n" } ?: "") +
                    (if (body != null) "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") + "\r\n"
                socket.outputStream.write(header.toByteArray(Charsets.US_ASCII))
                socket.outputStream.write(bytes); socket.outputStream.flush()
                val response = socket.inputStream.readBytes().toString(Charsets.UTF_8)
                response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
            }
        }
        var clientId: String? = null
        try {
            server.start(18443)
            val page = request("/")
            assertEquals(200, page.first); assertTrue(page.second.contains("Phone Use"))
            assertEquals(200, request("/app.js").first)
            assertEquals(200, request("/", site = "cross-site").first)
            assertEquals(403, request("/pair/request", json("client_name" to "cross-site"), site = "cross-site").first)
            assertEquals(401, request("/api/tools").first)
            val pending = request("/pair/request", json("client_name" to "HTTP regression test"))
            assertEquals(202, pending.first)
            val created = JSONObject(pending.second)
            val args = json("pairing_id" to created.getString("pairing_id"), "pairing_secret" to created.getString("pairing_secret"))
            assertEquals("pending", JSONObject(request("/pair/status", args).second).getString("status"))
            identity.pairing.decide(created.getString("pairing_id"), true)
            val credential = JSONObject(request("/pair/status", args).second)
            clientId = credential.getString("client_id")
            val token = credential.getString("token")
            assertTrue(JSONObject(request("/api/tools", token = token).second).getJSONArray("tools").length() > 0)
            val status = request("/api", json("tool" to "get_status"), token)
            assertEquals(200, status.first); assertFalse(JSONObject(status.second).has("error"))
            val rpc = request("/mcp", json("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"), token)
            assertEquals(200, rpc.first); assertTrue(JSONObject(rpc.second).has("result"))
        } finally {
            clientId?.let { identity.revoke(it) }
            identity.pairing.clear(); server.stop(); controller.stop()
        }
    }
}
