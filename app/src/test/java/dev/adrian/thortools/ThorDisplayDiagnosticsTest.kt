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
            defaultDisplayId = 0,
        )

        assertTrue(diagnostics.dualDisplayReady)
        assertEquals(ThorDisplayMode.DUAL, diagnostics.mode)
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
            defaultDisplayId = 0,
        )

        assertFalse(diagnostics.dualDisplayReady)
        assertEquals(ThorDisplayMode.UPPER_ONLY, diagnostics.mode)
        assertFalse(diagnostics.lower.present)
        assertEquals("Unavailable", diagnostics.lower.geometryLabel)
        assertEquals("Unavailable", diagnostics.lower.refreshRateLabel)
    }

    @Test
    fun acceptsTheLowerPanelWhenTheDisplayStackReportsNaturalPortraitGeometry() {
        val diagnostics = ThorDisplayDiagnostics(
            upper = ThorDisplayPanel(0, 1920, 1080, 120f, 0),
            lower = ThorDisplayPanel(1, 1080, 1240, 60f, 1),
            defaultDisplayId = 0,
        )

        assertTrue(diagnostics.dualDisplayReady)
        assertEquals(ThorDisplayMode.DUAL, diagnostics.mode)
        assertEquals("1080 x 1240", diagnostics.lower.geometryLabel)
        assertEquals("Rotated 90 deg", diagnostics.lower.orientationLabel)
    }

    @Test
    fun onlyReportsDualDisplayReadyForDistinctThorPanels() {
        assertFalse(
            ThorDisplayDiagnostics(
                upper = ThorDisplayPanel(0, 1920, 1080, 120f, 0),
                lower = ThorDisplayPanel(0, 1240, 1080, 60f, 0),
            ).dualDisplayReady,
        )
        assertFalse(
            ThorDisplayDiagnostics(
                upper = ThorDisplayPanel(0, 1920, 1080, 120f, 1),
                lower = ThorDisplayPanel(1, 1240, 1080, 60f, 0),
            ).dualDisplayReady,
        )
        assertFalse(
            ThorDisplayDiagnostics(
                upper = ThorDisplayPanel(0, 1920, 1080, 120f, 0),
                lower = ThorDisplayPanel(1, 1240, 1240, 60f, 0),
            ).dualDisplayReady,
        )
    }

    @Test
    fun identifiesTheLowerOnlyLidState() {
        val diagnostics = ThorDisplayDiagnostics(
            lower = ThorDisplayPanel(0, 1240, 1080, 60f, 0),
            defaultDisplayId = 0,
        )

        assertFalse(diagnostics.dualDisplayReady)
        assertEquals(ThorDisplayMode.LOWER_ONLY, diagnostics.mode)
        assertEquals("Lower display only", ThorDisplayMode.LOWER_ONLY.label)
    }

    @Test
    fun identifiesWhenNoThorPanelIsAvailable() {
        val diagnostics = ThorDisplayDiagnostics()

        assertEquals(ThorDisplayMode.UNAVAILABLE, diagnostics.mode)
        assertEquals("No Thor display detected", diagnostics.mode.label)
    }
}
