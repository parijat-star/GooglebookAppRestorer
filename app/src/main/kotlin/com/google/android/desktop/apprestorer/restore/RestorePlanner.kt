package com.google.android.desktop.apprestorer.restore

import android.content.Intent
import android.provider.Browser
import android.provider.Settings
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.WindowGeometryClassifier
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot

/** Platform-free description of the Intent used to relaunch a window. */
data class LaunchDescriptor(
  val packageName: String,
  /** Flattened ComponentName ("pkg/cls"), or null to use action + package. */
  val component: String?,
  val action: String,
  val categories: Set<String> = emptySet(),
  val dataUri: String? = null,
  val flags: Int,
  val booleanExtras: Map<String, Boolean> = emptyMap(),
  val stringExtras: Map<String, String> = emptyMap(),
  /** Intent.setIdentifier token keeping multi-instance intents distinct. */
  val identifier: String? = null,
)

data class WindowLaunch(
  val window: CapturedWindow,
  val targetDisplayId: Int,
  val targetDeskIndex: Int,
  val targetBounds: WindowBounds,
  val startMinimized: Boolean,
  val launch: LaunchDescriptor,
  /** False when only Overview-tile geometry was known: let the system pick a default size. */
  val applyBounds: Boolean = true,
  /** First Chrome window of the restore: Chrome restores its own tab_state natively. */
  val isPrimaryBrowserWindow: Boolean = false,
)

data class DeskRestorePlan(val deskIndex: Int, val launches: List<WindowLaunch>)

data class DisplayRestorePlan(
  val targetDisplayId: Int,
  val deskCount: Int,
  val finalActiveDesk: Int,
  /** Desks in ascending (creation) order; [finalActiveDesk] is re-activated at the end. */
  val desks: List<DeskRestorePlan>,
)

data class RestorePlan(val displays: List<DisplayRestorePlan>, val skipped: List<CapturedWindow>) {
  val launchCount: Int get() = displays.sumOf { d -> d.desks.sumOf { it.launches.size } }
  val allLaunches: List<WindowLaunch> get() = displays.flatMap { d -> d.desks.flatMap { it.launches } }
}

/** What the device can launch; backed by PackageManager in production. */
interface LaunchCapabilities {
  /** Exported launcher component ("pkg/cls") for [packageName], or null if not launchable. */
  fun launcherComponent(packageName: String): String?
  /** Whether an explicit exported component resolves (e.g. DocumentsUI FilesActivity). */
  fun isExportedComponent(component: String): Boolean
}

/**
 * Turns a snapshot into an ordered, executable plan:
 * - connected secondary displays first, DEFAULT_DISPLAY last (focus ends on the primary screen);
 * - per display, desks ascending with the pre-reboot active desk last;
 * - per desk, minimized windows first, then visible windows back-to-front (ascending z-order);
 * - unplugged-monitor windows fall back onto display 0 with proportional scaling, beneath the
 *   primary display's own windows.
 */
class RestorePlanner(private val caps: LaunchCapabilities) {

