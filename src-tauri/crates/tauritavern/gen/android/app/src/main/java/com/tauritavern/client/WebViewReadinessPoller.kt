package com.tauritavern.client

/**
 * A cancellable readiness poll.
 *
 * [cancel] is idempotent and always runs [WebViewReadinessPoller.pollUntilReady]'s
 * `onFinished` callback exactly once. After the poll is finished, late timers and
 * late `evaluateJavascript` results become no-ops; they cannot reach `onReady`.
 */
class WebViewReadinessPoll internal constructor(
  private val onFinished: () -> Unit,
) {
  @Volatile
  private var isFinished: Boolean = false

  fun cancel() {
    finish()
  }

  fun hasFinished(): Boolean = isFinished

  internal fun finish() {
    val shouldNotify =
      synchronized(this) {
        if (isFinished) {
          false
        } else {
          isFinished = true
          true
        }
      }

    if (shouldNotify) {
      onFinished()
    }
  }
}

interface ReadinessPoller<T> {
  fun pollUntilReady(
    readinessScript: String,
    canProbe: (T) -> Boolean,
    onReady: () -> Unit,
    onFinished: () -> Unit,
  ): WebViewReadinessPoll
}

/**
 * Retries a JavaScript readiness predicate on a WebView-like target until it
 * reports `true`, the page session changes, the target disappears, or the
 * configured bounded attempt limit is exhausted.
 *
 * The target is generic so the retry/cancellation state machine can be tested
 * on the JVM with an in-memory target instead of an Android WebView.
 */
class WebViewReadinessPoller<T>(
  private val targetProvider: () -> T?,
  private val isDestroyed: () -> Boolean,
  private val postToTarget: (T, () -> Unit) -> Unit,
  private val postDelayedToTarget: (T, Long, () -> Unit) -> Unit,
  private val evaluateOnTarget: (T, String, (String) -> Unit) -> Unit,
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
  private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) : ReadinessPoller<T> {
  override fun pollUntilReady(
    readinessScript: String,
    canProbe: (T) -> Boolean,
    onReady: () -> Unit,
    onFinished: () -> Unit,
  ): WebViewReadinessPoll {
    val poll = WebViewReadinessPoll(onFinished)
    startAttempt(
      poll = poll,
      readinessScript = readinessScript,
      canProbe = canProbe,
      onReady = onReady,
      attempt = 0,
    )
    return poll
  }

  private fun startAttempt(
    poll: WebViewReadinessPoll,
    readinessScript: String,
    canProbe: (T) -> Boolean,
    onReady: () -> Unit,
    attempt: Int,
  ) {
    if (poll.hasFinished()) {
      return
    }

    val target = targetProvider()
    if (target == null || isDestroyed()) {
      poll.finish()
      return
    }

    postToTarget(target) {
      if (poll.hasFinished()) {
        return@postToTarget
      }

      val activeTarget = targetProvider()
      if (activeTarget == null || activeTarget !== target || isDestroyed() || !canProbe(activeTarget)) {
        poll.finish()
        return@postToTarget
      }

      evaluateOnTarget(activeTarget, readinessScript) { value ->
        if (poll.hasFinished() || isDestroyed()) {
          poll.finish()
          return@evaluateOnTarget
        }

        val currentTarget = targetProvider()
        if (currentTarget == null || currentTarget !== target || !canProbe(currentTarget)) {
          poll.finish()
          return@evaluateOnTarget
        }

        if (value == "true") {
          poll.finish()
          onReady()
          return@evaluateOnTarget
        }

        if (attempt + 1 >= maxAttempts) {
          poll.finish()
          return@evaluateOnTarget
        }

        postDelayedToTarget(activeTarget, retryDelayMs) {
          startAttempt(
            poll = poll,
            readinessScript = readinessScript,
            canProbe = canProbe,
            onReady = onReady,
            attempt = attempt + 1,
          )
        }
      }
    }
  }

  companion object {
    const val DEFAULT_MAX_ATTEMPTS = 100
    const val DEFAULT_RETRY_DELAY_MS = 80L
  }
}
