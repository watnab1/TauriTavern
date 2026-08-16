package com.tauritavern.client

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class AndroidInsetsBridge(
  private val window: Window,
  private val resources: Resources,
  private val contentRootProvider: () -> View?,
  private val webViewProvider: () -> WebView?,
  private val isDestroyed: () -> Boolean,
  readinessPoller: ReadinessPoller<WebView>,
  private val pageSession: WebViewPageSession,
) {
  private var immersiveFullscreenEnabled: Boolean = true
  private var systemBarInsets: Insets = Insets.NONE
  private var imeBottomInset: Int = 0
  private var lastPushedInsetsSnapshot: InsetsSnapshot? = null
  private var isInsetsPushScheduled: Boolean = false
  private var hasPendingForcedInsetsPush: Boolean = false
  private var hasReadyPageInsetsInjection: Boolean = false
  private var isInsetsListenerAttached: Boolean = false
  private val webViewInsetsStyleApplier: WebViewInsetsStyleApplier by lazy {
    WebViewInsetsStyleApplier(resources)
  }
  private val readinessCoordinator: WebViewReadinessCoordinator<WebView> by lazy {
    WebViewReadinessCoordinator(
      pageSession = pageSession,
      targetProvider = webViewProvider,
      isDestroyed = isDestroyed,
      poller = readinessPoller,
      readinessScript = PAGE_READY_SCRIPT,
      onReady = { pushInsetsToWebView(force = true) },
    )
  }

  fun onCreate() {
    configureImmersiveSystemBars()
    attachSystemInsetsListenerIfNeeded()
    requestSystemInsets()
  }

  fun onConfigurationChanged() {
    configureImmersiveSystemBars()
    refreshInjection()
  }

  fun onWebViewAvailable() {
    resetWebViewInjectionState()
    configureImmersiveSystemBars()
    requestSystemInsets()
    readinessCoordinator.syncWhenPageReady()
  }

  fun onMainFrameNavigationStarted() {
    resetWebViewInjectionState()
    configureImmersiveSystemBars()
    requestSystemInsets()
    readinessCoordinator.onMainFrameNavigationStarted()
  }

  fun onMainFramePageFinished() {
    configureImmersiveSystemBars()
    requestSystemInsets()
    readinessCoordinator.onMainFramePageFinished()
  }

  fun onResume() {
    configureImmersiveSystemBars()
    refreshInjection()
  }

  fun onDestroy() {
    readinessCoordinator.onDestroy()
  }

  fun setImmersiveFullscreenEnabled(enabled: Boolean) {
    immersiveFullscreenEnabled = enabled
    configureImmersiveSystemBars()
    refreshInjection()
  }

  fun isImmersiveFullscreenEnabled(): Boolean = immersiveFullscreenEnabled

  fun refreshInjection() {
    attachSystemInsetsListenerIfNeeded()
    requestSystemInsets()
    readinessCoordinator.syncWhenPageReady()
  }

  private fun resetWebViewInjectionState() {
    lastPushedInsetsSnapshot = null
    hasPendingForcedInsetsPush = false
    hasReadyPageInsetsInjection = false
    webViewInsetsStyleApplier.onWebViewContextReset()
  }

  @Suppress("DEPRECATION")
  private fun configureImmersiveSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isStatusBarContrastEnforced = false
      window.isNavigationBarContrastEnforced = false
    }

    val isDarkMode =
      (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val insetsController = WindowInsetsControllerCompat(window, window.decorView)
    insetsController.isAppearanceLightStatusBars = !isDarkMode
    insetsController.isAppearanceLightNavigationBars = !isDarkMode
    insetsController.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    val systemBarsType =
      WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    if (immersiveFullscreenEnabled) {
      insetsController.hide(systemBarsType)
    } else {
      insetsController.show(systemBarsType)
    }
  }

  private fun attachSystemInsetsListenerIfNeeded() {
    if (isInsetsListenerAttached) {
      return
    }

    val contentRoot = contentRootProvider() ?: return
    ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
      updateWindowInsets(insets)
      filterInsetsForDescendants(insets)
    }
    isInsetsListenerAttached = true
  }

  private fun requestSystemInsets() {
    contentRootProvider()?.let { ViewCompat.requestApplyInsets(it) }
  }

  private fun updateWindowInsets(insets: WindowInsetsCompat) {
    updateSystemBarInsets(insets)
    updateImeInsets(insets)
    pushInsetsToWebView(force = false)
  }

  private fun updateSystemBarInsets(insets: WindowInsetsCompat) {
    if (immersiveFullscreenEnabled) {
      systemBarInsets = Insets.NONE
      return
    }

    val insetTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    val visibleInsets = insets.getInsets(insetTypes)
    val stableInsets = insets.getInsetsIgnoringVisibility(insetTypes)
    systemBarInsets =
      Insets.of(
        maxOf(visibleInsets.left, stableInsets.left),
        maxOf(visibleInsets.top, stableInsets.top),
        maxOf(visibleInsets.right, stableInsets.right),
        maxOf(visibleInsets.bottom, stableInsets.bottom),
      )
  }

  private fun updateImeInsets(insets: WindowInsetsCompat) {
    val imeType = WindowInsetsCompat.Type.ime()
    imeBottomInset = if (insets.isVisible(imeType)) insets.getInsets(imeType).bottom else 0
  }

  private fun filterInsetsForDescendants(insets: WindowInsetsCompat): WindowInsetsCompat {
    val imeType = WindowInsetsCompat.Type.ime()
    // The WebView consumes IME as a host CSS contract (`--tt-ime-bottom`), so
    // descendants must not also reinterpret it as a viewport resize. IME does
    // not support the "ignoring visibility" contract, so we only zero the live
    // inset and visibility state here.
    return WindowInsetsCompat.Builder(insets)
      .setInsets(imeType, Insets.NONE)
      .setVisible(imeType, false)
      .build()
  }

  private fun pushInsetsToWebView(force: Boolean) {
    if (isDestroyed()) {
      return
    }

    val targetWebView = webViewProvider() ?: return
    val navigationGeneration = pageSession.generation
    hasPendingForcedInsetsPush = hasPendingForcedInsetsPush || force
    if (!hasReadyPageInsetsInjection && !force) {
      // Nothing can be applied until the own page reports #sheld ready.
      // Do not post view work for about:blank or external pages.
      return
    }
    if (isInsetsPushScheduled) {
      return
    }
    isInsetsPushScheduled = true

    targetWebView.post {
      isInsetsPushScheduled = false

      // The previous page's pending view post must never mutate the new page.
      if (
        isDestroyed() ||
        !pageSession.isCurrent(navigationGeneration) ||
        !pageSession.ownsCurrentPage()
      ) {
        return@post
      }

      val activeWebView = webViewProvider() ?: return@post
      if (activeWebView !== targetWebView) {
        return@post
      }

      val snapshot = InsetsSnapshot(systemBarInsets, imeBottomInset)
      val shouldForcePush = hasPendingForcedInsetsPush
      hasPendingForcedInsetsPush = false

      if (!hasReadyPageInsetsInjection && !shouldForcePush) {
        return@post
      }

      if (!shouldForcePush && snapshot == lastPushedInsetsSnapshot) {
        return@post
      }

      webViewInsetsStyleApplier.apply(activeWebView, snapshot)
      lastPushedInsetsSnapshot = snapshot
      if (shouldForcePush) {
        hasReadyPageInsetsInjection = true
      }
    }
  }

  companion object {
    private val PAGE_READY_SCRIPT =
      """
      (() =>
        location.href !== 'about:blank' &&
        Boolean(document.getElementById('sheld'))
      )();
      """.trimIndent()
  }
}
