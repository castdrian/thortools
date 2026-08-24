package dev.adrian.thortools

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.adrian.thortools.screens.ThorControlScreen
import dev.adrian.thortools.screens.ThorDashboardScreen
import dev.adrian.thortools.ui.theme.ThorToolsTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var session: ThorSession
    private var displayManager: DisplayManager? = null
    private var secondaryPresentation: ThorPresentation? = null
    private var secondaryDisplayRetry: Job? = null
    private var displayRefreshJob: Job? = null
    private var secondaryDisplayRetryCount = 0
    private var secondaryPresentationRequested = false
    private var activityResumed = false
    private var sessionLoaded = false
    private var hasThorLowerDisplay by mutableStateOf(false)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            scheduleDisplayRefresh()
            requestSecondaryDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {
            scheduleDisplayRefresh()
            if (secondaryPresentation?.display?.displayId == displayId) {
                dismissSecondaryDisplay()
                scheduleSecondaryDisplayRetry()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            scheduleDisplayRefresh()
            requestSecondaryDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ThorSession(this)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        hasThorLowerDisplay = findThorLowerDisplay() != null
        configureThorWindow(window)
        lifecycleScope.launch {
            session.load()
            sessionLoaded = true
        }
        setContent {
            ThorToolsTheme {
                if (hasThorLowerDisplay) {
                    ThorDashboardScreen(session, this@MainActivity)
                } else {
                    ThorControlScreen(session, this@MainActivity, lifecycleScope)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        displayManager?.registerDisplayListener(displayListener, null)
        requestSecondaryDisplay()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (sessionLoaded) {
            lifecycleScope.launch { session.refresh() }
        }
        requestSecondaryDisplay()
    }

    override fun onPause() {
        activityResumed = false
        secondaryDisplayRetry?.cancel()
        secondaryDisplayRetry = null
        displayRefreshJob?.cancel()
        displayRefreshJob = null
        dismissSecondaryDisplay()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestSecondaryDisplay()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureThorWindow(window)
    }

    override fun onStop() {
        activityResumed = false
        secondaryDisplayRetry?.cancel()
        secondaryDisplayRetry = null
        displayRefreshJob?.cancel()
        displayRefreshJob = null
        secondaryDisplayRetryCount = 0
        dismissSecondaryDisplay(clearRequest = true)
        displayManager?.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    private fun requestSecondaryDisplay() {
        secondaryPresentationRequested = true
        secondaryDisplayRetry?.cancel()
        secondaryDisplayRetry = null
        secondaryDisplayRetryCount = 0
        showSecondaryDisplay()
    }

    private fun showSecondaryDisplay() {
        if (!secondaryPresentationRequested || !activityResumed || isFinishing) return
        val display = findThorLowerDisplay()
        if (display == null) {
            dismissSecondaryDisplay()
            scheduleSecondaryDisplayRetry()
            return
        }
        secondaryDisplayRetry?.cancel()
        secondaryDisplayRetry = null
        secondaryDisplayRetryCount = 0
        hasThorLowerDisplay = true
        secondaryPresentation?.let { existing ->
            if (existing.display.displayId == display.displayId && existing.isShowing) {
                existing.requestInputFocus()
                return
            }
            existing.dismiss()
        }
        hasThorLowerDisplay = false
        val presentation = ThorPresentation(this, display, session)
        secondaryPresentation = presentation
        presentation.setOnDismissListener {
            if (secondaryPresentation === presentation) {
                secondaryPresentation = null
                hasThorLowerDisplay = false
                scheduleSecondaryDisplayRetry()
            }
        }
        try {
            presentation.show()
            presentation.requestInputFocus()
            presentation.window?.decorView?.postDelayed({ presentation.requestInputFocus() }, 250L)
            hasThorLowerDisplay = secondaryPresentation === presentation && presentation.isShowing
        } catch (_: WindowManager.BadTokenException) {
            if (secondaryPresentation === presentation) secondaryPresentation = null
            hasThorLowerDisplay = false
            scheduleSecondaryDisplayRetry()
        } catch (_: WindowManager.InvalidDisplayException) {
            if (secondaryPresentation === presentation) secondaryPresentation = null
            hasThorLowerDisplay = false
            scheduleSecondaryDisplayRetry()
        } catch (_: RuntimeException) {
            if (secondaryPresentation === presentation) secondaryPresentation = null
            hasThorLowerDisplay = false
            scheduleSecondaryDisplayRetry()
        }
    }

    private fun findThorLowerDisplay(): Display? = runCatching { displayManager?.displays?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .firstOrNull { candidate ->
            candidate.displayId != Display.DEFAULT_DISPLAY &&
                candidate.modeOrNull()?.let { mode ->
                    candidate.rotationOrNull()?.let { rotation ->
                        DeviceProfile.isThorLowerDisplay(
                            mode.physicalWidth,
                            mode.physicalHeight,
                            rotation,
                        )
                    }
                } == true
        }

    private fun scheduleDisplayRefresh() {
        if (!sessionLoaded || !activityResumed || isFinishing) return
        displayRefreshJob?.cancel()
        displayRefreshJob = lifecycleScope.launch {
            delay(250L)
            displayRefreshJob = null
            if (activityResumed && !isFinishing) session.refresh()
        }
    }

    private fun scheduleSecondaryDisplayRetry() {
        if (!secondaryPresentationRequested || !activityResumed || isFinishing || secondaryDisplayRetry?.isActive == true) return
        secondaryDisplayRetryCount = (secondaryDisplayRetryCount + 1).coerceAtMost(8)
        secondaryDisplayRetry = lifecycleScope.launch {
            delay(300L * secondaryDisplayRetryCount)
            secondaryDisplayRetry = null
            showSecondaryDisplay()
        }
    }

    private fun dismissSecondaryDisplay(clearRequest: Boolean = false) {
        if (clearRequest) {
            secondaryPresentationRequested = false
            secondaryDisplayRetry?.cancel()
            secondaryDisplayRetry = null
            secondaryDisplayRetryCount = 0
        }
        secondaryPresentation?.dismiss()
        secondaryPresentation = null
        hasThorLowerDisplay = false
    }
}

private fun Display.modeOrNull(): Display.Mode? = runCatching { mode }.getOrNull()

private fun Display.rotationOrNull(): Int? = runCatching { rotation }.getOrNull()

private class ThorPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val session: ThorSession,
) : Presentation(activity, display) {
    private var contentView: ComposeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        configureThorWindow(window)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val composeView = ComposeView(context).apply {
            setTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner, activity)
            setTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner, activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                ThorToolsTheme(lowerDisplay = true) {
                    ThorControlScreen(session, context, activity.lifecycleScope, isLowerDisplay = true)
                }
            }
        }
        contentView = composeView
        setContentView(composeView)
        configureThorWindow(window)
        window?.decorView?.isFocusable = true
        window?.decorView?.isFocusableInTouchMode = true
        window?.takeKeyEvents(true)
        window?.decorView?.requestFocus()
        window?.decorView?.requestFocusFromTouch()
        composeView.requestFocus()
        composeView.requestFocusFromTouch()
    }

    fun requestInputFocus() {
        window?.decorView?.post {
            if (!isShowing) return@post
            configureThorWindow(window)
            window?.takeKeyEvents(true)
            window?.decorView?.requestFocus()
            window?.decorView?.requestFocusFromTouch()
            contentView?.requestFocus()
            contentView?.requestFocusFromTouch()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestInputFocus()
    }
}

private fun configureThorWindow(window: android.view.Window?) {
    window ?: return
    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    insetsController.hide(WindowInsetsCompat.Type.systemBars())
    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
