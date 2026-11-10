package com.google.android.desktop.apprestorer

import android.content.Intent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.desktop.apprestorer.Fixtures.EXTERNAL
import com.google.android.desktop.apprestorer.Fixtures.FakeNode
import com.google.android.desktop.apprestorer.Fixtures.PRIMARY
import com.google.android.desktop.apprestorer.Fixtures.id
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.WindowGeometryClassifier
import com.google.android.desktop.apprestorer.inspect.OverviewDeskInspector
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.permissions.PermissionState
import com.google.android.desktop.apprestorer.permissions.RequiredPermission
import com.google.android.desktop.apprestorer.restore.AttachedWindow
import com.google.android.desktop.apprestorer.restore.LaunchCapabilities
import com.google.android.desktop.apprestorer.restore.RestoreDriver
import com.google.android.desktop.apprestorer.restore.RestorePlanner
import com.google.android.desktop.apprestorer.restore.TabReconciler
import com.google.android.desktop.apprestorer.restore.WindowLaunch
import com.google.android.desktop.apprestorer.restore.WorkspaceRestoreCoordinator
import com.google.android.desktop.apprestorer.ui.MainScreen
import com.google.android.desktop.apprestorer.ui.MainUiState
import com.google.android.desktop.apprestorer.ui.PermissionOnboardingSection
import com.google.android.desktop.apprestorer.ui.RestorePromptContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w720dp-h1400dp")
class WorkspaceRestorerAndUiTest {

  @get:Rule val compose = createComposeRule()

  private val caps = object : LaunchCapabilities {
    override fun launcherComponent(packageName: String) = when (packageName) {
      KnownPackages.CHROME -> "${KnownPackages.CHROME}/${KnownPackages.CHROME_MAIN_ACTIVITY}"
      "uninstalled.app" -> null
      else -> "$packageName/.Launcher"
    }
    override fun isExportedComponent(component: String) = component.endsWith(KnownPackages.FILES_ACTIVITY)
  }
  private val planner = RestorePlanner(caps)

  private fun w(
    key: String, pkg: String, kind: AppKind, b: WindowBounds, display: Int = 0, desk: Int = 0, z: Int = 0,
    visible: Boolean = true, tabs: List<String> = emptyList(), displayGeom: DisplayGeometry = PRIMARY,
  ) = CapturedWindow(
    windowKey = key, packageName = pkg, label = key, kind = kind, displayId = display, deskIndex = desk,
    bounds = b, normalized = WindowGeometryClassifier.normalize(b, displayGeom),
    visualState = if (visible) WindowGeometryClassifier.classify(b, displayGeom, true) else WindowVisualState.MINIMIZED,
    preMinimizeState = if (visible) null else WindowVisualState.FREEFORM, isVisible = visible, zOrderLayer = z, tabUrls = tabs,
  )

  private val snapshot = WorkspaceSnapshot(
    displays = listOf(PRIMARY, EXTERNAL),
    activeDeskIndexByDisplay = mapOf(0 to 0, 2 to 0),
    totalDeskCountByDisplay = mapOf(0 to 2, 2 to 1),
    windows = listOf(
      w("chromeA", KnownPackages.CHROME, AppKind.CHROME_BROWSER, PRIMARY.workArea, z = 0, tabs = listOf("https://a.com", "https://b.com")),
      w("files1", "com.android.documentsui", AppKind.FILES, WindowBounds(300, 200, 1500, 1100), z = 2),
      w("settingsMin", "com.android.settings", AppKind.SETTINGS, WindowBounds(900, 300, 2100, 1300), z = 0, visible = false),
      w("chromeB", KnownPackages.CHROME, AppKind.CHROME_BROWSER, WindowBounds(0, 0, 1440, 1704), desk = 1, tabs = listOf("https://c.com")),
      w("files2", "com.android.documentsui", AppKind.FILES, WindowBounds(1500, 200, 2700, 1100), desk = 1, z = 1),
      w("calendar", "com.google.android.calendar", AppKind.TWA, WindowBounds(100, 100, 1100, 900), display = 2, displayGeom = EXTERNAL),
    ),
  )

  // ---------------------------------------------------------------- planner

  @Test
  fun plan_ordersDisplaysDesksAndZ() {
    val plan = planner.plan(snapshot, listOf(PRIMARY, EXTERNAL))
    assertEquals(listOf(2, 0), plan.displays.map { it.targetDisplayId })
    val primary = plan.displays.last()
    assertEquals(2, primary.deskCount)
    assertEquals(listOf(0, 1), primary.desks.map { it.deskIndex }) // Desk 2 is created after Desk 1 has windows
    assertEquals(0, primary.finalActiveDesk)
    assertEquals(listOf("settingsMin", "chromeA", "files1"), primary.desks.first().launches.map { it.window.windowKey })
    assertTrue(primary.desks.first().launches.first().startMinimized)
  }

