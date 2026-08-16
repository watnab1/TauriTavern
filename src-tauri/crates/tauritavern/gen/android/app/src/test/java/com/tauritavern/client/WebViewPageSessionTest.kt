package com.tauritavern.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewPageSessionTest {
  @Test
  fun `starts unknown before the first main-frame URL`() {
    val session = WebViewPageSession()

    assertEquals(WebViewPageOwnership.UNKNOWN, session.ownership)
    assertFalse(session.ownsCurrentPage())
  }

  @Test
  fun `classifies TauriTavern asset-loader pages as owned`() {
    val session = WebViewPageSession()

    assertEquals(
      WebViewPageOwnership.TAURITAVERN,
      session.classify("https://tauri.localhost/"),
    )
    assertEquals(
      WebViewPageOwnership.TAURITAVERN,
      session.classify("http://tauri.localhost/index.html?x=1"),
    )
    assertEquals(
      WebViewPageOwnership.TAURITAVERN,
      session.classify("tauri://localhost/index.html"),
    )
  }

  @Test
  fun `classifies non-host pages as external without hardcoding any test site`() {
    val session = WebViewPageSession()

    assertEquals(
      WebViewPageOwnership.EXTERNAL,
      session.classify("https://example.com/plain-page"),
    )
    assertEquals(
      WebViewPageOwnership.EXTERNAL,
      session.classify("http://127.0.0.1:8080/fixture.html"),
    )
    assertEquals(
      WebViewPageOwnership.EXTERNAL,
      session.classify("https://test.invalid/"),
    )
  }

  @Test
  fun `treats transitional URLs as unknown until a real page starts`() {
    val session = WebViewPageSession()

    assertEquals(WebViewPageOwnership.UNKNOWN, session.classify("about:blank"))
    assertEquals(WebViewPageOwnership.UNKNOWN, session.classify(null))
    assertEquals(WebViewPageOwnership.UNKNOWN, session.classify(""))
  }

  @Test
  fun `main-frame navigation advances the generation and changes ownership`() {
    val session = WebViewPageSession()
    val initialGeneration = session.generation

    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    val ownGeneration = session.generation

    assertTrue(ownGeneration > initialGeneration)
    assertEquals("https://tauri.localhost/", session.currentUrl)
    assertTrue(session.ownsCurrentPage())
    assertTrue(session.isCurrent(ownGeneration))
    assertFalse(session.isCurrent(initialGeneration))

    session.onMainFrameNavigationStarted("https://example.com/fixture")
    val externalGeneration = session.generation

    assertTrue(externalGeneration > ownGeneration)
    assertEquals(WebViewPageOwnership.EXTERNAL, session.ownership)
    assertFalse(session.ownsCurrentPage())
    assertFalse(session.isCurrent(ownGeneration))
  }

  @Test
  fun `invalidate ends the current page session`() {
    val session = WebViewPageSession()
    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    val pageGeneration = session.generation

    session.invalidate()

    assertEquals(WebViewPageOwnership.UNKNOWN, session.ownership)
    assertFalse(session.ownsCurrentPage())
    assertFalse(session.isCurrent(pageGeneration))
    assertTrue(session.generation > pageGeneration)
  }
}
