package ai.nidhoggfgg.phoneuse.server

import java.net.InetAddress
import java.net.URI

/** Only the receiving IP and an explicitly configured public host may address this server. */
object BrowserAccess {
    fun allowed(headers: Map<String, String>, address: String, port: Int, publicPage: Boolean = false,
        secure: Boolean = true, publicHost: String = ""): Boolean {
        val host = headers["host"] ?: return false
        val allowedHosts = setOfNotNull("${ConnectionAddresses.host(address)}:$port",
            publicHost.takeIf { it.isNotEmpty() }?.let { "${ConnectionAddresses.host(it)}:$port" })
        val authority = normalizedAuthority(host) ?: return false
        return allowedHosts.any { normalizedAuthority(it) == authority } && (publicPage ||
            ((headers["origin"] == null || headers["origin"] == "${if (secure) "https" else "http"}://$host") &&
                headers["sec-fetch-site"] !in setOf("cross-site", "same-site")))
    }

    private fun normalizedAuthority(value: String): String? = runCatching {
        val uri = URI("https://$value")
        val host = requireNotNull(uri.host).removeSurrounding("[", "]").lowercase()
        require(uri.rawUserInfo == null && uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null && '%' !in host)
        val canonical = if (':' in host) InetAddress.getByName(host).hostAddress else host
        "$canonical:${uri.port}"
    }.getOrNull()
}
