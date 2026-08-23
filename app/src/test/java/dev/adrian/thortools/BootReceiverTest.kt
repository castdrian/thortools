package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
