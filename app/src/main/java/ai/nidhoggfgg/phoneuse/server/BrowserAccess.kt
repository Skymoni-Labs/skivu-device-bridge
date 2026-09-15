package ai.nidhoggfgg.phoneuse.server

/** No domain Host aliases or cross-origin access, including DNS rebinding to the LAN. */
object BrowserAccess {
    fun allowed(headers: Map<String, String>, address: String, port: Int, publicPage: Boolean = false): Boolean {
        val host = "$address:$port"
        return headers["host"] == host &&
            (publicPage || ((headers["origin"] == null || headers["origin"] == "http://$host") &&
            headers["sec-fetch-site"] != "cross-site"))
    }
}
