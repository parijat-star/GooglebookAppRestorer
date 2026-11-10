package com.google.android.desktop.apprestorer.restore

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.google.android.desktop.apprestorer.WorkspaceRuntime
import com.google.android.desktop.apprestorer.inspect.OverviewInspection
import com.google.android.desktop.apprestorer.inspect.OverviewDeskInspector
import com.google.android.desktop.apprestorer.inspect.UiNode
import com.google.android.desktop.apprestorer.inspect.findAll
import com.google.android.desktop.apprestorer.inspect.findFirst
import com.google.android.desktop.apprestorer.inspect.hasAnyId
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.service.DiagnosticsLog
import com.google.android.desktop.apprestorer.service.WorkspaceCaptureAccessibilityService
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recreates Virtual Desks 2..N in Overview and activates a specific desk, per display:
 * AddDesktopButton (:id/add_desktop_button) → poll up to 2.4 s for a new DesktopTaskView + spring
 * settle → ACTION_SHOW_ON_SCREEN on the target card → click :id/task_view_desktop
 * (DesktopTaskView.onClick → activateDesk) → verify Overview closed.
 */
class VirtualDeskController(
  private val service: WorkspaceCaptureAccessibilityService,
  private val runtime: WorkspaceRuntime,
  private val gestures: WindowGestureController = WindowGestureController(service),
) {
  fun inspect(displayId: Int): OverviewInspection = OverviewDeskInspector.inspect(service.homeRoots(displayId))

  private fun displaySize(displayId: Int): Pair<Int, Int> {
    val g = service.displayProvider.current().firstOrNull { it.displayId == displayId }
    return (g?.widthPx ?: 2560) to (g?.heightPx ?: 1600)
  }

  suspend fun openOverview(displayId: Int): Boolean {
    if (inspect(displayId).isOverviewVisible) return true
    val opened = if (displayId != 0) clickTaskbarRecents(displayId) || service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    if (!opened) return false
    return poll(2_500) { inspect(displayId).isOverviewVisible }
  }

  private fun clickTaskbarRecents(displayId: Int): Boolean =
    service.homeRoots(displayId).firstNotNullOfOrNull { root ->
      root.findFirst(600) { it.hasAnyId("recent_apps", "taskbar_recents", "recents_button") }
    }?.click() == true

  /** Scrolls the RecentsView carousel horizontally until [deskIndex] is prominently on-screen. */
  private suspend fun scrollToDeskCard(displayId: Int, deskIndex: Int) {
    val (w, h) = displaySize(displayId)
    repeat(6) {
      val ov = inspect(displayId)
      val card = ov.desks.getOrNull(deskIndex) ?: return
      val b = card.node?.boundsInScreen ?: WindowBounds.EMPTY
      val visW = b.right.coerceIn(0, w) - b.left.coerceIn(0, w)
      if (visW >= (w * 0.45f).toInt()) return
      val centered = ov.desks.indexOfFirst {
        val cb = it.node?.boundsInScreen ?: WindowBounds.EMPTY
        (cb.right.coerceIn(0, w) - cb.left.coerceIn(0, w)) >= (w * 0.45f).toInt()
      }
      val goRight = if (centered >= 0) deskIndex > centered else (b.left >= w / 2 || b.isEmpty)
      val swiped = if (goRight) {
        gestures.swipe(displayId, w * 0.78f, h * 0.50f, w * 0.22f, h * 0.50f) ||
          (ov.overviewPanel?.scrollForward() == true)
      } else {
        gestures.swipe(displayId, w * 0.22f, h * 0.50f, w * 0.78f, h * 0.50f) ||
          (ov.overviewPanel?.scrollBackward() == true)
      }
      if (!swiped) card.node?.showOnScreen()
      delay(SPRING_SETTLE_MS + 80)
    }
  }

  suspend fun ensureDeskCount(displayId: Int, count: Int): Int {
    if (count <= 1) return 1
    if (!openOverview(displayId)) return 1
    val (w, h) = displaySize(displayId)
    var current = inspect(displayId).desks.size
    var attempts = 0
    while (current < count && attempts < count + 3) {
      attempts++
      if (current > 0) scrollToDeskCard(displayId, current - 1)
      var ov = inspect(displayId)
      var button = ov.addDeskButton ?: break
      val before = current
      var triggered = button.click()
      if (!triggered) {
        gestures.swipe(displayId, w * 0.78f, h * 0.50f, w * 0.28f, h * 0.50f)
        delay(SPRING_SETTLE_MS)
        ov = inspect(displayId)
        button = ov.addDeskButton ?: break
        val bb = button.boundsInScreen
        triggered = button.click() ||
          (!bb.isEmpty && bb.left in 0 until w && gestures.tap(displayId, bb.centerX.toFloat(), bb.centerY.toFloat()))
      }
      if (!triggered) break
      // AddDesktopButton may either stay in Overview with N+1 cards or activate the new desk directly.
      poll(2_400) {
        val next = inspect(displayId)
        !next.isOverviewVisible || next.desks.size > before
      }
      delay(SPRING_SETTLE_MS)
      if (!inspect(displayId).isOverviewVisible) {
        if (!openOverview(displayId)) {
          current = before + 1
          runtime.onEngineMaybeChanged(runtime.engine.onDeskAdded(displayId))
          break
        }
      }
      current = inspect(displayId).desks.size
      if (current > before) runtime.onEngineMaybeChanged(runtime.engine.onDeskAdded(displayId))
    }
    return current.coerceAtLeast(1)
  }

  suspend fun activateDesk(displayId: Int, deskIndex: Int): Boolean {
    if (!openOverview(displayId)) {
      DiagnosticsLog.add("activateDesk($displayId, ${deskIndex + 1}): openOverview failed")
      return false
    }
    scrollToDeskCard(displayId, deskIndex)
    var card = inspect(displayId).desks.getOrNull(deskIndex) ?: return false
    val clicked = card.node?.click() == true
    var closed = clicked && poll(900) { !inspect(displayId).isOverviewVisible }
    if (!closed) {
      val (w, h) = displaySize(displayId)
      val refreshed = inspect(displayId).desks.getOrNull(deskIndex) ?: card
      val cb = refreshed.node?.boundsInScreen ?: WindowBounds(w / 4, h / 4, w * 3 / 4, h * 3 / 4)
      val x0 = cb.left.coerceIn(80, w - 80)
      val x1 = cb.right.coerceIn(80, w - 80)
      val tapX = if (x1 > x0) (x0 + x1) / 2f else w / 2f
      // Below the top-left "Desktop" menu chip (y ~ 189..255), safely inside the desk card body.
      val tapY = (cb.top + cb.height * 0.58f).coerceIn(280f, (h - 260).toFloat())
      DiagnosticsLog.add("activateDesk($displayId, ${deskIndex + 1}): tap ($tapX, $tapY) bounds=$cb clicked=$clicked")
      gestures.tap(displayId, tapX, tapY)
      closed = poll(2_000) { !inspect(displayId).isOverviewVisible }
    }
    if (closed) {
      runtime.onEngineMaybeChanged(runtime.engine.onDeskSwitchHint(displayId, deskIndex, authoritative = true))
      delay(450)
    }
    return closed
  }

  companion object {
    const val SPRING_SETTLE_MS = 350L
  }
}

