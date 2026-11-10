package com.google.android.desktop.apprestorer.restore

import android.content.Intent
import android.net.Uri
import android.provider.Browser
import android.util.Log
import android.view.WindowManager
import com.google.android.desktop.apprestorer.WorkspaceRuntime
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.ObservedWindow
import com.google.android.desktop.apprestorer.capture.ResolvedIdentity
import com.google.android.desktop.apprestorer.capture.WindowGeometryClassifier
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.platform.DisplayGeometryProvider
import com.google.android.desktop.apprestorer.platform.IntentFactory
import com.google.android.desktop.apprestorer.service.WorkspaceCaptureAccessibilityService
import kotlinx.coroutines.delay

/** Full-fidelity driver backed by the bound accessibility service. */
class AccessibilityRestoreDriver(
  private val service: WorkspaceCaptureAccessibilityService,
  private val runtime: WorkspaceRuntime,
) : RestoreDriver {

  private val desks = VirtualDeskController(service, runtime)
  private val gestures = WindowGestureController(service)

  override val supportsDesks: Boolean = true

  override suspend fun awaitPromptDismissed() {
    poll(4_000) { !runtime.promptVisible }
    delay(PROMPT_SETTLE_MS) // return-to-Home transition
  }

  override fun currentDisplays(): List<DisplayGeometry> = service.displayProvider.current()

  override suspend fun ensureDeskCount(displayId: Int, count: Int): Int = desks.ensureDeskCount(displayId, count)

  override suspend fun activateDesk(displayId: Int, deskIndex: Int): Boolean = desks.activateDesk(displayId, deskIndex)

  private fun matches(launch: WindowLaunch, id: ResolvedIdentity): Boolean = when (launch.window.kind) {
    AppKind.GEMINI -> id.kind == AppKind.GEMINI
    else -> id.packageName == launch.window.packageName
  }

  private fun matching(launch: WindowLaunch): List<ObservedWindow> =
    service.resolvedAppWindows(launch.targetDisplayId).filter { matches(launch, it.second) }.map { it.first }

  override fun existingWindowIds(launch: WindowLaunch): Set<Int> = matching(launch).map { it.windowId }.toSet()

  override fun launch(launch: WindowLaunch): Boolean =
    try {
      val intent = IntentFactory.toIntent(service, launch.launch)
      service.startActivity(intent, IntentFactory.options(launch).toBundle())
      true
    } catch (e: Exception) {
      Log.w(TAG, "Launch failed for ${launch.window.packageName}", e)
      false
    }

  override suspend fun awaitAttached(launch: WindowLaunch, before: Set<Int>, timeoutMs: Long): AttachedWindow? {
    var found: ObservedWindow? = null
    poll(timeoutMs, 200) {
      found = matching(launch).filter { it.windowId !in before }.maxByOrNull { it.layer }
      found != null
    }
    // Trampolines (e.g. TWA launchers) may focus a pre-existing window instead.
    val w = found ?: matching(launch).firstOrNull { it.isFocused } ?: return null
    delay(ATTACH_SETTLE_MS)
    val latest = matching(launch).firstOrNull { it.windowId == w.windowId } ?: w
    return AttachedWindow(latest.windowId, latest.targetDisplay(launch), latest.bounds)
  }

  private fun ObservedWindow.targetDisplay(l: WindowLaunch) = if (displayId >= 0) displayId else l.targetDisplayId

  override suspend fun correctGeometry(attached: AttachedWindow, launch: WindowLaunch): AttachedWindow {
    val display = currentDisplays().firstOrNull { it.displayId == attached.displayId } ?: return attached
    var current = attached
    repeat(2) { attempt ->
      if (WindowGeometryClassifier.matches(current.bounds, launch.targetBounds, display)) return current
      val wantsMax = launch.targetBounds == display.workArea
      val isCurrentlyMax = WindowGeometryClassifier.matches(current.bounds, display.workArea, display)
      val root = service.rootOfWindow(current.displayId, current.windowId)
      if (wantsMax && attempt == 0) {
        gestures.findCaptionButton(current.displayId, current.bounds, WindowGestureController.CaptionButton.MAXIMIZE, root)?.click()
      } else {
        if (!wantsMax && isCurrentlyMax && attempt == 0) {
          gestures.findCaptionButton(current.displayId, current.bounds, WindowGestureController.CaptionButton.MAXIMIZE, root)?.click()
          delay(400)
          val unmax = service.resolvedAppWindows(current.displayId).firstOrNull { it.first.windowId == current.windowId }?.first
          if (unmax != null) current = current.copy(bounds = unmax.bounds)
        }
        if (!WindowGeometryClassifier.matches(current.bounds, launch.targetBounds, display)) {
          gestures.moveAndResize(current.displayId, current.bounds, launch.targetBounds)
        }
      }
      delay(500)
      val refreshed = service.resolvedAppWindows(current.displayId).firstOrNull { it.first.windowId == current.windowId }?.first
      current = refreshed?.let { current.copy(bounds = it.bounds) } ?: return current
    }
    return current
  }

  override suspend fun minimize(attached: AttachedWindow): Boolean {
    val root = service.rootOfWindow(attached.displayId, attached.windowId)
    val button = gestures.findCaptionButton(attached.displayId, attached.bounds, WindowGestureController.CaptionButton.MINIMIZE, root)
      ?: return false
    val ok = button.click()
    delay(350)
    return ok
  }

  override suspend fun reconcileChromeTabs(attached: AttachedWindow, launch: WindowLaunch): Int {
    val saved = launch.window.tabUrls
    if (saved.isEmpty()) return 0
    delay(CHROME_TAB_RESTORE_SETTLE_MS) // let TabPersistentStore finish restoring tab_state
    val root = service.rootOfWindow(attached.displayId, attached.windowId) ?: return 0
    val facts = TwaAndBrowserInspector.readBrowserFacts(root)
    val missing = TabReconciler.missingTabs(
      savedUrls = saved,
      savedTitles = launch.window.tabTitles,
      restoredTitles = facts.tabTitles,
      restoredCount = facts.tabCount,
      currentUrl = TwaAndBrowserInspector.normalizeUrl(facts.urlBarText),
      chromeRestoresNatively = launch.isPrimaryBrowserWindow,
    )
    var opened = 0
    for (url in missing) {
      try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
          .setPackage(launch.window.packageName)
          .addCategory(Intent.CATEGORY_BROWSABLE)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
        service.startActivity(intent, IntentFactory.options(attached.displayId, null).toBundle())
        opened++
        delay(450)
      } catch (e: Exception) {
        Log.w(TAG, "Could not reopen tab $url", e)
      }
    }
    return opened
  }

  override suspend fun purgePhantomBrowserWindows(displayId: Int, keepWindowIds: Set<Int>): Int {
    var purged = 0
    for ((o, id) in service.resolvedAppWindows(displayId)) {
      if (id.kind != AppKind.CHROME_BROWSER || o.windowId in keepWindowIds) continue
      if (WindowGeometryClassifier.isPlausibleWindow(o.bounds, isBrowser = true)) continue
      val root = service.rootOfWindow(displayId, o.windowId)
      val close = gestures.findCaptionButton(displayId, o.bounds, WindowGestureController.CaptionButton.CLOSE, root) ?: continue
      if (close.click()) purged++
      delay(250)
    }
    return purged
  }

  companion object {
    private const val TAG = "A11yRestoreDriver"
    const val PROMPT_SETTLE_MS = 700L
    const val ATTACH_SETTLE_MS = 450L
    const val CHROME_TAB_RESTORE_SETTLE_MS = 1_600L
  }
}

