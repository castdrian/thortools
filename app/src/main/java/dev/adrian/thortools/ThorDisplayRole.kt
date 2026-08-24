package dev.adrian.thortools

enum class ThorDisplayRole {
    UPPER,
    LOWER,
    UNKNOWN,

    ;

    fun opposite(): ThorDisplayRole = when (this) {
        UPPER -> LOWER
        LOWER -> UPPER
        UNKNOWN -> UNKNOWN
    }

    companion object {
        fun fromGeometry(widthPixels: Int, heightPixels: Int, rotation: Int): ThorDisplayRole = when {
            DeviceProfile.isThorUpperDisplay(widthPixels, heightPixels, rotation) -> UPPER
            DeviceProfile.isThorLowerDisplay(widthPixels, heightPixels, rotation) -> LOWER
            else -> UNKNOWN
        }
    }
}

internal fun resolvePrimaryDisplayRole(
    observedRole: ThorDisplayRole,
    isDefaultDisplay: Boolean,
    hasUpperDisplay: Boolean,
    hasLowerDisplay: Boolean,
): ThorDisplayRole = when {
    observedRole != ThorDisplayRole.UNKNOWN -> observedRole
    !isDefaultDisplay -> ThorDisplayRole.UNKNOWN
    hasUpperDisplay -> ThorDisplayRole.UPPER
    hasLowerDisplay -> ThorDisplayRole.LOWER
    else -> ThorDisplayRole.UNKNOWN
}
