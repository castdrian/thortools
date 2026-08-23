package dev.adrian.thortools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import androidx.lifecycle.Lifecycle
import dev.adrian.thortools.utils.RecoveryImageInput
import dev.adrian.thortools.utils.RecoveryManifestStore
import dev.adrian.thortools.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThorToolsInstrumentedTest {
    @Test
    fun usesThorToolsApplicationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.startsWith("dev.adrian.thortools"))
    }

    @Test
    fun keepsBootReceiverPrivateToTheApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            0,
        )
        assertFalse(receiver.exported)
    }

    @Test
    fun keepsBootOverridesDisabledUntilTheUserConfiguresThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("ThorToolsModulePolicyTest", android.content.Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().commit()
            assertFalse(AppSettings.hasDpiOverride(preferences))
            assertFalse(AppSettings.hasAnimationSpeedOverride(preferences))
            assertFalse(AppSettings.hasModuleSettings(preferences))
            preferences.edit().putInt(AppSettings.DPI_KEY, 320).commit()
            preferences.edit().putFloat(AppSettings.ANIMATIONS_SPEED_KEY, 0.5f).commit()
            preferences.edit().putInt(AppSettings.VOLUME_STEPS_KEY, 20).commit()
            assertTrue(AppSettings.hasDpiOverride(preferences))
            assertTrue(AppSettings.hasAnimationSpeedOverride(preferences))
            assertTrue(AppSettings.hasModuleSettings(preferences))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun debugBackendPresentsThorCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = SystemBackendFactory.create(context).snapshot()
        assertEquals(ThorVariant.PRO, snapshot.profile.variant)
        assertTrue(snapshot.rootServiceAvailable)
        assertTrue(snapshot.backupDestinationWritable)
        assertTrue(snapshot.displayDiagnostics.dualDisplayReady)
        assertEquals("1920 x 1080", snapshot.displayDiagnostics.upper.geometryLabel)
        assertEquals("1240 x 1080", snapshot.displayDiagnostics.lower.geometryLabel)
    }

    @Test
    fun clearingPatchedCacheKeepsStockBackups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = SystemBackendFactory.create(context)
        assertTrue(backend.perform(ThorOperation.BACKUP).success)
        assertTrue(backend.snapshot().stockRestoreAvailable)
        assertTrue(backend.perform(ThorOperation.PATCH).success)
        assertTrue(backend.snapshot().patchedBackupAvailable)
        assertTrue(backend.snapshot().patchedCacheAvailable)
        assertTrue(backend.perform(ThorOperation.CLEAR_CACHE).success)
        val snapshot = backend.snapshot()
        assertEquals(setOf("_a", "_b"), snapshot.stockBackupSlots)
        assertTrue(snapshot.patchedBackupSlots.isEmpty())
        assertFalse(snapshot.patchedCacheAvailable)
    }

    @Test
    fun debugBackendPersistsRecoveryManifestStatesForTheDashboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val directory = context.getExternalFilesDir(null)
        preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
        try {
            listOf(
                "boot_a.img",
                "boot_b.img",
                "init_boot_a.img",
                "init_boot_b.img",
                "boot_patched_a.img",
                "boot_patched_b.img",
                "init_boot_patched_a.img",
                "init_boot_patched_b.img",
            ).forEach { File(directory, it).delete() }
            val backend = SystemBackendFactory.create(context)
            assertTrue(backend.perform(ThorOperation.BACKUP).success)
            val stockRecords = RecoveryManifestStore.records(context).filterNot { it.patched }
            assertEquals(4, stockRecords.size)
            assertTrue(stockRecords.all { it.buildIdentity == "AYN/Thor/ThorTools:13/THOR_DEBUG/10001:userdebug/test-keys" })
            assertTrue(backend.perform(ThorOperation.PATCH).success)
            val statuses = RecoveryManifestStore.statuses(context, "AYN/Thor/ThorTools:13/THOR_DEBUG/10001:userdebug/test-keys")
            assertTrue(statuses.any { it.record.patched && it.currentBuild && it.localCopyVerified })
            assertTrue(backend.perform(ThorOperation.CLEAR_CACHE).success)
            assertTrue(RecoveryManifestStore.records(context).none { it.patched })
        } finally {
            preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
            directory?.listFiles()?.filter { it.name.contains("_a.img") || it.name.contains("_b.img") }?.forEach(File::delete)
        }
    }

    @Test
    fun recoveryManifestRejectsTamperingAndBuildChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val image = File(context.filesDir, "recovery-manifest-test.img")
        val patched = File(context.filesDir, "recovery-manifest-test-patched.img")
        preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
        try {
            image.writeText("stock")
            assertTrue(
                RecoveryManifestStore.recordLocalImages(
                    context,
                    listOf(
                        RecoveryImageInput(
                            fileName = image.name,
                            slot = "_a",
                            partition = "boot",
                            patched = false,
                            path = image.path,
                            buildIdentity = "test-fingerprint",
                        ),
                    ),
                ),
            )
            assertEquals(
                image.path,
                RecoveryManifestStore.verifiedStockSource(
                    context,
                    "_a",
                    "boot",
                    image.path,
                    image.path,
                    "test-fingerprint",
                ),
            )
            image.writeText("tampered")
            assertNull(
                RecoveryManifestStore.verifiedStockSource(
                    context,
                    "_a",
                    "boot",
                    image.path,
                    image.path,
                    "test-fingerprint",
                ),
            )
            image.writeText("stock")
            assertNull(
                RecoveryManifestStore.verifiedStockSource(
                    context,
                    "_a",
                    "boot",
                    image.path,
                    image.path,
                    "other-fingerprint",
                ),
            )
            patched.writeText("patched")
            val stockHash = RecoveryManifestStore.hashFile(image.path) ?: error("stock hash missing")
            assertTrue(
                RecoveryManifestStore.recordLocalImages(
                    context,
                    listOf(
                        RecoveryImageInput(
                            fileName = patched.name,
                            slot = "_a",
                            partition = "boot",
                            patched = true,
                            path = patched.path,
                            buildIdentity = "test-fingerprint",
                            sourceSha256 = stockHash,
                        ),
                    ),
                ),
            )
            assertTrue(
                RecoveryManifestStore.hasVerifiedPatchedImage(
                    context,
                    "_a",
                    "boot",
                    patched.path,
                    image.path,
                    "test-fingerprint",
                ),
            )
            image.writeText("new-stock")
            assertFalse(
                RecoveryManifestStore.hasVerifiedPatchedImage(
                    context,
                    "_a",
                    "boot",
                    patched.path,
                    image.path,
                    "test-fingerprint",
                ),
            )
        } finally {
            preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
            image.delete()
            patched.delete()
        }
    }

    @Test
    fun recoveryManifestStatusLabelsTheCurrentVerifiedCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val image = File(context.getExternalFilesDir(null), "recovery-status-test-${System.currentTimeMillis()}.img")
        val download = File(FileUtils.getPathDownload("/${image.name}"))
        preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
        try {
            image.writeText("stock")
            assertTrue(
                RecoveryManifestStore.recordLocalImages(
                    context,
                    listOf(
                        RecoveryImageInput(
                            fileName = image.name,
                            slot = "_a",
                            partition = "boot",
                            patched = false,
                            path = image.path,
                            buildIdentity = "current-fingerprint",
                        ),
                    ),
                ),
            )
            var status = RecoveryManifestStore.statuses(context, "current-fingerprint").single()
            assertTrue(status.currentBuild)
            assertTrue(status.localCopyVerified)
            assertFalse(status.downloadCopyVerified)
            assertFalse(
                RecoveryManifestStore.hasVerifiedStockCopies(
                    context,
                    "_a",
                    "boot",
                    image.path,
                    download.path,
                    "current-fingerprint",
                ),
            )
            assertEquals(image.path, status.localPath)
            assertEquals(FileUtils.getPathDownload("/${image.name}"), status.downloadPath)
            image.writeText("tampered")
            status = RecoveryManifestStore.statuses(context, "current-fingerprint").single()
            assertFalse(status.localCopyVerified)
        } finally {
            preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
            image.delete()
            download.delete()
        }
    }

    @Test
    fun thorAvdExposesLowerDisplayGeometry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayManager = context.getSystemService(DisplayManager::class.java)
        assertTrue(
            displayManager.displays.any { display ->
                DeviceProfile.isThorLowerDisplay(display.mode.physicalWidth, display.mode.physicalHeight)
            },
        )
    }

    @Test
    fun launchesTheDualDisplayActivityWithoutASecondaryWindowCrash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
        }
        scenario.close()
    }

    @Test
    fun restoresInterruptedOperationStateWithoutRepeatingBackendWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        preferences.edit()
            .putString(AppSettings.JOURNAL_OPERATION_KEY, ThorOperation.FLASH.name)
            .putString(AppSettings.JOURNAL_MESSAGE_KEY, "flash was interrupted")
            .commit()
        var performCount = 0
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult {
                performCount += 1
                return OperationResult(true, "unexpected backend execution")
            }
        }
        try {
            val session = ThorSession(context, backend)
            assertEquals(OperationStatus.INTERRUPTED, session.snapshot.operation.status)
            session.load()
            assertEquals(OperationStatus.INTERRUPTED, session.snapshot.operation.status)
            assertEquals(0, performCount)
            session.run(CoroutineScope(Dispatchers.Unconfined), ThorOperation.FLASH)
            assertEquals(0, performCount)
            assertTrue(session.acknowledgeInterruptedOperation())
            assertEquals(OperationStatus.IDLE, session.snapshot.operation.status)
            assertFalse(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
            assertFalse(preferences.contains(AppSettings.JOURNAL_MESSAGE_KEY))
        } finally {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .commit()
        }
    }

    @Test
    fun marksDisplayOperationCancellationAsInterrupted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult {
                started.countDown()
                release.await()
                return OperationResult(true, "unexpected completion")
            }
        }
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.SET_DPI, "320")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scope.cancel()
            release.countDown()
            scope.coroutineContext[Job]?.join()
            assertEquals(OperationStatus.INTERRUPTED, session.snapshot.operation.status)
            assertTrue(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
            assertTrue(session.acknowledgeInterruptedOperation())
        } finally {
            release.countDown()
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .commit()
        }
    }

    @Test
    fun persistsRebootRequiredStateUntilTheThorRestarts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(true, "write complete", rebootRequired = true)
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.SET_DPI, "320")
            withTimeout(5_000L) {
                while (!session.snapshot.operation.rebootRequired) delay(10L)
            }
            assertTrue(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            val restored = ThorSession(context, backend)
            restored.load()
            assertTrue(restored.snapshot.operation.rebootRequired)
            assertEquals(
                "Reboot the Thor before starting another operation",
                ThorOperationGuard.validate(restored.snapshot, ThorOperation.SET_DPI),
            )
        } finally {
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun persistsRebootLockAfterAnExplicitRebootRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(true, "reboot requested", rebootRequired = operation == ThorOperation.REBOOT)
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.REBOOT)
            withTimeout(5_000L) {
                while (!session.snapshot.operation.rebootRequired) delay(10L)
            }
            assertTrue(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            assertEquals(
                "Reboot the Thor before starting another operation",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
            val restored = ThorSession(context, backend)
            restored.load()
            assertEquals(
                "The Thor reboot has already been requested; wait for it to restart",
                ThorOperationGuard.validate(restored.snapshot, ThorOperation.REBOOT),
            )
        } finally {
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun keepsRebootLockAfterAnInterruptedWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val baseline = SystemBackendFactory.create(context).snapshot().copy(
            backupAvailable = true,
            stockRestoreAvailable = true,
            patchedBackupAvailable = true,
            stockBackupSlots = setOf("_a", "_b"),
        )
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult {
                started.countDown()
                release.await()
                return OperationResult(true, "unexpected completion")
            }
        }
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.FLASH)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scope.cancel()
            release.countDown()
            withTimeout(5_000L) {
                while (session.snapshot.operation.status != OperationStatus.INTERRUPTED) delay(10L)
            }
            assertTrue(session.snapshot.operation.rebootRequired)
            assertTrue(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            assertTrue(session.acknowledgeInterruptedOperation())
            assertTrue(session.snapshot.operation.rebootRequired)
            assertEquals(
                "Reboot the Thor before starting another operation",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
        } finally {
            release.countDown()
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun marksAnUnexpectedWriteFailureAsRebootRequired() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot().copy(
            backupAvailable = true,
            stockRestoreAvailable = true,
            patchedBackupAvailable = true,
            stockBackupSlots = setOf("_a", "_b"),
        )
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult {
                throw IllegalStateException("write service failed")
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.FLASH)
            withTimeout(5_000L) {
                while (session.snapshot.operation.status != OperationStatus.FAILURE) delay(10L)
            }
            assertTrue(session.snapshot.operation.rebootRequired)
            assertTrue(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            assertEquals(
                "Reboot the Thor before starting another operation",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
        } finally {
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }
}
