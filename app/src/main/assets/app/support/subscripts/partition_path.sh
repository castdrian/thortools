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
    ACTIVE_SLOT="$(normalize_slot "$(getprop ro.boot.slot_suffix)")" || return 1
    [ "$ACTIVE_SLOT" = "$EXPECTED_SLOT" ]
}
