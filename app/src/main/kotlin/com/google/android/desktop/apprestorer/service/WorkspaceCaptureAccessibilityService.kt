package com.google.android.desktop.apprestorer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.desktop.apprestorer.WorkspaceRuntime
import com.google.android.desktop.apprestorer.boot.BootCountdownOverlay
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.ObservedWindow
import com.google.android.desktop.apprestorer.capture.ResolvedIdentity
import com.google.android.desktop.apprestorer.capture.WorkspaceCaptureEngine
import com.google.android.desktop.apprestorer.inspect.OverviewDeskInspector
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import com.google.android.desktop.apprestorer.inspect.UiNode
import com.google.android.desktop.apprestorer.inspect.findFirst
import com.google.android.desktop.apprestorer.inspect.hasAnyId
import com.google.android.desktop.apprestorer.inspect.hasId
import com.google.android.desktop.apprestorer.inspect.walk
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.platform.DisplayGeometryProvider
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay

/**
 * Inspects every desktop window on every display (bounds, z-order, desk transitions, captions,
 * Chrome tabs, Overview desk cards) and feeds [WorkspaceCaptureEngine]. Also hosts the automation
 * used on restore (Overview desk creation, caption buttons, gestures).
 */
class WorkspaceCaptureAccessibilityService : AccessibilityService() {

  private val main = Handler(Looper.getMainLooper())
  private lateinit var runtime: WorkspaceRuntime
  lateinit var displayProvider: DisplayGeometryProvider
    private set
  private var sentinel: View? = null
  private val windowClassCache = HashMap<Int, Pair<String?, String?>>()
  private val displaysWithDesktopCaptions = HashSet<Int>()
  private var captureScheduled = false
  private var lastPowerMenuClassAt = 0L
  private var lastOverviewVisibleAtMs = 0L
  private var receiverRegistered = false

  private val captureRunnable = Runnable {
    captureScheduled = false
    captureNow()
  }

