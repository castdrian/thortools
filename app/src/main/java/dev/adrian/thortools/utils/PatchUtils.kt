package dev.adrian.thortools.utils

import android.content.Context
import dev.adrian.thortools.DeviceProfile
import java.io.File

data class PartitionWriteResult(
    val success: Boolean,
    val attempted: Boolean,
)

object PatchUtils {
    private val slots = listOf("_a", "_b")
    private val partitions = listOf("init_boot", "boot")

    fun backupBoot(context: Context, expectedSlot: String): Boolean {
        val slot = stableSlot(expectedSlot) ?: return false
        if (!RootUtils.hasPServer() || !FileUtils.isBackupDestinationWritable(context)) return false
        val initBootRequired = slots.any { RootUtils.hasPartition(context, "init_boot", it) }
        val bootRequired = slots.any { RootUtils.hasPartition(context, "boot", it) }
        val requiredSlots = slots.filter { slot ->
            (initBootRequired && RootUtils.hasPartition(context, "init_boot", slot)) ||
                (bootRequired && RootUtils.hasPartition(context, "boot", slot))
        }.toSet()
        if (requiredSlots.isEmpty()) return false
        val initBootBackedUp = !initBootRequired || RootUtils.runRootScript(context, "init_boot.backup.sh") == "0"
        val bootBackedUp = !bootRequired || RootUtils.runRootScript(context, "boot.backup.sh") == "0"
        if (!initBootBackedUp || !bootBackedUp) return false
        if (stableSlot(slot) == null) return false
        if (!RecoveryManifestStore.recordLocalImages(context, localImageInputs(context, patched = false))) return false
        return hasCompleteSlotCoverage(requiredSlots, stockBackupSlots(context))
    }

    internal fun hasCompleteSlotCoverage(requiredSlots: Set<String>, backedUpSlots: Set<String>): Boolean =
        requiredSlots.isNotEmpty() && requiredSlots.all(backedUpSlots::contains)

    fun availableBootSlots(context: Context): Set<String> = slots.filter { slot ->
        preferredPartition(context, slot) != null
    }.toSet()

    fun stockBackupSlots(context: Context): Set<String> = slots.filter { slot ->
        val partition = preferredPartition(context, slot) ?: return@filter false
        RecoveryManifestStore.hasVerifiedStockCopies(
            context = context,
            slot = slot,
            partition = partition,
            localPath = stockPath(context, partition, slot),
            downloadPath = downloadPath(partition, slot),
            buildIdentity = currentBuildIdentity(),
        )
    }.toSet()

    fun patchedBackupSlots(context: Context): Set<String> = slots.filter { slot ->
        val partition = preferredPartition(context, slot) ?: return@filter false
        RecoveryManifestStore.hasVerifiedPatchedImage(
            context = context,
            slot = slot,
            partition = partition,
            localPath = patchedPath(context, partition, slot),
            stockPath = stockPath(context, partition, slot),
            buildIdentity = currentBuildIdentity(),
        )
    }.toSet()

    fun checkActiveSlotBackupExists(context: Context): Boolean {
        val slot = validSlot() ?: return false
        val partition = preferredPartition(context, slot) ?: return false
        return RecoveryManifestStore.hasVerifiedStockImage(
            context = context,
            slot = slot,
            partition = partition,
            localPath = stockPath(context, partition, slot),
            downloadPath = downloadPath(partition, slot),
            buildIdentity = currentBuildIdentity(),
        )
    }

    fun checkActiveSlotRestoreExists(context: Context): Boolean {
        val slot = validSlot() ?: return false
        val partition = preferredPartition(context, slot) ?: return false
        val buildIdentity = currentBuildIdentity()
        return RecoveryManifestStore.hasVerifiedStockImage(
            context = context,
            slot = slot,
            partition = partition,
            localPath = stockPath(context, partition, slot),
            downloadPath = downloadPath(partition, slot),
            buildIdentity = buildIdentity,
        )
    }