  @Test
  fun plan_canonicalLaunchIntents() {
    val launches = planner.plan(snapshot, listOf(PRIMARY, EXTERNAL)).allLaunches.associateBy { it.window.windowKey }
    val chromeA = launches.getValue("chromeA") // launched first (Desk 1 before Desk 2)
    assertTrue(chromeA.isPrimaryBrowserWindow)
    assertEquals(Intent.ACTION_MAIN, chromeA.launch.action)
    assertEquals("${KnownPackages.CHROME}/${KnownPackages.CHROME_MAIN_ACTIVITY}", chromeA.launch.component)
    val chromeB = launches.getValue("chromeB")
    assertFalse(chromeB.isPrimaryBrowserWindow)
    assertEquals(Intent.ACTION_VIEW, chromeB.launch.action)
    assertEquals("https://c.com", chromeB.launch.dataUri)
    assertTrue(chromeB.launch.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
    assertEquals(true, chromeB.launch.booleanExtras[android.provider.Browser.EXTRA_CREATE_NEW_TAB])
    val files = listOf(launches.getValue("files1"), launches.getValue("files2"))
    assertTrue(files.all { it.launch.component!!.endsWith(KnownPackages.FILES_ACTIVITY) })
    assertEquals(1, files.count { it.launch.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0 })
    assertEquals(android.provider.Settings.ACTION_SETTINGS, launches.getValue("settingsMin").launch.action)
    assertEquals(PRIMARY.workArea, chromeA.targetBounds)
  }

  @Test
  fun plan_unpluggedMonitorFallsBackToPrimaryScaled() {
    val plan = planner.plan(snapshot, listOf(PRIMARY))
    assertEquals(listOf(0), plan.displays.map { it.targetDisplayId })
    val cal = plan.allLaunches.single { it.window.windowKey == "calendar" }
    assertEquals(0, cal.targetDisplayId)
    assertEquals(150, cal.targetBounds.left) // 100/1920 * 2880
    // Fallback windows sit beneath the primary display's own windows.
    assertEquals("calendar", plan.displays.single().desks.first().launches.first { it.window.isVisible }.window.windowKey)
  }

  @Test
  fun plan_unknownGeometry_usesSystemDefaultSize() {
    val s = snapshot.copy(windows = snapshot.windows.map { if (it.windowKey == "files2") it.copy(boundsKnown = false) else it })
    val launches = planner.plan(s, listOf(PRIMARY, EXTERNAL)).allLaunches.associateBy { it.window.windowKey }
    assertFalse(launches.getValue("files2").applyBounds)
    assertTrue(launches.getValue("files1").applyBounds)
  }

  @Test
  fun plan_remapsExternalDisplayWhoseIdChanged_andHonoursSelection() {
    val reconnected = EXTERNAL.copy(displayId = 5)
    val plan = planner.plan(snapshot, listOf(PRIMARY, reconnected), selectedKeys = setOf("calendar", "files1"))
    assertEquals(listOf(5, 0), plan.displays.map { it.targetDisplayId })
    assertEquals(setOf("calendar", "files1"), plan.allLaunches.map { it.window.windowKey }.toSet())
  }

  @Test
  fun plan_skipsUnlaunchableApps() {
    val s = snapshot.copy(windows = snapshot.windows + w("gone", "uninstalled.app", AppKind.STANDARD, WindowBounds(0, 0, 800, 600)))
    assertEquals(listOf("gone"), planner.plan(s, listOf(PRIMARY, EXTERNAL)).skipped.map { it.windowKey })
  }

  @Test
  fun plan_excludesDeviceLinkDebugAndConnectedPhoneAndDropsTheirDesk() {
    val withDeviceLink = snapshot.copy(
      totalDeskCountByDisplay = mapOf(0 to 3, 2 to 1),
      windows = snapshot.windows + listOf(
        w("Device Link Debug", "com.google.android.apps.devicelink.debug", AppKind.STANDARD, WindowBounds(400, 200, 1200, 900), desk = 2),
        w("Connected Phone", "com.google.ambient.streaming", AppKind.STANDARD, WindowBounds(400, 200, 1200, 900), desk = 2),
      ),
    )
    val plan = planner.plan(withDeviceLink, listOf(PRIMARY, EXTERNAL))
    val primary = plan.displays.single { it.targetDisplayId == 0 }
    assertEquals(2, primary.deskCount)
    assertEquals(listOf(0, 1), primary.desks.map { it.deskIndex })
    assertTrue(plan.allLaunches.none { it.window.label.contains("Device Link") || it.window.label.contains("Connected Phone") })
  }

  // ---------------------------------------------------------------- coordinator

  private class FakeDriver : RestoreDriver {
    val log = mutableListOf<String>()
    private var nextId = 100
    override val supportsDesks = true
    override suspend fun awaitPromptDismissed() { log += "prompt-dismissed" }
    override fun currentDisplays() = listOf(PRIMARY, EXTERNAL)
    override suspend fun ensureDeskCount(displayId: Int, count: Int): Int { log += "desks:$displayId=$count"; return count }
    override suspend fun activateDesk(displayId: Int, deskIndex: Int): Boolean { log += "activate:$displayId/$deskIndex"; return true }
    override fun existingWindowIds(launch: WindowLaunch) = emptySet<Int>()
    override fun launch(launch: WindowLaunch): Boolean { log += "launch:${launch.window.windowKey}"; return true }
    override suspend fun awaitAttached(launch: WindowLaunch, before: Set<Int>, timeoutMs: Long) =
      AttachedWindow(nextId++, launch.targetDisplayId, launch.targetBounds)
    override suspend fun correctGeometry(attached: AttachedWindow, launch: WindowLaunch) = attached
    override suspend fun minimize(attached: AttachedWindow): Boolean { log += "minimize:${attached.windowId}"; return true }
    override suspend fun reconcileChromeTabs(attached: AttachedWindow, launch: WindowLaunch): Int { log += "tabs:${launch.window.windowKey}"; return 1 }
    override suspend fun purgePhantomBrowserWindows(displayId: Int, keepWindowIds: Set<Int>): Int { log += "purge:$displayId"; return 0 }
  }

  @Test
  fun coordinator_executesDeterministicSequence() = runTest {
    val driver = FakeDriver()
    val report = WorkspaceRestoreCoordinator(driver, planner).restore(snapshot)
    assertEquals(
      listOf(
        "prompt-dismissed",
        "launch:calendar", "purge:2",
        "launch:settingsMin", "minimize:101", "launch:chromeA", "tabs:chromeA", "launch:files1",
        "desks:0=2", "activate:0/1", "launch:chromeB", "tabs:chromeB", "launch:files2",
        "activate:0/0",
        "purge:0",
      ),
      driver.log,
    )
    assertEquals(6, report.attached)
    assertEquals(2, report.tabsReopened)
  }

  // ---------------------------------------------------------------- tab reconciliation

  @Test
  fun tabReconciler_neverDuplicates() {
    val saved = listOf("https://a.com", "https://b.com", "https://c.com")
    val titles = listOf("A", "B", "C")
    // Chrome restored A and B natively (by title); only C is missing.
    assertEquals(listOf("https://c.com"), TabReconciler.missingTabs(saved, titles, listOf("A", "B"), 2, "https://b.com", true))
    // Nothing readable → trust Chrome.
    assertTrue(TabReconciler.missingTabs(saved, titles, emptyList(), 0, null, true).isEmpty())
    // Count known (3 restored) and no titles → nothing to add.
    assertTrue(TabReconciler.missingTabs(saved, emptyList(), emptyList(), 3, "https://a.com", true).isEmpty())
    // Secondary window opened with its active URL: add the rest once.
    assertEquals(listOf("https://a.com", "https://c.com"), TabReconciler.missingTabs(saved, emptyList(), emptyList(), 1, "https://b.com/", false))
  }

  // ---------------------------------------------------------------- overview & browser parsing

  @Test
  fun overviewInspector_readsComposeTaskHeadersAndCloseActions() {
    val desk1 = FakeNode(
      viewIdResourceName = id("task_view_desktop"), boundsInScreen = WindowBounds(100, 100, 1060, 700),
      children = listOf(
        FakeNode(viewIdResourceName = id("task_content_view"), boundsInScreen = WindowBounds(100, 100, 580, 700),
          children = listOf(FakeNode(text = "Chrome"))),
        FakeNode(viewIdResourceName = id("task_content_view"), boundsInScreen = WindowBounds(580, 160, 1000, 600),
          customActionLabels = listOf("Close Files")),
      ),
    )
    val desk2 = FakeNode(viewIdResourceName = id("task_view_desktop"), boundsInScreen = WindowBounds(1200, 100, 2160, 700))
    val add = FakeNode(viewIdResourceName = id("add_desktop_button"))
    val root = FakeNode(viewIdResourceName = id("recents_view"), children = listOf(desk1, desk2, add))
    val result = OverviewDeskInspector.inspect(listOf(root))
    assertTrue(result.isOverviewVisible)
    assertEquals(2, result.desks.size)
    assertEquals(listOf("Chrome", "Files"), result.desks[0].tasks.map { it.label })
    assertTrue(result.desks[1].isEmpty)
    assertEquals(0.5f, result.desks[0].tasks[0].relativeBounds!!.right, 0.01f)
    assertTrue(result.addDeskButton === add)
  }

  @Test
  fun overviewInspector_ignoresHomeOverviewActionsAndNullPlaceholders() {
    val homeRoot = FakeNode(
      viewIdResourceName = id("launcher"),
      children = listOf(
        FakeNode(viewIdResourceName = id("workspace")),
        FakeNode(viewIdResourceName = id("overview_actions_view")),
      ),
    )
    assertFalse(OverviewDeskInspector.isOverview(homeRoot))
    assertFalse(OverviewDeskInspector.inspect(listOf(homeRoot)).isOverviewVisible)

    val loadingDesk = FakeNode(
      viewIdResourceName = id("task_view_desktop"),
      children = listOf(
        FakeNode(
          viewIdResourceName = id("task_content_view"),
          children = listOf(
            FakeNode(viewIdResourceName = id("task_thumbnail_live_tile")),
            FakeNode(viewIdResourceName = id("header_app_title"), contentDescription = "null"),
          ),
        ),
        FakeNode(
          viewIdResourceName = id("task_content_view"),
          children = listOf(
            FakeNode(viewIdResourceName = id("task_thumbnail_live_tile")),
            FakeNode(viewIdResourceName = id("header_app_title"), text = "Antigravity"),
          ),
        ),
      ),
    )
    val parsed = OverviewDeskInspector.inspect(listOf(FakeNode(viewIdResourceName = id("overview_panel"), children = listOf(loadingDesk))))
    assertTrue(parsed.isOverviewVisible)
    assertEquals(listOf("Antigravity"), parsed.desks.single().tasks.map { it.label })
    assertTrue(parsed.desks.single().tasks.single().isLiveTile)
  }

  @Test
  fun browserFacts_readFromChromeHierarchy() {
    val chrome = "com.android.chrome"
    val root = FakeNode(children = listOf(
      FakeNode(viewIdResourceName = id("url_bar", chrome), text = "example.com/page"),
      FakeNode(viewIdResourceName = id("tab_switcher_button", chrome), contentDescription = "Switch or close tabs, 4 open tabs"),
      FakeNode(contentDescription = "Example, selected tab", isSelected = true),
      FakeNode(contentDescription = "Other, tab"),
    ))
    val facts = TwaAndBrowserInspector.readBrowserFacts(root)
    assertTrue(facts.hasBrowserChrome)
    assertFalse(facts.hasWebAppHeader)
    assertEquals("example.com/page", facts.urlBarText)
    assertEquals(4, facts.tabCount)
    assertEquals(listOf("Example", "Other"), facts.tabTitles)
    assertEquals("Example", facts.selectedTabTitle)
    val twa = TwaAndBrowserInspector.readBrowserFacts(FakeNode(children = listOf(
      FakeNode(viewIdResourceName = id("web_app_header_layout", chrome), children = listOf(FakeNode(text = "Calendar"))),
    )))
    assertTrue(twa.hasWebAppHeader)
    assertEquals("Calendar", twa.webAppTitle)
    assertNull(twa.urlBarText)
  }

  // ---------------------------------------------------------------- Compose UI

  @Test
  fun onboarding_showsGrantButtonsForMissingPermissions() {
    val clicked = mutableListOf<RequiredPermission>()
    val state = PermissionState(mapOf(RequiredPermission.ACCESSIBILITY to false, RequiredPermission.OVERLAY to true,
      RequiredPermission.USAGE_ACCESS to false, RequiredPermission.NOTIFICATIONS to true))
    compose.setContent { PermissionOnboardingSection(state, { clicked += it }, {}) }
    compose.onNodeWithText("Setup · 2 of 4 granted").assertExists()
    compose.onNodeWithTag("perm_granted_OVERLAY").assertExists()
    compose.onNodeWithTag("restricted_settings_help").assertExists()
    compose.onNodeWithTag("perm_grant_ACCESSIBILITY").performClick()
    compose.onNodeWithTag("perm_grant_USAGE_ACCESS").performClick()
    assertEquals(listOf(RequiredPermission.ACCESSIBILITY, RequiredPermission.USAGE_ACCESS), clicked)
  }

  @Test
  fun mainScreen_pillReadsArmedWhenReady() {
    val all = PermissionState(RequiredPermission.entries.associateWith { true })
    compose.setContent { MainScreen(MainUiState(all, serviceConnected = true, live = snapshot), {}, {}, {}, {}) }
    compose.onNodeWithText("Armed & Capturing Before Reboot").assertExists()
    compose.onNodeWithTag("live_summary").assertExists()
    compose.onNodeWithTag("desktop_preview").assertExists()
  }

  @Test
  fun restorePrompt_actionsAndSelection() {
    var restored = 0
    var notNow = 0
    var closed = 0
    var toggled: String? = null
    compose.setContent {
      RestorePromptContent(snapshot, snapshot.windows.map { it.windowKey }.toSet(), { toggled = it }, { restored++ }, { notNow++ }, { closed++ })
    }
    compose.onNodeWithText("Restore previous windows?").assertExists()
    compose.onNodeWithTag("restore_windows").performClick()
    compose.onNodeWithTag("not_now").performClick()
    compose.onNodeWithTag("prompt_close").performClick()
    compose.onNodeWithTag("check_files1").performClick()
    assertEquals(1, restored)
    assertEquals(1, notNow)
    assertEquals(1, closed)
    assertEquals("files1", toggled)
  }

  @Test
  fun restorePrompt_autoModeShowsCountdownAndAlwaysOption() {
    var always: Boolean? = null
    var cancelled = 0
    compose.setContent {
      RestorePromptContent(snapshot, snapshot.windows.map { it.windowKey }.toSet(), {}, {}, { cancelled++ }, {},
        autoAlways = true, onAutoAlwaysChange = { always = it }, countdownSeconds = 4)
    }
    compose.onNodeWithText("Workspace restoring automatically in 4s…").assertExists()
    compose.onNodeWithText("Restoring automatically in 4 s… Press Cancel to skip.").assertExists()
    compose.onNodeWithText("Restore now").assertExists()
    compose.onNodeWithText("Cancel").performClick()
    assertEquals(1, cancelled)
    compose.onNodeWithTag("auto_always").performClick()
    assertEquals(false, always)
  }

  @Test
  fun bootCountdownOverlay_formatsHeadlineAndSummaryCleanly() {
    assertEquals(
      "Workspace restoring automatically in 6s…",
      com.google.android.desktop.apprestorer.boot.BootCountdownOverlay.formatCountdownHeadline(6),
    )
    assertEquals(
      "6 windows across 2 desks · 3 Chrome tabs",
      com.google.android.desktop.apprestorer.boot.BootCountdownOverlay.formatCompactSummary(snapshot),
    )
  }

  @Test
  fun repository_autoRestoreDefaultsTrue_andCancelledBootExpiresOnSubsequentEmptyReboot() {
    var boot = com.google.android.desktop.apprestorer.data.BootToken(10, 1_000_000L)
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val repo = com.google.android.desktop.apprestorer.data.WorkspaceRepository(context) { boot }
    assertTrue(repo.autoRestore)

    // Boot 10: save live workspace.
    repo.saveLive(snapshot.copy(bootTokenAtCapture = boot.encode()))

    // Reboot -> Boot 11 (LOCKED_BOOT_COMPLETED then USER_PRESENT on same boot): pending stays intact.
    boot = com.google.android.desktop.apprestorer.data.BootToken(11, 2_000_000L)
    assertTrue(repo.promoteIfNewBoot())
    assertEquals(6, repo.loadPending()?.windows?.size)
    assertFalse(repo.promoteIfNewBoot())
    assertEquals(6, repo.loadPending()?.windows?.size)

    // User cancels auto-restore on Boot 11, opens no apps, and reboots to Boot 12 -> stale pending expires.
    boot = com.google.android.desktop.apprestorer.data.BootToken(12, 3_000_000L)
    assertFalse(repo.promoteIfNewBoot())
    assertNull(repo.loadPending())
  }

  @Test
  fun restorePrompt_disablesRestoreWithEmptySelection() {
    compose.setContent { RestorePromptContent(snapshot, emptySet(), {}, {}, {}, {}) }
    compose.onNodeWithTag("restore_windows").assertIsNotEnabled()
  }
}
