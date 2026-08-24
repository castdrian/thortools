package dev.adrian.thortools

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorScreensContractTest {
    @Test
    fun diagnosticRowsWrapLongThorValuesWithinTheAvailablePanel() {
        val source = File("src/main/java/dev/adrian/thortools/screens/ThorScreens.kt").readText()
        val dataLine = source.substringAfter("private fun DataLine").substringBefore("private fun ThorSection")

        assertTrue(dataLine.contains("Modifier.weight(1f).padding(start = 12.dp)"))
        assertTrue(dataLine.contains("softWrap = true"))
    }

    @Test
    fun lowerRootPanelShowsGuardBackedReadinessReasons() {
        val source = File("src/main/java/dev/adrian/thortools/screens/ThorScreens.kt").readText()
        val rootPanel = source.substringAfter("private fun RootPanel").substringBefore("private fun AboutPanel")

        assertTrue(rootPanel.contains("DashboardOperations(snapshot)"))
        assertTrue(rootPanel.contains("ThorOperationGuard.validate(snapshot, operation)"))
    }
}
