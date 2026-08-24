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
        val removed = source.substringAfter("override fun onDisplayRemoved(displayId: Int)").substringBefore("override fun onDisplayChanged")

        assertTrue(source.contains("private var secondaryPresentationRequested = false"))
        assertTrue(source.contains("private var displayRefreshJob: Job? = null"))
        assertTrue(source.contains("hasThorLowerDisplay = findThorLowerDisplay() != null"))
        assertTrue(source.contains("private fun findThorLowerDisplay(): Display?"))
        assertTrue(source.contains("secondaryPresentationRequested = true"))
        assertTrue(source.contains("scheduleDisplayRefresh()"))
        assertTrue(show.contains("!secondaryPresentationRequested || !activityResumed || isFinishing"))
        assertTrue(show.contains("val display = findThorLowerDisplay()"))
        assertTrue(show.contains("if (display == null)"))
        assertTrue(show.contains("scheduleSecondaryDisplayRetry()"))
        assertTrue(show.contains("candidate.rotationOrNull()"))
        assertTrue(show.contains("candidate.modeOrNull()"))
        assertTrue(source.contains("private fun Display.modeOrNull(): Display.Mode?"))
        assertTrue(source.contains("private fun Display.rotationOrNull(): Int?"))
        assertFalse(show.contains("candidate.mode.physicalWidth"))
        assertTrue(removed.contains("scheduleSecondaryDisplayRetry()"))
        assertTrue(show.contains("catch (_: WindowManager.BadTokenException)"))
        assertTrue(show.contains("catch (_: WindowManager.InvalidDisplayException)"))
        assertTrue(show.contains("hasThorLowerDisplay = false"))
        assertTrue(show.contains("hasThorLowerDisplay = secondaryPresentation === presentation && presentation.isShowing"))
        assertTrue(show.contains("existing.requestInputFocus()"))
        assertTrue(show.contains("presentation.requestInputFocus()"))
        assertTrue(show.contains("postDelayed({ presentation.requestInputFocus() }, 250L)"))
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
        assertTrue(source.contains("private var sessionLoaded = false"))
        assertTrue(source.contains("sessionLoaded = true"))
        assertFalse(start.contains("activityResumed = true"))
        assertTrue(resume.contains("activityResumed = true"))
        assertTrue(resume.contains("if (sessionLoaded)"))
        assertTrue(resume.contains("session.refresh()"))
        assertTrue(pause.contains("activityResumed = false"))
        assertTrue(pause.contains("dismissSecondaryDisplay()"))
        assertTrue(source.contains("!activityResumed || isFinishing"))

        val presentation = source.substringAfter("private class ThorPresentation").substringBefore("private fun configureThorWindow")
        assertTrue(presentation.contains("setCancelable(false)"))
        assertTrue(presentation.contains("setContentView(composeView)"))
        assertTrue(presentation.contains("configureThorWindow(window)"))
        assertTrue(presentation.contains("window?.decorView?.requestFocus()"))
        assertTrue(presentation.contains("contentView?.requestFocusFromTouch()"))
        assertTrue(presentation.contains("contentView?.requestFocus()"))
        assertTrue(presentation.contains("ThorToolsTheme(lowerDisplay = true)"))
        assertTrue(presentation.contains("fun requestInputFocus()"))
        assertTrue(presentation.contains("if (!isShowing) return@post"))
        assertTrue(source.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)"))
    }
}
