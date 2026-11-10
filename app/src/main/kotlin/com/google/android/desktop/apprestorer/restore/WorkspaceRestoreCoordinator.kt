package com.google.android.desktop.apprestorer.restore

import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot

/** A relaunched window as observed on screen. */
data class AttachedWindow(val windowId: Int, val displayId: Int, val bounds: WindowBounds)

/**
 * Platform side-effects needed to execute a [RestorePlan]. The AccessibilityService-backed driver
 * supports desks, geometry verification, minimizing and tab reconciliation; a context-only
 * fallback driver still relaunches every window at its saved bounds and display.
 */
interface RestoreDriver {
  val supportsDesks: Boolean
  suspend fun awaitPromptDismissed()
  fun currentDisplays(): List<DisplayGeometry>
  /** Ensures [count] desks exist on [displayId]; returns the count achieved. */
  suspend fun ensureDeskCount(displayId: Int, count: Int): Int
  suspend fun activateDesk(displayId: Int, deskIndex: Int): Boolean
  fun existingWindowIds(launch: WindowLaunch): Set<Int>
  fun launch(launch: WindowLaunch): Boolean
  suspend fun awaitAttached(launch: WindowLaunch, before: Set<Int>, timeoutMs: Long): AttachedWindow?
  /** Fixes bounds if a trampoline opened the window at default bounds. */
  suspend fun correctGeometry(attached: AttachedWindow, launch: WindowLaunch): AttachedWindow
  suspend fun minimize(attached: AttachedWindow): Boolean
  /** Opens only the tabs Chrome did not restore natively; returns how many were opened. */
  suspend fun reconcileChromeTabs(attached: AttachedWindow, launch: WindowLaunch): Int
  suspend fun purgePhantomBrowserWindows(displayId: Int, keepWindowIds: Set<Int>): Int
}

data class RestoreReport(
  val planned: Int,
  val launched: Int,
  val attached: Int,
  val failedLabels: List<String>,
  val skippedLabels: List<String>,
  val tabsReopened: Int,
  val phantomsPurged: Int,
  val desksByDisplay: Map<Int, Int>,
)

data class RestoreProgress(
  val total: Int = 0,
  val done: Int = 0,
  val currentLabel: String? = null,
  val running: Boolean = false,
  val report: RestoreReport? = null,
)

/** Executes a restore plan with deterministic desk activation and Z-order sequencing. */
class WorkspaceRestoreCoordinator(
  private val driver: RestoreDriver,
  private val planner: RestorePlanner,
  private val log: (String) -> Unit = {},
) {
  suspend fun restore(
    snapshot: WorkspaceSnapshot,
    selectedKeys: Set<String>? = null,
    onProgress: (RestoreProgress) -> Unit = {},
  ): RestoreReport {
    // 1. Let RestorePromptActivity close and settle so DesktopTasksController never minimizes
    //    the first restored windows behind it.
    driver.awaitPromptDismissed()
    val plan = planner.plan(snapshot, driver.currentDisplays(), selectedKeys)
    val total = plan.launchCount
    var done = 0
    var launched = 0
    var attachedCount = 0
    var tabs = 0
    var purged = 0
    val failed = mutableListOf<String>()
    val desks = HashMap<Int, Int>()
    onProgress(RestoreProgress(total, 0, null, running = true))

    // 2. Secondary displays first, DEFAULT_DISPLAY last (plan order).
    for (dp in plan.displays) {
      val useDesks = driver.supportsDesks && dp.deskCount > 1
      log("restore display=${dp.targetDisplayId} desks=${dp.deskCount} final=${dp.finalActiveDesk + 1} order=${dp.desks.map { it.deskIndex + 1 }}")
      var achievedDesks = 1
      var currentDesk = 0
      val keep = HashSet<Int>()
      for (desk in dp.desks.sortedBy { it.deskIndex }) {
        // Desk 1 is the current desk; Desk 2..N are added from Overview once earlier desks exist.
        if (useDesks && desk.deskIndex != currentDesk) {
          if (desk.deskIndex >= achievedDesks) achievedDesks = maxOf(achievedDesks, driver.ensureDeskCount(dp.targetDisplayId, desk.deskIndex + 1))
          val ok = desk.deskIndex < achievedDesks && driver.activateDesk(dp.targetDisplayId, desk.deskIndex)
          log("restore desk ${desk.deskIndex + 1}: available=$achievedDesks activated=$ok")
          if (ok) currentDesk = desk.deskIndex
        }
        // 3. Minimized windows first, then visible ones back-to-front, each awaited on screen.
        val ordered = desk.launches.sortedWith(compareBy({ if (it.startMinimized) 0 else 1 }, { it.window.zOrderLayer }))
        for (l in ordered) {
          onProgress(RestoreProgress(total, done, l.window.label, running = true))
          val before = driver.existingWindowIds(l)
          if (!driver.launch(l)) {
            failed += l.window.label.ifEmpty { l.window.packageName }
            done++
            continue
          }
          launched++
          log("restore launch ${l.window.label} desk=${l.targetDeskIndex + 1} bounds=${if (l.applyBounds) l.targetBounds.toString() else "system default"} minimized=${l.startMinimized}")
          val attached = driver.awaitAttached(l, before, if (l.isPrimaryBrowserWindow) 12_000 else ATTACH_TIMEOUT_MS)
          if (attached != null) {
            attachedCount++
            val fixed = if (l.applyBounds) driver.correctGeometry(attached, l) else attached
            log("restore attached ${l.window.label} at ${attached.bounds} -> ${fixed.bounds}")
            keep += fixed.windowId
            if (l.window.kind == AppKind.CHROME_BROWSER && l.window.tabUrls.isNotEmpty()) {
              tabs += driver.reconcileChromeTabs(fixed, l)
            }
            if (l.startMinimized) driver.minimize(fixed)
          }
          done++
        }
      }
      if (useDesks) {
        if (dp.deskCount > achievedDesks) achievedDesks = maxOf(achievedDesks, driver.ensureDeskCount(dp.targetDisplayId, dp.deskCount))
        if (dp.finalActiveDesk != currentDesk && dp.finalActiveDesk < achievedDesks) driver.activateDesk(dp.targetDisplayId, dp.finalActiveDesk)
      }
      desks[dp.targetDisplayId] = achievedDesks
      purged += driver.purgePhantomBrowserWindows(dp.targetDisplayId, keep)
    }
    val report = RestoreReport(
      planned = total, launched = launched, attached = attachedCount, failedLabels = failed,
      skippedLabels = plan.skipped.map { it.label.ifEmpty { it.packageName } },
      tabsReopened = tabs, phantomsPurged = purged, desksByDisplay = desks,
    )
    onProgress(RestoreProgress(total, done, null, running = false, report = report))
    return report
  }

  companion object {
    const val ATTACH_TIMEOUT_MS = 8_000L
  }
}
