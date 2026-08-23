#!/system/bin/sh

SCRIPT_DIR="${0%/*}"
[ "$SCRIPT_DIR" = "$0" ] && SCRIPT_DIR=.
. "$SCRIPT_DIR/partition_path.sh"

WORKING_PATH="${THORTOOLS_WORKING_PATH:-/storage/emulated/0/Android/data/dev.adrian.thortools/files}"
DOWNLOAD_PATH="/storage/emulated/0/Download"
LOG_FILE="$WORKING_PATH/boot.restore.log"

echo "Restore boot.img starting..." > $LOG_FILE

if ! require_active_slot "$2"; then
    echo "Active slot changed before boot restore" >> "$LOG_FILE"
    exit 2
fi
BOOT_DEVICE="$(resolve_partition "boot$ACTIVE_SLOT")"
BOOT_IMG="${1:-$WORKING_PATH/boot$ACTIVE_SLOT.img}"
if [ ! -s "$BOOT_IMG" ]; then
    BOOT_IMG="$DOWNLOAD_PATH/boot$ACTIVE_SLOT.img"
fi

if [ -n "$BOOT_DEVICE" ] && [ -s "$BOOT_IMG" ]; then
    echo "Restoring $BOOT_IMG..." >> $LOG_FILE
    if dd if="$BOOT_IMG" of="$BOOT_DEVICE" >> "$LOG_FILE" 2>&1 && sync; then
        echo "Restore boot.img complete!" >> "$LOG_FILE"
        exit 0
    fi
    echo "Restore boot.img failed!" >> "$LOG_FILE"
else
    echo "$BOOT_IMG not found"
    echo "Could not find restore file"
fi
exit 1
