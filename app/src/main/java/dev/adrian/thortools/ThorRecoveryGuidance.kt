package dev.adrian.thortools

object ThorRecoveryGuidance {
    fun forSnapshot(snapshot: ThorSnapshot): String = when {
        !snapshot.profile.isThor ->
            "Diagnostics only: no mutating action is available on this device."
        snapshot.operation.status == OperationStatus.RUNNING ->
            "Operation in progress. Keep the Thor connected and wait for the result."
        snapshot.operation.status == OperationStatus.INTERRUPTED ->
            "Recovery required: acknowledge the recovery record, verify the active slot and image hashes, then retry when the dashboard is ready."
        !snapshot.stateReadHealthy ->
            "Thor system state could not be read. Refresh before changing the device."
        snapshot.operation.rebootRequired ->
            "Reboot the Thor before starting another operation. Wait for the dashboard to read the new boot state."
        snapshot.operation.status == OperationStatus.FAILURE ->
            "The last operation failed. Review its message and the capability checks before retrying."
        !snapshot.rootServiceAvailable ->
            "Diagnostics only until the Thor privileged root service is available."
        snapshot.moduleSyncState == ThorModuleSyncState.FAILED ->
            "ThorTools could not synchronize its Magisk module. Verify root access, then retry the module setting before rebooting."
        snapshot.moduleSyncState == ThorModuleSyncState.PENDING ->
            "ThorTools module synchronization is pending. Keep the Thor rooted and wait for the dashboard to report it synced."
        snapshot.activeSlot !in setOf("_a", "_b") ->
            "Recovery operations are blocked until the active Thor slot can be read."
        !snapshot.initBootAvailable && !snapshot.bootAvailable ->
            "Recovery operations are blocked until a supported Thor boot partition can be read."
        !snapshot.profile.supports(ThorCapability.BATTERY_STATE) ->
            "Image operations are blocked until the Thor battery state can be read."
        snapshot.batteryPercent < 35 ->
            "Charge the Thor to at least 35% before starting an image operation."
        !snapshot.backupDestinationWritable ->
            "Image operations are blocked until the Thor recovery folder is writable."
        snapshot.rooted && !snapshot.stockRestoreAvailable ->
            "The Thor is rooted without a verified stock restore source. Do not update until a stock image is secured."
        snapshot.rooted && snapshot.stockRestoreAvailable ->
            "Keep the verified stock image before OTA or service work, and restore stock before updating."
        !snapshot.backupAvailable || !snapshot.stockBackupCoverageReady ->
            "Create verified stock backups for every available slot before preparing a root patch."
        snapshot.patchedBackupAvailable && !snapshot.rooted ->
            "A verified active-slot patch is ready. Review the hashes and flash only the active slot."
        !snapshot.magiskInstalled ->
            "Install Magisk after the stock backup is verified, then return to EZ Root."
        else ->
            "Review the capability checks before starting an image operation."
    }
}
