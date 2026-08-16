package com.tauritavern.client

import java.net.URI
import java.util.Locale

enum class WebViewPageOwnership {
  /** The current main-frame URL belongs to the TauriTavern host app. */
  TAURITAVERN,

  /** The current main-frame URL belongs to a page outside the host app. */
  EXTERNAL,

  /** No main-frame URL has been observed yet (for example before the first navigation). */
  UNKNOWN,
}

/**
 * Tracks the main-frame page identity for a single WebView.
 *
 * Navigation events are delivered on the main thread, but JavaScript bridge calls
 * can arrive on the JavaBridge worker thread. The mutable state is therefore kept
 * volatile so those workers either observe the previous or the next main-frame page,
 * never a partially written URL/generation pair.
 */
class WebViewPageSession(
  tauriTavernHosts: Set<String> = setOf("tauri.localhost"),
) {
  private val tauriTavernHosts: Set<String> =
    tauriTavernHosts.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
  @Volatile
  var generation: Long = INITIAL_GENERATION
    private set

  @Volatile
  var currentUrl: String? = null
    private set

  @Volatile
  var ownership: WebViewPageOwnership = WebViewPageOwnership.UNKNOWN
    private set

  fun onMainFrameNavigationStarted(url: String) {
    generation += 1
    currentUrl = url
    ownership = classify(url)
  }

  fun invalidate() {
    generation += 1
    currentUrl = null
    ownership = WebViewPageOwnership.UNKNOWN
  }

  fun ownsCurrentPage(): Boolean = ownership == WebViewPageOwnership.TAURITAVERN

  fun isCurrent(generation: Long): Boolean = generation == this.generation

  fun classify(url: String?): WebViewPageOwnership {
    if (url.isNullOrBlank()) {
      return WebViewPageOwnership.UNKNOWN
    }

    val uri =
      try {
        URI(url.trim())
      } catch (_: IllegalArgumentException) {
        return WebViewPageOwnership.EXTERNAL
      }

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (host == null || scheme == null) {
      return WebViewPageOwnership.UNKNOWN
    }

    val isTauriTavernOrigin =
      (
        host in tauriTavernHosts &&
          (scheme == "http" || scheme == "https")
        ) ||
        (scheme == "tauri" && host == "localhost")

    return if (isTauriTavernOrigin) {
      WebViewPageOwnership.TAURITAVERN
    } else {
      WebViewPageOwnership.EXTERNAL
    }
  }

  companion object {
    const val INITIAL_GENERATION = 0L
    const val INVALID_GENERATION = Long.MIN_VALUE
  }
}
