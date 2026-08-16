package com.tauritavern.client

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebChromeClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class MainActivity : TauriActivity(), AndroidWebFullscreenHost {
  private var webView: WebView? = null
  private val pageSession = WebViewPageSession()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val backgroundExecutor: ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "tauritavern-main-bg").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
  private var isActivityDestroyed: Boolean = false
  private val backNavigationController: AndroidBackNavigationController by lazy {
    AndroidBackNavigationController(
      webViewProvider = { webView },
      consumeNativeBack = { webFullscreenController.hide() },
      exitApp = { finish() },
    )
  }
  private val aiGenerationNotifier: AndroidAiGenerationNotifier by lazy {
    AndroidAiGenerationNotifier(applicationContext)
  }
  private val nativeJsBridgeGuard: TauriTavernNativeJsBridgeGuard by lazy {
    TauriTavernNativeJsBridgeGuard(pageSession)
  }
  private val aiGenerationJsBridge: AndroidAiGenerationJsBridge by lazy {
    AndroidAiGenerationJsBridge(mainHandler, aiGenerationNotifier, nativeJsBridgeGuard)
  }
  private val systemUiJsBridge: AndroidSystemUiJsBridge by lazy {
    AndroidSystemUiJsBridge(mainHandler, insetsBridge, nativeJsBridgeGuard)
  }
  private val importArchivePickerLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      handleImportArchivePickerResult(uri)
    }
  private val exportArchivePickerLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
      handleExportArchivePickerResult(uri)
    }
  private val publicDownloadPickerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      handlePublicDownloadPickerResult(result.resultCode, result.data?.data)
    }
  private val importArchiveJsBridge: AndroidImportArchiveJsBridge by lazy {
    AndroidImportArchiveJsBridge(
      contentResolver = contentResolver,
      launchImportArchivePicker = { launchImportArchivePicker() },
      launchExportArchivePicker = { suggestedName -> launchExportArchivePicker(suggestedName) },
      bridgeGuard = nativeJsBridgeGuard,
    )
  }
  private val publicDownloadJsBridge: AndroidPublicDownloadJsBridge by lazy {
    AndroidPublicDownloadJsBridge(
      contentResolver = contentResolver,
      exportStagingRoot = File(cacheDir, AndroidPublicDownloadJsBridge.EXPORT_STAGING_ROOT_NAME),
      launchCreateDocumentPicker = { suggestedName, mimeType ->
        launchPublicDownloadDocumentPicker(suggestedName, mimeType)
      },
      bridgeGuard = nativeJsBridgeGuard,
    )
  }

  private val readinessPoller: WebViewReadinessPoller<WebView> by lazy {
    WebViewReadinessPoller(
      targetProvider = { webView },
      isDestroyed = { isActivityDestroyed },
      postToTarget = { target, task -> target.post(task) },
      postDelayedToTarget = { target, delayMs, task -> target.postDelayed(task, delayMs) },
      evaluateOnTarget = { target, script, callback -> target.evaluateJavascript(script, callback) },
    )
  }

  private val insetsBridge: AndroidInsetsBridge by lazy {
    AndroidInsetsBridge(
      window = window,
      resources = resources,
      contentRootProvider = { window.decorView.findViewById(android.R.id.content) },
      webViewProvider = { webView },
      isDestroyed = { isActivityDestroyed },
      readinessPoller = readinessPoller,
      pageSession = pageSession,
    )
  }
  private val webFullscreenController: AndroidWebFullscreenController by lazy {
    AndroidWebFullscreenController(
      contentRootProvider = { window.decorView.findViewById<ViewGroup>(android.R.id.content) },
      insetsBridge = insetsBridge,
    )
  }

  private val shareIntentParser: ShareIntentParser by lazy {
    ShareIntentParser(contentResolver = contentResolver, cacheDir = cacheDir)
  }

  private val sharePayloadDispatcher: SharePayloadDispatcher by lazy {
    SharePayloadDispatcher(
      webViewProvider = { webView },
      isDestroyed = { isActivityDestroyed },
      readinessPoller = readinessPoller,
      pageSession = pageSession,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    installWebViewNavigationHooks()
    backNavigationController.register(onBackPressedDispatcher, this)
    aiGenerationNotifier.acknowledgeCompletionNotification()
    insetsBridge.onCreate()
    captureShareIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    aiGenerationNotifier.acknowledgeCompletionNotification()
    captureShareIntent(intent)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    insetsBridge.onConfigurationChanged()
  }

  override fun onWebViewCreate(webView: WebView) {
    configureWebViewDebugging()
    this.webView = webView
    Ipc.nativeBridgeGuard = nativeJsBridgeGuard
    webView.addJavascriptInterface(aiGenerationJsBridge, AndroidAiGenerationJsBridge.INTERFACE_NAME)
    webView.addJavascriptInterface(systemUiJsBridge, AndroidSystemUiJsBridge.INTERFACE_NAME)
    webView.addJavascriptInterface(
      importArchiveJsBridge,
      AndroidImportArchiveJsBridge.INTERFACE_NAME,
    )
    webView.addJavascriptInterface(
      publicDownloadJsBridge,
      AndroidPublicDownloadJsBridge.INTERFACE_NAME,
    )
    insetsBridge.onWebViewAvailable()
    sharePayloadDispatcher.requestDispatch()
  }

  override fun onResume() {
    super.onResume()
    acknowledgeCompletionNotificationIfForeground(
      AndroidAppPresence.setActivityResumed(true),
    )
    insetsBridge.onResume()
    sharePayloadDispatcher.requestDispatch()
  }

  override fun onPause() {
    AndroidAppPresence.setActivityResumed(false)
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    acknowledgeCompletionNotificationIfForeground(
      AndroidAppPresence.setWindowFocused(hasFocus),
    )
  }

  override fun showWebFullscreenView(
    view: View,
    callback: WebChromeClient.CustomViewCallback,
  ): Boolean = webFullscreenController.show(view, callback)

  override fun hideWebFullscreenView(): Boolean = webFullscreenController.hide()

  override fun onDestroy() {
    isActivityDestroyed = true
    Ipc.nativeBridgeGuard = null
    pageSession.invalidate()
    insetsBridge.onDestroy()
    sharePayloadDispatcher.onDestroy()
    mainHandler.removeCallbacksAndMessages(null)
    backgroundExecutor.shutdownNow()
    RustWebViewClient.mainFrameNavigationListener = null
    super.onDestroy()
  }

  private fun configureWebViewDebugging() {
    // Explicit policy for every build variant. Wry additionally enables CDP for
    // debug builds, but Release-like E2E has no debug_assertions and Production
    // must never inherit WebView 113+ auto-debugging by relying on manifest state.
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || BuildConfig.E2E_ENABLED)
  }

  private fun installWebViewNavigationHooks() {
    RustWebViewClient.mainFrameNavigationListener =
      object : RustWebViewClient.MainFrameNavigationListener {
        override fun onMainFramePageStarted(view: WebView, url: String) {
          val activeWebView = webView ?: return
          if (view !== activeWebView) {
            return
          }
          pageSession.onMainFrameNavigationStarted(url)
          insetsBridge.onMainFrameNavigationStarted()
          sharePayloadDispatcher.onMainFrameNavigationStarted()
        }

        override fun onMainFramePageFinished(view: WebView, url: String) {
          val activeWebView = webView ?: return
          if (view !== activeWebView || pageSession.currentUrl != url) {
            return
          }
          insetsBridge.onMainFramePageFinished()
          sharePayloadDispatcher.onMainFramePageFinished()
        }
      }
  }

  private fun acknowledgeCompletionNotificationIfForeground(enteredForegroundInteractive: Boolean) {
    if (enteredForegroundInteractive) {
      aiGenerationNotifier.acknowledgeCompletionNotification()
    }
  }

  private fun captureShareIntent(intent: Intent?) {
    val incomingIntent = intent ?: return
    if (!shareIntentParser.canHandle(incomingIntent)) {
      return
    }

    runOnBackground {
      val payloads = shareIntentParser.parse(Intent(incomingIntent))
      if (payloads.isEmpty()) {
        return@runOnBackground
      }

      mainHandler.post {
        if (isActivityDestroyed) {
          return@post
        }
        sharePayloadDispatcher.enqueue(payloads)
      }
    }
  }

  private fun runOnBackground(task: () -> Unit) {
    try {
      backgroundExecutor.execute(task)
    } catch (_: RejectedExecutionException) {
      // Activity is shutting down.
    }
  }

  private fun launchImportArchivePicker() {
    mainHandler.post {
      if (isActivityDestroyed) {
        return@post
      }

      importArchivePickerLauncher.launch(
        arrayOf(
          "application/zip",
          "application/x-zip-compressed",
          "application/gzip",
          "application/x-gzip",
          "application/x-tar",
          "application/octet-stream",
        ),
      )
    }
  }

  private fun launchExportArchivePicker(suggestedName: String) {
    mainHandler.post {
      if (isActivityDestroyed) {
        return@post
      }

      exportArchivePickerLauncher.launch(suggestedName)
    }
  }

  private fun launchPublicDownloadDocumentPicker(
    suggestedName: String,
    mimeType: String,
  ) {
    mainHandler.post {
      if (isActivityDestroyed) {
        return@post
      }

      val intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = mimeType
          putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
      publicDownloadPickerLauncher.launch(intent)
    }
  }

  private fun handleImportArchivePickerResult(uri: Uri?) {
    if (uri == null) {
      dispatchPickerResult(
        receiverName = IMPORT_ARCHIVE_PICKER_RECEIVER,
        contentUri = "",
        error = "Import archive selection cancelled",
      )
      return
    }

    dispatchPickerResult(
      receiverName = IMPORT_ARCHIVE_PICKER_RECEIVER,
      contentUri = uri.toString(),
      error = "",
    )
  }

  private fun handleExportArchivePickerResult(uri: Uri?) {
    if (uri == null) {
      dispatchPickerResult(
        receiverName = EXPORT_ARCHIVE_PICKER_RECEIVER,
        contentUri = "",
        error = "Export archive destination selection cancelled",
      )
      return
    }

    dispatchPickerResult(
      receiverName = EXPORT_ARCHIVE_PICKER_RECEIVER,
      contentUri = uri.toString(),
      error = "",
    )
  }

  private fun handlePublicDownloadPickerResult(
    resultCode: Int,
    uri: Uri?,
  ) {
    if (resultCode != Activity.RESULT_OK || uri == null) {
      dispatchPickerResult(
        receiverName = PUBLIC_DOWNLOAD_PICKER_RECEIVER,
        contentUri = "",
        error = "Export destination selection cancelled",
      )
      return
    }

    dispatchPickerResult(
      receiverName = PUBLIC_DOWNLOAD_PICKER_RECEIVER,
      contentUri = uri.toString(),
      error = "",
    )
  }

  private fun dispatchPickerResult(
    receiverName: String,
    contentUri: String,
    error: String,
  ) {
    val payload =
      JSONObject()
        .apply {
          put("content_uri", contentUri)
          put("error", error)
        }.toString()

    mainHandler.post {
      val currentWebView = webView ?: return@post
      if (isActivityDestroyed) {
        return@post
      }

      currentWebView.evaluateJavascript(
        "window['$receiverName']?.onNativeResult($payload);",
        null,
      )
    }
  }

  companion object {
    private const val IMPORT_ARCHIVE_PICKER_RECEIVER = "__TAURITAVERN_IMPORT_ARCHIVE_PICKER__"
    private const val EXPORT_ARCHIVE_PICKER_RECEIVER = "__TAURITAVERN_EXPORT_ARCHIVE_PICKER__"
    private const val PUBLIC_DOWNLOAD_PICKER_RECEIVER = "__TAURITAVERN_PUBLIC_DOWNLOAD_PICKER__"
  }
}
