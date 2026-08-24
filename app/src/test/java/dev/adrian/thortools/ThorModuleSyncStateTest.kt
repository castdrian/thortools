package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Test

class ThorModuleSyncStateTest {
    @Test
    fun treatsConfiguredLegacyPreferencesAsPending() {
        assertEquals(ThorModuleSyncState.PENDING, ThorModuleSyncState.fromStored(null, moduleConfigured = true))
        assertEquals(ThorModuleSyncState.PENDING, ThorModuleSyncState.fromStored("NOT_CONFIGURED", moduleConfigured = true))
    }

    @Test
    fun preservesKnownPersistedStates() {
        ThorModuleSyncState.entries.forEach { state ->
            assertEquals(state, ThorModuleSyncState.fromStored(state.name, moduleConfigured = state != ThorModuleSyncState.NOT_CONFIGURED))
        }
    }

    @Test
    fun rejectsUnknownStatesWithoutLosingConfigurationSignal() {
        assertEquals(ThorModuleSyncState.PENDING, ThorModuleSyncState.fromStored("unknown", moduleConfigured = true))
        assertEquals(ThorModuleSyncState.NOT_CONFIGURED, ThorModuleSyncState.fromStored("unknown", moduleConfigured = false))
    }

    @Test
    fun marksAPreviouslySyncedModuleAsFailedWhenLiveRootStateCannotFindIt() {
        assertEquals(
            ThorModuleSyncState.FAILED,
            ThorModuleSyncState.fromStored("SYNCED", moduleConfigured = true, moduleInstalled = false),
        )
        assertEquals(
            ThorModuleSyncState.SYNCED,
            ThorModuleSyncState.fromStored("SYNCED", moduleConfigured = true, moduleInstalled = null),
        )
    }

    @Test
    fun exposesStableLabels() {
        assertEquals("Not configured", ThorModuleSyncState.NOT_CONFIGURED.label)
        assertEquals("Pending", ThorModuleSyncState.PENDING.label)
        assertEquals("Synced", ThorModuleSyncState.SYNCED.label)
        assertEquals("Failed", ThorModuleSyncState.FAILED.label)
    }
}
