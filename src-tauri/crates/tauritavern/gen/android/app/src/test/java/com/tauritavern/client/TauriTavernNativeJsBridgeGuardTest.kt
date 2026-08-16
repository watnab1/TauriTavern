package com.tauritavern.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TauriTavernNativeJsBridgeGuardTest {
  @Test
  fun `admits unknown and host pages during bootstrap`() {
    val session = WebViewPageSession()
    val guard = TauriTavernNativeJsBridgeGuard(session)

    assertTrue(guard.isHostPage())
    guard.requireHostPage()

    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    assertTrue(guard.isHostPage())
    guard.requireHostPage()
  }

  @Test
  fun `rejects external main-frame pages`() {
    val session = WebViewPageSession()
    val guard = TauriTavernNativeJsBridgeGuard(session)
    session.onMainFrameNavigationStarted("https://example.com/fixture")

    assertFalse(guard.isHostPage())
    try {
      guard.requireHostPage()
      throw AssertionError("expected requireHostPage to reject the external page")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message.orEmpty().contains("external pages"))
    }
  }
}
