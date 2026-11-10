package com.google.android.desktop.apprestorer.capture

import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.NormalizedBounds
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry logic: classifies visual states against a display's usable work area, filters
 * phantom windows, and maps captured bounds onto (possibly different) displays on restore.
 */
object WindowGeometryClassifier {

  /** Compact desktop tools (Calculator, Gemini floaty) can be this small. */
  const val MIN_COMPACT_WIDTH = 200
  const val MIN_COMPACT_HEIGHT = 140

  /** Chrome live-tile phantoms (e.g. 660 × 302) are rejected by stricter browser minimums. */
  const val MIN_BROWSER_WIDTH = 480
  const val MIN_BROWSER_HEIGHT = 380

  /** Edge tolerance as a fraction of the display's smaller dimension (min 12 px). */
  private fun tolerance(display: DisplayGeometry): Int =
    maxOf(12, (minOf(display.widthPx, display.heightPx) * 0.015f).roundToInt())

  private fun near(a: Int, b: Int, tol: Int) = abs(a - b) <= tol

  fun classify(bounds: WindowBounds, display: DisplayGeometry, isVisible: Boolean): WindowVisualState {
    if (!isVisible) return WindowVisualState.MINIMIZED
    val tol = tolerance(display)
    val full = display.bounds
    val work = display.workArea
    if (bounds.maxEdgeDelta(full) <= tol) return WindowVisualState.FULLSCREEN
    if (bounds.maxEdgeDelta(work) <= tol) return WindowVisualState.MAXIMIZED
    val spansHeight = near(bounds.top, work.top, tol) && near(bounds.bottom, work.bottom, tol)
    if (spansHeight && bounds.width < work.width - tol) {
      val touchesLeft = near(bounds.left, work.left, tol)
      val touchesRight = near(bounds.right, work.right, tol)
      if (touchesLeft && !touchesRight) return WindowVisualState.SNAPPED_LEFT
      if (touchesRight && !touchesLeft) return WindowVisualState.SNAPPED_RIGHT
    }
    return WindowVisualState.FREEFORM
  }

  private fun Float.safeUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f

  fun normalize(bounds: WindowBounds, display: DisplayGeometry): NormalizedBounds {
    val w = display.widthPx.coerceAtLeast(1).toFloat()
    val h = display.heightPx.coerceAtLeast(1).toFloat()
    return NormalizedBounds(
      (bounds.left / w).safeUnit(),
      (bounds.top / h).safeUnit(),
      (bounds.right / w).safeUnit(),
      (bounds.bottom / h).safeUnit(),
    )
  }

  fun denormalize(n: NormalizedBounds, display: DisplayGeometry): WindowBounds {
    val dw = display.widthPx.coerceAtLeast(1)
    val dh = display.heightPx.coerceAtLeast(1)
    return WindowBounds(
      (n.left.safeUnit() * dw).roundToInt(),
      (n.top.safeUnit() * dh).roundToInt(),
      (n.right.safeUnit() * dw).roundToInt(),
      (n.bottom.safeUnit() * dh).roundToInt(),
    )
  }

  /** Rejects miniature/phantom windows: strict for browsers, lenient for compact tools. */
  fun isPlausibleWindow(bounds: WindowBounds, isBrowser: Boolean): Boolean =
    if (isBrowser) bounds.width >= MIN_BROWSER_WIDTH && bounds.height >= MIN_BROWSER_HEIGHT
    else bounds.width >= MIN_COMPACT_WIDTH && bounds.height >= MIN_COMPACT_HEIGHT

  /**
   * Computes launch bounds for [window] on [target].
   *
   * - MAXIMIZED → target work area; FULLSCREEN → full display (or work area when [coerceToDesktop]
   *   so it coexists with other windows on the same desk).
   * - Snapped → full-height edge-anchored with the same work-area width fraction.
   * - Freeform → exact pixels when the display geometry is unchanged, else proportional scaling;
   *   always clamped on-screen.
   */
  fun resolveRestoreBounds(
    window: CapturedWindow,
    captured: DisplayGeometry?,
    target: DisplayGeometry,
    coerceToDesktop: Boolean = false,
  ): WindowBounds {
    val state =
      if (window.visualState == WindowVisualState.MINIMIZED) window.preMinimizeState ?: WindowVisualState.FREEFORM
      else window.visualState
    val work = target.workArea
    return when (state) {
      WindowVisualState.MAXIMIZED -> work
      WindowVisualState.FULLSCREEN -> if (coerceToDesktop) work else target.bounds
      WindowVisualState.SNAPPED_LEFT, WindowVisualState.SNAPPED_RIGHT -> {
        val workW = work.width.coerceAtLeast(1)
        val fallbackSrc = (window.bounds.width.toLong().coerceAtLeast(1L) * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val srcWorkWidth = (captured?.workArea?.width ?: fallbackSrc).coerceAtLeast(1)
        val rawFraction = window.bounds.width.coerceAtLeast(0).toFloat() / srcWorkWidth
        val fraction = if (rawFraction.isFinite()) rawFraction.coerceIn(0.2f, 0.8f) else 0.5f
        val width = (workW * fraction).roundToInt().coerceIn(1, workW)
        val workRight = maxOf(work.left + workW, work.right)
        val workBottom = maxOf(work.top + 1, work.bottom)
        if (state == WindowVisualState.SNAPPED_LEFT) WindowBounds(work.left, work.top, work.left + width, workBottom)
        else WindowBounds(workRight - width, work.top, workRight, workBottom)
      }
      else -> {
        val sameGeometry = captured != null &&
          captured.widthPx == target.widthPx && captured.heightPx == target.heightPx
        val raw = if (sameGeometry) window.bounds else denormalize(window.normalized, target)
        clampOnScreen(raw, target)
      }
    }
  }

  /** Keeps a window fully inside the work area, shrinking only if it cannot fit. */
  fun clampOnScreen(b: WindowBounds, target: DisplayGeometry): WindowBounds {
    val rawWork = target.workArea
    val workLeft = minOf(rawWork.left, rawWork.right)
    val workTop = minOf(rawWork.top, rawWork.bottom)
    val workW = (maxOf(rawWork.left, rawWork.right).toLong() - workLeft.toLong()).coerceIn(1L, 32_768L).toInt()
    val workH = (maxOf(rawWork.top, rawWork.bottom).toLong() - workTop.toLong()).coerceIn(1L, 32_768L).toInt()
    val rawW = (b.right.toLong() - b.left.toLong()).coerceIn(0L, workW.toLong()).toInt()
    val rawH = (b.bottom.toLong() - b.top.toLong()).coerceIn(0L, workH.toLong()).toInt()
    val minW = MIN_COMPACT_WIDTH.coerceAtMost(workW)
    val minH = MIN_COMPACT_HEIGHT.coerceAtMost(workH)
    val w = rawW.coerceIn(minW, workW)
    val h = rawH.coerceIn(minH, workH)
    val maxLeft = workLeft + (workW - w).coerceAtLeast(0)
    val maxTop = workTop + (workH - h).coerceAtLeast(0)
    val left = b.left.coerceIn(workLeft, maxLeft)
    val top = b.top.coerceIn(workTop, maxTop)
    return WindowBounds(left, top, left + w, top + h)
  }

  /** True if [actual] is within tolerance of [expected] on [display]. */
  fun matches(actual: WindowBounds, expected: WindowBounds, display: DisplayGeometry): Boolean =
    actual.maxEdgeDelta(expected) <= tolerance(display) * 2
}
