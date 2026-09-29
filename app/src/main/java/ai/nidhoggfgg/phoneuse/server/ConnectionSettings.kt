package ai.nidhoggfgg.phoneuse.server

import android.content.Context
import java.net.InetAddress
import java.net.URI

/** Addresses are explicit authorities, never resolved from an untrusted request Host. */
object ConnectionAddresses {
    const val PORT = 8443
    const val LOCAL_CONSOLE = "http://127.0.0.1:8443/"

    fun host(address: String): String = if (':' in address) "[${address.removeSurrounding("[", "]")}]" else address

    fun publicHost(value: String): String {
        val text = value.trim().lowercase()
        if (text.isEmpty()) return ""
        require(text.none { it.isWhitespace() } && '%' !in text)
        val uri = URI("https://${host(text)}")
        val parsed = requireNotNull(uri.host).removeSurrounding("[", "]")
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null)
        if (':' in parsed || parsed.all { it.isDigit() || it == '.' }) {
            // Numeric literals only: do not perform DNS queries while validating settings.
            if (':' !in parsed) require(parsed.split('.').let { parts ->
                parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0')) }
            })
            val ip = InetAddress.getByName(parsed)
            require(!ip.isLoopbackAddress && !ip.isAnyLocalAddress && !ip.isMulticastAddress && !ip.isLinkLocalAddress)
        } else {
            require(parsed != "localhost" && !parsed.endsWith(".localhost") && parsed.length <= 253 &&
                parsed.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) })
        }
        return parsed
    }

    fun urls(addresses: List<String>, publicHost: String, allowHttp: Boolean, port: Int = PORT): List<String> =
        (addresses + publicHost.takeIf { it.isNotEmpty() }.orEmpty()).filter { it.isNotEmpty() }.distinct().flatMap {
            listOfNotNull("https://${host(it)}:$port", if (allowHttp) "http://${host(it)}:$port" else null)
        }
}

class ConnectionSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("connection_settings", Context.MODE_PRIVATE)
    var allowInsecureHttp: Boolean
        get() = preferences.getBoolean(ALLOW_HTTP, false)
        set(value) { preferences.edit().putBoolean(ALLOW_HTTP, value).apply() }
    var publicHost: String
        get() = preferences.getString(PUBLIC_HOST, "").orEmpty()
        set(value) { preferences.edit().putString(PUBLIC_HOST, ConnectionAddresses.publicHost(value)).apply() }

    companion object {
        const val ALLOW_HTTP = "allow_insecure_http"
        const val PUBLIC_HOST = "public_host"
    }
}
