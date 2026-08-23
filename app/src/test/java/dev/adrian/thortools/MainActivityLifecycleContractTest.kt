package dev.adrian.thortools

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLifecycleContractTest {
    @Test
    fun retriesTheLowerPresentationAcrossDisplayRecreation() {
        val source = File("src/main/java/dev/adrian/thortools/MainActivity.kt").readText()
        val show = source.substringAfter("private fun showSecondaryDisplay()").substringBefore("private fun scheduleSecondaryDisplayRetry")
        val retry = source.substringAfter("private fun scheduleSecondaryDisplayRetry()").substringBefore("private fun dismissSecondaryDisplay")
        val stop = source.substringAfter("override fun onStop()").substringBefore("private fun requestSecondaryDisplay")

        assertTrue(source.contains("private var secondaryPresentationRequested = false"))
        assertTrue(source.contains("secondaryPresentationRequested = true"))
        assertTrue(show.contains("!secondaryPresentationRequested || !activityResumed || isFinishing"))
        assertTrue(show.contains("if (display == null)"))
        assertTrue(show.contains("scheduleSecondaryDisplayRetry()"))
        assertTrue(show.contains("catch (_: WindowManager.BadTokenException)"))
        assertTrue(show.contains("catch (_: WindowManager.InvalidDisplayException)"))
        assertTrue(retry.contains("!secondaryPresentationRequested"))
        assertTrue(stop.contains("dismissSecondaryDisplay(clearRequest = true)"))
    }

    @Test
    fun lowerPresentationOnlyRunsWhileActivityIsResumed() {
        val source = File("src/main/java/dev/adrian/thortools/MainActivity.kt").readText()
        val start = source.substringAfter("override fun onStart()").substringBefore("override fun onResume()")
        val resume = source.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        val pause = source.substringAfter("override fun onPause()").substringBefore("override fun onConfigurationChanged")

        assertTrue(source.contains("private var activityResumed = false"))
        assertFalse(start.contains("activityResumed = true"))
        assertTrue(resume.contains("activityResumed = true"))
        assertTrue(pause.contains("activityResumed = false"))
        assertTrue(source.contains("!activityResumed || isFinishing"))

        val presentation = source.substringAfter("private class ThorPresentation").substringBefore("private fun configureThorWindow")
        assertTrue(presentation.contains("setCancelable(false)"))
        assertTrue(presentation.contains("setContentView(composeView)"))
        assertTrue(presentation.contains("configureThorWindow(window)"))
        assertTrue(presentation.contains("contentView?.requestFocusFromTouch()"))
        assertTrue(presentation.contains("ThorToolsTheme(lowerDisplay = true)"))
    }
}
