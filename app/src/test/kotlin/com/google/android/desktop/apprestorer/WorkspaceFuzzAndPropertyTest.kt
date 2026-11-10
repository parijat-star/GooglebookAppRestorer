package com.google.android.desktop.apprestorer

import com.google.android.desktop.apprestorer.Fixtures.EXTERNAL
import com.google.android.desktop.apprestorer.Fixtures.FakeCatalog
import com.google.android.desktop.apprestorer.Fixtures.FakeNode
import com.google.android.desktop.apprestorer.Fixtures.PRIMARY
import com.google.android.desktop.apprestorer.capture.BrowserFacts
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.ObservedWindow
import com.google.android.desktop.apprestorer.capture.WindowGeometryClassifier
import com.google.android.desktop.apprestorer.capture.WorkspaceCaptureEngine
import com.google.android.desktop.apprestorer.data.BootToken
import com.google.android.desktop.apprestorer.data.WorkspaceSnapshotCodec
import com.google.android.desktop.apprestorer.inspect.MAX_UI_TREE_DEPTH
import com.google.android.desktop.apprestorer.inspect.OverviewDeskCard
import com.google.android.desktop.apprestorer.inspect.OverviewDeskInspector
import com.google.android.desktop.apprestorer.inspect.OverviewInspection
import com.google.android.desktop.apprestorer.inspect.OverviewTask
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import com.google.android.desktop.apprestorer.inspect.walk
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.NormalizedBounds
import com.google.android.desktop.apprestorer.model.WORKSPACE_SCHEMA_VERSION
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.restore.LaunchCapabilities
import com.google.android.desktop.apprestorer.restore.RestorePlanner
import com.google.android.desktop.apprestorer.restore.TabReconciler
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic multi-seed fuzzing and property-based invariant test suite covering codecs,
 * parsers, URL scheme sanitization, geometry classifiers, deep UI trees, the workspace capture
 * state machine, and the restore planner.
 */
class WorkspaceFuzzAndPropertyTest {

  private val ownPkg = "com.google.android.desktop.apprestorer"

  private val fuzzCaps = object : LaunchCapabilities {
    override fun launcherComponent(packageName: String) = when (packageName) {
      "uninstalled.pkg" -> null
      KnownPackages.CHROME -> "${KnownPackages.CHROME}/${KnownPackages.CHROME_MAIN_ACTIVITY}"
      else -> "$packageName/.MainActivity"
    }
    override fun isExportedComponent(component: String) = component.endsWith(KnownPackages.FILES_ACTIVITY)
  }

