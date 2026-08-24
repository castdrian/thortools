package dev.adrian.thortools

import dev.adrian.thortools.utils.resolveRootState
import dev.adrian.thortools.utils.RootUtils
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootUtilsTest {
    @Test
    fun trustsTheLivePrivilegedServiceWhenItIsAvailable() {
        assertTrue(resolveRootState(rootServiceAvailable = true, serviceRooted = true, heuristicRooted = false))
        assertFalse(resolveRootState(rootServiceAvailable = true, serviceRooted = false, heuristicRooted = true))
    }

    @Test
    fun fallsBackToTheLocalHeuristicWhenTheServiceIsUnavailable() {
        assertTrue(resolveRootState(rootServiceAvailable = false, serviceRooted = false, heuristicRooted = true))
        assertFalse(resolveRootState(rootServiceAvailable = false, serviceRooted = true, heuristicRooted = false))
    }

    @Test
    fun prefersTheDpiOverrideReportedByAndroid() {
        assertEquals(
            480,
            RootUtils.parseDpiOutput("Physical density: 420\nOverride density: 480"),
        )
        assertEquals(420, RootUtils.parseDpiOutput("Physical density: 420"))
        assertNull(RootUtils.parseDpiOutput("Override density: unknown"))
    }

    @Test
    fun parsesAnimationReadbackValuesAndRejectsMissingSettings() {
        assertEquals(0.5f, RootUtils.parseAnimationSpeedOutput("0.5"))
        assertNull(RootUtils.parseAnimationSpeedOutput("null"))
        assertNull(RootUtils.parseAnimationSpeedOutput("not-a-number"))
        assertEquals(0.5f, RootUtils.resolveAnimationSpeed(listOf(0.5f, 0.5f, 0.5f)))
        assertNull(RootUtils.resolveAnimationSpeed(listOf(0.5f, 1f, 0.5f)))
    }

    @Test
    fun requiresEveryBundledThorSupportScript() {
        val supportDirectory = Files.createTempDirectory("thortools-support").toFile()
        try {
            val scriptsDirectory = supportDirectory.resolve("subscripts")
            scriptsDirectory.mkdirs()
            val scripts = listOf(
                "partition_path.sh",
                "boot.backup.sh",
                "boot.patch.sh",
                "boot.flash.sh",
                "boot.restore.sh",
                "init_boot.backup.sh",
                "init_boot.patch.sh",
                "init_boot.flash.sh",
                "init_boot.restore.sh",
            )
            scripts.forEach { scriptsDirectory.resolve(it).writeText("#!/system/bin/sh\n") }
            assertTrue(RootUtils.areSupportFilesReady(supportDirectory))
            scriptsDirectory.resolve("boot.flash.sh").delete()
            assertFalse(RootUtils.areSupportFilesReady(supportDirectory))
        } finally {
            supportDirectory.deleteRecursively()
        }
    }
}
