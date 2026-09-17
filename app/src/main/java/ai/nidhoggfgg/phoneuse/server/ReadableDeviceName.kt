package ai.nidhoggfgg.phoneuse.server

/** Use names exposed by the OS; model identifiers are not a reliable marketing-name database. */
internal fun readableDeviceName(
    systemName: String?,
    manufacturerName: String?,
    manufacturer: String,
    model: String,
    emulator: Boolean
): String {
    fun valid(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) &&
            !it.equals("unknown", ignoreCase = true)
    }
    val hardwareModel = valid(model)
    val names = listOfNotNull(valid(systemName), valid(manufacturerName))
    // A manufacturer-provided friendly name is more useful than the raw model repeated globally.
    names.firstOrNull { !it.equals(hardwareModel, ignoreCase = true) }?.let { return it }
    if (emulator) return "Android Emulator"
    names.firstOrNull()?.let { return it }
    val brand = valid(manufacturer)?.replaceFirstChar { it.titlecase() }
    return when {
        hardwareModel == null -> brand?.let { "$it Android" } ?: "Android"
        brand == null || hardwareModel.startsWith(brand, ignoreCase = true) -> hardwareModel
        else -> "$brand $hardwareModel".take(80)
    }
}
