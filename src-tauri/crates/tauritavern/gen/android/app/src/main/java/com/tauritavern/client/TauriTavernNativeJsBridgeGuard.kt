package com.tauritavern.client

/**
 * Fail-closed admission for the app-owned JavaScript bridges.
 *
 * Unknown pages (before the first main-frame URL is observed) are admitted so
 * the host app can bootstrap. Once the current main frame is known to be an
 * external page, native entry points reject the call.
 */
class TauriTavernNativeJsBridgeGuard(
  private val pageSession: WebViewPageSession,
) {
  fun isHostPage(): Boolean = pageSession.ownership != WebViewPageOwnership.EXTERNAL

  fun requireHostPage() {
    check(isHostPage()) {
      "TauriTavern native bridge is not available to external pages."
    }
  }
}