  private val systemReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        Intent.ACTION_SHUTDOWN, ACTION_QUICKBOOT_POWEROFF, ACTION_REBOOT -> runtime.onShutdown()
        Intent.ACTION_SCREEN_OFF -> runtime.onScreenOff()
        Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
          if (intent.action == Intent.ACTION_USER_PRESENT) {
            WorkspaceMonitorForegroundService.start(this@WorkspaceCaptureAccessibilityService)
          }
          runtime.onUserPresent()
          scheduleCapture(500)
        }
      }
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instanceRef = WeakReference(this)
    runtime = WorkspaceRuntime.get(this)
    displayProvider = DisplayGeometryProvider(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    serviceInfo = serviceInfo.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
    }
    attachSentinel()
    registerSystemReceiver()
    WorkspaceMonitorForegroundService.start(this)
    runtime.onServiceConnected(this)
    scheduleCapture(0)
  }

  override fun onUnbind(intent: Intent?): Boolean {
    teardown()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    teardown()
    super.onDestroy()
  }

  private fun teardown() {
    BootCountdownOverlay.dismiss()
    if (instanceRef?.get() === this) instanceRef = null
    main.removeCallbacks(captureRunnable)
    if (receiverRegistered) {
      runCatching { unregisterReceiver(systemReceiver) }
      receiverRegistered = false
    }
    sentinel?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
    sentinel = null
    if (::runtime.isInitialized) runtime.onServiceDisconnected(this)
  }

  override fun onInterrupt() = Unit

  // ---------------------------------------------------------------------------------------------
  // Keep-alive & lifecycle signals
  // ---------------------------------------------------------------------------------------------

  /**
   * 1×1 transparent, non-touchable TYPE_ACCESSIBILITY_OVERLAY: marks the process as having overlay
   * UI so closing our own task window does not tear the process (and this service) down.
   */
  private fun attachSentinel() {
    if (sentinel != null) return
    val wm = getSystemService(WindowManager::class.java)
    val v = View(this)
    val lp = WindowManager.LayoutParams(
      1, 1,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSPARENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      title = "AppRestorerSentinel"
    }
    try {
      wm.addView(v, lp)
      sentinel = v
    } catch (e: RuntimeException) {
      Log.w(TAG, "Sentinel overlay unavailable", e)
    }
  }

  private fun registerSystemReceiver() {
    if (receiverRegistered) return
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SHUTDOWN)
      addAction(ACTION_QUICKBOOT_POWEROFF)
      addAction(ACTION_REBOOT)
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_USER_PRESENT)
      priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
    }
    registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
    receiverRegistered = true
  }

  // ---------------------------------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------------------------------

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    event ?: return
    val pkg = event.packageName?.toString()
    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        val cls = event.className?.toString()
        if (event.windowId >= 0 && pkg != null) {
          // Ignore transient widget / dialog classes; keep real activity names.
          val prev = windowClassCache[event.windowId]
          val isDialogOrPopup = cls != null && (cls.contains("Dialog") || cls.contains("Popup") || cls.contains("Toast"))
          val isActivity = cls != null && !isDialogOrPopup && (cls.contains("Activity") || cls.contains(".robin."))
          val keepCls = when {
            isActivity -> cls
            isDialogOrPopup -> prev?.second ?: cls
            else -> prev?.second
          }
          windowClassCache[event.windowId] = pkg to keepCls
        }
        if (pkg == KnownPackages.SYSTEM_UI && cls?.contains("GlobalActions") == true) {
          lastPowerMenuClassAt = System.currentTimeMillis()
          runtime.onPowerMenuShown()
        }
        if (!runtime.automationBusy) scheduleCapture(150)
      }
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (!runtime.automationBusy) scheduleCapture(200)
      AccessibilityEvent.TYPE_VIEW_CLICKED -> if (!runtime.automationBusy) handleClick(event, pkg)
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
        if (!runtime.automationBusy && KnownPackages.isChrome(pkg) && !captureScheduled) scheduleCapture(900)
    }
  }

  private fun handleClick(event: AccessibilityEvent, pkg: String?) {
    val src = event.source
    val node = src?.let { AccessibilityUiNode(it) }
    val desc = (event.contentDescription ?: src?.contentDescription)?.toString().orEmpty()
    val text = event.text.joinToString(" ").trim()
    val displayId = src?.window?.displayId ?: 0
    val bounds = node?.boundsInScreen ?: WindowBounds.EMPTY
    when {
      pkg == KnownPackages.SYSTEM_UI -> {
        // WMShell caption: id=minimize_window desc="Minimize <App>", id=close_window desc="Close <App>".
        val action = when {
          node?.hasAnyId("close_window", "close_button") == true || desc.startsWith("Close ", true) -> WorkspaceCaptureEngine.CaptionAction.CLOSE
          node?.hasAnyId("minimize_window", "minimize_button_view", "minimize_button") == true || desc.startsWith("Minimi", true) ->
            WorkspaceCaptureEngine.CaptionAction.MINIMIZE
          else -> null
        }
        val appLabel = desc.substringAfter(' ', "").takeIf { it.isNotBlank() }
        if (action != null) runtime.engine.onCaptionAction(displayId, bounds, action, appLabel)
      }
      pkg != null && runtime.catalog.isHomePackage(pkg) && node != null -> {
        when {
          node.hasId("add_desktop_button") -> runtime.onEngineMaybeChanged(runtime.engine.onDeskAdded(displayId))
          else -> {
            val desks = if (!bounds.isEmpty) OverviewDeskInspector.inspect(homeRoots(displayId)).desks else emptyList()
            val clickedDesk = desks.indexOfFirst { card ->
              val cb = card.node?.boundsInScreen ?: WindowBounds.EMPTY
              !cb.isEmpty && (cb == bounds || cb.contains(bounds.centerX, bounds.centerY))
            }
            if (clickedDesk >= 0) {
              runtime.engine.onDeskSwitchHint(displayId, clickedDesk)
            } else {
              val label = desc.ifEmpty { text }
              if (label.isNotEmpty() && !KnownPackages.isExcludedCompanionLabel(label)) {
                runtime.engine.onTaskbarAppClicked(displayId, label)
              }
            }
          }
        }
      }
    }
    scheduleCapture(250)
  }

  fun scheduleCapture(delayMs: Long) {
    if (captureScheduled) main.removeCallbacks(captureRunnable)
    captureScheduled = true
    main.postDelayed(captureRunnable, delayMs)
  }

  // ---------------------------------------------------------------------------------------------
  // Capture
  // ---------------------------------------------------------------------------------------------

  fun captureNow(allowDuringAutomation: Boolean = false) {
    if (!::runtime.isInitialized) return
    if (!allowDuringAutomation && runtime.automationBusy) return
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (keyguard?.isKeyguardLocked == true) return
    runtime.maybeShowBootPrompt()

    val now = System.currentTimeMillis()
    val geometries = displayProvider.current()
    val all = try {
      windowsOnAllDisplays
    } catch (e: RuntimeException) {
      Log.w(TAG, "windowsOnAllDisplays failed", e)
      return
    }
    val observed = ArrayList<ObservedWindow>()
    val captions = ArrayList<Pair<Int, AccessibilityWindowInfo>>()
    val overviewRoots = HashMap<Int, MutableList<UiNode>>()
    var powerMenu = now - lastPowerMenuClassAt < 1_500
    val taskbarItems = LinkedHashMap<String, Boolean>()
    val taskbarMinimized = LinkedHashSet<String>()

    for (i in 0 until all.size()) {
      val displayId = all.keyAt(i)
      for (w in all.valueAt(i)) {
        val root: AccessibilityNodeInfo? = try { w.root } catch (_: RuntimeException) { null }
        val pkg = root?.packageName?.toString() ?: windowClassCache[w.id]?.first
        if (pkg != null && root != null) {
          val prev = windowClassCache[w.id]
          windowClassCache[w.id] = pkg to prev?.second
        }
        when {
          pkg == KnownPackages.SYSTEM_UI -> {
            if (root != null && isPowerMenu(AccessibilityUiNode(root))) powerMenu = true
            else captions += displayId to w
          }
          pkg != null && runtime.catalog.isHomePackage(pkg) -> {
            if (root != null) {
              val node = AccessibilityUiNode(root)
              val wTitle = w.title?.toString()
              if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM || wTitle == "Taskbar") {
                val tb = parseTaskbar(node)
                taskbarItems.putAll(tb.first)
                taskbarMinimized.addAll(tb.second)
              } else if (wTitle.equals("Recent apps", ignoreCase = true) || OverviewDeskInspector.isOverview(node)) {
                overviewRoots.getOrPut(displayId) { mutableListOf() } += node
              }
            }
          }
          w.type == AccessibilityWindowInfo.TYPE_APPLICATION -> observed += buildObserved(w, displayId, root, pkg)
        }
      }
    }

    if (powerMenu) {
      runtime.onPowerMenuShown()
      return
    }
    runtime.onPowerMenuGone()

    var changed = runtime.engine.onTaskbarState(taskbarItems, taskbarMinimized)
    val overviewActive = overviewRoots.isNotEmpty()
    if (overviewActive) {
      lastOverviewVisibleAtMs = now
      for ((displayId, roots) in overviewRoots) {
        changed = runtime.engine.onOverviewInspected(displayId, OverviewDeskInspector.inspect(roots)) || changed
      }
    }

    // While Overview is open or animating closed, Launcher3 live-tile surfaces report TYPE_APPLICATION
    // windows scaled down to ~480x301 thumbnail coordinates; never feed them into engine.observe().
    val inOverviewTransition = overviewActive || (now - lastOverviewVisibleAtMs < OVERVIEW_EXIT_SETTLE_MS)
    if (!inOverviewTransition) {
      val withCaptions = attachCaptionLabels(observed, captions)
      val displayMap = geometries.associateBy { it.displayId }
      var companionSurfaceVisible = false
      val topLevelWindows = withCaptions.filter { o ->
        val isOwn = o.rootPackage == packageName
        val appLabel = o.rootPackage?.takeIf { !KnownPackages.isChrome(it) }?.let { runtime.catalog.labelFor(it) }
        if (!isOwn && KnownPackages.isExcludedCompanionApp(o.rootPackage, appLabel, o.captionAppLabel, o.title)) {
          companionSurfaceVisible = true
          return@filter false
        }
        val isDialog = o.activityClassName?.let { it.contains("Dialog") || it.contains("Popup") } == true
        val isFullscreen = displayMap[o.displayId]?.bounds == o.bounds
        !isDialog && (isOwn || o.hasDesktopCaption || isFullscreen || o.displayId !in displaysWithDesktopCaptions)
      }
      // If Connected Phone / Device Link Debug popped up (or switched to its own dedicated desk),
      // treat missing desktop windows as transiently occluded rather than desk-switched or closed.
      changed = runtime.engine.observe(geometries, topLevelWindows, transientOcclusion = companionSurfaceVisible) || changed
    } else if (!runtime.automationBusy) {
      scheduleCapture(OVERVIEW_EXIT_SETTLE_MS + 100)
    }

    runtime.onEngineMaybeChanged(changed)
    if (!runtime.automationBusy && runtime.engine.hasPendingVanish) {
      scheduleCapture(WorkspaceCaptureEngine.VANISH_SETTLE_MS + 200)
    }
    // Keep classes of hidden windows (inactive desks) but bound the cache.
    if (windowClassCache.size > 256) {
      val present = (0 until all.size()).flatMap { all.valueAt(it).map { w -> w.id } }.toSet()
      windowClassCache.keys.retainAll(present)
    }
  }

  private fun buildObserved(w: AccessibilityWindowInfo, displayId: Int, root: AccessibilityNodeInfo?, pkg: String?): ObservedWindow {
    val r = Rect()
    w.getBoundsInScreen(r)
    val node = root?.let { AccessibilityUiNode(it) }
    return ObservedWindow(
      windowId = w.id,
      displayId = displayId,
      rootPackage = pkg,
      title = w.title?.toString(),
      bounds = WindowBounds(r.left, r.top, r.right, r.bottom),
      layer = w.layer,
      isFocused = w.isFocused,
      activityClassName = windowClassCache[w.id]?.second,
      browser = if (KnownPackages.isChrome(pkg) && node != null) TwaAndBrowserInspector.readBrowserFacts(node) else null,
      isSearchOverlay = pkg == KnownPackages.GOOGLE_QUICK_SEARCH_BOX &&
        node?.findFirst(300) { it.hasAnyId("search_plate", "search_box", "search_edit_frame") } != null,
    )
  }

  /**
   * Reads WMShell desktop_mode_caption windows and attaches each one strictly to the desktop window
   * sharing its top/left/right edges (preventing a floating window's caption from matching a
   * maximized window underneath).
   */
  private fun attachCaptionLabels(
    observed: List<ObservedWindow>,
    captions: List<Pair<Int, AccessibilityWindowInfo>>,
  ): List<ObservedWindow> {
    if (captions.isEmpty()) return observed.map { it.copy(hasDesktopCaption = false) }
    val density = resources.displayMetrics.density
    val maxCaptionPx = (density * 96).toInt()
    val edgeTol = (density * 12).toInt().coerceAtLeast(10)
    data class Caption(val displayId: Int, val bounds: WindowBounds, val label: String?)
    val parsed = captions.mapNotNull { (displayId, w) ->
      val r = Rect().also { w.getBoundsInScreen(it) }
      if (r.width() <= 0 || r.height() <= 0 || r.height() > maxCaptionPx) return@mapNotNull null
      val root = try { w.root } catch (_: RuntimeException) { null } ?: return@mapNotNull null
      val node = AccessibilityUiNode(root)
      val isDesktopCaption = node.hasId("desktop_mode_caption") ||
        node.findFirst(120) { it.hasAnyId("desktop_mode_caption", "close_window", "minimize_window", "caption_handle") } != null
      if (!isDesktopCaption) return@mapNotNull null
      displaysWithDesktopCaptions += displayId
      val closeLabel = node.findFirst(120) { n ->
        n.contentDescription?.startsWith("Close ", true) == true || n.customActionLabels.any { it.startsWith("Close ", true) }
      }?.let { n ->
        (n.contentDescription?.takeIf { it.startsWith("Close ", true) } ?: n.customActionLabels.first { it.startsWith("Close ", true) })
          .substring(6).trim()
      }
      val appName = closeLabel ?: node.findFirst(120) { it.hasAnyId("application_name", "app_name_text") }?.text
      Caption(displayId, WindowBounds(r.left, r.top, r.right, r.bottom), appName?.trim()?.takeIf { it.isNotEmpty() })
    }
    return observed.map { o ->
      val c = parsed.firstOrNull { c ->
        c.displayId == o.displayId &&
          kotlin.math.abs(c.bounds.left - o.bounds.left) <= edgeTol &&
          kotlin.math.abs(c.bounds.right - o.bounds.right) <= edgeTol &&
          kotlin.math.abs(c.bounds.top - o.bounds.top) <= edgeTol
      }
      o.copy(captionAppLabel = c?.label ?: o.captionAppLabel, hasDesktopCaption = c != null)
    }
  }

  /**
   * Taskbar icons: text="Files" desc="Files Active" / "Files Minimized" → running; pinned-only apps
   * have desc == title. Returns (runningByLabel, minimizedLabels).
   */
  private fun parseTaskbar(root: UiNode): Pair<Map<String, Boolean>, Set<String>> {
    val runningMap = LinkedHashMap<String, Boolean>()
    val minimizedSet = LinkedHashSet<String>()
    val bar = root.findFirst(300) { it.hasId("taskbar_view") } ?: return runningMap to minimizedSet
    bar.walk(200) { n, depth ->
      val title = n.text?.trim()
      val d = n.contentDescription?.trim()
      if (depth > 0 && !title.isNullOrEmpty() && d != null && d.startsWith(title)) {
        val suffix = d.substring(title.length).trim()
        val running = suffix.isNotEmpty()
        runningMap[title] = (runningMap[title] ?: false) || running
        if (suffix.equals("Minimized", ignoreCase = true)) minimizedSet += title
      }
      true
    }
    return runningMap to minimizedSet
  }

  private fun isPowerMenu(root: UiNode): Boolean =
    root.findFirst(250) { n ->
      n.hasAnyId("global_actions_view", "global_actions_grid", "global_actions_container", "global_actions_grid_root") ||
        n.className?.contains("GlobalActions") == true
    } != null

  // ---------------------------------------------------------------------------------------------
  // Helpers for restore automation
  // ---------------------------------------------------------------------------------------------

  fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
    try {
      windowsOnAllDisplays.get(displayId).orEmpty()
    } catch (_: RuntimeException) {
      emptyList()
    }

  fun homeRoots(displayId: Int): List<UiNode> =
    windowsOn(displayId).mapNotNull { w ->
      val root = try { w.root } catch (_: RuntimeException) { null } ?: return@mapNotNull null
      val pkg = root.packageName?.toString() ?: return@mapNotNull null
      if (runtime.catalog.isHomePackage(pkg)) AccessibilityUiNode(root) else null
    }

  fun systemUiRoots(displayId: Int): List<UiNode> =
    windowsOn(displayId).mapNotNull { w ->
      val root = try { w.root } catch (_: RuntimeException) { null } ?: return@mapNotNull null
      if (root.packageName?.toString() == KnownPackages.SYSTEM_UI) AccessibilityUiNode(root) else null
    }

  /** Application windows on [displayId] with their resolved identity (TWA/Gemini aware). */
  fun resolvedAppWindows(displayId: Int): List<Pair<ObservedWindow, ResolvedIdentity>> {
    val all = windowsOn(displayId)
    val captions = all.mapNotNull { w ->
      val root = try { w.root } catch (_: RuntimeException) { null }
      if (root?.packageName?.toString() == KnownPackages.SYSTEM_UI) displayId to w else null
    }
    val raw = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { w ->
      val root = try { w.root } catch (_: RuntimeException) { null }
      buildObserved(w, displayId, root, root?.packageName?.toString() ?: windowClassCache[w.id]?.first)
    }
    val observed = attachCaptionLabels(raw, captions).filter { o ->
      o.activityClassName?.let { it.contains("Dialog") || it.contains("Popup") } != true
    }
    return observed.mapNotNull { o -> runtime.engine.inspector.resolve(o)?.let { o to it } }
  }

  fun rootOfWindow(displayId: Int, windowId: Int): UiNode? =
    windowsOn(displayId).firstOrNull { it.id == windowId }?.let { w ->
      try { w.root } catch (_: RuntimeException) { null }
    }?.let { AccessibilityUiNode(it) }

  fun cachedClassName(windowId: Int): String? = windowClassCache[windowId]?.second

  private fun isOverviewCurrentlyOpen(): Boolean {
    val all = try { windowsOnAllDisplays } catch (_: RuntimeException) { return false }
    for (i in 0 until all.size()) {
      for (w in all.valueAt(i)) {
        val root = try { w.root } catch (_: RuntimeException) { null } ?: continue
        val pkg = root.packageName?.toString() ?: continue
        if (!runtime.catalog.isHomePackage(pkg)) continue
        if (w.title?.toString().equals("Recent apps", ignoreCase = true) ||
          OverviewDeskInspector.isOverview(AccessibilityUiNode(root))) return true
      }
    }
    return false
  }

  /**
   * Chrome only shows the selected tab's URL. Briefly selects each tab whose URL was never seen
   * (tab-strip click), reads the omnibox, then re-selects the originally active tab — updating only
   * Chrome's tab state so window geometry and desk membership are never disturbed.
   */
  suspend fun harvestChromeTabUrls(): Int {
    var learned = 0
    val displays = try { windowsOnAllDisplays.let { a -> (0 until a.size()).map { a.keyAt(it) } } } catch (_: RuntimeException) { return 0 }
    for (displayId in displays) {
      for ((o, id) in resolvedAppWindows(displayId)) {
        if (id.kind != com.google.android.desktop.apprestorer.model.AppKind.CHROME_BROWSER) continue
        val root = rootOfWindow(displayId, o.windowId) ?: continue
        val tabs = TwaAndBrowserInspector.findTabNodes(root)
        if (tabs.size < 2) continue
        val unknown = runtime.engine.unknownTabTitles(o.windowId, tabs.map { it.first })
        if (unknown.isEmpty()) continue
        val original = tabs.firstOrNull { it.second.isSelected }?.first
        DiagnosticsLog.add("tab-harvest window=${o.windowId} tabs=${tabs.size} unknown=${unknown.size}")
        for (title in unknown) {
          val node = rootOfWindow(displayId, o.windowId)?.let { TwaAndBrowserInspector.findTabNodes(it) }
            ?.firstOrNull { it.first == title }?.second ?: continue
          if (!node.click()) continue
          delay(650)
          val facts = rootOfWindow(displayId, o.windowId)?.let { TwaAndBrowserInspector.readBrowserFacts(it) }
          if (facts != null) runtime.onEngineMaybeChanged(runtime.engine.onBrowserFactsObserved(o.windowId, facts))
          learned++
        }
        if (original != null) {
          rootOfWindow(displayId, o.windowId)?.let { TwaAndBrowserInspector.findTabNodes(it) }
            ?.firstOrNull { it.first == original }?.second?.click()
          delay(400)
          val facts = rootOfWindow(displayId, o.windowId)?.let { TwaAndBrowserInspector.readBrowserFacts(it) }
          if (facts != null) runtime.onEngineMaybeChanged(runtime.engine.onBrowserFactsObserved(o.windowId, facts))
        }
      }
    }
    return learned
  }

  /** MANUAL_CAPTURE: capture desktop bounds first, harvest tabs + Overview desk cards cleanly, persist. */
  suspend fun performManualCapture(deepScan: Boolean) {
    runtime.automationBusy = true
    try {
      captureNow(allowDuringAutomation = true)
      if (deepScan) {
        harvestChromeTabUrls()
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        delay(1_150)
        // Overview is open: captureNow updates Overview desk cards & taskbar without touching window bounds.
        captureNow(allowDuringAutomation = true)
        performGlobalAction(GLOBAL_ACTION_BACK)
        val deadline = System.currentTimeMillis() + 2_200L
        while (System.currentTimeMillis() < deadline && isOverviewCurrentlyOpen()) {
          delay(120)
        }
        delay(OVERVIEW_EXIT_SETTLE_MS)
      }
      runtime.onEngineMaybeChanged(runtime.engine.reconcileWithUsageStats())
      runtime.persistNow(CaptureReason.MANUAL_CAPTURE)
    } finally {
      runtime.automationBusy = false
    }
  }

  companion object {
    private const val TAG = "WorkspaceCaptureA11y"
    private const val OVERVIEW_EXIT_SETTLE_MS = 1_300L
    const val ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF"
    const val ACTION_REBOOT = "android.intent.action.REBOOT"

    @Volatile
    private var instanceRef: WeakReference<WorkspaceCaptureAccessibilityService>? = null
    val instance: WorkspaceCaptureAccessibilityService? get() = instanceRef?.get()
  }
}
