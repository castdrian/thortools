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
import dev.adrian.thortools.utils.SystemUtils
import dev.adrian.thortools.utils.RootUtils
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
import java.util.concurrent.atomic.AtomicBoolean
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
    fun derivesPendingModuleStateForConfiguredPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("ThorToolsModuleStateTest", android.content.Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().commit()
            assertEquals(ThorModuleSyncState.NOT_CONFIGURED, AppSettings.getModuleSyncState(preferences))
            preferences.edit().putInt(AppSettings.VOLUME_STEPS_KEY, 20).commit()
            assertEquals(ThorModuleSyncState.PENDING, AppSettings.getModuleSyncState(preferences))
            AppSettings.setModuleSyncState(preferences, ThorModuleSyncState.FAILED)
            assertEquals(ThorModuleSyncState.FAILED, AppSettings.getModuleSyncState(preferences))
            AppSettings.setModuleSyncState(preferences, ThorModuleSyncState.SYNCED)
            assertEquals(ThorModuleSyncState.SYNCED, AppSettings.getModuleSyncState(preferences))
            assertEquals(
                ThorModuleSyncState.UNVERIFIED,
                AppSettings.getModuleSyncState(preferences, verificationAvailable = false),
            )
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun restoresModuleSettingsWithoutCreatingOverridesThatWereAbsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val originalVolumeConfigured = preferences.contains(AppSettings.VOLUME_STEPS_KEY)
        val originalVolume = AppSettings.getVolumeSteps(preferences)
        val originalBootConfigured = preferences.contains(AppSettings.SKIP_BOOT_ANIMATION_KEY)
        val originalBoot = AppSettings.getSkipBootAnimation(preferences)
        try {
            preferences.edit()
                .remove(AppSettings.VOLUME_STEPS_KEY)
                .remove(AppSettings.SKIP_BOOT_ANIMATION_KEY)
                .commit()
            AppSettings.setVolumeSteps(preferences, 30)
            AppSettings.restoreVolumeSteps(preferences, false, 15)
            assertFalse(preferences.contains(AppSettings.VOLUME_STEPS_KEY))
            AppSettings.setVolumeSteps(preferences, 30)
            AppSettings.restoreVolumeSteps(preferences, true, 15)
            assertEquals(15, AppSettings.getVolumeSteps(preferences))
            AppSettings.setSkipBootAnimation(preferences, true)
            AppSettings.restoreSkipBootAnimation(preferences, false, false)
            assertFalse(preferences.contains(AppSettings.SKIP_BOOT_ANIMATION_KEY))
            AppSettings.setSkipBootAnimation(preferences, true)
            AppSettings.restoreSkipBootAnimation(preferences, true, false)
            assertFalse(AppSettings.getSkipBootAnimation(preferences))
        } finally {
            if (originalVolumeConfigured) {
                AppSettings.setVolumeSteps(preferences, originalVolume)
            } else {
                preferences.edit().remove(AppSettings.VOLUME_STEPS_KEY).commit()
            }
            if (originalBootConfigured) {
                AppSettings.setSkipBootAnimation(preferences, originalBoot)
            } else {
                preferences.edit().remove(AppSettings.SKIP_BOOT_ANIMATION_KEY).commit()
            }
        }
    }

    @Test
    fun debugBackendPresentsThorCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = SystemBackendFactory.create(context).snapshot()
        assertEquals(ThorVariant.PRO, snapshot.profile.variant)
        assertEquals("AYN", snapshot.profile.properties.manufacturer)
        assertEquals("AYN", snapshot.profile.properties.brand)
        assertEquals("kalama", snapshot.profile.properties.device)
        assertEquals("kalama", snapshot.profile.properties.product)
        assertEquals("kalama", snapshot.profile.properties.board)
        assertEquals("qcs8550", snapshot.profile.properties.soc)
        assertEquals("sm8550", snapshot.profile.properties.platform)
        assertTrue(snapshot.rootServiceAvailable)
        assertTrue(snapshot.backupDestinationWritable)
        assertTrue(RootUtils.areSupportFilesReady(context))
        assertTrue(snapshot.profile.supports(ThorCapability.SUPPORT_FILES))
        assertTrue(snapshot.displayDiagnostics.dualDisplayReady)
        assertEquals(ThorDisplayMode.DUAL, snapshot.displayDiagnostics.mode)
        assertEquals("1920 x 1080", snapshot.displayDiagnostics.upper.geometryLabel)
        assertEquals("1240 x 1080", snapshot.displayDiagnostics.lower.geometryLabel)
    }

    @Test
    fun debugBackendMirrorsRuntimeThorDisplayIdentityAndRefreshRate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val upper = displayManager.displays.firstOrNull { display ->
            DeviceProfile.isThorUpperDisplay(display.mode.physicalWidth, display.mode.physicalHeight, display.rotation)
        } ?: error("Thor upper display is missing")
        val lower = displayManager.displays.firstOrNull { display ->
            DeviceProfile.isThorLowerDisplay(display.mode.physicalWidth, display.mode.physicalHeight, display.rotation)
        } ?: error("Thor lower display is missing")
        val snapshot = SystemBackendFactory.create(context).snapshot()
        assertEquals(upper.displayId, snapshot.displayDiagnostics.upper.displayId)
        assertEquals(lower.displayId, snapshot.displayDiagnostics.lower.displayId)
        assertEquals(upper.mode.refreshRate, snapshot.displayDiagnostics.upper.refreshRateHz, 0.01f)
        assertEquals(lower.mode.refreshRate, snapshot.displayDiagnostics.lower.refreshRateHz, 0.01f)
        assertEquals(0, snapshot.displayDiagnostics.defaultDisplayId)
    }

    @Test
    fun debugBackendPersistsDisplayTweaksAcrossSnapshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = SystemBackendFactory.create(context)
        val original = backend.snapshot()
        try {
            assertTrue(backend.perform(ThorOperation.SET_DPI, "333").success)
            assertEquals(333, backend.snapshot().lcdDensity)
            assertEquals(ThorBootOverrideState.APPLIED, backend.snapshot().bootOverrideState)
            assertTrue(backend.perform(ThorOperation.SET_ANIMATION, "0.5").success)
            assertEquals(0.5f, backend.snapshot().animationSpeed)
            assertEquals(ThorBootOverrideState.APPLIED, backend.snapshot().bootOverrideState)
            val recreatedBackend = SystemBackendFactory.create(context)
            assertEquals(333, recreatedBackend.snapshot().lcdDensity)
            assertEquals(0.5f, recreatedBackend.snapshot().animationSpeed)
            assertFalse(backend.perform(ThorOperation.SET_DPI, "289").success)
            assertFalse(backend.perform(ThorOperation.SET_ANIMATION, "1.1").success)
        } finally {
            backend.perform(ThorOperation.SET_DPI, original.lcdDensity.toString())
            backend.perform(ThorOperation.SET_ANIMATION, original.animationSpeed.toString())
        }
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
    fun recoveryManifestStatusRejectsAChangedPatchedSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val directory = context.getExternalFilesDir(null)
            ?: error("Thor recovery directory is unavailable")
        val suffix = System.currentTimeMillis()
        val stock = File(directory, "status-source-stock-$suffix.img")
        val patched = File(directory, "status-source-patched-$suffix.img")
        preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
        try {
            stock.writeText("stock")
            patched.writeText("patched")
            val stockHash = RecoveryManifestStore.hashFile(stock.path) ?: error("stock hash missing")
            assertTrue(
                RecoveryManifestStore.recordLocalImages(
                    context,
                    listOf(
                        RecoveryImageInput(
                            fileName = stock.name,
                            slot = "_a",
                            partition = "boot",
                            patched = false,
                            path = stock.path,
                            buildIdentity = "status-build",
                        ),
                        RecoveryImageInput(
                            fileName = patched.name,
                            slot = "_a",
                            partition = "boot",
                            patched = true,
                            path = patched.path,
                            buildIdentity = "status-build",
                            sourceSha256 = stockHash,
                        ),
                    ),
                ),
            )
            var status = RecoveryManifestStore.statuses(context, "status-build")
                .single { it.record.patched }
            assertTrue(status.sourceStockVerified)
            assertTrue(status.patchedImageReady)
            stock.writeText("changed")
            status = RecoveryManifestStore.statuses(context, "status-build")
                .single { it.record.patched }
            assertFalse(status.sourceStockVerified)
            assertFalse(status.patchedImageReady)
        } finally {
            preferences.edit().remove(AppSettings.RECOVERY_MANIFEST_KEY).commit()
            stock.delete()
            patched.delete()
        }
    }

    @Test
    fun thorAvdExposesLowerDisplayGeometry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayManager = context.getSystemService(DisplayManager::class.java)
        assertTrue(
            displayManager.displays.any { display ->
                DeviceProfile.isThorLowerDisplay(
                    display.mode.physicalWidth,
                    display.mode.physicalHeight,
                    display.rotation,
                )
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
            assertFalse(preferences.contains(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY))
        } finally {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun failsClosedWhenARefreshCannotReadThorState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        preferences.edit()
            .remove(AppSettings.JOURNAL_OPERATION_KEY)
            .remove(AppSettings.JOURNAL_MESSAGE_KEY)
            .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
            .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
            .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
            .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
            .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
            .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
            .commit()
        val baseline = SystemBackendFactory.create(context).snapshot()
        var failRefresh = false
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot {
                if (failRefresh) error("state read failed")
                return baseline.copy(operation = operation)
            }

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(false, "unexpected backend execution")
        }
        try {
            val session = ThorSession(context, backend)
            session.load()
            assertTrue(session.snapshot.stateReadHealthy)
            failRefresh = true
            session.refresh()
            assertFalse(session.snapshot.stateReadHealthy)
            assertEquals(baseline.profile.properties.model, session.snapshot.profile.properties.model)
            assertEquals(
                "Thor system state is unavailable; refresh before changing the Thor",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
            assertEquals(OperationStatus.FAILURE, session.snapshot.operation.status)
        } finally {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun failsClosedWhenThePostOperationStateReadCannotComplete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val failAfterPerform = AtomicBoolean(false)
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot {
                if (failAfterPerform.get() && operation.status != OperationStatus.RUNNING) {
                    error("post-operation state read failed")
                }
                return baseline.copy(operation = operation)
            }

            override fun perform(operation: ThorOperation, argument: String?): OperationResult {
                failAfterPerform.set(true)
                return OperationResult(true, "setting updated")
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.SET_DPI, "320")
            withTimeout(5_000L) {
                while (session.snapshot.operation.status != OperationStatus.FAILURE) delay(10L)
            }
            assertFalse(session.snapshot.stateReadHealthy)
            assertTrue(session.snapshot.profile.capabilities.isEmpty())
            assertEquals(
                "Thor system state is unavailable; refresh before changing the Thor",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
        } finally {
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun keepsRecoveryRecordUntilCurrentThorStateIsReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = ThorSnapshot.loading(OperationState())
        var failRefresh = true
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot {
                if (failRefresh) error("state read failed")
                return baseline.copy(operation = operation, stateReadHealthy = true)
            }

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(false, "unexpected backend execution")
        }
        try {
            preferences.edit()
                .putString(AppSettings.JOURNAL_OPERATION_KEY, ThorOperation.FLASH.name)
                .putString(AppSettings.JOURNAL_MESSAGE_KEY, "flash was interrupted")
                .commit()
            val session = ThorSession(context, backend)
            assertFalse(session.acknowledgeInterruptedOperation())
            assertTrue(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
            session.load()
            assertFalse(session.snapshot.stateReadHealthy)
            assertFalse(session.acknowledgeInterruptedOperation())
            assertTrue(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
            failRefresh = false
            session.refresh()
            assertTrue(session.snapshot.stateReadHealthy)
            assertTrue(session.acknowledgeInterruptedOperation())
            assertFalse(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
        } finally {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun preservesRebootRequirementInInterruptedJournal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(true, "unexpected backend execution")
        }
        try {
            preferences.edit()
                .putString(AppSettings.JOURNAL_OPERATION_KEY, ThorOperation.FLASH.name)
                .putString(AppSettings.JOURNAL_MESSAGE_KEY, "write completed before journal failure")
                .putBoolean(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY, true)
                .putString(AppSettings.JOURNAL_BOOT_MARKER_KEY, SystemUtils.getBootMarker(context))
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            assertTrue(session.snapshot.operation.rebootRequired)
            assertTrue(session.acknowledgeInterruptedOperation())
            assertTrue(session.snapshot.operation.rebootRequired)
            assertTrue(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            assertEquals(
                "Reboot the Thor before starting another operation",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
        } finally {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun doesNotRequireASecondRebootAfterJournalSurvivesRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(true, "unexpected backend execution")
        }
        try {
            preferences.edit()
                .putString(AppSettings.JOURNAL_OPERATION_KEY, ThorOperation.FLASH.name)
                .putString(AppSettings.JOURNAL_MESSAGE_KEY, "flash completed before journal cleanup")
                .putBoolean(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY, true)
                .putString(AppSettings.JOURNAL_BOOT_MARKER_KEY, "previous-boot-marker")
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            assertFalse(session.snapshot.operation.rebootRequired)
            assertTrue(session.snapshot.operation.message.contains("last reboot"))
            assertEquals(
                "Acknowledge the Thor recovery record before starting another operation",
                ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI),
            )
            assertTrue(session.acknowledgeInterruptedOperation())
            assertFalse(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            assertNull(ThorOperationGuard.validate(session.snapshot, ThorOperation.SET_DPI))
        } finally {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.SET_DPI, "320")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(session.canCancelCurrentOperation())
            assertTrue(session.cancelCurrentOperation())
            scope.cancel()
            release.countDown()
            scope.coroutineContext[Job]?.join()
            assertEquals(OperationStatus.INTERRUPTED, session.snapshot.operation.status)
            assertTrue(session.snapshot.operation.message.contains("cancelled"))
            assertTrue(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
            assertTrue(session.acknowledgeInterruptedOperation())
        } finally {
            release.countDown()
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .commit()
        }
    }

    @Test
    fun marksAnOperationInterruptedWhenItsScopeWasAlreadyCancelled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppSettings.getSharedPrefs(context)
        val baseline = SystemBackendFactory.create(context).snapshot()
        val backend = object : SystemBackend {
            override fun snapshot(operation: OperationState): ThorSnapshot = baseline.copy(operation = operation)

            override fun perform(operation: ThorOperation, argument: String?): OperationResult =
                OperationResult(true, "unexpected backend execution")
        }
        val parent = Job()
        parent.cancel()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        try {
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.INSTALL_MAGISK)
            assertEquals(OperationStatus.INTERRUPTED, session.snapshot.operation.status)
            assertTrue(session.snapshot.operation.message.contains("interrupted"))
            assertTrue(preferences.contains(AppSettings.JOURNAL_OPERATION_KEY))
        } finally {
            scope.cancel()
            preferences.edit()
                .remove(AppSettings.JOURNAL_OPERATION_KEY)
                .remove(AppSettings.JOURNAL_MESSAGE_KEY)
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
            val session = ThorSession(context, backend)
            session.load()
            session.run(scope, ThorOperation.REBOOT)
            withTimeout(5_000L) {
                while (!preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY)) delay(10L)
            }
            assertTrue(session.snapshot.operation.rebootRequired)
            assertTrue(preferences.contains(AppSettings.PENDING_REBOOT_OPERATION_KEY))
            assertEquals(
                SystemUtils.getBootMarker(context),
                preferences.getString(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY, null),
            )
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
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
            assertTrue(preferences.getBoolean(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY, false))
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
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
                .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
                .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
                .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
                .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
                .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
                .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
                .commit()
        }
    }
}