    fun checkBootMagiskExists(context: Context): Boolean {
        val slot = validSlot() ?: return false
        val partition = preferredPartition(context, slot) ?: return false
        return RecoveryManifestStore.hasVerifiedPatchedImage(
            context = context,
            slot = slot,
            partition = partition,
            localPath = patchedPath(context, partition, slot),
            stockPath = stockPath(context, partition, slot),
            buildIdentity = currentBuildIdentity(),
        )
    }

    fun hasPatchedCache(context: Context): Boolean =
        RecoveryManifestStore.records(context).any { it.patched } || slots.any { slot ->
            partitions.any { partition -> nonEmpty(patchedPath(context, partition, slot)) }
        }

    fun imagePaths(context: Context): Map<String, String> = slots.flatMap { slot ->
        listOf(
            "init_boot$slot.img" to stockPath(context, "init_boot", slot),
            "boot$slot.img" to stockPath(context, "boot", slot),
            "init_boot_patched$slot.img" to patchedPath(context, "init_boot", slot),
            "boot_patched$slot.img" to patchedPath(context, "boot", slot),
        )
    }.filter { (_, path) -> nonEmpty(path) }.toMap()

    fun imageHashes(context: Context): Map<String, String> = imagePaths(context).mapNotNull { (name, path) ->
        RecoveryManifestStore.hashFile(path)?.let { name to it }
    }.toMap()

    fun clearBootCache(context: Context): Boolean {
        var success = true
        val removedNames = RecoveryManifestStore.records(context)
            .filter { it.patched }
            .mapTo(mutableSetOf()) { it.fileName }
        slots.forEach { slot ->
            partitions.forEach { partition ->
                val path = patchedPath(context, partition, slot)
                if (File(path).exists()) {
                    if (!FileUtils.deleteFile(path)) success = false else removedNames += File(path).name
                }
            }
        }
        if (!RecoveryManifestStore.removePatchedRecords(context, removedNames)) success = false
        return success
    }

    fun flashBoot(context: Context, expectedSlot: String): PartitionWriteResult {
        val slot = stableSlot(expectedSlot) ?: return PartitionWriteResult(false, false)
        val partition = preferredPartition(context, slot) ?: return PartitionWriteResult(false, false)
        val buildIdentity = currentBuildIdentity()
        val patchedHash = RecoveryManifestStore.verifiedPatchedHash(
                context = context,
                slot = slot,
                partition = partition,
                localPath = patchedPath(context, partition, slot),
                stockPath = stockPath(context, partition, slot),
                buildIdentity = buildIdentity,
            ) ?: return PartitionWriteResult(false, false)
        return PartitionWriteResult(
            success = RootUtils.runRootScript(context, "$partition.flash.sh", listOf(slot, patchedHash)) == "0",
            attempted = true,
        )
    }

    fun patchBoot(context: Context, expectedSlot: String): String {
        val magiskPath = MagiskUtil.getMagiskPath(context)
        val slot = stableSlot(expectedSlot) ?: return ""
        val partition = preferredPartition(context, slot) ?: return ""
        val buildIdentity = currentBuildIdentity()
        if (magiskPath.isBlank()) return ""
        val source = stockPath(context, partition, slot)
        val sourceHash = RecoveryManifestStore.verifiedStockHash(
            context = context,
            slot = slot,
            partition = partition,
            localPath = source,
            buildIdentity = buildIdentity,
        ) ?: return ""
        val output = patchedPath(context, partition, slot)
        if (patchPartition(context, partition, output, magiskPath, slot) &&
            stableSlot(slot) != null &&
            recordPatchedImage(context, partition, slot, output, buildIdentity, sourceHash)
        ) return output
        FileUtils.deleteFile(output)
        return ""
    }

