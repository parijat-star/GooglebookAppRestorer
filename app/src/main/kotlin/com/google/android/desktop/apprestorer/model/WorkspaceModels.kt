package com.google.android.desktop.apprestorer.model

import kotlinx.serialization.Serializable

/** Current on-disk schema. v5 added Chrome tabUrls, v6 added per-display desk maps. */
const val WORKSPACE_SCHEMA_VERSION = 6

/** Desktop-mode visual state of a captured window, classified against the usable work area. */
@Serializable
enum class WindowVisualState { FULLSCREEN, MAXIMIZED, SNAPPED_LEFT, SNAPPED_RIGHT, FREEFORM, MINIMIZED }

/** How a window must be relaunched. */
@Serializable
enum class AppKind { STANDARD, CHROME_BROWSER, TWA, GEMINI, FILES, SETTINGS }

/** Why a snapshot was written. */
@Serializable
enum class CaptureReason { LIVE, MANUAL_CAPTURE, POWER_MENU_PRE_FREEZE, SHUTDOWN_BROADCAST, SCREEN_OFF }

/** Raw pixel bounds in display coordinates (right/bottom exclusive, like android.graphics.Rect). */
@Serializable
data class WindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top
  val centerX: Int get() = (left + right) / 2
  val centerY: Int get() = (top + bottom) / 2
  val isEmpty: Boolean get() = width <= 0 || height <= 0

  fun offset(dx: Int, dy: Int) = WindowBounds(left + dx, top + dy, right + dx, bottom + dy)

  fun intersects(o: WindowBounds) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

  fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

  fun union(o: WindowBounds) =
    WindowBounds(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

  /** Largest absolute edge delta; used for "close enough" geometry checks. */
  fun maxEdgeDelta(o: WindowBounds): Int =
    maxOf(kotlin.math.abs(left - o.left), kotlin.math.abs(top - o.top),
      kotlin.math.abs(right - o.right), kotlin.math.abs(bottom - o.bottom))

  companion object {
    val EMPTY = WindowBounds(0, 0, 0, 0)
  }
}

/** Bounds expressed as 0.0..1.0 fractions of the display, for resolution-independent restore. */
@Serializable
data class NormalizedBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Geometry of a display at capture time. */
@Serializable
data class DisplayGeometry(
  val displayId: Int,
  val widthPx: Int,
  val heightPx: Int,
  /** Usable area excluding taskbar / status bar (WindowInsets.Type.systemBars()). */
  val workArea: WindowBounds,
  val densityDpi: Int = 160,
  val name: String = "",
) {
  val bounds: WindowBounds get() = WindowBounds(0, 0, widthPx, heightPx)
  val isDefault: Boolean get() = displayId == DEFAULT_DISPLAY_ID

  /** Identity used to re-match an external monitor whose displayId changed across boots. */
  val matchKey: String get() = "$name|${widthPx}x$heightPx"

  companion object {
    const val DEFAULT_DISPLAY_ID = 0
  }
}

/** One genuinely open desktop window. */
@Serializable
data class CapturedWindow(
  /** Stable key within one boot session (windowId-derived) — not used on restore. */
  val windowKey: String,
  val packageName: String,
  val label: String = "",
  val activityClassName: String? = null,
  val kind: AppKind = AppKind.STANDARD,
  /** For TWAs/WebAPKs: the browser that renders them (e.g. com.android.chrome). */
  val hostBrowserPackage: String? = null,
  val displayId: Int = DisplayGeometry.DEFAULT_DISPLAY_ID,
  /** 0-based Virtual Desk index on [displayId] (Desk 1 == 0). */
  val deskIndex: Int = 0,
  val bounds: WindowBounds,
  val normalized: NormalizedBounds,
  /** False for windows only seen as Overview tiles (grid-sized, not real geometry). */
  val boundsKnown: Boolean = true,
  val visualState: WindowVisualState,
  /** State before minimizing, so a minimized window is relaunched with the right geometry. */
  val preMinimizeState: WindowVisualState? = null,
  val isVisible: Boolean = true,
  /** 0 == bottom-most on its display/desk; larger == closer to the front. */
  val zOrderLayer: Int = 0,
  val instanceIndex: Int = 0,
  val totalInstancesForApp: Int = 1,
  /** Chrome: open tab URLs in tab-strip order (Schema v5). */
  val tabUrls: List<String> = emptyList(),
  /** Chrome: tab titles aligned with [tabUrls] (blank when unknown), used for de-duplication. */
  val tabTitles: List<String> = emptyList(),
  val activeTabIndex: Int = 0,
  val tabCount: Int = 0,
  val lastSeenAtMs: Long = 0L,
)

/** Complete snapshot persisted to device-protected storage. */
@Serializable
data class WorkspaceSnapshot(
  val schemaVersion: Int = WORKSPACE_SCHEMA_VERSION,
  val capturedAtMs: Long = 0L,
  val bootTokenAtCapture: String = "",
  val reason: CaptureReason = CaptureReason.LIVE,
  val displays: List<DisplayGeometry> = emptyList(),
  /** Schema v6: active desk index per displayId. */
  val activeDeskIndexByDisplay: Map<Int, Int> = emptyMap(),
  /** Schema v6: total desk count per displayId. */
  val totalDeskCountByDisplay: Map<Int, Int> = emptyMap(),
  val windows: List<CapturedWindow> = emptyList(),
) {
  fun display(id: Int): DisplayGeometry? = displays.firstOrNull { it.displayId == id }

  val isEmpty: Boolean get() = windows.isEmpty()
}
