package com.tauritavern.client

import android.webkit.WebView
import org.json.JSONArray

class SharePayloadDispatcher(
  private val webViewProvider: () -> WebView?,
  private val isDestroyed: () -> Boolean,
  readinessPoller: ReadinessPoller<WebView>,
  private val pageSession: WebViewPageSession,
) {
  private val pendingSharePayloads = ArrayDeque<NativeSharePayload>()
  private val readinessCoordinator: WebViewReadinessCoordinator<WebView> by lazy {
    WebViewReadinessCoordinator(
      pageSession = pageSession,
      targetProvider = webViewProvider,
      isDestroyed = isDestroyed,
      poller = readinessPoller,
      readinessScript = SHARE_BRIDGE_READY_SCRIPT,
      onReady = { flushPendingSharePayloads() },
    )
  }

  fun enqueue(payloads: Collection<NativeSharePayload>) {
    if (payloads.isEmpty()) {
      return
    }

    pendingSharePayloads.addAll(payloads)
    requestDispatch()
  }

  fun requestDispatch() {
    if (pendingSharePayloads.isEmpty()) {
      return
    }

    readinessCoordinator.syncWhenPageReady()
  }

  fun onMainFrameNavigationStarted() {
    readinessCoordinator.onMainFrameNavigationStarted()
    requestDispatch()
  }

  fun onMainFramePageFinished() {
    readinessCoordinator.onMainFramePageFinished()
    requestDispatch()
  }

  fun onDestroy() {
    readinessCoordinator.onDestroy()
  }

  private fun flushPendingSharePayloads() {
    val targetWebView = webViewProvider() ?: return
    if (isDestroyed() || !pageSession.ownsCurrentPage()) {
      return
    }
    if (pendingSharePayloads.isEmpty()) {
      return
    }

    val payloads = mutableListOf<NativeSharePayload>()
    while (pendingSharePayloads.isNotEmpty()) {
      payloads.add(pendingSharePayloads.removeFirst())
    }

    val payloadArray = JSONArray()
    for (payload in payloads) {
      payloadArray.put(payload.toJsonObject())
    }

    val script =
      """
      (() => {
        const bridge = window.__TAURITAVERN_NATIVE_SHARE__;
        if (!bridge || typeof bridge.push !== 'function') return;
        const payloads = $payloadArray;
        for (const payload of payloads) {
          bridge.push(payload);
        }
      })();
      """.trimIndent()
    val navigationGeneration = pageSession.generation

    targetWebView.post {
      val activeWebView = webViewProvider()
      if (
        activeWebView == null ||
        activeWebView !== targetWebView ||
        isDestroyed() ||
        !pageSession.isCurrent(navigationGeneration) ||
        !pageSession.ownsCurrentPage()
      ) {
        requeueAtFront(payloads)
        if (!isDestroyed() && pageSession.ownsCurrentPage()) {
          requestDispatch()
        }
        return@post
      }

      activeWebView.evaluateJavascript(script, null)
    }
  }

  private fun requeueAtFront(payloads: List<NativeSharePayload>) {
    for (index in payloads.indices.reversed()) {
      pendingSharePayloads.addFirst(payloads[index])
    }
  }

  companion object {
    private val SHARE_BRIDGE_READY_SCRIPT =
      """
      (() => (
        document.readyState !== 'loading'
        && location.href !== 'about:blank'
        && !!window.__TAURITAVERN_NATIVE_SHARE__
        && typeof window.__TAURITAVERN_NATIVE_SHARE__.push === 'function'
      ))();
      """.trimIndent()
  }
}