    fun restoreBoot(context: Context, expectedSlot: String): PartitionWriteResult {
        val slot = stableSlot(expectedSlot) ?: return PartitionWriteResult(false, false)
        val partition = preferredPartition(context, slot) ?: return PartitionWriteResult(false, false)
        val buildIdentity = currentBuildIdentity()
        val source = RecoveryManifestStore.verifiedStockSourceInfo(
            context = context,
            slot = slot,
            partition = partition,
            localPath = stockPath(context, partition, slot),
            downloadPath = downloadPath(partition, slot),
            buildIdentity = buildIdentity,
        ) ?: return PartitionWriteResult(false, false)
        return PartitionWriteResult(
            success = RootUtils.runRootScript(context, "$partition.restore.sh", listOf(source.path, slot, source.sha256)) == "0",
            attempted = true,
        )
    }

    private fun patchPartition(context: Context, partition: String, output: String, magiskPath: String, slot: String): Boolean {
        FileUtils.deleteFile(output)
        return RootUtils.runRootScript(context, "$partition.patch.sh", listOf(magiskPath, slot)) == "0" && nonEmpty(output)
    }

    private fun recordPatchedImage(
        context: Context,
        partition: String,
        slot: String,
        path: String,
        buildIdentity: String,
        sourceHash: String,
    ): Boolean = RecoveryManifestStore.recordLocalImages(
        context,
        listOf(
            RecoveryImageInput(
                fileName = File(path).name,
                slot = slot,
                partition = partition,
                patched = true,
                path = path,
                buildIdentity = buildIdentity,
                sourceSha256 = sourceHash,
            ),
        ),
    )

    private fun localImageInputs(context: Context, patched: Boolean): List<RecoveryImageInput> {
        val buildIdentity = currentBuildIdentity()
        return slots.flatMap { slot ->
            partitions.mapNotNull { partition ->
                if (!RootUtils.hasPartition(context, partition, slot)) return@mapNotNull null
                val path = if (patched) patchedPath(context, partition, slot) else stockPath(context, partition, slot)
                if (!nonEmpty(path)) return@mapNotNull null
                RecoveryImageInput(
                    fileName = File(path).name,
                    slot = slot,
                    partition = partition,
                    patched = patched,
                    path = path,
                    buildIdentity = buildIdentity,
                )
            }
        }
    }

    private fun stockPath(context: Context, partition: String, slot: String): String =
        FileUtils.getPathBackup(context, "/$partition$slot.img")

    private fun patchedPath(context: Context, partition: String, slot: String): String =
        FileUtils.getPathBackup(context, "/${partition}_patched$slot.img")

    private fun downloadPath(partition: String, slot: String): String =
        FileUtils.getPathDownload("/$partition$slot.img")

    private fun currentBuildIdentity(): String = SystemUtils.getDeviceProperties().buildIdentity

    private fun validSlot(): String? = SystemUtils.getPropSlot().takeIf { it == "_a" || it == "_b" }

    internal fun normalizeStableSlot(expectedSlot: String, actualSlot: String): String? {
        val normalizedExpected = DeviceProfile.normalizeSlot(expectedSlot)
        val normalizedActual = DeviceProfile.normalizeSlot(actualSlot)
        return normalizedExpected.takeIf { it.isNotBlank() && it == normalizedActual }
    }

    private fun stableSlot(expectedSlot: String): String? =
        normalizeStableSlot(expectedSlot, SystemUtils.getPropSlot())

    private fun preferredPartition(context: Context, slot: String): String? = selectPartition(
        initBootAvailable = RootUtils.hasPartition(context, "init_boot", slot),
        bootAvailable = RootUtils.hasPartition(context, "boot", slot),
    )

    internal fun selectPartition(initBootAvailable: Boolean, bootAvailable: Boolean): String? = when {
        initBootAvailable -> "init_boot"
        bootAvailable -> "boot"
        else -> null
    }

    private fun nonEmpty(path: String): Boolean = File(path).isFile && File(path).length() > 0
}
