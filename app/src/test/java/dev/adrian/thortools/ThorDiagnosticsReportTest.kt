package dev.adrian.thortools

import dev.adrian.thortools.utils.RecoveryImageRecord
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorDiagnosticsReportTest {
    @Test
    fun includesThorIdentityOperationDisplayAndRecoveryEvidence() {
        val snapshot = ThorSnapshot.loading(
            OperationState(
                operation = ThorOperation.FLASH,
                status = OperationStatus.FAILURE,
                message = "write failed",
                rebootRequired = true,
            ),
        ).copy(
            profile = DeviceProfile.detect(
                DeviceProperties(
                    manufacturer = "AYN",
                    model = "AYN Thor Pro",
                    serial = "thor-serial",
                    buildFingerprint = "ayn/thor/pro",
                ),
            ),
            activeSlot = "_b",
            displayDiagnostics = ThorDisplayDiagnostics(
                upper = ThorDisplayPanel(0, 1920, 1080, 120f),
                lower = ThorDisplayPanel(1, 1240, 1080, 60f),
            ),
        )
        val report = ThorDiagnosticsReport.build(
            snapshot = snapshot,
            records = listOf(
                RecoveryImageRecord(
                    fileName = "init_boot_b.img",
                    slot = "_b",
                    partition = "init_boot",
                    patched = false,
                    size = 1024,
                    sha256 = "stock-hash",
                    buildIdentity = "ayn/thor/pro",
                ),
            ),
            recoveryPath = "/recovery",
            logPath = "/recovery/lastlog.txt",
        )

        assertTrue(report.contains("device.isThor=true"))
        assertTrue(report.contains("model=AYN Thor Pro"))
        assertTrue(report.contains("serial=thor-serial"))
        assertTrue(report.contains("activeSlot=_b"))
        assertTrue(report.contains("operation=FLASH"))
        assertTrue(report.contains("operationStatus=FAILURE"))
        assertTrue(report.contains("operationRebootRequired=true"))
        assertTrue(report.contains("stateReadHealthy=true"))
        assertTrue(report.contains("display.upper.width=1920"))
        assertTrue(report.contains("display.lower.width=1240"))
        assertTrue(report.contains("recovery[0].sha256=stock-hash"))
        assertTrue(report.contains("recoveryPath=/recovery"))
    }

    @Test
    fun keepsReportValuesOnSingleLines() {
        val report = ThorDiagnosticsReport.build(
            snapshot = ThorSnapshot.loading(OperationState()).copy(
                profile = DeviceProfile.detect(DeviceProperties(model = "AYN\nThor")),
                operation = OperationState(message = "line one\nline two"),
            ),
            records = emptyList(),
            recoveryPath = "/recovery\npath",
            logPath = "/log",
        )

        assertTrue(report.contains("model=AYN Thor"))
        assertTrue(report.contains("operationMessage=line one line two"))
        assertTrue(report.contains("recoveryPath=/recovery path"))
    }
}