/** Caption-button and gesture automation for individual desktop windows. */
class WindowGestureController(private val service: WorkspaceCaptureAccessibilityService) {

  enum class CaptionButton(val ids: Array<String>, val descPrefix: String) {
    CLOSE(arrayOf("close_window", "close_button"), "Close"),
    MINIMIZE(arrayOf("minimize_window", "minimize_button_view", "minimize_button"), "Minimi"),
    MAXIMIZE(arrayOf("maximize_window", "maximize_button_view", "maximize_button"), "Maximi"),
  }

  private val density get() = service.resources.displayMetrics.density

  /**
   * The WMShell caption (id=desktop_mode_caption) belonging to the window at [windowBounds]:
   * same left/right edges and top as the window (it overlays the window's top strip).
   */
  fun captionRoot(displayId: Int, windowBounds: WindowBounds): UiNode? {
    val tol = (density * 8).toInt().coerceAtLeast(6)
    return service.systemUiRoots(displayId).firstOrNull { root ->
      val b = root.boundsInScreen
      (root.hasAnyId("desktop_mode_caption") || root.findFirst(4) { it.hasAnyId("desktop_mode_caption") } != null) &&
        kotlin.math.abs(b.left - windowBounds.left) <= tol && kotlin.math.abs(b.right - windowBounds.right) <= tol &&
        kotlin.math.abs(b.top - windowBounds.top) <= tol
    }
  }

