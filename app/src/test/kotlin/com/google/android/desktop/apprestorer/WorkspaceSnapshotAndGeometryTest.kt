package com.google.android.desktop.apprestorer

import com.google.android.desktop.apprestorer.Fixtures.EXTERNAL
import com.google.android.desktop.apprestorer.Fixtures.PRIMARY
import com.google.android.desktop.apprestorer.Fixtures.win
import com.google.android.desktop.apprestorer.capture.BrowserFacts
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.UsageEventRecord
import com.google.android.desktop.apprestorer.capture.UsageStatsWorkspaceReconciler
import com.google.android.desktop.apprestorer.capture.WindowGeometryClassifier
import com.google.android.desktop.apprestorer.capture.WorkspaceCaptureEngine
import com.google.android.desktop.apprestorer.data.BootToken
import com.google.android.desktop.apprestorer.data.WorkspaceSnapshotCodec
import com.google.android.desktop.apprestorer.inspect.ChromeWindowTabTracker
import com.google.android.desktop.apprestorer.inspect.OverviewDeskCard
import com.google.android.desktop.apprestorer.inspect.OverviewInspection
import com.google.android.desktop.apprestorer.inspect.OverviewTask
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.NormalizedBounds
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceSnapshotAndGeometryTest {

  private var now = 1_000_000L
  private lateinit var engine: WorkspaceCaptureEngine
  private val own = "com.google.android.desktop.apprestorer"

  private val chromeLeft = WindowBounds(0, 0, 1440, 1704)
  private val chromeRight = WindowBounds(1440, 0, 2880, 1704)
  private val filesFree = WindowBounds(300, 200, 1500, 1100)
  private val settingsFree = WindowBounds(900, 300, 2100, 1300)

  @Before
  fun setUp() {
    engine = WorkspaceCaptureEngine(Fixtures.FakeCatalog(), own, clock = { now })
  }

  private fun observe(vararg w: com.google.android.desktop.apprestorer.capture.ObservedWindow, transient: Boolean = false, displays: List<com.google.android.desktop.apprestorer.model.DisplayGeometry> = listOf(PRIMARY, EXTERNAL)) =
    engine.observe(displays, w.toList(), transient)

  private fun snap() = engine.snapshot(CaptureReason.LIVE, "bc=1;ep=0")

  private fun advance(ms: Long) { now += ms }

  // ---------------------------------------------------------------- geometry

  @Test
  fun classify_allVisualStates() {
    assertEquals(WindowVisualState.FULLSCREEN, WindowGeometryClassifier.classify(PRIMARY.bounds, PRIMARY, true))
    assertEquals(WindowVisualState.MAXIMIZED, WindowGeometryClassifier.classify(PRIMARY.workArea, PRIMARY, true))
    assertEquals(WindowVisualState.SNAPPED_LEFT, WindowGeometryClassifier.classify(chromeLeft, PRIMARY, true))
    assertEquals(WindowVisualState.SNAPPED_RIGHT, WindowGeometryClassifier.classify(chromeRight, PRIMARY, true))
    assertEquals(WindowVisualState.FREEFORM, WindowGeometryClassifier.classify(filesFree, PRIMARY, true))
    assertEquals(WindowVisualState.MINIMIZED, WindowGeometryClassifier.classify(filesFree, PRIMARY, false))
  }

  @Test
  fun plausibility_blocksMiniChromePhantomButKeepsCompactCalculator() {
    assertFalse(WindowGeometryClassifier.isPlausibleWindow(WindowBounds(0, 0, 660, 302), isBrowser = true))
    assertTrue(WindowGeometryClassifier.isPlausibleWindow(WindowBounds(0, 0, 360, 560), isBrowser = false))
    assertFalse(WindowGeometryClassifier.isPlausibleWindow(WindowBounds(0, 0, 150, 100), isBrowser = false))
  }

  @Test
  fun restoreBounds_exactOnSameDisplay_scaledAndClampedOnSmallerDisplay() {
    val w = captured("a", filesFree, WindowVisualState.FREEFORM)
    assertEquals(filesFree, WindowGeometryClassifier.resolveRestoreBounds(w, PRIMARY, PRIMARY))
    val scaled = WindowGeometryClassifier.resolveRestoreBounds(w, PRIMARY, EXTERNAL)
    assertEquals(200, scaled.left)
    assertEquals(120, scaled.top)
    assertTrue(scaled.right <= EXTERNAL.workArea.right && scaled.bottom <= EXTERNAL.workArea.bottom)
  }

  @Test
  fun restoreBounds_statesFollowTargetWorkArea() {
    assertEquals(EXTERNAL.workArea, WindowGeometryClassifier.resolveRestoreBounds(captured("m", PRIMARY.workArea, WindowVisualState.MAXIMIZED), PRIMARY, EXTERNAL))
    val left = WindowGeometryClassifier.resolveRestoreBounds(captured("l", chromeLeft, WindowVisualState.SNAPPED_LEFT), PRIMARY, EXTERNAL)
    assertEquals(WindowBounds(0, 0, 960, 1032), left)
    val full = captured("f", PRIMARY.bounds, WindowVisualState.FULLSCREEN)
    assertEquals(PRIMARY.bounds, WindowGeometryClassifier.resolveRestoreBounds(full, PRIMARY, PRIMARY))
    assertEquals(PRIMARY.workArea, WindowGeometryClassifier.resolveRestoreBounds(full, PRIMARY, PRIMARY, coerceToDesktop = true))
    val min = captured("n", filesFree, WindowVisualState.MINIMIZED).copy(preMinimizeState = WindowVisualState.MAXIMIZED)
    assertEquals(PRIMARY.workArea, WindowGeometryClassifier.resolveRestoreBounds(min, PRIMARY, PRIMARY))
  }

  // ---------------------------------------------------------------- codec / boot token

  @Test
  fun codec_roundTripsAndMigratesV5() {
    val s = WorkspaceSnapshot(windows = listOf(captured("a", filesFree, WindowVisualState.FREEFORM).copy(deskIndex = 2)), displays = listOf(PRIMARY))
    assertEquals(s, WorkspaceSnapshotCodec.decode(WorkspaceSnapshotCodec.encode(s)))
    val v5 = WorkspaceSnapshotCodec.encode(s.copy(schemaVersion = 5, totalDeskCountByDisplay = emptyMap(), activeDeskIndexByDisplay = emptyMap()))
      .let { it.dropLast(1) + ",\"futureField\":1}" }
    val migrated = WorkspaceSnapshotCodec.decode(v5)!!
    assertEquals(6, migrated.schemaVersion)
    assertEquals(3, migrated.totalDeskCountByDisplay[0])
    assertEquals(0, migrated.activeDeskIndexByDisplay[0])
    assertNull(WorkspaceSnapshotCodec.decode("not json"))
  }

  @Test
  fun bootToken_detectsNewBoot() {
    val a = BootToken(41, 1_000_000)
    assertFalse(BootToken.isNewBoot(a.encode(), BootToken(41, 1_030_000)))
    assertTrue(BootToken.isNewBoot(a.encode(), BootToken(42, 1_000_000)))
    assertFalse(BootToken.isNewBoot(BootToken(-1, 1_000_000).encode(), BootToken(-1, 1_060_000)))
    assertTrue(BootToken.isNewBoot(BootToken(-1, 1_000_000).encode(), BootToken(-1, 9_000_000)))
    assertTrue(BootToken.isNewBoot("", a))
  }

  // ---------------------------------------------------------------- engine: capture basics

  @Test
  fun capture_multiDisplay_zOrder_instances() {
    observe(
      win(1, KnownPackages.CHROME, chromeLeft, layer = 10),
      win(2, KnownPackages.CHROME, chromeRight, layer = 12),
      win(3, "com.android.documentsui", filesFree, layer = 20, focused = true),
      win(4, "com.android.settings", WindowBounds(100, 100, 1100, 900), layer = 5, display = 2),
    )
    val s = snap()
    assertEquals(4, s.windows.size)
    val chrome = s.windows.filter { it.packageName == KnownPackages.CHROME }
    assertEquals(listOf(0, 1), chrome.map { it.instanceIndex }.sorted())
    assertTrue(chrome.all { it.totalInstancesForApp == 2 })
    val files = s.windows.single { it.kind == AppKind.FILES }
    assertEquals(2, files.zOrderLayer)
    assertEquals(WindowVisualState.SNAPPED_LEFT, chrome.single { it.bounds == chromeLeft }.visualState)
    assertEquals(2, s.windows.single { it.kind == AppKind.SETTINGS }.displayId)
    assertEquals(setOf(0, 2), s.totalDeskCountByDisplay.keys)
  }

  @Test
  fun captionMinimize_marksMinimizedWithPreMinimizeState() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(3, "com.android.settings", settingsFree, layer = 9))
    engine.onCaptionAction(0, WindowBounds(1900, 300, 2000, 350), WorkspaceCaptureEngine.CaptionAction.MINIMIZE)
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    val settings = snap().windows.single { it.kind == AppKind.SETTINGS }
    assertFalse(settings.isVisible)
    assertEquals(WindowVisualState.MINIMIZED, settings.visualState)
    assertEquals(WindowVisualState.FREEFORM, settings.preMinimizeState)
    // Restored from the taskbar: same window comes back visible.
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(3, "com.android.settings", settingsFree, layer = 15))
    assertTrue(snap().windows.single { it.kind == AppKind.SETTINGS }.isVisible)
  }

  @Test
  fun captionClose_purgesImmediately_andUnexplainedVanishPurgesAfterSettle() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(3, "com.android.settings", settingsFree), win(4, "com.android.documentsui", filesFree))
    engine.onCaptionAction(0, WindowBounds(1900, 300, 2000, 350), WorkspaceCaptureEngine.CaptionAction.CLOSE)
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    assertTrue(snap().windows.none { it.kind == AppKind.SETTINGS })
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    assertTrue("pending vanish kept (as minimized) until settled", snap().windows.any { it.kind == AppKind.FILES })
    advance(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 10)
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    assertTrue(snap().windows.none { it.kind == AppKind.FILES })
  }

  @Test
  fun usageStats_stoppedWithoutDestroyed_meansMinimized() {
    val events = mutableListOf(UsageEventRecord("x", null, UsageStatsWorkspaceReconciler.ACTIVITY_DESTROYED, 10))
    val reconciler = UsageStatsWorkspaceReconciler { _, _ -> events }
    engine = WorkspaceCaptureEngine(Fixtures.FakeCatalog(), own, reconciler, clock = { now })
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    events += UsageEventRecord("com.android.documentsui", null, UsageStatsWorkspaceReconciler.ACTIVITY_STOPPED, now + 50)
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    advance(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 10)
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    val files = snap().windows.single { it.kind == AppKind.FILES }
    assertFalse(files.isVisible)
  }

  @Test
  fun taskbarActiveMarker_distinguishesMinimizeFromClose() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree), win(3, "com.android.settings", settingsFree))
    engine.onTaskbarState(mapOf("Chrome" to true, "Files" to true, "Settings" to false))
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    advance(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 10)
    engine.onTaskbarState(mapOf("Chrome" to true, "Files" to true, "Settings" to false))
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    val s = snap()
    assertFalse("Files still running in taskbar -> minimized", s.windows.single { it.kind == AppKind.FILES }.isVisible)
    assertTrue("Settings no longer running -> closed", s.windows.none { it.kind == AppKind.SETTINGS })
  }

  @Test
  fun captionMinimize_matchedByAppLabel() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree, layer = 9))
    engine.onCaptionAction(0, WindowBounds.EMPTY, WorkspaceCaptureEngine.CaptionAction.MINIMIZE, "Files")
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    assertFalse(snap().windows.single { it.kind == AppKind.FILES }.isVisible)
  }

  @Test
  fun overview_minimizedWindowOnActiveCard_andHarvestedSettingsOnDesk2() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    observe(win(1, KnownPackages.CHROME, chromeLeft)) // Files minimized without a caught click
    engine.onOverviewInspected(0, OverviewInspection(true, listOf(
      OverviewDeskCard(0, listOf(OverviewTask("Chrome", null), OverviewTask("Files", null))),
      OverviewDeskCard(1, listOf(OverviewTask("Settings", NormalizedBounds(0.3f, 0.2f, 0.7f, 0.7f)))),
    ), null))
    advance(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 10)
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    val s = snap()
    val files = s.windows.single { it.kind == AppKind.FILES }
    assertFalse(files.isVisible)
    assertEquals(filesFree, files.bounds)
    val settings = s.windows.single { it.kind == AppKind.SETTINGS }
    assertEquals(1, settings.deskIndex)
    assertTrue(settings.isVisible)
    assertEquals(2, s.totalDeskCountByDisplay[0])
  }

  @Test
  fun taskbarClickOnFocusedApp_isMinimize() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree, focused = true))
    engine.onTaskbarAppClicked(0, "Files")
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    assertFalse(snap().windows.single { it.kind == AppKind.FILES }.isVisible)
  }

  // ---------------------------------------------------------------- engine: virtual desks

  @Test
  fun deskSwitch_keepsHiddenDeskWindows_andTracksActiveDesk() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    // Switch to an empty Desk 2 and open Settings there.
    observe(win(7, "com.android.settings", settingsFree))
    var s = snap()
    assertEquals(1, s.activeDeskIndexByDisplay[0])
    assertEquals(2, s.totalDeskCountByDisplay[0])
    assertEquals(1, s.windows.single { it.kind == AppKind.SETTINGS }.deskIndex)
    assertTrue(s.windows.filter { it.kind != AppKind.SETTINGS }.all { it.deskIndex == 0 && it.isVisible })
    // Back to Desk 1: the same windowIds re-appear.
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    s = snap()
    assertEquals(0, s.activeDeskIndexByDisplay[0])
    assertEquals(3, s.windows.size)
    assertEquals(1, s.windows.single { it.kind == AppKind.SETTINGS }.deskIndex)
  }

  @Test
  fun deskSwitch_withSlideAnimationOverlap_isNotMistakenForCloses() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree), win(50, own, WindowBounds(400, 200, 1600, 1200)))
    advance(10_000)
    // Frame 1: Desk 2's Settings slides in while Desk 1's windows are still visible (shifted).
    observe(
      win(1, KnownPackages.CHROME, chromeLeft.offset(-900, 0)),
      win(4, "com.android.documentsui", filesFree.offset(-900, 0)),
      win(50, own, WindowBounds(400, 200, 1600, 1200).offset(-900, 0)),
      win(7, "com.android.settings", settingsFree.offset(1200, 0)),
    )
    advance(200)
    // Frame 2: Desk 1 gone, Settings settled.
    observe(win(7, "com.android.settings", settingsFree))
    advance(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 100)
    observe(win(7, "com.android.settings", settingsFree))
    val s = snap()
    assertEquals(1, s.activeDeskIndexByDisplay[0])
    assertEquals(1, s.windows.single { it.kind == AppKind.SETTINGS }.deskIndex)
    val chrome = s.windows.single { it.kind == AppKind.CHROME_BROWSER }
    assertEquals(0, chrome.deskIndex)
    assertTrue(chrome.isVisible)
    assertEquals("animation frame bounds reverted", chromeLeft, chrome.bounds)
    assertEquals(0, s.windows.single { it.kind == AppKind.FILES }.deskIndex)
  }

  @Test
  fun overviewWithUnreadableLabels_neverPurges() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    observe(win(7, "com.android.settings", settingsFree))
    engine.onOverviewInspected(0, OverviewInspection(true, listOf(OverviewDeskCard(0, emptyList()), OverviewDeskCard(1, emptyList())), null))
    assertEquals(3, snap().windows.size)
  }

  @Test
  fun deskSwitch_isIndependentPerDisplay() {
    val ext = win(9, "com.android.settings", WindowBounds(100, 100, 1100, 900), display = 2)
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree), ext)
    observe(win(7, KnownPackages.CALCULATOR, WindowBounds(100, 100, 460, 660)), ext)
    val s = snap()
    assertEquals(1, s.activeDeskIndexByDisplay[0])
    assertEquals(0, s.activeDeskIndexByDisplay[2])
    assertEquals(0, s.windows.single { it.displayId == 2 }.deskIndex)
    assertEquals(1, s.totalDeskCountByDisplay[2])
  }

  @Test
  fun ownAppActsAsDeskAnchor_andIsNotRestored() {
    observe(win(50, own, WindowBounds(400, 200, 1600, 1200)))
    engine.onDeskSwitchHint(0, 1)
    observe(win(1, KnownPackages.CHROME, chromeLeft))
    engine.onDeskSwitchHint(0, 0)
    observe(win(50, own, WindowBounds(400, 200, 1600, 1200)))
    val s = snap()
    assertEquals(0, s.activeDeskIndexByDisplay[0])
    assertEquals(1, s.windows.single().deskIndex)
    assertTrue(s.windows.none { it.packageName == own })
  }

  @Test
  fun overviewInspection_assignsDesks_harvestsUnvisited_andPurgesClosed() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    observe(win(7, "com.android.settings", settingsFree)) // desk 2
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree)) // back on desk 1
    val inspection = OverviewInspection(
      isOverviewVisible = true,
      desks = listOf(
        OverviewDeskCard(0, listOf(OverviewTask("Chrome", null), OverviewTask("Files", null))),
        OverviewDeskCard(1, emptyList()), // Settings was closed from Overview
        OverviewDeskCard(2, listOf(OverviewTask("Calculator", NormalizedBounds(0.1f, 0.1f, 0.25f, 0.5f)))),
      ),
      addDeskButton = null,
    )
    engine.onOverviewInspected(0, inspection)
    val s = snap()
    assertEquals(3, s.totalDeskCountByDisplay[0])
    assertTrue(s.windows.none { it.kind == AppKind.SETTINGS })
    val calc = s.windows.single { it.packageName == KnownPackages.CALCULATOR }
    assertEquals(2, calc.deskIndex)
    assertFalse("Overview tiles are a grid, not real geometry", calc.boundsKnown)
  }

  @Test
  fun transientOcclusion_keepsWindows() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    observe(transient = true)
    advance(5_000)
    observe(transient = true)
    assertEquals(2, snap().windows.size)
    assertEquals(0, snap().activeDeskIndexByDisplay[0])
  }

  @Test
  fun freeze_ignoresPowerMenuTeardown() {
    observe(win(1, KnownPackages.CHROME, chromeLeft), win(4, "com.android.documentsui", filesFree))
    engine.freeze(CaptureReason.POWER_MENU_PRE_FREEZE)
    observe()
    assertEquals(2, snap().windows.size)
    engine.freeze(CaptureReason.SHUTDOWN_BROADCAST)
    engine.unfreeze()
    assertTrue(engine.isFrozen)
  }

  // ---------------------------------------------------------------- engine: identity

  @Test
  fun twaInChromeTask_isCapturedAsStandaloneApp() {
    observe(win(5, KnownPackages.CHROME, filesFree, caption = "Calendar", browser = BrowserFacts(hasWebAppHeader = true)))
    val w = snap().windows.single()
    assertEquals("com.google.android.calendar", w.packageName)
    assertEquals(AppKind.TWA, w.kind)
    assertEquals(KnownPackages.CHROME, w.hostBrowserPackage)
  }

  @Test
  fun chromeBrowserWindow_withCaptionChrome_staysBrowser() {
    observe(win(5, KnownPackages.CHROME, filesFree, caption = "Chrome", browser = BrowserFacts(hasBrowserChrome = true, urlBarText = "example.com")))
    val w = snap().windows.single()
    assertEquals(AppKind.CHROME_BROWSER, w.kind)
    assertEquals(listOf("https://example.com"), w.tabUrls)
  }

  @Test
  fun chromeBrowserWithOmnibox_neverMisclassifiedAsTwaOrOwnApp() {
    observe(
      win(
        5, KnownPackages.CHROME, PRIMARY.workArea, caption = "Calendar",
        browser = BrowserFacts(hasBrowserChrome = true, hasWebAppHeader = false, urlBarText = "github.com"),
      )
    )
    val w = snap().windows.single()
    assertEquals(AppKind.CHROME_BROWSER, w.kind)
    assertEquals(KnownPackages.CHROME, w.packageName)
  }

  @Test
  fun maximizedWindowCoveringFreeformWindows_preservesExactBoundsWithoutPhantomDesks() {
    val antigravityBounds = WindowBounds(562, 285, 2383, 1446)
    val filesBounds = WindowBounds(226, 393, 2026, 1523)
    engine.onTaskbarState(mapOf("Chrome" to true, "Files" to true, "Calculator" to true))
    observe(
      win(10, KnownPackages.CHROME, PRIMARY.workArea, layer = 1),
      win(11, "com.android.documentsui", filesBounds, layer = 3),
      win(12, KnownPackages.CALCULATOR, antigravityBounds, layer = 4, focused = true),
    )
    // User clicks maximized Chrome, bringing it to front and occluding Files & Calculator on Desk 1.
    observe(win(10, KnownPackages.CHROME, PRIMARY.workArea, layer = 6, focused = true))
    advance(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 100)
    observe(win(10, KnownPackages.CHROME, PRIMARY.workArea, layer = 6, focused = true))

    val s = snap()
    assertEquals(1, s.totalDeskCountByDisplay[0])
    assertEquals(0, s.activeDeskIndexByDisplay[0])
    val files = s.windows.single { it.kind == AppKind.FILES }
    val calc = s.windows.single { it.packageName == KnownPackages.CALCULATOR }
    val chrome = s.windows.single { it.kind == AppKind.CHROME_BROWSER }
    assertEquals(filesBounds, files.bounds)
    assertEquals(antigravityBounds, calc.bounds)
    assertTrue("Occluded freeform windows remain unminimized", files.isVisible && calc.isVisible)
    assertTrue("Maximized Chrome is above occluded windows in z-order", chrome.zOrderLayer > files.zOrderLayer && chrome.zOrderLayer > calc.zOrderLayer)
  }

  @Test
  fun overviewLiveTiles_keepOccludedActiveDeskWindowsUnminimized_andClampPhantomDesks() {
    val filesBounds = WindowBounds(226, 393, 2026, 1523)
    observe(
      win(10, KnownPackages.CHROME, PRIMARY.workArea, layer = 5, focused = true),
      win(11, "com.android.documentsui", filesBounds, layer = 2),
      win(50, own, WindowBounds(203, 133, 2356, 1381), layer = 6),
    )
    // Chrome covers Files; Terminal is minimized; Settings is on Desk 2.
    observe(
      win(10, KnownPackages.CHROME, PRIMARY.workArea, layer = 5, focused = true),
      win(50, own, WindowBounds(203, 133, 2356, 1381), layer = 6),
    )
    val inspection = OverviewInspection(
      isOverviewVisible = true,
      desks = listOf(
        OverviewDeskCard(0, listOf(
          OverviewTask(own, null, isLiveTile = true),
          OverviewTask("Chrome", null, isLiveTile = true),
          OverviewTask("Files", null, isLiveTile = true),
          OverviewTask("Calculator", null, isLiveTile = false),
        )),
        OverviewDeskCard(1, listOf(OverviewTask("Settings", null, isLiveTile = false))),
      ),
      addDeskButton = null,
    )
    engine.onOverviewInspected(0, inspection)
    val s = snap()
    assertEquals(2, s.totalDeskCountByDisplay[0])
    assertEquals(0, s.activeDeskIndexByDisplay[0])
    val files = s.windows.single { it.kind == AppKind.FILES }
    assertEquals(filesBounds, files.bounds)
    assertTrue("Live-tile Files on active desk is unminimized", files.isVisible)
    val calc = s.windows.single { it.packageName == KnownPackages.CALCULATOR }
    assertFalse("Non-live-tile Calculator on active desk is minimized", calc.isVisible)
    val settings = s.windows.single { it.kind == AppKind.SETTINGS }
    assertEquals(1, settings.deskIndex)
    assertTrue("Settings on Desk 2 is unminimized", settings.isVisible)
  }

  @Test
  fun deviceLinkDebugAndConnectedPhone_excludedFromCaptureAndOverviewDesks() {
    assertTrue(KnownPackages.isExcludedCompanionApp("com.google.android.apps.devicelink.debug", "Device Link Debug"))
    assertTrue(KnownPackages.isExcludedCompanionApp("com.example.unknown", "Connected Phone"))
    assertTrue(KnownPackages.isExcludedCompanionApp("com.google.ambient.streaming", "Cross-Device Services"))
    assertFalse("Chrome tab searching for connected phone stays included",
      KnownPackages.isExcludedCompanionApp(KnownPackages.CHROME, "Chrome", windowTitle = "Chrome: connected phone"))
    assertFalse("Settings Connected devices screen stays included",
      KnownPackages.isExcludedCompanionApp("com.android.settings", "Settings", windowTitle = "Connected devices"))

    observe(
      win(1, KnownPackages.CHROME, chromeLeft),
      win(2, "com.android.documentsui", filesFree),
      win(99, "com.google.android.apps.devicelink.debug", settingsFree, title = "Device Link Debug", caption = "Device Link Debug"),
    )
    assertEquals(listOf("Chrome", "Files"), snap().windows.map { it.label })

    // Device Link Debug opened a dedicated Desk 2 in Overview: both the app and its desk are ignored.
    engine.onOverviewInspected(0, OverviewInspection(
      isOverviewVisible = true,
      desks = listOf(
        OverviewDeskCard(0, listOf(OverviewTask("Chrome", null), OverviewTask("Files", null))),
        OverviewDeskCard(1, listOf(OverviewTask("Device Link Debug", null))),
        OverviewDeskCard(2, listOf(OverviewTask("Connected Phone", null))),
      ),
      addDeskButton = null,
    ))
    val s = snap()
    assertEquals(1, s.totalDeskCountByDisplay[0])
    assertEquals(0, s.activeDeskIndexByDisplay[0])
    assertEquals(listOf("Chrome", "Files"), s.windows.map { it.label })
  }

  @Test
  fun gemini_robinIsCaptured_searchOverlayRejected() {
    observe(
      win(6, KnownPackages.GOOGLE_QUICK_SEARCH_BOX, WindowBounds(1800, 200, 2400, 1400), cls = "com.google.android.apps.search.robin.main.MainActivity"),
      win(8, KnownPackages.GOOGLE_QUICK_SEARCH_BOX, WindowBounds(500, 200, 1800, 900), cls = "com.google.android.apps.gsa.velvet.ui.OpaActivity"),
    )
    val w = snap().windows.single()
    assertEquals(KnownPackages.GEMINI, w.packageName)
    assertEquals(AppKind.GEMINI, w.kind)
  }

  @Test
  fun phantomMiniChrome_isIgnored_andEmbeddedPanesCoalesce() {
    observe(
      win(1, KnownPackages.CHROME, WindowBounds(100, 100, 760, 402)),
      win(2, "com.android.settings", WindowBounds(200, 200, 800, 1200)),
      win(3, "com.android.settings", WindowBounds(800, 200, 1600, 1200)),
    )
    val s = snap()
    assertEquals(1, s.windows.size)
    assertEquals(WindowBounds(200, 200, 1600, 1200), s.windows.single().bounds)
  }

  // ---------------------------------------------------------------- chrome tabs

  @Test
  fun tabTracker_followsTabStripAndIgnoresTyping() {
    val t = ChromeWindowTabTracker()
    val titles = listOf("News", "Docs", "Mail")
    t.update(1, BrowserFacts(hasBrowserChrome = true, urlBarText = "news.example.com", tabTitles = titles, selectedTabTitle = "News", tabCount = 3))
    t.update(1, BrowserFacts(hasBrowserChrome = true, urlBarText = "docs.example.com/d/1", tabTitles = titles, selectedTabTitle = "Docs", tabCount = 3))
    t.update(1, BrowserFacts(hasBrowserChrome = true, urlBarText = "typing something", urlBarFocused = true, tabTitles = titles, selectedTabTitle = "Mail", tabCount = 3))
    t.update(1, BrowserFacts(hasBrowserChrome = true, urlBarText = "https://mail.example.com", tabTitles = titles, selectedTabTitle = "Mail", tabCount = 3))
    var state = t.tabsFor(1)!!
    assertEquals(listOf("https://news.example.com", "https://docs.example.com/d/1", "https://mail.example.com"), state.urls)
    assertEquals(2, state.activeIndex)
    // "Docs" tab closed.
    t.update(1, BrowserFacts(hasBrowserChrome = true, urlBarText = "mail.example.com", tabTitles = listOf("News", "Mail"), selectedTabTitle = "Mail", tabCount = 2))
    state = t.tabsFor(1)!!
    assertEquals(listOf("https://news.example.com", "https://mail.example.com"), state.urls)
  }

  @Test
  fun browserFacts_desktopTabStripBehindLargePage() {
    val page = Fixtures.FakeNode(className = "android.webkit.WebView", children = List(50) { i ->
      Fixtures.FakeNode(children = List(100) { j -> Fixtures.FakeNode(text = "node $i-$j") })
    })
    val tabs = (1..6).map { i ->
      Fixtures.FakeNode(contentDescription = if (i == 3) "Tab $i, Selected Tab" else "Tab $i, Tab")
    }
    val root = Fixtures.FakeNode(children = listOf(
      Fixtures.FakeNode(children = listOf(page)),
      Fixtures.FakeNode(viewIdResourceName = "com.android.chrome:id/url_bar", text = "three.example.com"),
      Fixtures.FakeNode(children = tabs),
    ))
    val facts = TwaAndBrowserInspector.readBrowserFacts(root)
    assertEquals((1..6).map { "Tab $it" }, facts.tabTitles)
    assertEquals("Tab 3", facts.selectedTabTitle)
    assertEquals(6, facts.tabCount)
    assertEquals(6, TwaAndBrowserInspector.findTabNodes(root).size)
  }

  @Test
  fun normalizeUrl_rules() {
    assertEquals("https://a.com/x", TwaAndBrowserInspector.normalizeUrl("a.com/x"))
    assertEquals("http://b.org", TwaAndBrowserInspector.normalizeUrl("http://b.org"))
    assertNull(TwaAndBrowserInspector.normalizeUrl("chrome://newtab"))
    assertNull(TwaAndBrowserInspector.normalizeUrl("search terms here"))
    assertNull(TwaAndBrowserInspector.normalizeUrl("Search"))
    assertNotNull(TwaAndBrowserInspector.normalizeUrl("localhost:8080/app"))
  }

  @Test
  fun closedAppMode_capturesDesksMinimizedGeometryAndTabsWithoutOwnWindow() {
    // Googlebook App Restorer window is NEVER opened in this session:
    // 1. User opens Chrome (snapped left, 2 tabs) and Files (freeform) on Desk 1.
    val chrome = win(
      1, KnownPackages.CHROME, chromeLeft, display = 0, layer = 1,
      browser = BrowserFacts(hasBrowserChrome = true, urlBarText = "docs.example.com", tabTitles = listOf("Docs", "Specs"), selectedTabTitle = "Docs", tabCount = 2),
    )
    val files = win(2, "com.android.documentsui", filesFree, display = 0, layer = 2)
    engine.onTaskbarState(mapOf("Chrome" to true, "Files" to true))
    observe(chrome, files)

    // 2. User minimizes Files -> Files stays captured as MINIMIZED with exact pre-minimize bounds.
    engine.onCaptionAction(0, WindowBounds(filesFree.left, filesFree.top - 40, filesFree.right, filesFree.top), WorkspaceCaptureEngine.CaptionAction.MINIMIZE, "Files")
    engine.onTaskbarState(mapOf("Chrome" to true, "Files" to true), minimizedLabels = setOf("Files"))
    observe(chrome)

    // 3. User adds Desk 2 and 4-finger swipes from Desk 1 (only 1 visible window: Chrome, Restorer is closed!) to empty Desk 2.
    engine.onDeskAdded(0)
    advance(500)
    observe() // empty Desk 2; taskbar still reports Chrome Active (not minimized)
    assertEquals(1, engine.activeDesk(0))

    // 4. User opens Settings on Desk 2, then opens Power Menu before reboot (freezes state).
    val settings = win(3, "com.android.settings", settingsFree, display = 0, layer = 3)
    engine.onTaskbarState(mapOf("Chrome" to true, "Files" to true, "Settings" to true), minimizedLabels = setOf("Files"))
    observe(settings)
    engine.freeze(CaptureReason.POWER_MENU_PRE_FREEZE)
    // Shutdown tears down Settings window while frozen -> ignored!
    observe()

    val s = engine.snapshot(CaptureReason.POWER_MENU_PRE_FREEZE, "bc=5;ep=100")
    assertEquals(2, s.totalDeskCountByDisplay[0])
    assertEquals(1, s.activeDeskIndexByDisplay[0])
    val byPkg = s.windows.associateBy { it.packageName }
    assertEquals(0, byPkg.getValue(KnownPackages.CHROME).deskIndex)
    assertEquals(WindowVisualState.SNAPPED_LEFT, byPkg.getValue(KnownPackages.CHROME).visualState)
    assertEquals(listOf("https://docs.example.com"), byPkg.getValue(KnownPackages.CHROME).tabUrls)
    assertEquals(0, byPkg.getValue("com.android.documentsui").deskIndex)
    assertEquals(WindowVisualState.MINIMIZED, byPkg.getValue("com.android.documentsui").visualState)
    assertEquals(filesFree, byPkg.getValue("com.android.documentsui").bounds)
    assertEquals(1, byPkg.getValue("com.android.settings").deskIndex)
    assertEquals(settingsFree, byPkg.getValue("com.android.settings").bounds)
  }

  private fun captured(key: String, b: WindowBounds, state: WindowVisualState) = CapturedWindow(
    windowKey = key, packageName = "com.android.documentsui", bounds = b,
    normalized = WindowGeometryClassifier.normalize(b, PRIMARY), visualState = state,
    isVisible = state != WindowVisualState.MINIMIZED,
  )
}