/**
 * Fallback when the accessibility service is not bound: still relaunches every window on its
 * display at its saved bounds and in Z-order, without desks / minimize / verification.
 */
class BasicRestoreDriver(private val runtime: WorkspaceRuntime) : RestoreDriver {
  private val context = runtime.appContext
  override val supportsDesks: Boolean = false

  override suspend fun awaitPromptDismissed() {
    poll(4_000) { !runtime.promptVisible }
    delay(AccessibilityRestoreDriver.PROMPT_SETTLE_MS)
  }

  override fun currentDisplays(): List<DisplayGeometry> =
    DisplayGeometryProvider(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).current()

  override suspend fun ensureDeskCount(displayId: Int, count: Int): Int = 1
  override suspend fun activateDesk(displayId: Int, deskIndex: Int): Boolean = false
  override fun existingWindowIds(launch: WindowLaunch): Set<Int> = emptySet()

  override fun launch(launch: WindowLaunch): Boolean =
    try {
      context.startActivity(IntentFactory.toIntent(context, launch.launch), IntentFactory.options(launch).toBundle())
      true
    } catch (e: Exception) {
      Log.w("BasicRestoreDriver", "Launch failed for ${launch.window.packageName}", e)
      false
    }

  override suspend fun awaitAttached(launch: WindowLaunch, before: Set<Int>, timeoutMs: Long): AttachedWindow? {
    delay(1_200) // keep Z-order deterministic without window inspection
    return null
  }

  override suspend fun correctGeometry(attached: AttachedWindow, launch: WindowLaunch) = attached
  override suspend fun minimize(attached: AttachedWindow) = false
  override suspend fun reconcileChromeTabs(attached: AttachedWindow, launch: WindowLaunch) = 0
  override suspend fun purgePhantomBrowserWindows(displayId: Int, keepWindowIds: Set<Int>) = 0
}

/** Pure zero-duplicate tab reconciliation (1 tab → 1 tab, never opened twice). */
object TabReconciler {
  fun missingTabs(
    savedUrls: List<String>,
    savedTitles: List<String>,
    restoredTitles: List<String>,
    restoredCount: Int,
    currentUrl: String?,
    chromeRestoresNatively: Boolean,
  ): List<String> {
    val restoredTitleSet = restoredTitles.map { it.trim().lowercase() }.toSet()
    fun alreadyOpen(i: Int, url: String): Boolean {
      if (currentUrl != null && sameUrl(url, currentUrl)) return true
      val t = savedTitles.getOrNull(i)?.trim()?.lowercase().orEmpty()
      return t.isNotEmpty() && t in restoredTitleSet
    }
    val candidates = savedUrls.withIndex().filter { (i, u) -> !alreadyOpen(i, u) }.map { it.value }.distinct()
    if (!chromeRestoresNatively) return candidates
    // Chrome restored its own tab_state: only add what it is provably missing.
    if (restoredTitles.isEmpty()) {
      val deficit = (savedUrls.size - restoredCount).coerceAtLeast(0)
      // Nothing readable: trust Chrome's native restore rather than risk duplicates.
      return if (restoredCount <= 0) emptyList() else candidates.takeLast(deficit)
    }
    return candidates
  }

  private fun sameUrl(a: String, b: String): Boolean {
    fun n(s: String) = s.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    return n(a) == n(b)
  }
}
