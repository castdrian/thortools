package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorDisplayDiagnosticsTest {
    @Test
    fun reportsTheCompleteThorPanelTopology() {
        val diagnostics = ThorDisplayDiagnostics(
            upper = ThorDisplayPanel(0, 1920, 1080, 120f, 0),
            lower = ThorDisplayPanel(1, 1240, 1080, 60f, 0),
        )

        assertTrue(diagnostics.dualDisplayReady)
        assertTrue(diagnostics.upper.present)
        assertEquals("1920 x 1080", diagnostics.upper.geometryLabel)
        assertEquals("120 Hz", diagnostics.upper.refreshRateLabel)
        assertEquals("Upright", diagnostics.upper.orientationLabel)
        assertEquals("1240 x 1080", diagnostics.lower.geometryLabel)
        assertEquals("60 Hz", diagnostics.lower.refreshRateLabel)
        assertEquals("60 Hz", ThorDisplayPanel(1, 1240, 1080, 59.94f, 0).refreshRateLabel)
    }

    @Test
    fun reportsSingleDisplayFallbackWhenTheLowerPanelIsMissing() {
        val diagnostics = ThorDisplayDiagnostics(
            upper = ThorDisplayPanel(0, 1920, 1080, 120f, 0),
        )

        assertFalse(diagnostics.dualDisplayReady)
        assertFalse(diagnostics.lower.present)
        assertEquals("Unavailable", diagnostics.lower.geometryLabel)
        assertEquals("Unavailable", diagnostics.lower.refreshRateLabel)
    }
}
