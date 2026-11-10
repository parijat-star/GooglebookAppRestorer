package com.google.android.desktop.apprestorer.capture

import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.WindowBounds

/** Facts read from a Chrome window's view hierarchy. */
data class BrowserFacts(
  /** Chrome's standalone web-app header (TWA / WebAPK window) is present. */
  val hasWebAppHeader: Boolean = false,
  /** Browser :id/url_bar / tab strip present (a real browser window). */
  val hasBrowserChrome: Boolean = false,
  val urlBarText: String? = null,
  val urlBarFocused: Boolean = false,
  val suggestionsOpen: Boolean = false,
  val tabTitles: List<String> = emptyList(),
  val selectedTabTitle: String? = null,
  /** Count parsed from the tab-switcher button ("5 open tabs"), 0 if unknown. */
  val tabCount: Int = 0,
  val webAppTitle: String? = null,
)

/** Raw, platform-independent description of one on-screen application window. */
data class ObservedWindow(
  val windowId: Int,
  val displayId: Int,
  val rootPackage: String?,
  val title: String?,
  val bounds: WindowBounds,
  /** AccessibilityWindowInfo.getLayer(): larger == closer to the front. */
  val layer: Int,
  val isFocused: Boolean = false,
  /** Last activity class seen for this window via TYPE_WINDOW_STATE_CHANGED. */
  val activityClassName: String? = null,
  /** App label from the WMShell caption ("Close Calendar" → "Calendar"). */
  val captionAppLabel: String? = null,
  val browser: BrowserFacts? = null,
  /** Google Search / launcher overlay (:id/search_plate, OpaActivity). */
  val isSearchOverlay: Boolean = false,
  /** Whether a WMShell desktop_mode_caption aligns with this window's top/left/right edges. */
  val hasDesktopCaption: Boolean = true,
)

/** Who a window really belongs to, after TWA / Gemini / Files classification. */
data class ResolvedIdentity(
  val packageName: String,
  val label: String,
  val kind: AppKind,
  val hostBrowserPackage: String? = null,
  /** Own app windows: tracked as Virtual Desk anchors but never relaunched. */
  val isAnchor: Boolean = false,
) {
  val isBrowser: Boolean get() = kind == AppKind.CHROME_BROWSER
}

/** Package-manager lookups needed by classification; faked in tests. */
interface AppCatalog {
  fun labelFor(packageName: String): String
  fun isInstalled(packageName: String): Boolean
  /** Launcher / SystemUI / IME and other non-restorable system surfaces. */
  fun isSystemSurface(packageName: String): Boolean
  /** Installed launchable TWA / WebAPK (or other launchable non-browser app) with that label. */
  fun findWebAppPackageByLabel(label: String): String?
  /** Any launchable package with that label. */
  fun findPackageByLabel(label: String): String?
}
