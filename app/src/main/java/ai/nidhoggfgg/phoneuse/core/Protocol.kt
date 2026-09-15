package ai.nidhoggfgg.phoneuse.core

import org.json.JSONArray
import org.json.JSONObject

class DeviceError(val code: String, override val message: String) : RuntimeException(message)
fun fail(code: String, message: String): Nothing = throw DeviceError(code, message)
fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
fun JSONObject.string(key: String, max: Int = 256): String {
    val value = opt(key) as? String ?: fail("INVALID_ARGUMENT", "$key must be a string")
    if (value.isEmpty() || value.length > max) fail("INVALID_ARGUMENT", "$key length must be 1..$max")
    return value
}
fun JSONObject.integer(key: String, default: Int, min: Int, max: Int): Int {
    if (!has(key)) return default
    val number = opt(key) as? Number ?: fail("INVALID_ARGUMENT", "$key must be an integer")
    val value = number.toDouble()
    if (!value.isFinite() || value % 1.0 != 0.0 || value < min || value > max)
        fail("INVALID_ARGUMENT", "$key must be in $min..$max")
    return value.toInt()
}
fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonical(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    null, JSONObject.NULL -> "null"
    else -> value.toString()
}
fun errorResult(error: Throwable, state: String = "failed"): JSONObject {
    val known = error as? DeviceError
    return json("state" to state, "error" to json(
        "code" to (known?.code ?: "INTERNAL_ERROR"),
        "message" to (known?.message ?: "Unexpected device error; observe before retrying")
    ))
}

interface DeviceBackend {
    fun capabilities(): JSONObject
    fun observe(args: JSONObject): JSONObject
    fun listApps(): JSONObject
    fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject
    fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject
}
