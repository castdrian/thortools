package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootReceiverTest {
    @Test
    fun retriesUntilThePrivilegedServiceIsAvailable() {
        var checks = 0
        var waits = 0

        assertTrue(
            awaitRootService(
                isAvailable = { ++checks == 3 },
                wait = {
                    waits++
                    true
                },
            ),
        )
        assertEquals(3, checks)
        assertEquals(2, waits)
    }

    @Test
    fun stopsAfterTheBoundedRetryWindow() {
        var waits = 0

        assertFalse(
            awaitRootService(
                isAvailable = { false },
                wait = {
                    waits++
                    true
                },
            ),
        )
        assertEquals(11, waits)
    }

    @Test
    fun stopsWhenWaitingIsInterrupted() {
        var checks = 0

        assertFalse(
            awaitRootService(
                isAvailable = { ++checks > 1 },
                wait = { false },
            ),
        )
        assertEquals(1, checks)
    }

    @Test
    fun appliesEveryConfiguredOverrideAndReportsSuccess() {
        val dpiValues = mutableListOf<Int>()
        val animationValues = mutableListOf<Float>()

        assertTrue(
            applyBootOverrides(
                hasDpiOverride = true,
                dpi = 333,
                hasAnimationSpeedOverride = true,
                animationSpeed = 0.5f,
                setDpi = { dpiValues += it; true },
                setAnimationSpeed = { animationValues += it; true },
            ),
        )
        assertEquals(listOf(333), dpiValues)
        assertEquals(listOf(0.5f), animationValues)
    }

    @Test
    fun reportsFailureForInvalidOrRejectedOverridesWithoutSkippingTheOtherOne() {
        val animationValues = mutableListOf<Float>()

        assertFalse(
            applyBootOverrides(
                hasDpiOverride = true,
                dpi = AppSettings.DPI_MIN - 1,
                hasAnimationSpeedOverride = true,
                animationSpeed = 0.5f,
                setDpi = { error("invalid DPI must not be applied") },
                setAnimationSpeed = { animationValues += it; false },
            ),
        )
        assertEquals(listOf(0.5f), animationValues)
    }

    @Test
    fun treatsUnconfiguredOverridesAsAlreadyApplied() {
        assertTrue(
            applyBootOverrides(
                hasDpiOverride = false,
                dpi = 0,
                hasAnimationSpeedOverride = false,
                animationSpeed = Float.NaN,
                setDpi = { error("DPI must not be applied") },
                setAnimationSpeed = { error("animation must not be applied") },
            ),
        )
    }

    @Test
    fun configuredBootOverrideStateDefaultsToPending() {
        assertEquals(ThorBootOverrideState.PENDING, ThorBootOverrideState.fromStored(null, configured = true))
        assertEquals(ThorBootOverrideState.NOT_CONFIGURED, ThorBootOverrideState.fromStored("FAILED", configured = false))
        assertEquals(ThorBootOverrideState.APPLIED, ThorBootOverrideState.fromStored("APPLIED", configured = true))
    }

    @Test
    fun retainsModuleSynchronizationFailureAfterBootRetry() {
        val source = File("src/main/java/dev/adrian/thortools/BootReceiver.kt").readText()
        assertTrue(source.contains("AppSettings.setModuleSyncState(prefs, ThorModuleSyncState.FAILED)"))
        assertTrue(source.contains("if (AppSettings.hasModuleSettings(prefs))"))
        assertTrue(source.contains("AppSettings.save(context)"))
        assertTrue(source.contains("AppSettings.setBootOverrideState(prefs, ThorBootOverrideState.FAILED)"))
        assertTrue(source.contains("applyBootOverrides("))
    }
}
