package com.tauritavern.client

class FakeTarget

class FakeReadinessRuntime(
  private val maxAttempts: Int = WebViewReadinessPoller.DEFAULT_MAX_ATTEMPTS,
) {
  var target: FakeTarget? = FakeTarget()
  var destroyed: Boolean = false

  val postedTasks = ArrayDeque<() -> Unit>()
  val delayedTasks = ArrayDeque<DelayedTask>()
  val pendingEvaluations = ArrayDeque<Evaluation>()
  val evaluationResults = ArrayDeque<String>()

  val poller: WebViewReadinessPoller<FakeTarget> =
    WebViewReadinessPoller(
      targetProvider = { target },
      isDestroyed = { destroyed },
      postToTarget = { _, task -> postedTasks.addLast(task) },
      postDelayedToTarget = { _, _, task -> delayedTasks.addLast(DelayedTask(task)) },
      evaluateOnTarget = { _, script, callback ->
        pendingEvaluations.addLast(Evaluation(script, callback))
      },
      maxAttempts = maxAttempts,
      retryDelayMs = 1L,
    )

  fun runPostedOne() {
    postedTasks.removeFirstOrNull()?.invoke()
  }

  fun runPostedAll() {
    while (postedTasks.isNotEmpty()) {
      postedTasks.removeFirst().invoke()
    }
  }

  fun runDelayedOne() {
    delayedTasks.removeFirstOrNull()?.task?.invoke()
  }

  fun deliverEvaluation(result: String = nextEvaluationResult()) {
    pendingEvaluations.removeFirst().callback(result)
  }

  fun nextEvaluationResult(): String = evaluationResults.removeFirstOrNull() ?: "false"

  data class DelayedTask(val task: () -> Unit)

  data class Evaluation(val script: String, val callback: (String) -> Unit)
}