  /** Finds a caption button (Minimize / Maximize / Close) of the window at [windowBounds]. */
  fun findCaptionButton(displayId: Int, windowBounds: WindowBounds, button: CaptionButton, appRoot: UiNode?): UiNode? {
    fun matches(n: UiNode) = n.hasAnyId(*button.ids) || n.contentDescription?.startsWith(button.descPrefix, ignoreCase = true) == true
    captionRoot(displayId, windowBounds)?.let { root -> root.findFirst(80) { matches(it) }?.let { return it } }
    // Fallback: any caption button horizontally inside the window, near its top edge.
    val band = (density * 40).toInt()
    val candidates = service.systemUiRoots(displayId) + listOfNotNull(appRoot)
    return candidates.asSequence().flatMap { root -> root.findAll(300) { matches(it) }.asSequence() }
      .firstOrNull { n ->
        val b = n.boundsInScreen
        b.centerX in windowBounds.left..windowBounds.right && b.centerY in (windowBounds.top - band / 2)..(windowBounds.top + band * 2)
      }
  }

  /** Draggable caption area (id=caption_handle) or the caption itself. */
  fun captionBar(displayId: Int, windowBounds: WindowBounds): WindowBounds? {
    val root = captionRoot(displayId, windowBounds) ?: return null
    return (root.findFirst(40) { it.hasAnyId("caption_handle") } ?: root).boundsInScreen
  }

  /** Drags the caption to move the window, then the bottom-right corner to resize it. */
  suspend fun moveAndResize(displayId: Int, from: WindowBounds, to: WindowBounds): Boolean {
    val captionH = (density * 40).toInt()
    val bar = captionBar(displayId, from)
    val grabX = (bar?.let { it.left + it.width * 0.5f } ?: (from.left + from.width * 0.5f))
    val grabY = (bar?.let { it.centerY.toFloat() } ?: (from.top + captionH / 2f))
    var ok = true
    if (from.left != to.left || from.top != to.top) {
      ok = drag(displayId, grabX, grabY, grabX + (to.left - from.left), grabY + (to.top - from.top))
      delay(450)
    }
    if (from.width != to.width || from.height != to.height) {
      val cx = to.left + from.width - 3f
      val cy = to.top + from.height - 3f
      ok = drag(displayId, cx, cy, to.right - 3f, to.bottom - 3f) && ok
      delay(450)
    }
    return ok
  }

  /** Single-stroke tap (used when accessibility ACTION_CLICK is ignored by an Overview card). */
  suspend fun tap(displayId: Int, x: Float, y: Float): Boolean {
    val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y) }
    return dispatch(displayId, GestureDescription.StrokeDescription(path, 0, 60, false))
  }

  /** Fast single-stroke swipe without a stationary hold (scrolls paged carousels without dragging). */
  suspend fun swipe(displayId: Int, x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long = 240): Boolean {
    val path = Path().apply { moveTo(x0, y0); lineTo(x1, y1) }
    return dispatch(displayId, GestureDescription.StrokeDescription(path, 0, durationMs, false))
  }

  /** Press-hold, move, release — as three continued strokes for reliable WMShell drag detection. */
  suspend fun drag(displayId: Int, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
    val hold = Path().apply { moveTo(x0, y0); lineTo(x0 + 0.5f, y0) }
    val s1 = GestureDescription.StrokeDescription(hold, 0, 220, true)
    val move = Path().apply { moveTo(x0 + 0.5f, y0); lineTo(x1, y1) }
    val s2 = s1.continueStroke(move, 0, 480, true)
    val rest = Path().apply { moveTo(x1, y1); lineTo(x1 + 0.5f, y1) }
    val s3 = s2.continueStroke(rest, 0, 120, false)
    return dispatch(displayId, s1) && dispatch(displayId, s2) && dispatch(displayId, s3)
  }

  private suspend fun dispatch(displayId: Int, stroke: GestureDescription.StrokeDescription): Boolean {
    val gesture = GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build()
    return withTimeoutOrNull(3_000) {
      suspendCancellableCoroutine { cont ->
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
          override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
          override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
      }
    } ?: false
  }
}

internal suspend fun poll(timeoutMs: Long, intervalMs: Long = 120, condition: () -> Boolean): Boolean {
  val deadline = System.currentTimeMillis() + timeoutMs
  while (System.currentTimeMillis() < deadline) {
    if (condition()) return true
    delay(intervalMs)
  }
  return condition()
}
