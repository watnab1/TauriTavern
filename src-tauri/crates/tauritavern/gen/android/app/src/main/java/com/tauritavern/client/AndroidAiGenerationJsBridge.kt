package com.tauritavern.client

import android.os.Build
import android.os.Handler
import android.webkit.JavascriptInterface

class AndroidAiGenerationJsBridge(
  private val mainHandler: Handler,
  private val notifier: AndroidAiGenerationNotifier,
  private val bridgeGuard: TauriTavernNativeJsBridgeGuard,
) {
  @JavascriptInterface
  fun onGenerationProgress(outputTokens: Long) {
    bridgeGuard.requireHostPage()
    mainHandler.post { notifier.onGenerationProgress(outputTokens) }
  }

  @JavascriptInterface
  fun supportsLiveUpdates(): Boolean {
    bridgeGuard.requireHostPage()
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
  }

  @JavascriptInterface
  fun supportsNativeCompletion(): Boolean {
    bridgeGuard.requireHostPage()
    return true
  }

  companion object {
    const val INTERFACE_NAME = "TauriTavernAndroidAiBridge"
  }
}
