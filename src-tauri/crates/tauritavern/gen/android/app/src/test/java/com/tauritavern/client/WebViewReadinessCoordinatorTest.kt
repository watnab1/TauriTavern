package com.tauritavern.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewReadinessCoordinatorTest {
  private fun createCoordinator(
    runtime: FakeReadinessRuntime,
    session: WebViewPageSession,
    readyEvents: MutableList<String> = mutableListOf(),
  ): WebViewReadinessCoordinator<FakeTarget> =
    WebViewReadinessCoordinator(
      pageSession = session,
      targetProvider = { runtime.target },
      isDestroyed = { runtime.destroyed },
      poller = runtime.poller,
      readinessScript = "readiness()",
      onReady = { readyEvents.add(session.currentUrl ?: "unknown") },
    )

  @Test
  fun `own page polls until ready and stops without leftover work`() {
    val runtime = FakeReadinessRuntime()
    val session = WebViewPageSession()
    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    val readyEvents = mutableListOf<String>()
    val coordinator = createCoordinator(runtime, session, readyEvents)

    coordinator.syncWhenPageReady()
    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "false")
    assertTrue(coordinator.hasActivePoll())
    assertFalse(readyEvents.isNotEmpty())

    runtime.runDelayedOne()
    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "true")

    assertTrue(readyEvents == listOf("https://tauri.localhost/"))
    assertFalse(coordinator.hasActivePoll())
    assertTrue(runtime.postedTasks.isEmpty())
    assertTrue(runtime.delayedTasks.isEmpty())
    assertTrue(runtime.pendingEvaluations.isEmpty())
  }

  @Test
  fun `external page is never probed`() {
    val runtime = FakeReadinessRuntime()
    val session = WebViewPageSession()
    session.onMainFrameNavigationStarted("https://example.com/fixture")
    val readyEvents = mutableListOf<String>()
    val coordinator = createCoordinator(runtime, session, readyEvents)

    coordinator.syncWhenPageReady()

    assertFalse(coordinator.hasActivePoll())
    assertTrue(runtime.postedTasks.isEmpty())
    assertTrue(runtime.pendingEvaluations.isEmpty())
    assertFalse(readyEvents.isNotEmpty())
  }

  @Test
  fun `navigation away from own page invalidates the previous readiness task`() {
    val runtime = FakeReadinessRuntime()
    val session = WebViewPageSession()
    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    val readyEvents = mutableListOf<String>()
    val coordinator = createCoordinator(runtime, session, readyEvents)

    coordinator.syncWhenPageReady()
    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "false")
    assertTrue(coordinator.hasActivePoll())

    session.onMainFrameNavigationStarted("https://example.com/fixture")
    coordinator.onMainFrameNavigationStarted()

    assertFalse(coordinator.hasActivePoll())
    runtime.runDelayedOne()

    assertFalse(readyEvents.isNotEmpty())
    assertTrue(runtime.postedTasks.isEmpty())
    assertTrue(runtime.pendingEvaluations.isEmpty())
  }

  @Test
  fun `returning to an own page re-establishes readiness polling and injection`() {
    val runtime = FakeReadinessRuntime()
    val session = WebViewPageSession()
    val readyEvents = mutableListOf<String>()
    val coordinator = createCoordinator(runtime, session, readyEvents)

    session.onMainFrameNavigationStarted("https://example.com/fixture")
    coordinator.onMainFrameNavigationStarted()
    assertFalse(coordinator.hasActivePoll())

    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    coordinator.onMainFrameNavigationStarted()
    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "true")

    assertTrue(readyEvents == listOf("https://tauri.localhost/"))
  }

  @Test
  fun `destroy cancels readiness work and ignores late evaluate results`() {
    val runtime = FakeReadinessRuntime()
    val session = WebViewPageSession()
    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    val readyEvents = mutableListOf<String>()
    val coordinator = createCoordinator(runtime, session, readyEvents)

    coordinator.syncWhenPageReady()
    runtime.runPostedOne()

    coordinator.onDestroy()
    runtime.deliverEvaluation(result = "true")

    assertFalse(coordinator.hasActivePoll())
    assertFalse(readyEvents.isNotEmpty())
    assertTrue(runtime.delayedTasks.isEmpty())
  }

  @Test
  fun `page finished gives an own page one fresh bounded readiness cycle`() {
    val runtime = FakeReadinessRuntime(maxAttempts = 1)
    val session = WebViewPageSession()
    session.onMainFrameNavigationStarted("https://tauri.localhost/")
    val readyEvents = mutableListOf<String>()
    val coordinator = createCoordinator(runtime, session, readyEvents)

    coordinator.syncWhenPageReady()
    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "false")
    assertFalse(coordinator.hasActivePoll())

    runtime.evaluationResults.addLast("true")
    coordinator.onMainFramePageFinished()
    runtime.runPostedOne()
    runtime.deliverEvaluation()

    assertTrue(readyEvents == listOf("https://tauri.localhost/"))
  }
}
