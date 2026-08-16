package com.tauritavern.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewReadinessPollerTest {
  @Test
  fun `reports ready and finishes once when the page is already ready`() {
    val runtime = FakeReadinessRuntime()
    runtime.evaluationResults.addLast("true")
    var ready = false
    var finished = 0

    val poll =
      runtime.poller.pollUntilReady(
        readinessScript = "ready()",
        canProbe = { true },
        onReady = { ready = true },
        onFinished = { finished += 1 },
      )

    runtime.runPostedOne()
    runtime.deliverEvaluation()

    assertTrue(ready)
    assertTrue(poll.hasFinished())
    assertTrue(finished == 1)
    assertTrue(runtime.delayedTasks.isEmpty())
  }

  @Test
  fun `retries while the page reports not-ready and stops after ready`() {
    val runtime = FakeReadinessRuntime()
    var ready = false

    runtime.poller.pollUntilReady(
      readinessScript = "ready()",
      canProbe = { true },
      onReady = { ready = true },
      onFinished = {},
    )

    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "false")
    assertFalse(ready)
    assertFalse(runtime.delayedTasks.isEmpty())

    runtime.runDelayedOne()
    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "true")
    assertTrue(ready)
    assertTrue(runtime.postedTasks.isEmpty())
    assertTrue(runtime.delayedTasks.isEmpty())
    assertTrue(runtime.pendingEvaluations.isEmpty())
  }

  @Test
  fun `cancel stops the pending retry chain`() {
    val runtime = FakeReadinessRuntime()
    var ready = false
    var finished = 0

    val poll =
      runtime.poller.pollUntilReady(
        readinessScript = "ready()",
        canProbe = { true },
        onReady = { ready = true },
        onFinished = { finished += 1 },
      )

    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "false")

    poll.cancel()

    assertTrue(poll.hasFinished())
    assertTrue(finished == 1)
    runtime.runDelayedOne()

    assertFalse(ready)
    assertTrue(runtime.postedTasks.isEmpty())
    assertTrue(runtime.pendingEvaluations.isEmpty())
  }

  @Test
  fun `a late evaluate result cannot reach onReady after cancel`() {
    val runtime = FakeReadinessRuntime()
    var ready = false

    val poll =
      runtime.poller.pollUntilReady(
        readinessScript = "ready()",
        canProbe = { true },
        onReady = { ready = true },
        onFinished = {},
      )

    runtime.runPostedOne()
    poll.cancel()
    runtime.deliverEvaluation(result = "true")

    assertFalse(ready)
    assertTrue(runtime.delayedTasks.isEmpty())
  }

  @Test
  fun `probe predicate becoming false ends the poll without another retry`() {
    val runtime = FakeReadinessRuntime()
    var canProbe = true
    var ready = false

    runtime.poller.pollUntilReady(
      readinessScript = "ready()",
      canProbe = { canProbe },
      onReady = { ready = true },
      onFinished = {},
    )

    runtime.runPostedOne()
    canProbe = false
    runtime.deliverEvaluation(result = "false")

    assertFalse(ready)
    assertTrue(runtime.delayedTasks.isEmpty())
  }

  @Test
  fun `destroyed target ends the poll before evaluating`() {
    val runtime = FakeReadinessRuntime()
    runtime.destroyed = true
    var finished = false

    runtime.poller.pollUntilReady(
      readinessScript = "ready()",
      canProbe = { true },
      onReady = {},
      onFinished = { finished = true },
    )

    runtime.runPostedOne()

    assertTrue(finished)
    assertTrue(runtime.pendingEvaluations.isEmpty())
  }

  @Test
  fun `bounded attempts end without onReady when readiness never appears`() {
    val runtime = FakeReadinessRuntime(maxAttempts = 1)
    var ready = false
    var finished = false

    runtime.poller.pollUntilReady(
      readinessScript = "ready()",
      canProbe = { true },
      onReady = { ready = true },
      onFinished = { finished = true },
    )

    runtime.runPostedOne()
    runtime.deliverEvaluation(result = "false")

    assertFalse(ready)
    assertTrue(finished)
    assertTrue(runtime.delayedTasks.isEmpty())
  }
}
