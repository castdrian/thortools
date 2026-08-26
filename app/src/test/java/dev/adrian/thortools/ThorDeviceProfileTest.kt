package dev.adrian.thortools

import dev.adrian.thortools.utils.SystemUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorDeviceProfileTest {
    @Test
    fun recognizesThorFromAnyHardwareIdentityField() {
        assertTrue(DeviceProfile.detect(DeviceProperties(model = "AYN Thor")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(model = "tHoR")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(model = "AYNThor")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(device = "thor_lite")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(device = "thorpro")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(product = "AYNThorMax")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(product = "AYN Thor Max")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(device = "odin2thor")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(buildProduct = "odin2thor")).isThor)
        assertEquals(ThorVariant.PRO, DeviceProfile.detect(DeviceProperties(device = "odin2thorpro")).variant)
        assertEquals(ThorVariant.PRO, DeviceProfile.detect(DeviceProperties(model = "AYN/Thor Pro")).variant)
        assertEquals(ThorVariant.LITE, DeviceProfile.detect(DeviceProperties(model = "AYN.Thor Lite")).variant)
        assertEquals(ThorVariant.LITE, DeviceProfile.detect(DeviceProperties(model = "AYN Thor", board = "sm8250")).variant)
        assertEquals(ThorVariant.LITE, DeviceProfile.detect(DeviceProperties(model = "AYN Thor", board = "kona")).variant)
        assertEquals(ThorVariant.UNKNOWN, DeviceProfile.detect(DeviceProperties(model = "AYN Thor", board = "lahaina")).variant)
        assertEquals(ThorVariant.UNKNOWN, DeviceProfile.detect(DeviceProperties(model = "AYN Thor", board = "kalama")).variant)
        assertTrue(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", model = "Thor")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", device = "kalama", board = "kalama")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(brand = "AYN", product = "KALAMA", board = "KALAMA")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", board = "kona")).isThor)
        assertEquals(ThorVariant.LITE, DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", board = "sm8250")).variant)
        assertTrue(DeviceProfile.detect(DeviceProperties(hardware = "thor_lite")).isThor)
        assertTrue(DeviceProfile.detect(DeviceProperties(board = "thor_max_board")).isThor)
    }

    @Test
    fun classifiesEveryThorVariant() {
        assertEquals(ThorVariant.LITE, DeviceProfile.variantFor("AYN Thor Lite"))
        assertEquals(ThorVariant.UNKNOWN, DeviceProfile.variantFor("AYN Thor"))
        assertEquals(ThorVariant.BASE, DeviceProfile.variantFor("AYN Thor Base"))
        assertEquals(ThorVariant.PRO, DeviceProfile.variantFor("AYN Thor Pro"))
        assertEquals(ThorVariant.MAX, DeviceProfile.variantFor("AYN Thor Max"))
        assertEquals(ThorVariant.PRO, DeviceProfile.variantFor("thorpro"))
        assertEquals(ThorVariant.PRO, DeviceProfile.variantFor("odin2thorpro"))
        assertEquals(ThorVariant.UNKNOWN, DeviceProfile.variantFor("AYN Loki"))
        assertEquals(ThorVariant.UNKNOWN, DeviceProfile.variantFor("Loki Pro"))
    }

    @Test
    fun doesNotTreatOtherDevicesAsThor() {
        assertFalse(DeviceProfile.detect(DeviceProperties(model = "AYN Loki")).isThor)
        assertFalse(DeviceProfile.detect(DeviceProperties(model = "Retroid Pocket Mini")).isThor)
        assertFalse(DeviceProfile.detect(DeviceProperties(manufacturer = "Qualcomm", board = "kalama")).isThor)
        assertFalse(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", board = "kalama")).isThor)
        assertFalse(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", device = "odin2", board = "kalama")).isThor)
        assertFalse(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", device = "odin2", board = "sm8250")).isThor)
        assertFalse(DeviceProfile.detect(DeviceProperties(manufacturer = "AYN", board = "kalama_fake")).isThor)
    }

    @Test
    fun rejectsConflictingThorAndOdinIdentities() {
        val conflictingModel = DeviceProfile.detect(
            DeviceProperties(model = "AYN Thor Pro", device = "odin2"),
        )
        assertFalse(conflictingModel.isThor)
        assertEquals(ThorVariant.UNKNOWN, conflictingModel.variant)
        assertFalse(
            DeviceProfile.detect(
                DeviceProperties(product = "thor", buildProduct = "odin2"),
            ).isThor,
        )
    }

    @Test
    fun classifiesThorDisplayGeometry() {
        assertTrue(DeviceProfile.isThorLowerDisplay(1240, 1080))
        assertTrue(DeviceProfile.isThorLowerDisplay(1240, 1080, DeviceProfile.THOR_DISPLAY_ROTATION))
        assertTrue(DeviceProfile.isThorLowerDisplay(1080, 1240, 1))
        assertTrue(DeviceProfile.isThorLowerDisplay(1080, 1240, 3))
        assertFalse(DeviceProfile.isThorLowerDisplay(1240, 1080, 1))
        assertFalse(DeviceProfile.isThorLowerDisplay(1240, 1080, 2))
        assertFalse(DeviceProfile.isThorLowerDisplay(1080, 1240))
        assertFalse(DeviceProfile.isThorLowerDisplay(1920, 1080))
        assertEquals("lower", DeviceProfile.displayKind(1240, 1080))
        assertEquals("upper", DeviceProfile.displayKind(1920, 1080))
    }

    @Test
    fun classifiesUpperDisplayGeometry() {
        assertTrue(DeviceProfile.isThorUpperDisplay(1920, 1080))
        assertTrue(DeviceProfile.isThorUpperDisplay(1920, 1080, DeviceProfile.THOR_DISPLAY_ROTATION))
        assertFalse(DeviceProfile.isThorUpperDisplay(1920, 1080, 1))
        assertFalse(DeviceProfile.isThorUpperDisplay(1080, 1920))
        assertFalse(DeviceProfile.isThorUpperDisplay(1240, 1080))
    }

    @Test
    fun keepsLowerDisplayTextReadable() {
        assertTrue(
            DeviceProfile.minimumReadablePixels(1240, 1080) >
                DeviceProfile.minimumReadablePixels(1920, 1080),
        )
        assertEquals(
            47.25f,
            DeviceProfile.minimumReadablePixels(1240, 1080, 2.625f),
            0.001f,
        )
    }

    @Test
    fun normalizesActiveSlotIdentifiers() {
        assertEquals("_a", DeviceProfile.normalizeSlot("_a"))
        assertEquals("_a", DeviceProfile.normalizeSlot("a"))
        assertEquals("_b", DeviceProfile.normalizeSlot(" B "))
        assertEquals("", DeviceProfile.normalizeSlot("unknown"))
    }

    @Test
    fun prefersBuildFingerprintForRecoveryIdentity() {
        assertEquals(
            "ayn/thor:13/THOR/1:user/release-keys",
            DeviceProperties(
                buildFingerprint = "ayn/thor:13/THOR/1:user/release-keys",
                buildId = "fallback",
            ).buildIdentity,
        )
        assertEquals(
            "fallback|display|14|date",
            DeviceProperties(
                buildId = "fallback",
                buildDisplayId = "display",
                firmware = "14",
                buildDate = "date",
            ).buildIdentity,
        )
    }

    @Test
    fun readsVendorAndBootPropertyFallbacksWithoutOverridingPrimaryValues() {
        val values = mapOf(
            "ro.product.vendor.manufacturer" to "AYN",
            "ro.product.odm.model" to "AYN Thor Lite",
            "ro.product.vendor.device" to "thor_lite",
            "ro.product.product.name" to "thor",
            "ro.product.vendor.board" to "sm8650",
            "ro.boot.hardware" to "qcom",
            "ro.board.platform" to "sm8650",
            "ro.vendor.build.fingerprint" to "ayn/thor:14/THOR/2:user/release-keys",
            "ro.boot.serialno" to "thor-lite-serial",
            "ro.boot.slot" to "b",
            "ro.boot.flash.locked" to "0",
            "ro.boot.vbmeta.device_state" to "unlocked",
            "ro.boot.verifiedbootstate" to "orange",
        )
        val properties = SystemUtils.getDeviceProperties { values[it].orEmpty() }

        assertEquals("AYN", properties.manufacturer)
        assertEquals("AYN Thor Lite", properties.model)
        assertEquals("thor_lite", properties.device)
        assertEquals("thor", properties.product)
        assertEquals("sm8650", properties.board)
        assertEquals("qcom", properties.hardware)
        assertEquals("sm8650", properties.soc)
        assertEquals("ayn/thor:14/THOR/2:user/release-keys", properties.buildFingerprint)
        assertEquals("thor-lite-serial", properties.serial)
        assertEquals("_b", properties.slot)
        assertEquals("0", properties.flashLocked)
        assertEquals("unlocked", properties.bootloaderDeviceState)
        assertEquals("orange", properties.verifiedBootState)
        assertTrue(properties.bootloaderUnlocked == true)
        assertTrue(DeviceProfile.detect(properties).isThor)
        assertEquals(ThorVariant.LITE, DeviceProfile.detect(properties).variant)
    }

    @Test
    fun classifiesBootloaderStateFromAndroidBootProperties() {
        assertTrue(DeviceProperties(flashLocked = "0").bootloaderUnlocked == true)
        assertTrue(DeviceProperties(bootloaderDeviceState = "UNLOCKED").bootloaderUnlocked == true)
        assertTrue(DeviceProperties(verifiedBootState = "orange").bootloaderUnlocked == true)
        assertFalse(DeviceProperties(flashLocked = "1").bootloaderUnlocked == true)
        assertFalse(DeviceProperties(bootloaderDeviceState = "locked").bootloaderUnlocked == true)
        assertFalse(DeviceProperties(verifiedBootState = "green").bootloaderUnlocked == true)
        assertNull(DeviceProperties().bootloaderUnlocked)
        assertNull(DeviceProperties(flashLocked = "0", verifiedBootState = "green").bootloaderUnlocked)
        assertEquals("Unknown", DeviceProperties().bootloaderStateLabel)
        assertEquals("Locked", DeviceProperties(verifiedBootState = "yellow").bootloaderStateLabel)
    }

    @Test
    fun readsLineageSystemIdentityFallbacks() {
        val values = mapOf(
            "ro.product.system.manufacturer" to "AYN",
            "ro.product.system.brand" to "AYN",
            "ro.product.system.model" to "Thor",
            "ro.product.system.device" to "thor",
            "ro.product.system.name" to "Thor",
            "ro.build.product" to "odin2thor",
        )
        val properties = SystemUtils.getDeviceProperties { values[it].orEmpty() }

        assertEquals("AYN", properties.manufacturer)
        assertEquals("AYN", properties.brand)
        assertEquals("Thor", properties.model)
        assertEquals("thor", properties.device)
        assertEquals("Thor", properties.product)
        assertEquals("thor", properties.systemDevice)
        assertEquals("Thor", properties.systemName)
        assertEquals("odin2thor", properties.buildProduct)
        assertTrue(DeviceProfile.detect(properties).isThor)
    }

    @Test
    fun readsProductPartitionIdentityFallbacks() {
        val values = mapOf(
            "ro.product.product.manufacturer" to "AYN",
            "ro.product.product.brand" to "AYN",
            "ro.product.product.model" to "AYN Thor Max",
            "ro.product.product.device" to "odin2thor",
            "ro.product.product.name" to "odin2thor",
            "ro.product.product.board" to "qcs8550",
        )

        val properties = SystemUtils.getDeviceProperties { values[it].orEmpty() }

        assertEquals("AYN", properties.manufacturer)
        assertEquals("AYN", properties.brand)
        assertEquals("AYN Thor Max", properties.model)
        assertEquals("odin2thor", properties.device)
        assertEquals("odin2thor", properties.product)
        assertEquals("qcs8550", properties.board)
        assertTrue(DeviceProfile.detect(properties).isThor)
        assertEquals(ThorVariant.MAX, DeviceProfile.detect(properties).variant)
    }

    @Test
    fun prefersPrimaryPropertyNamespacesBeforeFallbacks() {
        val values = mapOf(
            "ro.product.manufacturer" to "AYN",
            "ro.product.vendor.manufacturer" to "fallback",
            "ro.product.model" to "AYN Thor Pro",
            "ro.product.vendor.model" to "AYN Thor Lite",
            "ro.boot.slot_suffix" to "_a",
            "ro.boot.slot" to "b",
        )
        val properties = SystemUtils.getDeviceProperties { values[it].orEmpty() }

        assertEquals("AYN", properties.manufacturer)
        assertEquals("AYN Thor Pro", properties.model)
        assertEquals("_a", properties.slot)
    }

    @Test
    fun fallsBackWhenThePrimarySlotPropertyIsInvalid() {
        val values = mapOf(
            "ro.product.model" to "AYN Thor",
            "ro.boot.slot_suffix" to "unknown",
            "ro.boot.slot" to "b",
        )

        val properties = SystemUtils.getDeviceProperties { values[it].orEmpty() }

        assertEquals("_b", properties.slot)
    }

    @Test
    fun hidesThorCapabilitiesForDiagnosticsOutsideThor() {
        val snapshot = ThorSnapshot.loading(OperationState()).copy(
            profile = DeviceProfile.detect(DeviceProperties(model = "AYN Loki")).copy(
                capabilities = ThorCapability.entries.toSet(),
            ),
        )
        val rows = snapshot.capabilityRows.toMap()
        assertFalse(rows.getValue("Thor device"))
        assertFalse(rows.getValue("Root service"))
        assertFalse(rows.getValue("Magisk"))
        assertFalse(rows.getValue("Backup destination"))
    }
}
