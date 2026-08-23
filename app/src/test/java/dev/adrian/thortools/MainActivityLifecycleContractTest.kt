package dev.adrian.thortools

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLifecycleContractTest {
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
        assertTrue(source.contains("if (!activityResumed || isFinishing) return"))
    }
}
