#!/system/bin/sh

SCRIPT_DIR="${0%/*}"
[ "$SCRIPT_DIR" = "$0" ] && SCRIPT_DIR=.
. "$SCRIPT_DIR/partition_path.sh"

WORKING_PATH="${THORTOOLS_WORKING_PATH:-/storage/emulated/0/Android/data/dev.adrian.thortools/files}"
DOWNLOAD_PATH="/storage/emulated/0/Download"
LOG_FILE="${THORTOOLS_LOG_PATH:-$WORKING_PATH/init_boot.restore.log}"

echo "Restore init_boot.img starting..." >> "$LOG_FILE"

if ! require_active_slot "$2"; then
    echo "Active slot changed before init_boot restore" >> "$LOG_FILE"
    exit 2
fi
EXPECTED_SHA256="$3"
BOOT_DEVICE="$(resolve_partition "init_boot$ACTIVE_SLOT")"
BOOT_IMG="${1:-$WORKING_PATH/init_boot$ACTIVE_SLOT.img}"
if [ ! -s "$BOOT_IMG" ]; then
    BOOT_IMG="$DOWNLOAD_PATH/init_boot$ACTIVE_SLOT.img"
fi

if [ -n "$BOOT_DEVICE" ] && verify_image_hash "$BOOT_IMG" "$EXPECTED_SHA256" && image_fits_partition "$BOOT_IMG" "$BOOT_DEVICE"; then
    if ! require_active_slot "$2"; then
        echo "Active slot changed before init_boot restore write" >> "$LOG_FILE"
        exit 2
    fi
    BOOT_DEVICE="$(resolve_partition "init_boot$ACTIVE_SLOT")"
    echo "Restoring $BOOT_IMG..." >> $LOG_FILE
    if [ -n "$BOOT_DEVICE" ] && verify_image_hash "$BOOT_IMG" "$EXPECTED_SHA256" && image_fits_partition "$BOOT_IMG" "$BOOT_DEVICE" && dd if="$BOOT_IMG" of="$BOOT_DEVICE" >> "$LOG_FILE" 2>&1 && sync; then
        echo "Restore init_boot.img complete!" >> "$LOG_FILE"
        exit 0
    fi
    echo "Restore init_boot.img failed!" >> "$LOG_FILE"
else
    echo "$BOOT_IMG not found"
    echo "Could not find restore file"
fi
exit 1
