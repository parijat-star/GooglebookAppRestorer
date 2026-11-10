package com.google.android.desktop.apprestorer

import com.google.android.desktop.apprestorer.capture.AppCatalog
import com.google.android.desktop.apprestorer.capture.BrowserFacts
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.ObservedWindow
import com.google.android.desktop.apprestorer.inspect.UiNode
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds

/** Shared test fixtures. */
object Fixtures {
  /** 2880 × 1800 Googlebook panel with a 96 px taskbar. */
  val PRIMARY = DisplayGeometry(0, 2880, 1800, WindowBounds(0, 0, 2880, 1704), 320, "Built-in Screen")
  val EXTERNAL = DisplayGeometry(2, 1920, 1080, WindowBounds(0, 0, 1920, 1032), 160, "DELL U2720Q")

  class FakeCatalog(
    private val labels: Map<String, String> = mapOf(
      KnownPackages.CHROME to "Chrome",
      "com.google.android.calendar" to "Calendar",
      "com.android.documentsui" to "Files",
      "com.android.settings" to "Settings",
      KnownPackages.CALCULATOR to "Calculator",
      KnownPackages.GEMINI to "Gemini",
      "com.google.android.youtube" to "YouTube",
    ),
    private val webApps: Set<String> = setOf("com.google.android.calendar", "com.google.android.youtube"),
    private val system: Set<String> = setOf("com.google.android.apps.nexuslauncher", KnownPackages.SYSTEM_UI),
  ) : AppCatalog {
    override fun labelFor(packageName: String) = labels[packageName] ?: packageName
    override fun isInstalled(packageName: String) = packageName in labels
    override fun isSystemSurface(packageName: String) = packageName in system
    override fun findWebAppPackageByLabel(label: String) =
      labels.entries.filter { it.value.equals(label, true) && !KnownPackages.isChrome(it.key) }
        .sortedBy { if (it.key in webApps) 0 else 1 }.firstOrNull()?.key
    override fun findPackageByLabel(label: String) = labels.entries.firstOrNull { it.value.equals(label, true) }?.key
  }

  fun win(
    id: Int,
    pkg: String,
    b: WindowBounds,
    layer: Int = id,
    display: Int = 0,
    title: String? = null,
    cls: String? = null,
    caption: String? = null,
    browser: BrowserFacts? = null,
    focused: Boolean = false,
  ) = ObservedWindow(id, display, pkg, title, b, layer, focused, cls, caption, browser)

  data class FakeNode(
    override val viewIdResourceName: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val boundsInScreen: WindowBounds = WindowBounds(0, 0, 100, 100),
    val children: List<FakeNode> = emptyList(),
    override val customActionLabels: List<String> = emptyList(),
    override val className: String? = null,
    override val isFocused: Boolean = false,
    override val isSelected: Boolean = false,
    override val packageName: String? = null,
    override val isVisibleToUser: Boolean = true,
  ) : UiNode {
    var clicks = 0
    override val childCount: Int get() = children.size
    override fun child(index: Int): UiNode? = children.getOrNull(index)
    override fun click(): Boolean { clicks++; return true }
    override fun showOnScreen() = true
    override fun performCustomAction(label: String) = label in customActionLabels
  }

  fun id(name: String, pkg: String = "com.google.android.apps.nexuslauncher") = "$pkg:id/$name"
}
