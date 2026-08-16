package com.tauritavern.client

/**
 * Binds a single readiness poll to the lifetime of the current TauriTavern page.
 *
 * External pages are never probed. A main-frame navigation invalidates the
 * previous page's poll before a new poll is created, so timers, callbacks and
 * evaluate results from the old page cannot observe or mutate the new page's
 * injection state.
 */
class WebViewReadinessCoordinator<T>(
  private val pageSession: WebViewPageSession,
  private val targetProvider: () -> T?,
  private val isDestroyed: () -> Boolean,
  private val poller: ReadinessPoller<T>,
  private val readinessScript: String,
  private val onReady: () -> Unit,
) {
  private var activePoll: WebViewReadinessPoll? = null
  private var scheduledGeneration: Long = WebViewPageSession.INVALID_GENERATION

  fun syncWhenPageReady() {
    if (!pageSession.ownsCurrentPage() || isDestroyed()) {
      cancelActivePoll()
      return
    }

    val target = targetProvider() ?: return
    if (activePoll != null) {
      return
    }

    val generation = pageSession.generation
    var poll: WebViewReadinessPoll? = null
    poll =
      poller.pollUntilReady(
        readinessScript = readinessScript,
        canProbe = { currentTarget ->
          currentTarget === target &&
            pageSession.isCurrent(generation) &&
            pageSession.ownsCurrentPage() &&
            currentTarget === targetProvider() &&
            !isDestroyed()
        },
        onReady = {
          if (
            pageSession.isCurrent(generation) &&
            pageSession.ownsCurrentPage() &&
            !isDestroyed()
          ) {
            onReady()
          }
        },
        onFinished = {
          if (scheduledGeneration == generation && activePoll === poll) {
            activePoll = null
            scheduledGeneration = WebViewPageSession.INVALID_GENERATION
          }
        },
      )

    // The poller posts its first attempt to the target, so it cannot finish
    // synchronously when the preconditions above were satisfied. Keep the
    // assignment after construction to avoid replacing a cancelled poll with a
    // stale token.
    if (!poll.hasFinished()) {
      activePoll = poll
      scheduledGeneration = generation
    }
  }

  fun onMainFrameNavigationStarted() {
    cancelActivePoll()
    syncWhenPageReady()
  }

  fun onMainFramePageFinished() {
    // Give an own page one full bounded retry cycle after load has completed.
    // Late mounting of the host shell (for example a suspended WebView resuming
    // mid-load) therefore recovers without an unbounded background timer loop.
    cancelActivePoll()
    syncWhenPageReady()
  }

  fun onDestroy() {
    cancelActivePoll()
  }

  fun hasActivePoll(): Boolean = activePoll != null

  private fun cancelActivePoll() {
    val poll = activePoll
    activePoll = null
    scheduledGeneration = WebViewPageSession.INVALID_GENERATION
    poll?.cancel()
  }
}
