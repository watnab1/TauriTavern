// Local override of Wry's generated Ipc.
// Baseline: wry 0.55.1. Local delta: fail closed for external main-frame pages.

package com.tauritavern.client

import android.webkit.JavascriptInterface

class Ipc(
  val webView: RustWebView,
  val webViewClient: RustWebViewClient,
) {
  @JavascriptInterface
  fun postMessage(message: String?) {
    if (nativeBridgeGuard?.isHostPage() != true) {
      // External pages, teardown races and unguarded states must not reach the
      // Tauri IPC/native command surface.
      return
    }

    message?.let { m ->
      // Wry tracks the current URL on the webview client instead of calling
      // WebView.getUrl() because this callback arrives on a JavaBridge worker.
      Rust.ipc(webView.id, webViewClient.currentUrl, m)
    }
  }

  companion object {
    @Volatile
    var nativeBridgeGuard: TauriTavernNativeJsBridgeGuard? = null
  }
}
