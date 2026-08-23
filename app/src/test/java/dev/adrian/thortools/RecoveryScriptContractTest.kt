package dev.adrian.thortools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecoveryScriptContractTest {
    @Test
    fun stockBackupsRequireVerifiedDownloadCopies() {
        listOf("boot.backup.sh", "init_boot.backup.sh").forEach { name ->
            val source = script(name)
            assertTrue(source.contains("THORTOOLS_LOG_PATH"))
            assertTrue(source.contains("failed=0"))
            assertTrue(source.contains("expected=0"))
            assertTrue(source.contains("expected=\$((expected + 1))"))
            assertTrue(source.contains("copied=\$((copied + 1))"))
            assertTrue(source.contains("DOWNLOAD_FILE="))
            assertTrue(source.contains("verify_copy_hash \"\$OUTPUT_FILE\" \"\$DOWNLOAD_FILE\""))
            assertTrue(source.contains("[ \"\$expected\" -gt 0 ] && [ \"\$copied\" -eq \"\$expected\" ] && [ \"\$failed\" -eq 0 ]"))
        }
    }

    @Test
    fun patchedImagesRemainUsableWhenDownloadCopyFails() {
        listOf("boot.patch.sh", "init_boot.patch.sh").forEach { name ->
            val source = script(name)
            assertTrue(source.contains("THORTOOLS_LOG_PATH"))
            assertTrue(source.contains("[ -s \"\$PATCHED_BOOT\" ]"))
            assertTrue(source.contains("Download copy unavailable; the app-local patched image remains usable"))
        }
    }

    @Test
    fun patchScriptsReverifyTheStockImageBeforeMagiskRuns() {
        listOf("boot.patch.sh", "init_boot.patch.sh").forEach { name ->
            val source = script(name)
            assertTrue(source.contains("EXPECTED_SHA256=\"\$3\""))
            assertTrue(source.contains("verify_image_hash \"\$BOOT_IMG\" \"\$EXPECTED_SHA256\""))
            assertTrue(source.contains("changed before patch"))
            assertTrue(source.contains("changed during patch"))
            assertTrue(source.contains("rm -f \"\$MAGISK_NEWBOOT\""))
        }
    }

    @Test
    fun patchScriptsRejectAMagiskFailureEvenIfOutputRemains() {
        listOf("boot.patch.sh", "init_boot.patch.sh").forEach { name ->
            val source = script(name)
            assertTrue(source.contains("if ! KEEPVERITY=true KEEPFORCEENCRYPT=true sh \"\$MAGISK_PATCH\" \"\$BOOT_IMG\""))
            assertTrue(source.contains("Magisk patch command failed"))
        }
    }

    @Test
    fun restoreScriptsAcceptAnExplicitVerifiedSource() {
        assertTrue(script("boot.restore.sh").contains("THORTOOLS_LOG_PATH"))
        assertTrue(script("init_boot.restore.sh").contains("THORTOOLS_LOG_PATH"))
        assertTrue(script("boot.restore.sh").contains("BOOT_IMG=\"\${1:-\$WORKING_PATH/boot\$ACTIVE_SLOT.img}\""))
        assertTrue(script("init_boot.restore.sh").contains("BOOT_IMG=\"\${1:-\$WORKING_PATH/init_boot\$ACTIVE_SLOT.img}\""))
    }

    @Test
    fun mutatingScriptsAbortWhenTheActiveSlotChanges() {
        listOf(
            "boot.patch.sh" to "require_active_slot \"\$2\"",
            "init_boot.patch.sh" to "require_active_slot \"\$2\"",
            "boot.flash.sh" to "require_active_slot \"\$1\"",
            "init_boot.flash.sh" to "require_active_slot \"\$1\"",
            "boot.restore.sh" to "require_active_slot \"\$2\"",
            "init_boot.restore.sh" to "require_active_slot \"\$2\"",
        ).forEach { (name, guard) ->
            val source = script(name)
            assertTrue(source.contains("require_active_slot"))
            assertTrue(source.contains(guard))
            assertTrue(source.contains("exit 2"))
        }
        val partitionSource = script("partition_path.sh")
        assertTrue(partitionSource.contains("normalize_slot"))
        assertTrue(partitionSource.contains("EXPECTED_SLOT"))
        assertTrue(partitionSource.contains("ACTIVE_SLOT"))
        assertTrue(partitionSource.contains("getprop ro.boot.slot_suffix"))
        assertTrue(partitionSource.contains("getprop ro.boot.slot"))
    }

    @Test
    fun mutatingScriptsVerifyImageHashAndPartitionFitBeforeWriting() {
        listOf("boot.flash.sh", "init_boot.flash.sh", "boot.restore.sh", "init_boot.restore.sh").forEach { name ->
            val source = script(name)
            assertTrue(source.contains("verify_image_hash"))
            assertTrue(source.contains("image_fits_partition"))
            assertTrue(source.contains("EXPECTED_SHA256"))
        }
        val partitionSource = script("partition_path.sh")
        assertTrue(partitionSource.contains("sha256sum"))
        assertTrue(partitionSource.contains("verify_copy_hash"))
        assertTrue(partitionSource.contains("blockdev --getsize64"))
        assertTrue(partitionSource.contains("/sys/class/block/\$DEVICE_NAME/size"))
    }

    @Test
    fun flashAndRestoreScriptsRecheckSlotAndResolvePartitionBeforeWriting() {
        listOf(
            "boot.flash.sh" to "before boot write",
            "init_boot.flash.sh" to "before init_boot write",
            "boot.restore.sh" to "before boot restore write",
            "init_boot.restore.sh" to "before init_boot restore write",
        ).forEach { (name, message) ->
            val source = script(name)
            assertTrue(source.split("require_active_slot").size - 1 >= 2)
            assertTrue(source.contains(message))
            assertTrue(source.contains("BOOT_DEVICE=\"\$(resolve_partition"))
        }
    }

    @Test
    fun rootScriptRunnerSharesTheAdvertisedLatestLogPath() {
        val source = File("src/main/java/dev/adrian/thortools/utils/RootUtils.kt").readText()
        assertTrue(source.contains("THORTOOLS_LOG_PATH="))
        assertTrue(source.contains(": > \${shellQuote(logPath)} &&"))
        assertTrue(source.contains(">> \${shellQuote(logPath)} 2>&1"))
        assertTrue(source.contains("clearLog: Boolean = true"))
        assertTrue(source.contains("val logPreparation = if (clearLog)"))
    }

    @Test
    fun backupCombinesInitBootAndBootResultsInOneLog() {
        val source = File("src/main/java/dev/adrian/thortools/utils/PatchUtils.kt").readText()
        assertTrue(source.contains("var clearLog = true"))
        assertTrue(source.contains("runRootScript(context, script, clearLog = clearLog)"))
        assertTrue(source.contains("clearLog = false"))
    }

    @Test
    fun patchUtilsPassesValidatedSlotsToMutatingScripts() {
        val patchUtilsSource = File("src/main/java/dev/adrian/thortools/utils/PatchUtils.kt").readText()
        assertTrue(patchUtilsSource.contains("fun backupBoot(context: Context, expectedSlot: String)"))
        assertTrue(patchUtilsSource.contains("fun patchBoot(context: Context, expectedSlot: String)"))
        assertTrue(patchUtilsSource.contains("fun flashBoot(context: Context, expectedSlot: String)"))
        assertTrue(patchUtilsSource.contains("fun restoreBoot(context: Context, expectedSlot: String)"))
        assertTrue(patchUtilsSource.contains("stableSlot(expectedSlot)"))
        assertTrue(patchUtilsSource.contains("\"\$partition.flash.sh\", listOf(slot, patchedHash)"))
        assertTrue(patchUtilsSource.contains("\"\$partition.patch.sh\", listOf(magiskPath, slot, sourceHash)"))
        assertTrue(patchUtilsSource.contains("\"\$partition.restore.sh\", listOf(source.path, slot, source.sha256)"))
    }

    private fun script(name: String): String =
        File("src/main/assets/app/support/subscripts/$name").readText()
}
