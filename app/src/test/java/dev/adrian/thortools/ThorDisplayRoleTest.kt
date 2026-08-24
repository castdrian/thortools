package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Test

class ThorDisplayRoleTest {
    @Test
    fun identifiesBothNativeThorPanelGeometries() {
        assertEquals(ThorDisplayRole.UPPER, ThorDisplayRole.fromGeometry(1920, 1080, 0))
        assertEquals(ThorDisplayRole.LOWER, ThorDisplayRole.fromGeometry(1240, 1080, 0))
    }

    @Test
    fun identifiesRotatedLowerPanelGeometry() {
        assertEquals(ThorDisplayRole.LOWER, ThorDisplayRole.fromGeometry(1080, 1240, 1))
        assertEquals(ThorDisplayRole.LOWER, ThorDisplayRole.fromGeometry(1080, 1240, 3))
    }

    @Test
    fun rejectsInvalidPanelOrientationsAndSizes() {
        assertEquals(ThorDisplayRole.UNKNOWN, ThorDisplayRole.fromGeometry(1920, 1080, 1))
        assertEquals(ThorDisplayRole.UNKNOWN, ThorDisplayRole.fromGeometry(1240, 1080, 1))
        assertEquals(ThorDisplayRole.UNKNOWN, ThorDisplayRole.fromGeometry(1280, 720, 0))
    }

    @Test
    fun selectsTheOppositePanelForTheSecondaryPresentation() {
        assertEquals(ThorDisplayRole.LOWER, ThorDisplayRole.UPPER.opposite())
        assertEquals(ThorDisplayRole.UPPER, ThorDisplayRole.LOWER.opposite())
        assertEquals(ThorDisplayRole.UNKNOWN, ThorDisplayRole.UNKNOWN.opposite())
    }
}
