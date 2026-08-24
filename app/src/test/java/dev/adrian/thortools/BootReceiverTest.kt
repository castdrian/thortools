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
    fun retainsModuleSynchronizationFailureAfterBootRetry() {
        val source = File("src/main/java/dev/adrian/thortools/BootReceiver.kt").readText()
        assertTrue(source.contains("AppSettings.setModuleSyncState(prefs, ThorModuleSyncState.FAILED)"))
        assertTrue(source.contains("if (AppSettings.hasModuleSettings(prefs))"))
        assertTrue(source.contains("AppSettings.save(context)"))
    }
}