  fun plan(
    snapshot: WorkspaceSnapshot,
    currentDisplays: List<DisplayGeometry>,
    selectedKeys: Set<String>? = null,
  ): RestorePlan {
    val primary = currentDisplays.firstOrNull { it.isDefault } ?: currentDisplays.firstOrNull()
      ?: return RestorePlan(emptyList(), snapshot.windows)
    val displayMap = mapDisplays(snapshot.displays, currentDisplays)
    val skipped = mutableListOf<CapturedWindow>()

    data class Placed(val w: CapturedWindow, val target: DisplayGeometry, val desk: Int, val fallback: Boolean)
    val placed = mutableListOf<Placed>()
    for (w in snapshot.windows) {
      if (KnownPackages.isExcludedCompanionApp(w.packageName, w.label)) continue
      if (selectedKeys != null && w.windowKey !in selectedKeys) continue
      val target = displayMap[w.displayId]
      if (target != null) {
        placed += Placed(w, target, w.deskIndex, fallback = false)
      } else {
        // Unplugged external monitor: fold onto the primary display's matching / active desk.
        val primaryTotal = snapshot.totalDeskCountByDisplay[primary.displayId] ?: 1
        val desk = if (w.deskIndex < primaryTotal) w.deskIndex else snapshot.activeDeskIndexByDisplay[primary.displayId] ?: 0
        placed += Placed(w, primary, desk, fallback = true)
      }
    }

    // Execution order (== instance ordering per package): desks ascending, because Desk 2..N can
    // only be added from Overview once Desk 1 has windows.
    val ordered = placed.sortedWith(
      compareBy<Placed>({ if (it.target.isDefault) 1 else 0 }, { it.target.displayId }, { it.desk },
        { if (it.w.isVisible) 1 else 0 }, { if (it.fallback) 0 else 1 }, { it.w.zOrderLayer })
    )
    val instanceCounter = HashMap<String, Int>()
    val launchesByDisplayDesk = LinkedHashMap<Pair<Int, Int>, MutableList<WindowLaunch>>()
    val windowsPerDesk = ordered.groupingBy { it.target.displayId to it.desk }.eachCount()
    for (p in ordered) {
      val idx = instanceCounter.getOrDefault(p.w.packageName, 0)
      val descriptor = resolveLaunch(p.w, idx)
      if (descriptor == null) {
        skipped += p.w
        continue
      }
      instanceCounter[p.w.packageName] = idx + 1
      val source = snapshot.display(p.w.displayId)
      val coerce = (windowsPerDesk[p.target.displayId to p.desk] ?: 1) > 1
      val bounds = WindowGeometryClassifier.resolveRestoreBounds(p.w, source.takeUnless { p.fallback }, p.target, coerceToDesktop = coerce)
      launchesByDisplayDesk.getOrPut(p.target.displayId to p.desk) { mutableListOf() } += WindowLaunch(
        window = p.w,
        targetDisplayId = p.target.displayId,
        targetDeskIndex = p.desk,
        targetBounds = bounds,
        startMinimized = !p.w.isVisible,
        launch = descriptor,
        applyBounds = p.w.boundsKnown,
        isPrimaryBrowserWindow = p.w.kind == AppKind.CHROME_BROWSER && idx == 0,
      )
    }

    val targetIds = currentDisplays.map { it.displayId }
      .filter { id -> launchesByDisplayDesk.keys.any { it.first == id } || snapshot.totalDeskCountByDisplay.containsKey(sourceIdFor(id, displayMap)) }
      .sortedWith(compareBy({ if (it == DisplayGeometry.DEFAULT_DISPLAY_ID) 1 else 0 }, { it }))

    val displayPlans = targetIds.mapNotNull { targetId ->
      val sourceId = sourceIdFor(targetId, displayMap)
      val desksWithWindows = launchesByDisplayDesk.keys.filter { it.first == targetId }.map { it.second }
      if (desksWithWindows.isEmpty()) return@mapNotNull null
      val deskCount = (desksWithWindows.maxOrNull() ?: 0) + 1
      val finalDesk = (sourceId?.let { snapshot.activeDeskIndexByDisplay[it] } ?: 0).coerceIn(0, deskCount - 1)
      val deskOrder = desksWithWindows.toSortedSet().toList()
      val desks = deskOrder.map { d -> DeskRestorePlan(d, launchesByDisplayDesk[targetId to d].orEmpty()) }
      DisplayRestorePlan(targetId, deskCount, finalDesk, desks)
    }
    return RestorePlan(displayPlans, skipped)
  }

  private fun sourceIdFor(targetId: Int, map: Map<Int, DisplayGeometry>): Int? =
    map.entries.firstOrNull { it.value.displayId == targetId }?.key

