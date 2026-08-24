package dev.adrian.thortools

import dev.adrian.thortools.utils.RecoveryImageRecord

object ThorDiagnosticsReport {
    fun build(
        snapshot: ThorSnapshot,
        records: List<RecoveryImageRecord>,
        recoveryPath: String,
        logPath: String,
    ): String = buildString {
        appendLine("ThorTools diagnostics")
        appendLine("device.isThor=${snapshot.profile.isThor}")
        appendLine("device.variant=${snapshot.profile.variant.name}")
        appendLine("device.displayName=${snapshot.profile.displayName.safeValue()}")
        appendProperty("manufacturer", snapshot.profile.properties.manufacturer)
        appendProperty("brand", snapshot.profile.properties.brand)
        appendProperty("model", snapshot.profile.properties.model)
        appendProperty("device", snapshot.profile.properties.device)
        appendProperty("product", snapshot.profile.properties.product)
        appendProperty("systemDevice", snapshot.profile.properties.systemDevice)
        appendProperty("systemName", snapshot.profile.properties.systemName)
        appendProperty("buildProduct", snapshot.profile.properties.buildProduct)
        appendProperty("board", snapshot.profile.properties.board)
        appendProperty("hardware", snapshot.profile.properties.hardware)
        appendProperty("soc", snapshot.profile.properties.soc)
        appendProperty("platform", snapshot.profile.properties.platform)
        appendProperty("firmware", snapshot.profile.properties.firmware)
        appendProperty("buildId", snapshot.profile.properties.buildId)
        appendProperty("buildDisplayId", snapshot.profile.properties.buildDisplayId)
        appendProperty("buildDate", snapshot.profile.properties.buildDate)
        appendProperty("buildFingerprint", snapshot.profile.properties.buildFingerprint)
        appendProperty("serial", snapshot.profile.properties.serial)
        appendProperty("flashLocked", snapshot.profile.properties.flashLocked)
        appendProperty("bootloaderDeviceState", snapshot.profile.properties.bootloaderDeviceState)
        appendProperty("verifiedBootState", snapshot.profile.properties.verifiedBootState)
        appendLine("bootloaderUnlocked=${snapshot.profile.properties.bootloaderUnlocked}")
        appendProperty("activeSlot", snapshot.activeSlot)
        appendProperty("availableBootSlots", snapshot.availableBootSlots.sorted().joinToString(","))
        appendProperty(
            "availablePartitionsBySlot",
            snapshot.availablePartitionsBySlot.entries
                .sortedBy { it.key }
                .joinToString(",") { (slot, partitions) ->
                    "$slot:${partitions.sorted().joinToString("+")}"
                },
        )
        appendProperty("kernelVersion", snapshot.kernelVersion)
        appendLine("batteryPercent=${snapshot.batteryPercent}")
        appendLine("lcdDensity=${snapshot.lcdDensity}")
        appendLine("volumeSteps=${snapshot.volumeSteps}")
        appendLine("animationSpeed=${snapshot.animationSpeed}")
        appendLine("rootServiceAvailable=${snapshot.rootServiceAvailable}")
        appendLine("rooted=${snapshot.rooted}")
        appendLine("magiskInstalled=${snapshot.magiskInstalled}")
        appendLine("moduleSyncState=${snapshot.moduleSyncState.name}")
        appendLine("bootOverrideState=${snapshot.bootOverrideState.name}")
        appendLine("initBootAvailable=${snapshot.initBootAvailable}")
        appendLine("bootAvailable=${snapshot.bootAvailable}")
        appendLine("backupDestinationWritable=${snapshot.backupDestinationWritable}")
        appendLine("stateReadHealthy=${snapshot.stateReadHealthy}")
        appendLine("backupAvailable=${snapshot.backupAvailable}")
        appendLine("stockRestoreAvailable=${snapshot.stockRestoreAvailable}")
        appendLine("patchedBackupAvailable=${snapshot.patchedBackupAvailable}")
        appendProperty("stockBackupSlots", snapshot.stockBackupSlots.sorted().joinToString(","))
        appendProperty("stockRecoverySlots", snapshot.stockRecoverySlots.sorted().joinToString(","))
        appendProperty("patchedBackupSlots", snapshot.patchedBackupSlots.sorted().joinToString(","))
        appendProperty("capabilities", snapshot.profile.capabilities.map { it.name }.sorted().joinToString(","))
        appendLine("operation=${snapshot.operation.operation?.name ?: "NONE"}")
        appendLine("operationStatus=${snapshot.operation.status.name}")
        appendLine("operationRebootRequired=${snapshot.operation.rebootRequired}")
        appendProperty("operationMessage", snapshot.operation.message)
        appendDisplay("upper", snapshot.displayDiagnostics.upper)
        appendDisplay("lower", snapshot.displayDiagnostics.lower)
        appendLine("display.defaultDisplayId=${snapshot.displayDiagnostics.defaultDisplayId}")
        appendLine("display.mode=${snapshot.displayDiagnostics.mode.name}")
        appendLine("dualDisplayReady=${snapshot.displayDiagnostics.dualDisplayReady}")
        appendProperty("recoveryPath", recoveryPath)
        appendProperty("logPath", logPath)
        records.sortedWith(compareBy({ it.patched }, { it.partition }, { it.slot }, { it.fileName })).forEachIndexed { index, record ->
            val prefix = "recovery[$index]"
            appendProperty("$prefix.fileName", record.fileName)
            appendProperty("$prefix.slot", record.slot)
            appendProperty("$prefix.partition", record.partition)
            appendLine("$prefix.patched=${record.patched}")
            appendLine("$prefix.size=${record.size}")
            appendProperty("$prefix.sha256", record.sha256)
            appendProperty("$prefix.buildIdentity", record.buildIdentity)
            appendProperty("$prefix.sourceSha256", record.sourceSha256)
        }
    }

    private fun StringBuilder.appendProperty(name: String, value: String) {
        appendLine("$name=${value.safeValue()}")
    }

    private fun StringBuilder.appendDisplay(name: String, panel: ThorDisplayPanel) {
        appendLine("display.$name.id=${panel.displayId}")
        appendLine("display.$name.width=${panel.widthPixels}")
        appendLine("display.$name.height=${panel.heightPixels}")
        appendLine("display.$name.refreshRateHz=${panel.refreshRateHz}")
        appendLine("display.$name.rotation=${panel.rotation}")
        appendLine("display.$name.present=${panel.present}")
    }

    private fun String.safeValue(): String = replace('\r', ' ').replace('\n', ' ').trim()
}