  private val interestingInts = intArrayOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -100_000, -1920, -1, 0, 1, 12, 139, 140, 199, 200,
    379, 380, 479, 480, 1080, 1600, 1920, 2560, 3840, 7680, 100_000, Int.MAX_VALUE - 1, Int.MAX_VALUE,
  )

  private val interestingFloats = floatArrayOf(
    Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
    -100f, -0.001f, 0f, 0.25f, 0.5f, 0.75f, 1f, 1.001f, 1e9f,
  )

  private fun Random.nextInterestingInt(): Int =
    if (nextInt(4) == 0) interestingInts[nextInt(interestingInts.size)] else nextInt(-4096, 8192)

  private fun Random.nextInterestingFloat(): Float =
    if (nextInt(3) == 0) interestingFloats[nextInt(interestingFloats.size)] else nextFloat() * 2f - 0.5f

  private fun Random.nextFuzzString(maxLen: Int = 96): String {
    val len = nextInt(0, maxLen + 1)
    val sb = StringBuilder(len)
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:/?.#-_%;=& \t\r\n\u0000\u200B🌐\"'{}[]"
    repeat(len) {
      sb.append(if (nextInt(8) == 0) nextInt(0, 0xFFFF).toChar() else alphabet[nextInt(alphabet.length)])
    }
    return sb.toString()
  }

  private fun Random.nextBounds(): WindowBounds =
    WindowBounds(nextInterestingInt(), nextInterestingInt(), nextInterestingInt(), nextInterestingInt())

  private fun Random.nextDisplay(id: Int = nextInt(0, 5)): DisplayGeometry =
    DisplayGeometry(
      displayId = id,
      widthPx = nextInterestingInt(),
      heightPx = nextInterestingInt(),
      workArea = nextBounds(),
      densityDpi = nextInterestingInt(),
      name = nextFuzzString(24),
    )

  // ------------------------------------------------------------------------- 1. Codec & Migration Fuzzer

  @Test
  fun fuzz_snapshotCodecAndMigration_neverThrowsAndRoundTrips() {
    val rng = Random(0xC0DEC_01L)
    val kinds = AppKind.entries
    val states = WindowVisualState.entries
    val reasons = CaptureReason.entries

    // Round-trip + mutation fuzzing across 350 generated snapshots & 1,200 corrupted inputs.
    repeat(350) {
      val displays = List(rng.nextInt(0, 4)) { idx ->
        val w = rng.nextInt(320, 5120)
        val h = rng.nextInt(240, 3200)
        DisplayGeometry(idx, w, h, WindowBounds(0, 0, w, (h - 64).coerceAtLeast(1)), 160, "Disp-$idx")
      }
      val windows = List(rng.nextInt(0, 12)) { wIdx ->
        val dispId = displays.firstOrNull()?.displayId ?: 0
        val desk = rng.nextInt(0, 4)
        CapturedWindow(
          windowKey = "w-$wIdx-${rng.nextInt(1000)}",
          packageName = "com.example.app$wIdx",
          label = "App $wIdx",
          kind = kinds[rng.nextInt(kinds.size)],
          displayId = dispId,
          deskIndex = desk,
          bounds = WindowBounds(rng.nextInt(0, 1000), rng.nextInt(0, 800), rng.nextInt(1001, 2400), rng.nextInt(801, 1600)),
          normalized = NormalizedBounds(0.1f, 0.1f, 0.7f, 0.7f),
          boundsKnown = rng.nextBoolean(),
          visualState = states[rng.nextInt(states.size)],
          preMinimizeState = if (rng.nextBoolean()) WindowVisualState.FREEFORM else null,
          isVisible = rng.nextBoolean(),
          zOrderLayer = wIdx,
          tabUrls = List(rng.nextInt(0, 5)) { t -> "https://example$t.com/p" },
          tabTitles = List(rng.nextInt(0, 5)) { t -> "Tab $t" },
          tabCount = rng.nextInt(0, 8),
        )
      }
      val snap = WorkspaceSnapshot(
        schemaVersion = WORKSPACE_SCHEMA_VERSION,
        capturedAtMs = rng.nextLong(0L, 9_000_000_000_000L),
        bootTokenAtCapture = "bc=${rng.nextInt(0, 100)};ep=${rng.nextLong(0L, 1_000_000_000L)}",
        reason = reasons[rng.nextInt(reasons.size)],
        displays = displays,
        activeDeskIndexByDisplay = displays.associate { it.displayId to rng.nextInt(0, 3) },
        totalDeskCountByDisplay = displays.associate { it.displayId to rng.nextInt(1, 5) },
        windows = windows,
      )
      val json = WorkspaceSnapshotCodec.encode(snap)
      val decoded = WorkspaceSnapshotCodec.decode(json)
      assertEquals(snap, decoded)

      // Mutate / truncate the encoded JSON at random positions: decode() must NEVER throw.
      repeat(3) {
        val cut = rng.nextInt(0, json.length + 1)
        val mutated = json.substring(0, cut) + rng.nextFuzzString(16)
        WorkspaceSnapshotCodec.decode(mutated)
      }

      // Downgrade schemaVersion to < v6 and verify migrate() produces valid invariants.
      val legacy = WorkspaceSnapshotCodec.migrate(
        snap.copy(schemaVersion = rng.nextInt(-2, WORKSPACE_SCHEMA_VERSION), totalDeskCountByDisplay = emptyMap(), activeDeskIndexByDisplay = emptyMap())
      )
      assertEquals(WORKSPACE_SCHEMA_VERSION, legacy.schemaVersion)
      for ((id, total) in legacy.totalDeskCountByDisplay) {
        assertTrue(total >= 1)
        assertTrue(legacy.activeDeskIndexByDisplay.getValue(id) in 0 until total)
      }
    }

    repeat(500) {
      WorkspaceSnapshotCodec.decode(rng.nextFuzzString(256))
    }
  }

  // ------------------------------------------------------------------------- 2. BootToken Fuzzer

  @Test
  fun fuzz_bootTokenParser_neverThrowsAndRespectsSymmetry() {
    val rng = Random(0xB007_704EL)
    val adversarial = listOf(
      null, "", "   ", ";", "=", "bc=;ep=", "bc=abc;ep=123", "bc=1;ep=xyz",
      "bc=${Int.MAX_VALUE};ep=${Long.MAX_VALUE}", "bc=${Int.MIN_VALUE};ep=${Long.MIN_VALUE}",
      "bc=-1;ep=1000000", "bc=0;ep=0;extra=foo=bar",
    )
    for (s in adversarial) {
      val parsed = BootToken.parse(s)
      if (parsed != null) {
        assertEquals(parsed, BootToken.parse(parsed.encode()))
        assertTrue(parsed.isSameBootAs(parsed))
        assertFalse(BootToken.isNewBoot(parsed.encode(), parsed))
      }
    }
    repeat(1_500) {
      val raw = rng.nextFuzzString(64)
      val parsed = BootToken.parse(raw)
      if (parsed != null) {
        assertEquals(parsed, BootToken.parse(parsed.encode()))
        assertTrue(parsed.isSameBootAs(parsed))
      }
      val t1 = BootToken(rng.nextInt(-5, 50), rng.nextLong(0L, 10_000_000L))
      val t2 = BootToken(rng.nextInt(-5, 50), rng.nextLong(0L, 10_000_000L))
      assertEquals(t1.isSameBootAs(t2), t2.isSameBootAs(t1))
    }
  }

  // ------------------------------------------------------------------------- 3. URL Scheme & Omnibox Fuzzer

  @Test
  fun fuzz_urlNormalizer_blocksSchemeInjectionAndNeverThrows() {
    val dangerousPayloads = listOf(
      "javascript:alert(1)",
      "javascript://evil.com/%0aalert(1)",
      "JAVASCRIPT:prompt(document.cookie)",
      "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
      "file:///storage/emulated/0/Download/secret.txt",
      "file:///etc/hosts",
      "content://com.android.contacts/contacts/1",
      "data:text/html,<script>alert(1)</script>",
      "blob:https://example.com/550e8400",
      "vbscript:msgbox(1)",
      "chrome://settings",
      "chrome-extension://abcdef/popup.html",
      "view-source:https://example.com",
      "about:blank",
      "mailto:test@example.com",
      "tel:+15551234567",
      "http://",
      "https://",
      "example.com:99999/path",
      "example.com:0/path",
      "example.com:notaport",
      "..example.com",
      "example..com",
      "https://evil.com/path\r\nHeader: injected",
      "https://evil.com/\u0000null",
    )
    for (payload in dangerousPayloads) {
      assertNull("Expected dangerous/invalid URL to be rejected: $payload", TwaAndBrowserInspector.normalizeUrl(payload))
    }

    val validUrls = listOf(
      "example.com" to "https://example.com",
      "docs.google.com/spreadsheets/d/123" to "https://docs.google.com/spreadsheets/d/123",
      "localhost:8080/api" to "https://localhost:8080/api",
      "http://intranet.corp.local:3000/test" to "http://intranet.corp.local:3000/test",
      "https://kotlinlang.org/docs/home.html" to "https://kotlinlang.org/docs/home.html",
    )
    for ((raw, expected) in validUrls) {
      val norm = TwaAndBrowserInspector.normalizeUrl(raw)
      assertEquals(expected, norm)
      // Idempotence: normalizing an already-normalized URL returns the exact same string.
      assertEquals(norm, TwaAndBrowserInspector.normalizeUrl(norm))
    }

    val rng = Random(0x5AFE_0911L)
    repeat(2_500) {
      val candidate = rng.nextFuzzString(128)
      val out = TwaAndBrowserInspector.normalizeUrl(candidate)
      if (out != null) {
        val lower = out.lowercase()
        assertTrue("Non-HTTP(S) scheme leaked: $out", lower.startsWith("http://") || lower.startsWith("https://"))
        assertFalse(out.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f })
        assertEquals(out, TwaAndBrowserInspector.normalizeUrl(out))
      }
    }
  }

  // ------------------------------------------------------------------------- 4. Geometry Classifier Fuzzer

  @Test
  fun fuzz_windowGeometryClassifier_neverThrowsOnDegenerateOrExtremeBounds() {
    val rng = Random(0x6E0A_E721L)
    val states = WindowVisualState.entries

    repeat(2_500) {
      val srcDisplay = if (rng.nextBoolean()) null else rng.nextDisplay()
      val dstDisplay = rng.nextDisplay()
      val bounds = rng.nextBounds()
      val norm = NormalizedBounds(
        rng.nextInterestingFloat(),
        rng.nextInterestingFloat(),
        rng.nextInterestingFloat(),
        rng.nextInterestingFloat(),
      )
      val classified = WindowGeometryClassifier.classify(bounds, dstDisplay, rng.nextBoolean())
      val normalized = WindowGeometryClassifier.normalize(bounds, dstDisplay)
      assertTrue(normalized.left in 0f..1f && normalized.top in 0f..1f)
      assertTrue(normalized.right in 0f..1f && normalized.bottom in 0f..1f)

      val denorm = WindowGeometryClassifier.denormalize(norm, dstDisplay)
      val clamped = WindowGeometryClassifier.clampOnScreen(bounds, dstDisplay)
      assertTrue("Clamped width must be >= 1, got $clamped", clamped.width >= 1)
      assertTrue("Clamped height must be >= 1, got $clamped", clamped.height >= 1)

      val cw = CapturedWindow(
        windowKey = "k",
        packageName = "com.example.test",
        bounds = bounds,
        normalized = norm,
        visualState = states[rng.nextInt(states.size)],
        preMinimizeState = if (rng.nextBoolean()) states[rng.nextInt(states.size)] else null,
      )
      val restored = WindowGeometryClassifier.resolveRestoreBounds(cw, srcDisplay, dstDisplay, coerceToDesktop = rng.nextBoolean())
      WindowGeometryClassifier.matches(restored, denorm, dstDisplay)
      WindowGeometryClassifier.isPlausibleWindow(bounds, rng.nextBoolean())
    }
  }

  // ------------------------------------------------------------------------- 5. Deep / Malformed UI Tree Fuzzer

  @Test
  fun fuzz_uiTreeAndOverviewAndBrowserInspectors_boundedAgainstDeepAndMalformedTrees() {
    // 1. Pathological 450-deep chain must stop cleanly at MAX_UI_TREE_DEPTH without StackOverflowError.
    var chain = FakeNode(text = "leaf", viewIdResourceName = "com.android.chrome:id/url_bar")
    repeat(450) {
      chain = FakeNode(children = listOf(chain))
    }
    var maxVisitedDepth = 0
    chain.walk(maxNodes = 10_000) { _, depth ->
      if (depth > maxVisitedDepth) maxVisitedDepth = depth
      true
    }
    assertTrue(maxVisitedDepth <= MAX_UI_TREE_DEPTH)
    OverviewDeskInspector.inspect(listOf(chain))
    TwaAndBrowserInspector.readBrowserFacts(chain)

    // 2. Random malformed UI hierarchies.
    val rng = Random(0x72EE_F022L)
    val sampleIds = listOf(
      null,
      "com.google.android.apps.nexuslauncher:id/recents_view",
      "com.google.android.apps.nexuslauncher:id/task_view_desktop",
      "com.google.android.apps.nexuslauncher:id/task_content_view",
      "com.google.android.apps.nexuslauncher:id/task_thumbnail_live_tile",
      "com.google.android.apps.nexuslauncher:id/header_app_title",
      "com.google.android.apps.nexuslauncher:id/add_desktop_button",
      "com.google.android.apps.nexuslauncher:id/overview_actions_view",
      "com.android.chrome:id/url_bar",
      "com.android.chrome:id/tab_switcher_button",
      "com.android.chrome:id/web_app_header_layout",
    )
    val sampleClasses = listOf(null, "android.view.View", "android.webkit.WebView", "com.android.quickstep.views.DesktopTaskView")
    val sampleTexts = listOf(null, "", "null", "NULL", "Chrome", "Files", "Device Link Debug", "docs.example.com", "3 open tabs")

    fun buildRandomTree(depth: Int): FakeNode {
      val childCount = if (depth >= 5) 0 else rng.nextInt(0, 5)
      return FakeNode(
        viewIdResourceName = sampleIds[rng.nextInt(sampleIds.size)],
        className = sampleClasses[rng.nextInt(sampleClasses.size)],
        text = sampleTexts[rng.nextInt(sampleTexts.size)] ?: rng.nextFuzzString(24),
        contentDescription = sampleTexts[rng.nextInt(sampleTexts.size)] ?: "${rng.nextFuzzString(16)}, selected tab",
        boundsInScreen = rng.nextBounds(),
        isSelected = rng.nextBoolean(),
        isFocused = rng.nextBoolean(),
        customActionLabels = if (rng.nextBoolean()) emptyList() else listOf("Close ${sampleTexts[rng.nextInt(sampleTexts.size)]}"),
        children = List(childCount) { buildRandomTree(depth + 1) },
      )
    }

    repeat(250) {
      val root = buildRandomTree(0)
      val ov = OverviewDeskInspector.inspect(listOf(root))
      for (desk in ov.desks) {
        for (task in desk.tasks) {
          assertFalse("Literal 'null' placeholder leaked into task label", task.label.equals("null", ignoreCase = true))
          assertTrue(task.label.isNotBlank())
        }
      }
      val facts = TwaAndBrowserInspector.readBrowserFacts(root)
      TwaAndBrowserInspector.findTabNodes(root)
      assertTrue(facts.tabCount >= 0)
    }
  }

  // ------------------------------------------------------------------------- 6. Stateful Engine & Planner Fuzzer

  @Test
  fun fuzz_captureEngineAndRestorePlanner_stateMachineInvariantsHoldAcrossRandomSessions() {
    val packages = listOf(
      KnownPackages.CHROME,
      "com.android.documentsui",
      "com.android.settings",
      "com.google.android.calendar",
      KnownPackages.GEMINI,
      KnownPackages.CALCULATOR,
      ownPkg, // anchor window (must never appear in snapshot)
      "com.google.ambient.streaming", // excluded Connected Phone (must never appear in snapshot)
      "com.google.android.apps.devicelink.debug", // excluded Device Link Debug (must never appear in snapshot)
      "com.android.systemui", // system surface (must never appear in snapshot)
    )
    val labels = listOf("Chrome", "Files", "Settings", "Calendar", "Gemini", "Calculator", "Device Link Debug", "Connected Phone")

    for (seed in 1L..12L) {
      val rng = Random(seed * 0x9E3779B97F4A7C15UL.toLong())
      var now = 10_000_000L
      val engine = WorkspaceCaptureEngine(FakeCatalog(), ownPkg, clock = { now })
      val planner = RestorePlanner(fuzzCaps)
      val activeWindows = mutableListOf<ObservedWindow>()
      var nextWinId = 1

      repeat(220) { step ->
        now += rng.nextLong(10L, 2_500L)
        val wasFrozen = engine.isFrozen
        val preFreezeSnap = if (wasFrozen) engine.snapshot(CaptureReason.LIVE, "bc=1;ep=1") else null

        when (rng.nextInt(10)) {
          // Spawn or update a window
          0, 1, 2 -> {
            val pkg = packages[rng.nextInt(packages.size)]
            val display = if (rng.nextInt(4) == 0) EXTERNAL else PRIMARY
            val w = rng.nextInt(150, display.widthPx + 100)
            val h = rng.nextInt(100, display.heightPx + 100)
            val left = rng.nextInt(-50, (display.widthPx - 200).coerceAtLeast(1))
            val top = rng.nextInt(0, (display.heightPx - 200).coerceAtLeast(1))
            val b = WindowBounds(left, top, left + w, top + h)
            val browser = if (pkg == KnownPackages.CHROME) {
              val tCount = rng.nextInt(1, 5)
              val titles = (1..tCount).map { "Tab $it" }
              BrowserFacts(
                hasBrowserChrome = true,
                urlBarText = "site${rng.nextInt(1, 9)}.example.com/page",
                tabTitles = titles,
                selectedTabTitle = titles[rng.nextInt(titles.size)],
                tabCount = tCount,
              )
            } else null
            val obs = ObservedWindow(
              windowId = nextWinId++,
              displayId = display.displayId,
              rootPackage = pkg,
              title = pkg.substringAfterLast('.'),
              bounds = b,
              layer = rng.nextInt(0, 30),
              isFocused = rng.nextBoolean(),
              activityClassName = if (pkg == KnownPackages.GEMINI) "com.google.android.apps.search.assistant.surfaces.robin.MainActivity" else null,
              browser = browser,
              hasDesktopCaption = true,
            )
            if (activeWindows.size >= 8) activeWindows.removeAt(rng.nextInt(activeWindows.size))
            activeWindows += obs
            engine.observe(listOf(PRIMARY, EXTERNAL), activeWindows.toList(), transientOcclusion = rng.nextInt(8) == 0)
          }
          // Remove some windows (close / vanish / desk switch)
          3 -> {
            if (activeWindows.isNotEmpty()) {
              if (rng.nextBoolean()) activeWindows.clear()
              else activeWindows.removeAt(rng.nextInt(activeWindows.size))
            }
            engine.observe(listOf(PRIMARY, EXTERNAL), activeWindows.toList(), transientOcclusion = rng.nextInt(6) == 0)
          }
          // Caption action
          4 -> {
            val target = activeWindows.firstOrNull()
            if (target != null) {
              val act = if (rng.nextBoolean()) WorkspaceCaptureEngine.CaptionAction.MINIMIZE else WorkspaceCaptureEngine.CaptionAction.CLOSE
              engine.onCaptionAction(target.displayId, target.bounds, act, null)
              activeWindows.remove(target)
              engine.observe(listOf(PRIMARY, EXTERNAL), activeWindows.toList())
            }
          }
          // Taskbar state update
          5 -> {
            val tb = labels.associateWith { rng.nextBoolean() }
            val minSet = labels.filter { rng.nextInt(4) == 0 }.toSet()
            engine.onTaskbarState(tb, minSet)
          }
          // Overview inspection (including companion tasks that must be stripped)
          6 -> {
            val cardCount = rng.nextInt(1, 5)
            val cards = (0 until cardCount).map { idx ->
              val tasks = List(rng.nextInt(0, 4)) {
                OverviewTask(
                  label = labels[rng.nextInt(labels.size)],
                  relativeBounds = NormalizedBounds(0.1f, 0.1f, 0.6f, 0.6f),
                  isLiveTile = rng.nextBoolean(),
                )
              }
              OverviewDeskCard(idx, tasks)
            }
            engine.onOverviewInspected(0, OverviewInspection(true, cards, null))
          }
          // Desk hints & additions
          7 -> {
            if (rng.nextBoolean()) engine.onDeskAdded(0)
            else engine.onDeskSwitchHint(0, rng.nextInt(0, 4), authoritative = rng.nextBoolean())
          }
          // Freeze / unfreeze transitions
          8 -> {
            if (!engine.isFrozen) engine.freeze(CaptureReason.POWER_MENU_PRE_FREEZE)
            else engine.unfreeze()
          }
          // Resize an existing window
          9 -> {
            if (activeWindows.isNotEmpty()) {
              val idx = rng.nextInt(activeWindows.size)
              val old = activeWindows[idx]
              activeWindows[idx] = old.copy(bounds = WindowBounds(100, 100, 900 + step, 700 + step))
              engine.observe(listOf(PRIMARY, EXTERNAL), activeWindows.toList())
            }
          }
        }

        val snap = engine.snapshot(CaptureReason.LIVE, "bc=1;ep=1")

        // Invariant A: If engine was frozen before this step (and not unfrozen in branch 8), windows never changed.
        if (wasFrozen && engine.isFrozen && preFreezeSnap != null) {
          assertEquals(preFreezeSnap.windows, snap.windows)
          assertEquals(preFreezeSnap.totalDeskCountByDisplay, snap.totalDeskCountByDisplay)
        }

        // Invariant B: Own anchor package and excluded companion apps NEVER appear in snapshots.
        for (w in snap.windows) {
          assertFalse("Own anchor leaked into snapshot", w.packageName == ownPkg)
          assertFalse(
            "Excluded companion app leaked into snapshot: ${w.packageName} / ${w.label}",
            KnownPackages.isExcludedCompanionApp(w.packageName, w.label),
          )
          val totalDesks = snap.totalDeskCountByDisplay[w.displayId] ?: 1
          assertTrue("Invalid deskIndex=${w.deskIndex} vs totalDesks=$totalDesks", w.deskIndex in 0 until totalDesks)
        }

        // Invariant C: Active desk indices are always valid 0 until totalDesks.
        for ((dispId, total) in snap.totalDeskCountByDisplay) {
          assertTrue(total >= 1)
          assertTrue(snap.activeDeskIndexByDisplay.getValue(dispId) in 0 until total)
        }

        // Invariant D: RestorePlanner never throws and produces valid desk plans on both dual & single monitor topologies.
        val currentTopology = if (rng.nextBoolean()) listOf(PRIMARY, EXTERNAL) else listOf(PRIMARY)
        val plan = planner.plan(snap, currentTopology)
        for (dispPlan in plan.displays) {
          assertTrue(dispPlan.deskCount >= 1)
          assertTrue(dispPlan.finalActiveDesk in 0 until dispPlan.deskCount)
          for (deskPlan in dispPlan.desks) {
            assertTrue(deskPlan.deskIndex in 0 until dispPlan.deskCount)
            for (launch in deskPlan.launches) {
              assertFalse(KnownPackages.isExcludedCompanionApp(launch.window.packageName, launch.window.label))
            }
          }
        }
      }
    }

    // TabReconciler deduplication property fuzzing.
    val tabRng = Random(0x7AB_F022L)
    repeat(500) {
      val saved = List(tabRng.nextInt(0, 8)) { "https://site${tabRng.nextInt(0, 6)}.example.com/p" }
      val titles = saved.mapIndexed { i, _ -> "T$i" }
      val openTitles = titles.filter { tabRng.nextBoolean() }
      val currentUrl = saved.firstOrNull()?.takeIf { tabRng.nextBoolean() }
      val missing = TabReconciler.missingTabs(saved, titles, openTitles, openTitles.size, currentUrl, tabRng.nextBoolean())
      // Never returns duplicates and never re-opens currentUrl when normalized.
      assertEquals(missing.distinct(), missing)
      if (currentUrl != null) assertFalse(currentUrl in missing)
    }
  }
}