  /**
   * Captured displayId → current display: the primary always maps to the primary; external
   * monitors are matched by name + resolution first (their ids change across boots), then by id
   * if the name matches.
   */
  fun mapDisplays(captured: List<DisplayGeometry>, current: List<DisplayGeometry>): Map<Int, DisplayGeometry> {
    val out = HashMap<Int, DisplayGeometry>()
    val used = HashSet<Int>()
    current.firstOrNull { it.isDefault }?.let { p ->
      out[DisplayGeometry.DEFAULT_DISPLAY_ID] = p
      used += p.displayId
    }
    for (c in captured.filter { !it.isDefault }) {
      val match = current.firstOrNull { it.displayId !in used && !it.isDefault && it.matchKey == c.matchKey }
        ?: current.firstOrNull { it.displayId !in used && !it.isDefault && it.name.isNotEmpty() && it.name == c.name }
        ?: current.firstOrNull { it.displayId !in used && !it.isDefault && it.displayId == c.displayId && c.name.isEmpty() }
      if (match != null) {
        out[c.displayId] = match
        used += match.displayId
      }
    }
    return out
  }

  /** Canonical, unprivileged launcher entry point for each window kind. */
  fun resolveLaunch(w: CapturedWindow, instanceIndex: Int): LaunchDescriptor? {
    val secondary = instanceIndex > 0
    val baseFlags = Intent.FLAG_ACTIVITY_NEW_TASK
    val multiFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    val identifier = "restore:${w.packageName}:$instanceIndex:${w.windowKey}"
    fun launcher(pkg: String, multi: Boolean): LaunchDescriptor? {
      val comp = caps.launcherComponent(pkg) ?: return null
      return LaunchDescriptor(
        packageName = pkg, component = comp, action = Intent.ACTION_MAIN,
        categories = setOf(Intent.CATEGORY_LAUNCHER),
        flags = if (multi) multiFlags else baseFlags,
        identifier = if (multi) identifier else null,
      )
    }
    return when (w.kind) {
      AppKind.CHROME_BROWSER -> {
        if (!secondary) {
          // ACTION_MAIN without EXTRA_CREATE_NEW_TAB: TabPersistentStore restores tab_state0.
          launcher(w.packageName, multi = false)
        } else {
          val url = w.tabUrls.getOrNull(w.activeTabIndex) ?: w.tabUrls.firstOrNull()
          if (caps.launcherComponent(w.packageName) == null) return null
          LaunchDescriptor(
            packageName = w.packageName, component = null,
            action = if (url != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN,
            categories = if (url != null) setOf(Intent.CATEGORY_BROWSABLE) else setOf(Intent.CATEGORY_LAUNCHER),
            dataUri = url, flags = multiFlags,
            booleanExtras = mapOf(Browser.EXTRA_CREATE_NEW_TAB to true),
            identifier = identifier,
          )
        }
      }
      AppKind.TWA -> launcher(w.packageName, secondary)
      AppKind.GEMINI -> launcher(KnownPackages.GEMINI, secondary)
        ?: launcher(KnownPackages.GOOGLE_QUICK_SEARCH_BOX, secondary)
      AppKind.FILES -> {
        // DocumentsUI's LauncherActivity trampoline focuses an existing window via findTask();
        // target the exported FilesActivity directly to get a second window.
        val files = "${w.packageName}/${KnownPackages.FILES_ACTIVITY}"
        if (caps.isExportedComponent(files)) {
          LaunchDescriptor(
            packageName = w.packageName, component = files, action = Intent.ACTION_MAIN,
            flags = if (secondary) multiFlags else baseFlags,
            identifier = identifier,
          )
        } else launcher(w.packageName, secondary)
      }
      AppKind.SETTINGS -> LaunchDescriptor(
        packageName = w.packageName, component = null, action = Settings.ACTION_SETTINGS,
        flags = if (secondary) multiFlags else baseFlags,
        identifier = if (secondary) identifier else null,
      )
      AppKind.STANDARD -> launcher(w.packageName, secondary)
    }
  }

  companion object {
    fun isMaximizeLike(state: WindowVisualState) =
      state == WindowVisualState.MAXIMIZED || state == WindowVisualState.FULLSCREEN
  }
}
