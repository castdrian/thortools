package dev.adrian.thortools

enum class ThorVariant {
    LITE,
    BASE,
    PRO,
    MAX,
    UNKNOWN,
}

enum class ThorCapability {
    ROOT_SERVICE,
    ROOTED,
    MAGISK,
    ACTIVE_SLOT,
    INIT_BOOT_PARTITION,
    BOOT_PARTITION,
    BATTERY_STATE,
    BACKUP_DESTINATION,
    SUPPORT_FILES,
}

data class DeviceProperties(
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val product: String = "",
    val systemDevice: String = "",
    val systemName: String = "",
    val buildProduct: String = "",
    val board: String = "",
    val hardware: String = "",
    val soc: String = "",
    val platform: String = "",
    val firmware: String = "",
    val buildId: String = "",
    val buildDisplayId: String = "",
    val buildDate: String = "",
    val buildFingerprint: String = "",
    val serial: String = "",
    val slot: String = "",
) {
    val buildIdentity: String
        get() = buildFingerprint.ifBlank {
            listOf(buildId, buildDisplayId, firmware, buildDate)
                .filter(String::isNotBlank)
                .joinToString("|")
        }
}

data class ThorDeviceProfile(
    val properties: DeviceProperties,
    val isThor: Boolean,
    val variant: ThorVariant,
    val capabilities: Set<ThorCapability> = emptySet(),
) {
    val displayName: String
        get() = when {
            !isThor -> properties.model.ifBlank { "Unknown device" }
            variant == ThorVariant.UNKNOWN -> "AYN Thor"
            else -> "AYN Thor ${variant.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }

    fun supports(capability: ThorCapability): Boolean = capability in capabilities
}

object DeviceProfile {
    const val THOR_MODEL = "AYN Thor"
    const val UPPER_WIDTH_PIXELS = 1920
    const val UPPER_HEIGHT_PIXELS = 1080
    const val LOWER_WIDTH_PIXELS = 1240
    const val LOWER_HEIGHT_PIXELS = 1080
    const val UPPER_DIAGONAL_MILLIMETRES = 152.4f
    const val LOWER_DIAGONAL_MILLIMETRES = 99.6f
    const val LOWER_MINIMUM_TEXT_SP = 18f
    const val THOR_DISPLAY_ROTATION = 0

    private val thorPattern = Regex("(^|\\s)(ayn\\s*)?thor(?:\\s*(lite|base|pro|max))?(\\s|$)")
    private val compactThorPattern = Regex("^(?:ayn)?(?:odin2)?thor(?:lite|base|pro|max)?$")
    private val identifierSeparatorPattern = Regex("[^a-z0-9]+")

    fun detect(properties: DeviceProperties): ThorDeviceProfile {
        val searchable = listOf(
            properties.manufacturer,
            properties.brand,
            properties.model,
            properties.device,
            properties.product,
            properties.systemDevice,
            properties.systemName,
            properties.buildProduct,
            properties.board,
            properties.hardware,
            properties.soc,
            properties.platform,
        ).joinToString(" ").normalized()
        val isThor = searchable.containsThorIdentifier()
        return ThorDeviceProfile(properties, isThor, variantFor(properties))
    }

    fun variantFor(value: String): ThorVariant {
        val normalized = value.normalized()
        if (!normalized.containsThorIdentifier()) return ThorVariant.UNKNOWN
        return when {
            normalized.contains("lite") -> ThorVariant.LITE
            normalized.contains("max") -> ThorVariant.MAX
            normalized.contains("pro") -> ThorVariant.PRO
            normalized.contains("base") -> ThorVariant.BASE
            else -> ThorVariant.UNKNOWN
        }
    }

    fun variantFor(properties: DeviceProperties): ThorVariant {
        val explicitVariant = variantFor(
            listOf(
                properties.model,
                properties.device,
                properties.product,
                properties.systemDevice,
                properties.systemName,
                properties.buildProduct,
                properties.board,
                properties.hardware,
                properties.soc,
                properties.platform,
            ).joinToString(" "),
        )
        if (explicitVariant != ThorVariant.UNKNOWN) return explicitVariant
        if (isLitePlatform(properties)) return ThorVariant.LITE
        return ThorVariant.UNKNOWN
    }

    private fun isLitePlatform(properties: DeviceProperties): Boolean {
        val platform = listOf(
            properties.board,
            properties.hardware,
            properties.soc,
            properties.platform,
        ).joinToString(" ").normalized()
        return litePlatformPattern.containsMatchIn(platform)
    }

    private fun String.normalized(): String = identifierSeparatorPattern.replace(lowercase(), " ").trim()

    private fun String.containsThorIdentifier(): Boolean =
        thorPattern.containsMatchIn(this) || split(' ').any(compactThorPattern::matches)

    private val litePlatformPattern = Regex("(^|\\s)(sm8250|kona)(\\s|$)")

    fun isThorLowerDisplay(
        widthPixels: Int,
        heightPixels: Int,
        rotation: Int = THOR_DISPLAY_ROTATION,
    ): Boolean = when {
        widthPixels == LOWER_WIDTH_PIXELS && heightPixels == LOWER_HEIGHT_PIXELS ->
            rotation == THOR_DISPLAY_ROTATION
        widthPixels == LOWER_HEIGHT_PIXELS && heightPixels == LOWER_WIDTH_PIXELS ->
            rotation == 1 || rotation == 3
        else -> false
    }

    fun isThorUpperDisplay(
        widthPixels: Int,
        heightPixels: Int,
        rotation: Int = THOR_DISPLAY_ROTATION,
    ): Boolean =
        widthPixels == UPPER_WIDTH_PIXELS &&
            heightPixels == UPPER_HEIGHT_PIXELS &&
            rotation == THOR_DISPLAY_ROTATION

    fun normalizeSlot(value: String): String = when (value.trim().lowercase()) {
        "a", "_a" -> "_a"
        "b", "_b" -> "_b"
        else -> ""
    }

    fun displayKind(widthPixels: Int, heightPixels: Int): String = if (
        widthPixels <= 1400 && heightPixels >= 900 && widthPixels.toFloat() / heightPixels < 1.4f
    ) {
        "lower"
    } else {
        "upper"
    }

    fun minimumReadablePixels(
        widthPixels: Int,
        heightPixels: Int,
        scaledDensity: Float = 1f,
        minimumTextSp: Float = LOWER_MINIMUM_TEXT_SP,
    ): Float = if (displayKind(widthPixels, heightPixels) == "lower") {
        maxOf(32f, minimumTextSp * scaledDensity)
    } else {
        24f
    }
}
