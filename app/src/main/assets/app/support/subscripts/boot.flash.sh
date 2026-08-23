#!/system/bin/sh

SCRIPT_DIR="${0%/*}"
[ "$SCRIPT_DIR" = "$0" ] && SCRIPT_DIR=.
. "$SCRIPT_DIR/partition_path.sh"

WORKING_PATH="${THORTOOLS_WORKING_PATH:-/storage/emulated/0/Android/data/dev.adrian.thortools/files}"
LOG_FILE="${THORTOOLS_LOG_PATH:-$WORKING_PATH/boot.flash.log}"

echo "Flash rooted boot.img starting..." >> "$LOG_FILE"

if ! require_active_slot "$1"; then
    echo "Active slot changed before boot flash" >> "$LOG_FILE"
    exit 2
fi
EXPECTED_SHA256="$2"
BOOT_IMG="$WORKING_PATH/boot_patched$ACTIVE_SLOT.img"
BOOT_DEVICE="$(resolve_partition "boot$ACTIVE_SLOT")"

if [ -n "$BOOT_DEVICE" ] && verify_image_hash "$BOOT_IMG" "$EXPECTED_SHA256" && image_fits_partition "$BOOT_IMG" "$BOOT_DEVICE"; then
    if ! require_active_slot "$1"; then
        echo "Active slot changed before boot write" >> "$LOG_FILE"
        exit 2
    fi
    BOOT_DEVICE="$(resolve_partition "boot$ACTIVE_SLOT")"
    if [ -n "$BOOT_DEVICE" ] && verify_image_hash "$BOOT_IMG" "$EXPECTED_SHA256" && image_fits_partition "$BOOT_IMG" "$BOOT_DEVICE" && dd if="$BOOT_IMG" of="$BOOT_DEVICE" >> "$LOG_FILE" 2>&1 && sync; then
        echo "Flash rooted boot.img complete!" >> "$LOG_FILE"
        exit 0
    fi
fi

echo "Flash rooted boot.img failed!" >> "$LOG_FILE"
exit 1
