package ai.nidhoggfgg.phoneuse.server

import android.content.Context
import android.os.Build
import ai.nidhoggfgg.phoneuse.core.fail
import ai.nidhoggfgg.phoneuse.core.json
import org.json.JSONObject
import java.util.UUID

/** Installation identity is separate from network addresses and paired-client credentials.
 * Shared preferences are excluded from both cloud backup and device transfer by the app rules.
 */
@android.annotation.SuppressLint("ApplySharedPref")
class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    val id: String = synchronized(lock) {
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            if (!preferences.edit().putString("device_id", it).commit())
                fail("STORAGE_ERROR", "Could not save device identity")
        }
    }
    val name: String get() = preferences.getString("name", null) ?: Build.MODEL

    fun rename(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > 80 || normalized.any { it.isISOControl() })
            fail("INVALID_ARGUMENT", "Device name must contain 1–80 printable characters")
        if (!preferences.edit().putString("name", normalized).commit())
            fail("STORAGE_ERROR", "Could not save device name")
    }

    fun describe(serviceInstanceId: String): JSONObject = json(
        "device_id" to id,
        "name" to name,
        "model" to Build.MODEL,
        "android_version" to Build.VERSION.RELEASE,
        "is_emulator" to isEmulator(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT, Build.HARDWARE),
        "service_instance_id" to serviceInstanceId
    )

    companion object {
        private val lock = Any()
        internal fun isEmulator(fingerprint: String, model: String, product: String, hardware: String): Boolean =
            fingerprint.startsWith("generic", true) || fingerprint.startsWith("unknown", true) ||
                model.contains("Emulator", true) || model.contains("Android SDK", true) ||
                model.startsWith("sdk_", true) || product.startsWith("sdk", true) ||
                product.contains("emulator", true) || hardware in setOf("goldfish", "ranchu")
    }
}
