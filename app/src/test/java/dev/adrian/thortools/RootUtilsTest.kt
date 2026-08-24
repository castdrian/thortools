package dev.adrian.thortools

import dev.adrian.thortools.utils.resolveRootState
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
}
