#!/system/bin/sh

resolve_partition() {
    partition_name="$1"
    for base_path in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name /dev/block/platform/*/*/*/by-name; do
        partition_path="$base_path/$partition_name"
        if [ -e "$partition_path" ]; then
            printf '%s' "$partition_path"
            return 0
        fi
    done
    return 1
}

normalize_slot() {
    case "$1" in
        a|_a) printf '%s' "_a" ;;
        b|_b) printf '%s' "_b" ;;
        *) return 1 ;;
    esac
}

require_active_slot() {
    EXPECTED_SLOT="$(normalize_slot "$1")" || return 1
    ACTIVE_PROPERTY="$(getprop ro.boot.slot_suffix)"
    [ -n "$ACTIVE_PROPERTY" ] || ACTIVE_PROPERTY="$(getprop ro.boot.slot)"
    ACTIVE_SLOT="$(normalize_slot "$ACTIVE_PROPERTY")" || return 1
    [ "$ACTIVE_SLOT" = "$EXPECTED_SLOT" ]
}

verify_image_hash() {
    IMAGE_PATH="$1"
    EXPECTED_HASH="$2"
    case "$EXPECTED_HASH" in
        ''|*[!0-9a-fA-F]*) return 1 ;;
    esac
    ACTUAL_HASH="$(sha256sum "$IMAGE_PATH" 2>/dev/null | sed 's/[[:space:]].*$//' | tr '[:upper:]' '[:lower:]')"
    [ "$ACTUAL_HASH" = "$(printf '%s' "$EXPECTED_HASH" | tr '[:upper:]' '[:lower:]')" ]
}

verify_copy_hash() {
    SOURCE_PATH="$1"
    DESTINATION_PATH="$2"
    [ -s "$SOURCE_PATH" ] && [ -s "$DESTINATION_PATH" ] || return 1
    SOURCE_HASH="$(sha256sum "$SOURCE_PATH" 2>/dev/null | sed 's/[[:space:]].*$//' | tr '[:upper:]' '[:lower:]')"
    verify_image_hash "$DESTINATION_PATH" "$SOURCE_HASH"
}

image_fits_partition() {
    IMAGE_PATH="$1"
    DEVICE_PATH="$2"
    [ -s "$IMAGE_PATH" ] || return 1
    IMAGE_BYTES="$(wc -c < "$IMAGE_PATH" 2>/dev/null | tr -d '[:space:]')"
    case "$IMAGE_BYTES" in
        ''|*[!0-9]*) return 1 ;;
    esac
    PARTITION_BYTES="$(blockdev --getsize64 "$DEVICE_PATH" 2>/dev/null | tr -d '[:space:]')"
    case "$PARTITION_BYTES" in
        ''|*[!0-9]*|0)
            DEVICE_NAME="${DEVICE_PATH##*/}"
            SECTOR_COUNT="$(cat "/sys/class/block/$DEVICE_NAME/size" 2>/dev/null | tr -d '[:space:]')"
            case "$SECTOR_COUNT" in
                ''|*[!0-9]*|0) return 1 ;;
            esac
            PARTITION_BYTES=$((SECTOR_COUNT * 512))
            ;;
    esac
    [ "$PARTITION_BYTES" -gt 0 ] && [ "$IMAGE_BYTES" -le "$PARTITION_BYTES" ]
}
