package com.google.android.desktop.apprestorer.capture

import com.google.android.desktop.apprestorer.inspect.ChromeWindowTabTracker
import com.google.android.desktop.apprestorer.inspect.OverviewInspection
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import kotlin.math.abs

/**
 * Platform-independent workspace state machine.
 *
 * Fed with periodic [observe] calls (from AccessibilityService window lists) plus discrete hints
 * (caption clicks, taskbar clicks, Overview inspections, desk switches), it keeps a per-display
 * model of every genuinely open window — including windows minimized to the taskbar and windows
 * alive on inactive Virtual Desks — and emits Schema v6 [WorkspaceSnapshot]s.
 *
 * Threading: all methods must be called from a single thread (the main thread).
 */
class WorkspaceCaptureEngine(
  private val catalog: AppCatalog,
  private val ownPackageName: String,
  private val reconciler: UsageStatsWorkspaceReconciler = UsageStatsWorkspaceReconciler(null),
  private val clock: () -> Long = System::currentTimeMillis,
  /** Decision log (desk switches, purges) surfaced in diagnostics exports. */
  var logger: (String) -> Unit = {},
) {
  enum class CaptionAction { CLOSE, MINIMIZE }

  val inspector = TwaAndBrowserInspector(catalog, ownPackageName)
  private val tabTracker = ChromeWindowTabTracker()

  private class Tracked(
    val key: String,
    var windowId: Int,
    var identity: ResolvedIdentity,
    var displayId: Int,
    var deskIndex: Int,
    var bounds: WindowBounds,
    var rawLayer: Int,
    var onScreen: Boolean,
    var minimized: Boolean,
    var lastSeenMs: Long,
    var activityClassName: String?,
    val order: Long,
    var focused: Boolean = false,
    var pendingVanishSinceMs: Long? = null,
    var tabs: ChromeWindowTabTracker.TabState? = null,
    /** When the window last went from hidden to visible. */
    var appearedAtMs: Long = 0L,
    /** Desk a hidden (non-minimized) window lived on before re-appearing. */
    var deskBeforeAppear: Int? = null,
    /** Bounds before the latest change (to undo desk-slide animation frames). */
    var prevBounds: WindowBounds? = null,
    var boundsChangedAtMs: Long = 0L,
    var boundsKnown: Boolean = true,
  )

  private val tracked = LinkedHashMap<String, Tracked>()
  private val displays = LinkedHashMap<Int, DisplayGeometry>()
  private val activeDeskByDisplay = HashMap<Int, Int>()
  private val knownTotalDesksByDisplay = HashMap<Int, Int>()
  private val overviewAuthoritativeDesks = HashSet<Int>()
  private val pendingCaption = HashMap<String, Pair<CaptionAction, Long>>()
  private val deskHints = HashMap<Int, Pair<Int, Long>>()
  private var orderCounter = 0L
  private var taskbarItems: Map<String, Boolean> = emptyMap()
  private var taskbarMinimized: Set<String> = emptySet()
  private var taskbarSeenAtMs = 0L
  private var lastSignature = 0

  var frozenReason: CaptureReason? = null
    private set
  val isFrozen: Boolean get() = frozenReason != null

  /** True while some vanished window still awaits close-vs-minimize classification. */
  val hasPendingVanish: Boolean get() = tracked.values.any { it.pendingVanishSinceMs != null }

  fun activeDesk(displayId: Int): Int = activeDeskByDisplay[displayId] ?: 0

  fun totalDesks(displayId: Int): Int = maxOf(
    knownTotalDesksByDisplay[displayId] ?: 1,
    (tracked.values.filter { it.displayId == displayId }.maxOfOrNull { it.deskIndex } ?: 0) + 1,
    activeDesk(displayId) + 1,
  )

  // ---------------------------------------------------------------------------------------------
  // Observation
  // ---------------------------------------------------------------------------------------------

  /**
   * Reconciles the current on-screen application windows.
   *
   * @param transientOcclusion Overview / keyguard / power menu is covering the desk: windows that
   *   are missing from [observed] are kept as-is rather than treated as closed or desk-switched.
   * @return true if the snapshot-relevant state changed.
   */
  fun observe(
    displayList: List<DisplayGeometry>,
    observed: List<ObservedWindow>,
    transientOcclusion: Boolean = false,
  ): Boolean {
    if (isFrozen) return false
    val now = clock()
    displayList.forEach { displays[it.displayId] = it }
    expireHints(now)

    val resolved = coalesceActivityEmbedding(
      observed.mapNotNull { o ->
        val id = inspector.resolve(o) ?: return@mapNotNull null
        if (!id.isAnchor && !WindowGeometryClassifier.isPlausibleWindow(o.bounds, id.isBrowser)) return@mapNotNull null
        o to id
      }
    )

    val wasOnScreen = tracked.values.filter { it.onScreen }.map { it.key }.toSet()
    val wasMinimized = tracked.values.filter { it.minimized }.map { it.key }.toSet()
    val matched = LinkedHashMap<String, Pair<ObservedWindow, ResolvedIdentity>>()
    val byWindowId = tracked.values.filter { it.windowId >= 0 }.associateBy { it.windowId }
    val unmatched = mutableListOf<Pair<ObservedWindow, ResolvedIdentity>>()
    val created = HashSet<String>()
    for (pair in resolved) {
      val t = byWindowId[pair.first.windowId]
      if (t != null && t.key !in matched && t.identity.packageName == pair.second.packageName) matched[t.key] = pair
      else unmatched += pair
    }
    for ((o, id) in unmatched) {
      val t = findRevivalCandidate(o, id, matched.keys, wasOnScreen)
        ?: newTracked(o, id).also { created += it.key }
      matched[t.key] = o to id
    }

    // --- vanished windows: close / minimize / occlusion / desk switch ------------------------
    val vanished = tracked.values.filter { it.onScreen && it.key !in matched }
    val decidedDeskSwitch = HashSet<Int>()
    if (!transientOcclusion) {
      for ((displayId, gone) in vanished.groupBy { it.displayId }) {
        val rest = mutableListOf<Tracked>()
        for (v in gone) {
          when (pendingCaption.remove(v.key)?.first) {
            CaptionAction.CLOSE -> remove(v)
            CaptionAction.MINIMIZE -> { v.onScreen = false; v.minimized = true; v.pendingVanishSinceMs = null }
            null -> rest += v
          }
        }
        if (rest.isEmpty()) continue
        val current = activeDesk(displayId)
        val stillVisible = matched.keys.count { it in wasOnScreen && tracked[it]?.displayId == displayId }
        val appeared = matched.filter { (k, p) -> k !in wasOnScreen && p.first.displayId == displayId }.keys
          .mapNotNull { tracked[it] }
        val frontOnDisplay = matched.values.map { it.first }.filter { it.displayId == displayId }
        val unminimizedSameDeskCover = stillVisible == 0 && appeared.isNotEmpty() &&
          appeared.all { it.key in wasMinimized && it.deskIndex == current } &&
          rest.all { v -> frontOnDisplay.any { f -> covers(f.bounds, v.bounds) } }
        val hint = deskHints[displayId]
        val singleAuthoritativeDesk = displayId in overviewAuthoritativeDesks &&
          (knownTotalDesksByDisplay[displayId] ?: 1) <= 1 && hint == null
        val singleWindowSwipedToOtherDesk = rest.size == 1 && totalDesks(displayId) >= 2 &&
          frontOnDisplay.isEmpty() && taskbarSays(rest[0]) == true && !taskbarIsMinimized(rest[0])
        val isDeskSwitch = !unminimizedSameDeskCover && !singleAuthoritativeDesk &&
          stillVisible == 0 && (rest.size >= 2 || appeared.isNotEmpty() || hint != null || singleWindowSwipedToOtherDesk)
        if (isDeskSwitch) {
          val newDesk = hint?.first
            ?: appeared.firstOrNull { it.key !in created && it.key !in wasMinimized && it.deskIndex != current }?.deskIndex
            ?: firstEmptyDesk(displayId, exclude = current)
          if (newDesk != current) {
            rest.forEach { it.onScreen = false; it.pendingVanishSinceMs = null; revertAnimatedBounds(it, now) } // alive on inactive desk
            logger("desk-switch(immediate) display=$displayId ${current + 1}->${newDesk + 1} hidden=${rest.map { it.identity.label }}")
            setActiveDesk(displayId, newDesk)
            deskHints.remove(displayId)
            decidedDeskSwitch += displayId
          } else {
            markOccludedOrVanished(rest, frontOnDisplay, now)
          }
        } else {
          markOccludedOrVanished(rest, frontOnDisplay, now)
        }
      }
    }

    // --- desk switch with nothing vanishing (e.g. from an empty desk) --------------------------
    for ((displayId, keys) in matched.keys.filter { it !in wasOnScreen }.groupBy { matched[it]!!.first.displayId }) {
      if (displayId in decidedDeskSwitch || transientOcclusion) continue
      val anyStillVisible = matched.keys.any { it in wasOnScreen && tracked[it]?.displayId == displayId }
      if (anyStillVisible) continue
      val revivedDesk = keys.mapNotNull { tracked[it] }
        .firstOrNull { it.key !in created && !it.minimized && it.deskIndex != activeDesk(displayId) && it.displayId == displayId }
        ?.deskIndex
      val target = revivedDesk ?: deskHints[displayId]?.first
      if (target != null) {
        setActiveDesk(displayId, target)
        deskHints.remove(displayId)
      }
    }

    // --- apply matched updates ---------------------------------------------------------------
    for ((key, pair) in matched) {
      val (o, id) = pair
      val t = tracked[key] ?: continue
      if (t.windowId != o.windowId) {
        if (t.windowId >= 0) tabTracker.forget(t.windowId)
        t.tabs?.let { tabTracker.seed(o.windowId, it.urls, it.titles) }
        t.windowId = o.windowId
      }
      if (key !in wasOnScreen) {
        t.appearedAtMs = now
        t.deskBeforeAppear = if (key in created || t.minimized || t.deskIndex < 0) null else t.deskIndex
      }
      if (t.bounds != o.bounds && key !in created) {
        t.prevBounds = t.bounds
        t.boundsChangedAtMs = now
      }
      t.identity = id
      t.displayId = o.displayId
      t.deskIndex = activeDesk(o.displayId)
      t.bounds = o.bounds
      t.boundsKnown = true
      t.rawLayer = o.layer
      t.onScreen = true
      t.minimized = false
      t.pendingVanishSinceMs = null
      t.focused = o.isFocused
      t.lastSeenMs = now
      t.activityClassName = o.activityClassName ?: t.activityClassName
      if (id.kind == AppKind.CHROME_BROWSER && o.browser != null) {
        tabTracker.update(o.windowId, o.browser)
        tabTracker.tabsFor(o.windowId)?.let { t.tabs = it }
      }
      bumpTotal(o.displayId, t.deskIndex + 1)
    }

    if (!transientOcclusion) resolveOverlappingDeskSwaps(now)
    resolvePendingVanishes(now)
    return commitIfChanged()
  }

  /**
   * Desk-switch slide animations can briefly show windows of both desks, so the swap arrives as
   * "new desk's windows appear" and, a frame later, "old desk's windows vanish". If every window
   * now visible on a display appeared around the time the others vanished, it is a desk switch,
   * not a series of closes.
   */
  private fun resolveOverlappingDeskSwaps(now: Long) {
    val pendingByDisplay = tracked.values.filter { it.pendingVanishSinceMs != null }.groupBy { it.displayId }
    for ((displayId, gone) in pendingByDisplay) {
      val hint = deskHints[displayId]
      if (displayId in overviewAuthoritativeDesks && (knownTotalDesksByDisplay[displayId] ?: 1) <= 1 && hint == null) continue
      val earliest = gone.minOf { it.pendingVanishSinceMs!! }
      val visible = tracked.values.filter { it.displayId == displayId && it.onScreen }
      if (visible.isEmpty()) continue
      if (!visible.all { it.appearedAtMs >= earliest - SWAP_WINDOW_MS }) continue
      // The new desk's windows must have shown up after the old desk's (not side by side at start).
      val oldestGoneAppearance = gone.maxOf { it.appearedAtMs }
      if (!visible.all { it.appearedAtMs > oldestGoneAppearance }) continue
      val oldDesk = gone.first().deskIndex
      val revived = visible.firstOrNull { it.deskBeforeAppear != null && it.deskBeforeAppear != oldDesk }
      val anchorInvolved = gone.any { it.identity.isAnchor }
      if (!(gone.size >= 2 || revived != null || hint != null || anchorInvolved)) continue
      val newDesk = hint?.first ?: revived?.deskBeforeAppear ?: firstEmptyDesk(displayId, exclude = oldDesk)
      if (newDesk == oldDesk) continue
      gone.forEach { it.pendingVanishSinceMs = null; it.onScreen = false; it.deskIndex = oldDesk }
      visible.forEach { it.deskIndex = newDesk; it.deskBeforeAppear = null }
      logger("desk-switch(overlap) display=$displayId ${oldDesk + 1}->${newDesk + 1} hidden=${gone.map { it.identity.label }} shown=${visible.map { it.identity.label }}")
      setActiveDesk(displayId, newDesk)
      deskHints.remove(displayId)
    }
  }

  private fun covers(front: WindowBounds, behind: WindowBounds): Boolean =
    !front.isEmpty && !behind.isEmpty &&
      front.left <= behind.left + 8 && front.top <= behind.top + 8 &&
      front.right >= behind.right - 8 && front.bottom >= behind.bottom - 8

  private fun markOccludedOrVanished(rest: List<Tracked>, frontOnDisplay: List<ObservedWindow>, now: Long) {
    for (v in rest) {
      v.onScreen = false
      revertAnimatedBounds(v, now)
      val covering = frontOnDisplay.filter { it.windowId != v.windowId && covers(it.bounds, v.bounds) }
      val onlyWindowOfApp = tracked.values.count { it.identity.packageName == v.identity.packageName } <= 1
      if (covering.isNotEmpty() && taskbarSays(v) != false && !(onlyWindowOfApp && taskbarIsMinimized(v))) {
        v.minimized = false
        v.pendingVanishSinceMs = null
        val minFrontLayer = covering.minOf { it.layer }
        if (v.rawLayer >= minFrontLayer) v.rawLayer = minFrontLayer - 1
      } else {
        v.pendingVanishSinceMs = now
      }
    }
  }

  /** A window that vanished mid-slide keeps its last settled bounds, not an animation frame. */
  private fun revertAnimatedBounds(t: Tracked, now: Long) {
    val prev = t.prevBounds ?: return
    if (now - t.boundsChangedAtMs <= STABLE_BOUNDS_MS) t.bounds = prev
  }

  private fun newTracked(o: ObservedWindow, id: ResolvedIdentity): Tracked {
    val t = Tracked(
      key = "w${o.windowId}-${orderCounter}",
      windowId = o.windowId,
      identity = id,
      displayId = o.displayId,
      deskIndex = activeDesk(o.displayId),
      bounds = o.bounds,
      rawLayer = o.layer,
      onScreen = false, // becomes true when matched updates are applied
      minimized = false,
      lastSeenMs = 0L,
      activityClassName = o.activityClassName,
      order = orderCounter++,
    )
    tracked[t.key] = t
    return t
  }

  /**
   * Re-associates a window whose windowId changed (activity recreation), that was restored from
   * the taskbar, or that was harvested from Overview without a windowId.
   */
  private fun findRevivalCandidate(
    o: ObservedWindow,
    id: ResolvedIdentity,
    alreadyMatched: Set<String>,
    wasOnScreen: Set<String>,
  ): Tracked? {
    val now = clock()
    val sameApp = tracked.values.filter {
      it.key !in alreadyMatched && it.identity.packageName == id.packageName &&
        (it.displayId == o.displayId || displays[it.displayId] == null)
    }
    fun closest(c: List<Tracked>) = c.minByOrNull { distance(it.bounds, o.bounds) }
    // 1. Vanished in this very round at roughly the same place: activity recreation.
    closest(sameApp.filter { it.key in wasOnScreen && it.windowId != o.windowId && distance(it.bounds, o.bounds) < 200 })?.let { return it }
    // 2. Minimized / pending-vanish windows coming back.
    closest(sameApp.filter { !it.onScreen && (it.minimized || (it.pendingVanishSinceMs?.let { s -> now - s < 15_000 } == true)) })?.let { return it }
    // 3. Harvested from Overview (no windowId yet).
    closest(sameApp.filter { !it.onScreen && it.windowId < 0 })?.let { return it }
    return null
  }

  private fun distance(a: WindowBounds, b: WindowBounds) = abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)

  private fun resolvePendingVanishes(now: Long) {
    for (t in tracked.values.toList()) {
      val since = t.pendingVanishSinceMs ?: continue
      if (now - since < VANISH_SETTLE_MS) continue
      val usage = reconciler.classifyVanished(t.identity.packageName, since, now)
      val running = taskbarSays(t)
      val otherVisibleWindows = tracked.values.any { it !== t && it.onScreen && it.identity.packageName == t.identity.packageName }
      val coveredOnSameDesk = tracked.values.any {
        it !== t && it.onScreen && it.displayId == t.displayId && it.deskIndex == t.deskIndex && covers(it.bounds, t.bounds)
      }
      if (coveredOnSameDesk && running != false && !taskbarIsMinimized(t) && usage != UsageStatsWorkspaceReconciler.VanishVerdict.CLOSED) {
        t.minimized = false
        t.pendingVanishSinceMs = null
        logger("vanish ${t.identity.label} display=${t.displayId} desk=${t.deskIndex + 1} -> OCCLUDED")
        continue
      }
      val verdict = when {
        running == false -> UsageStatsWorkspaceReconciler.VanishVerdict.CLOSED
        taskbarIsMinimized(t) && !otherVisibleWindows -> UsageStatsWorkspaceReconciler.VanishVerdict.MINIMIZED
        running == true && !otherVisibleWindows -> UsageStatsWorkspaceReconciler.VanishVerdict.MINIMIZED
        else -> usage
      }
      logger("vanish ${t.identity.label} display=${t.displayId} desk=${t.deskIndex + 1} -> $verdict")
      when (verdict) {
        UsageStatsWorkspaceReconciler.VanishVerdict.MINIMIZED -> { t.minimized = true; t.pendingVanishSinceMs = null }
        // Spec: closed windows are purged immediately unless explicitly minimized.
        else -> remove(t)
      }
    }
  }

  /** Merges ActivityEmbedding split panes (e.g. two-pane Settings) into one desktop window. */
  private fun coalesceActivityEmbedding(
    input: List<Pair<ObservedWindow, ResolvedIdentity>>,
  ): List<Pair<ObservedWindow, ResolvedIdentity>> {
    val out = mutableListOf<Pair<ObservedWindow, ResolvedIdentity>>()
    for ((_, group) in input.groupBy { it.first.displayId to it.second.packageName }) {
      val sorted = group.sortedBy { it.first.bounds.left }.toMutableList()
      var i = 0
      while (i < sorted.size) {
        var cur = sorted[i]
        var j = i + 1
        while (j < sorted.size) {
          val a = cur.first.bounds
          val b = sorted[j].first.bounds
          val adjacent = abs(a.top - b.top) <= 8 && abs(a.bottom - b.bottom) <= 8 && abs(a.right - b.left) <= 8
          if (adjacent && cur.second.kind != AppKind.CHROME_BROWSER) {
            val keepId = minOf(cur.first.windowId, sorted[j].first.windowId)
            val merged = cur.first.copy(
              windowId = keepId,
              bounds = a.union(b),
              layer = maxOf(cur.first.layer, sorted[j].first.layer),
              isFocused = cur.first.isFocused || sorted[j].first.isFocused,
            )
            cur = merged to cur.second
            sorted.removeAt(j)
          } else j++
        }
        out += cur
        i++
      }
    }
    return out
  }

  // ---------------------------------------------------------------------------------------------
  // Hints
  // ---------------------------------------------------------------------------------------------

  /** A WMShell caption Close / Minimize button was clicked inside [captionBounds]. */
  fun onCaptionAction(displayId: Int, captionBounds: WindowBounds, action: CaptionAction, appLabel: String? = null): Boolean {
    val byLabel = appLabel?.let { l ->
      tracked.values.filter { it.onScreen && it.displayId == displayId && it.identity.label.equals(l.trim(), ignoreCase = true) }
        .let { c -> c.firstOrNull { captionBounds.isEmpty.not() && it.bounds.contains(captionBounds.centerX, captionBounds.centerY) } ?: c.maxByOrNull { it.rawLayer } }
    }
    val target = byLabel ?: tracked.values
      .filter { it.onScreen && it.displayId == displayId }
      .filter { it.bounds.contains(captionBounds.centerX, captionBounds.centerY) || abs(it.bounds.top - captionBounds.bottom) < 80 && it.bounds.intersects(captionBounds.copy(bottom = captionBounds.bottom + 80)) }
      .maxByOrNull { it.rawLayer } ?: return false
    pendingCaption[target.key] = action to clock()
    logger("caption $action -> ${target.identity.label}")
    return true
  }

  /** Clicking the focused app's taskbar icon minimizes it on Android Desktop. */
  fun onTaskbarAppClicked(displayId: Int, label: String) {
    val t = tracked.values.firstOrNull {
      it.onScreen && it.focused && it.displayId == displayId && it.identity.label.equals(label.trim(), ignoreCase = true)
    } ?: return
    pendingCaption[t.key] = CaptionAction.MINIMIZE to clock()
  }

  /**
   * The active desk on [displayId] is (about to be) [deskIndex]. [authoritative] hints (from our
   * own restore automation) apply immediately; others are used to interpret the next window swap.
   */
  fun onDeskSwitchHint(displayId: Int, deskIndex: Int, authoritative: Boolean = false): Boolean {
    if (authoritative) {
      tracked.values.filter { it.displayId == displayId && it.onScreen && it.deskIndex != deskIndex }
        .forEach { it.onScreen = false }
      setActiveDesk(displayId, deskIndex)
      deskHints.remove(displayId)
      return commitIfChanged()
    }
    deskHints[displayId] = deskIndex to clock()
    return false
  }

  fun onDeskAdded(displayId: Int): Boolean {
    knownTotalDesksByDisplay[displayId] = totalDesks(displayId) + 1
    return commitIfChanged()
  }

  /**
   * Reconciles hidden windows against Overview desk cards: assigns each window on an inactive desk
   * to its exact desk, purges windows closed while hidden, and harvests apps on desks never
   * visited this session.
   */
  private fun isExcludedOverviewTask(label: String): Boolean {
    if (KnownPackages.isExcludedCompanionLabel(label)) return true
    val pkg = catalog.findPackageByLabel(label) ?: return false
    return KnownPackages.isExcludedCompanionApp(pkg, label)
  }

  private fun sanitizeOverview(raw: OverviewInspection): OverviewInspection {
    val kept = mutableListOf<com.google.android.desktop.apprestorer.inspect.OverviewDeskCard>()
    for (card in raw.desks) {
      val filtered = card.tasks.filterNot { isExcludedOverviewTask(it.label) }
      // Drop desks opened solely for Connected Phone / Device Link Debug.
      if (card.tasks.isNotEmpty() && filtered.isEmpty()) continue
      kept += card.copy(index = kept.size, tasks = filtered)
    }
    return raw.copy(desks = kept)
  }

  fun onOverviewInspected(displayId: Int, rawInspection: OverviewInspection): Boolean {
    if (isFrozen || rawInspection.desks.isEmpty()) return false
    val inspection = sanitizeOverview(rawInspection)
    if (inspection.desks.isEmpty()) return false
    val display = displays[displayId]
    val deskCount = inspection.desks.size
    overviewAuthoritativeDesks += displayId
    knownTotalDesksByDisplay[displayId] = deskCount
    logger("overview display=$displayId desks=${inspection.desks.map { d -> d.tasks.map { it.label } }}")
    fun norm(s: String) = s.trim().lowercase()
    val remaining = inspection.desks.associate { it.index to it.tasks.toMutableList() }
    fun consume(desk: Int, label: String, preferLiveTile: Boolean? = null): com.google.android.desktop.apprestorer.inspect.OverviewTask? {
      val list = remaining[desk] ?: return null
      val i = (if (preferLiveTile != null) {
        list.indexOfFirst { norm(it.label) == norm(label) && it.isLiveTile == preferLiveTile }.takeIf { it >= 0 }
      } else null) ?: list.indexOfFirst { norm(it.label) == norm(label) }
      if (i < 0) return null
      return list.removeAt(i)
    }

    // Active desk == card covering the most currently visible windows (Overview is authoritative).
    val visible = tracked.values.filter { it.displayId == displayId && it.onScreen }
    val activeCard = inspection.desks.maxByOrNull { card ->
      visible.count { w -> card.tasks.any { norm(it.label) == norm(w.identity.label) } }
    }?.takeIf { card -> visible.any { w -> card.tasks.any { norm(it.label) == norm(w.identity.label) } } }

    // Only trust card contents when parsing demonstrably works on this build.
    val parsingTrusted = inspection.desks.any { it.tasks.isNotEmpty() } && (visible.isEmpty() || activeCard != null)
    if (!parsingTrusted) {
      activeDeskByDisplay[displayId] = activeDesk(displayId).coerceIn(0, (deskCount - 1).coerceAtLeast(0))
      knownTotalDesksByDisplay[displayId] = deskCount
      logger("overview labels unreadable; keeping hidden windows untouched")
      return commitIfChanged()
    }
    val liveTilesPresent = activeCard != null && activeCard.tasks.any { it.isLiveTile }
    if (activeCard != null) {
      setActiveDesk(displayId, activeCard.index)
      for (w in visible) {
        w.deskIndex = activeCard.index
        consume(activeCard.index, w.identity.label, preferLiveTile = true)
      }
    }
    val active = activeDesk(displayId).coerceIn(0, (deskCount - 1).coerceAtLeast(0))
    setActiveDesk(displayId, active)

    val notVisible = tracked.values.filter { it.displayId == displayId && !it.onScreen }.toList()
    // 1. Unminimized windows occluded on the active desk (e.g. behind maximized Chrome).
    for (w in notVisible.filter { !it.minimized && it.pendingVanishSinceMs == null && it.deskIndex == active }) {
      val task = consume(active, w.identity.label, preferLiveTile = true)
      if (task != null) {
        w.minimized = if (liveTilesPresent) !task.isLiveTile else false
      } else {
        val other = remaining.keys.firstOrNull { d -> d != active && remaining[d]!!.any { norm(it.label) == norm(w.identity.label) } }
        if (other != null) {
          consume(other, w.identity.label)
          w.deskIndex = other
          w.minimized = false
        } else if (!w.identity.isAnchor) {
          logger("overview purge ${w.identity.label} (not on any desk card)")
          remove(w)
        }
      }
    }

    // 2. Windows that vanished from the active desk or were already marked minimized.
    for (w in notVisible.filter { it.key in tracked && (it.pendingVanishSinceMs != null || it.minimized) }) {
      val targetDesk = w.deskIndex.takeIf { it in remaining } ?: active
      val task = consume(targetDesk, w.identity.label, preferLiveTile = false)
      if (task != null) {
        val nowMin = if (liveTilesPresent && targetDesk == active) !task.isLiveTile else true
        if (!w.minimized && nowMin) logger("overview: ${w.identity.label} is minimized")
        w.minimized = nowMin
        w.deskIndex = targetDesk
        w.pendingVanishSinceMs = null
      } else {
        val other = remaining.keys.firstOrNull { d -> d != active && remaining[d]!!.any { norm(it.label) == norm(w.identity.label) } }
        if (other != null) {
          consume(other, w.identity.label)
          logger("overview: ${w.identity.label} lives on desk ${other + 1}")
          w.minimized = false
          w.pendingVanishSinceMs = null
          w.deskIndex = other
        } else if (!w.identity.isAnchor) {
          logger("overview purge ${w.identity.label} (closed while minimized)")
          remove(w)
        }
      }
    }

    // 3. Windows alive on inactive desks: assign their exact desk, purge if gone from every card.
    for (w in notVisible.filter { it.key in tracked && !it.minimized && it.pendingVanishSinceMs == null && it.deskIndex != active }) {
      val deskOrder = remaining.keys.sortedBy { if (it == w.deskIndex) -1 else it }
      val hit = deskOrder.firstOrNull { d -> remaining[d]!!.any { norm(it.label) == norm(w.identity.label) } }
      if (hit == null) {
        if (!w.identity.isAnchor) {
          logger("overview purge ${w.identity.label} (not on any desk card)")
          remove(w)
        } else {
          w.deskIndex = active
        }
      } else {
        val task = consume(hit, w.identity.label)
        w.deskIndex = hit
        if (hit == active && liveTilesPresent && task != null) w.minimized = !task.isLiveTile
      }
    }

    // 4. Harvest windows we have never seen: on other desks (alive there) or on this desk.
    if (display != null) {
      for ((deskIndex, tasks) in remaining) {
        for (task in tasks) {
          val pkg = catalog.findPackageByLabel(task.label) ?: continue
          val id = identityForPackage(pkg) ?: continue
          // Overview tiles are a uniform grid, not a scaled desktop: real geometry is unknown.
          val bounds = display.workArea
          val isMin = if (deskIndex == active) (if (liveTilesPresent) !task.isLiveTile else true) else false
          val t = Tracked(
            key = "ov$displayId-$deskIndex-${orderCounter}", windowId = -1, identity = id,
            displayId = displayId, deskIndex = deskIndex, bounds = bounds, rawLayer = 0,
            onScreen = false, minimized = isMin, lastSeenMs = clock(), activityClassName = null,
            order = orderCounter++, boundsKnown = false,
          )
          tracked[t.key] = t
          logger("overview harvest ${id.label} -> desk ${deskIndex + 1}${if (t.minimized) " (minimized)" else ""}")
        }
      }
    }

    // Clamp any residual out-of-range desk indices (e.g. anchors) to the authoritative desk count.
    val maxValidDesk = (deskCount - 1).coerceAtLeast(0)
    tracked.values.filter { it.displayId == displayId && it.deskIndex > maxValidDesk }
      .forEach { it.deskIndex = active.coerceAtMost(maxValidDesk) }
    knownTotalDesksByDisplay[displayId] = deskCount
    return commitIfChanged()
  }

  /**
   * Taskbar running-app state ("<App> Active" vs "<App> Minimized"). An app that is no longer
   * running cannot have a minimized or hidden-desk window; a running app whose only window
   * vanished was minimized.
   */
  fun onTaskbarState(items: Map<String, Boolean>, minimizedLabels: Set<String> = emptySet()): Boolean {
    if (isFrozen || items.isEmpty()) return false
    taskbarItems = items.mapKeys { it.key.trim().lowercase() }
    taskbarMinimized = minimizedLabels.map { it.trim().lowercase() }.toSet()
    taskbarSeenAtMs = clock()
    // Windows hidden on other desks / minimized whose app stopped running were closed.
    tracked.values.filter { !it.onScreen && !it.identity.isAnchor && taskbarSays(it) == false }.toList().forEach {
      logger("taskbar: ${it.identity.label} no longer running -> purge")
      remove(it)
    }
    return commitIfChanged()
  }

  /** Updates tab URLs read during synthetic Chrome tab harvesting without touching window geometry. */
  fun onBrowserFactsObserved(windowId: Int, facts: BrowserFacts): Boolean {
    if (isFrozen) return false
    tabTracker.update(windowId, facts)
    val tabs = tabTracker.tabsFor(windowId) ?: return false
    val t = tracked.values.firstOrNull { it.windowId == windowId } ?: return false
    t.tabs = tabs
    return commitIfChanged()
  }

  /** true = app running, false = shown but not running, null = unknown. */
  private fun taskbarSays(t: Tracked): Boolean? {
    if (clock() - taskbarSeenAtMs > 15_000) return null
    return taskbarItems[t.identity.label.trim().lowercase()]
  }

  private fun taskbarIsMinimized(t: Tracked): Boolean {
    if (clock() - taskbarSeenAtMs > 15_000) return false
    return t.identity.label.trim().lowercase() in taskbarMinimized
  }

  /** MANUAL_CAPTURE: purge hidden windows whose app was closed after we last saw them. */
  fun reconcileWithUsageStats(): Boolean {
    val hidden = tracked.values.filter { !it.onScreen }
    if (hidden.isEmpty() || !reconciler.isAvailable) return false
    val since = hidden.minOf { it.lastSeenMs }.coerceAtLeast(clock() - 12 * 3_600_000L)
    val closed = reconciler.packagesClosedSince(since, clock())
    hidden.filter { it.identity.packageName in closed && it.pendingVanishSinceMs == null && !it.minimized }
      .forEach { remove(it) }
    return commitIfChanged()
  }

  /** Tab titles of a Chrome window whose URL has not been observed yet. */
  fun unknownTabTitles(windowId: Int, titles: List<String>): List<String> = tabTracker.unknownTitles(windowId, titles)

  fun identityForPackage(pkg: String): ResolvedIdentity? {
    if (pkg == ownPackageName || catalog.isSystemSurface(pkg)) return null
    val label = catalog.labelFor(pkg)
    if (KnownPackages.isExcludedCompanionApp(pkg, label)) return null
    val kind = when {
      KnownPackages.isChrome(pkg) -> AppKind.CHROME_BROWSER
      pkg in KnownPackages.FILES_PACKAGES -> AppKind.FILES
      pkg in KnownPackages.SETTINGS_PACKAGES -> AppKind.SETTINGS
      pkg == KnownPackages.GEMINI -> AppKind.GEMINI
      KnownPackages.isWebApkPackage(pkg) -> AppKind.TWA
      else -> AppKind.STANDARD
    }
    return ResolvedIdentity(pkg, label, kind)
  }

  // ---------------------------------------------------------------------------------------------
  // Freeze (power menu / shutdown / screen off)
  // ---------------------------------------------------------------------------------------------

  fun freeze(reason: CaptureReason) {
    // A shutdown freeze is never downgraded.
    if (frozenReason == CaptureReason.SHUTDOWN_BROADCAST) return
    frozenReason = reason
  }

  fun unfreeze() {
    if (frozenReason == CaptureReason.SHUTDOWN_BROADCAST) return
    frozenReason = null
  }

  // ---------------------------------------------------------------------------------------------
  // Snapshot
  // ---------------------------------------------------------------------------------------------

  fun snapshot(reason: CaptureReason, bootToken: String): WorkspaceSnapshot {
    val live = tracked.values.filter { !it.identity.isAnchor }
    val fallbackDisplay = displays[DisplayGeometry.DEFAULT_DISPLAY_ID] ?: displays.values.firstOrNull()

    // z-order rank within each (display, desk); hidden/minimized keep their last relative layer.
    val zRank = HashMap<String, Int>()
    for ((_, group) in live.groupBy { it.displayId to it.deskIndex }) {
      group.sortedWith(compareBy<Tracked>({ if (it.minimized || it.pendingVanishSinceMs != null) 0 else 1 }, { it.rawLayer }, { it.order }))
        .forEachIndexed { i, t -> zRank[t.key] = i }
    }
    val instance = HashMap<String, Int>()
    val totals = HashMap<String, Int>()
    for ((pkg, group) in live.groupBy { it.identity.packageName }) {
      group.sortedWith(compareBy({ it.displayId }, { it.deskIndex }, { -(zRank[it.key] ?: 0) }, { it.order }))
        .forEachIndexed { i, t -> instance[t.key] = i }
      totals[pkg] = group.size
    }

    val windows = live.mapNotNull { t ->
      val display = displays[t.displayId] ?: fallbackDisplay ?: return@mapNotNull null
      val minimized = t.minimized || t.pendingVanishSinceMs != null
      val visibleState = WindowGeometryClassifier.classify(t.bounds, display, isVisible = true)
      CapturedWindow(
        windowKey = t.key,
        packageName = t.identity.packageName,
        label = t.identity.label,
        activityClassName = t.activityClassName,
        kind = t.identity.kind,
        hostBrowserPackage = t.identity.hostBrowserPackage,
        displayId = t.displayId,
        deskIndex = t.deskIndex.coerceAtLeast(0),
        bounds = t.bounds,
        normalized = WindowGeometryClassifier.normalize(t.bounds, display),
        boundsKnown = t.boundsKnown,
        visualState = if (minimized) WindowVisualState.MINIMIZED else visibleState,
        preMinimizeState = if (minimized) visibleState else null,
        isVisible = !minimized,
        zOrderLayer = zRank[t.key] ?: 0,
        instanceIndex = instance[t.key] ?: 0,
        totalInstancesForApp = totals[t.identity.packageName] ?: 1,
        tabUrls = t.tabs?.urls.orEmpty(),
        tabTitles = t.tabs?.titles.orEmpty(),
        activeTabIndex = t.tabs?.activeIndex ?: 0,
        tabCount = t.tabs?.tabCount ?: 0,
        lastSeenAtMs = t.lastSeenMs,
      )
    }.sortedWith(compareBy({ it.displayId }, { it.deskIndex }, { it.zOrderLayer }))

    val displayIds = (displays.keys + windows.map { it.displayId }).toSortedSet()
    val effectiveTotalDesks = displayIds.associateWith { id ->
      val maxOccupiedDesk = windows.filter { it.displayId == id }.maxOfOrNull { it.deskIndex } ?: 0
      (maxOccupiedDesk + 1).coerceAtLeast(1)
    }
    val effectiveActiveDesk = displayIds.associateWith { id ->
      activeDesk(id).coerceIn(0, (effectiveTotalDesks.getValue(id) - 1).coerceAtLeast(0))
    }
    return WorkspaceSnapshot(
      capturedAtMs = clock(),
      bootTokenAtCapture = bootToken,
      reason = reason,
      displays = displays.values.sortedBy { it.displayId },
      activeDeskIndexByDisplay = effectiveActiveDesk,
      totalDeskCountByDisplay = effectiveTotalDesks,
      windows = windows,
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private fun remove(t: Tracked) {
    tracked.remove(t.key)
    pendingCaption.remove(t.key)
    if (t.windowId >= 0) tabTracker.forget(t.windowId)
  }

  private fun setActiveDesk(displayId: Int, desk: Int) {
    activeDeskByDisplay[displayId] = desk.coerceAtLeast(0)
    bumpTotal(displayId, desk + 1)
  }

  private fun bumpTotal(displayId: Int, atLeast: Int) {
    knownTotalDesksByDisplay[displayId] = maxOf(knownTotalDesksByDisplay[displayId] ?: 1, atLeast)
  }

  private fun firstEmptyDesk(displayId: Int, exclude: Int): Int {
    val used = tracked.values.filter { it.displayId == displayId }.map { it.deskIndex }.toSet() + exclude
    if (displayId in overviewAuthoritativeDesks) {
      val cap = (knownTotalDesksByDisplay[displayId] ?: 1).coerceAtLeast(1)
      return (0 until cap).firstOrNull { it !in used } ?: (0 until cap).firstOrNull { it != exclude } ?: 0
    }
    val total = totalDesks(displayId)
    return (0 until total).firstOrNull { it !in used } ?: total
  }

  private fun expireHints(now: Long) {
    deskHints.entries.removeIf { now - it.value.second > HINT_TTL_MS }
    pendingCaption.entries.removeIf { now - it.value.second > HINT_TTL_MS }
  }

  private fun signature(): Int {
    var h = activeDeskByDisplay.hashCode() * 31 + knownTotalDesksByDisplay.hashCode()
    for (t in tracked.values) {
      h = h * 31 + listOf(
        t.key, t.identity, t.displayId, t.deskIndex, t.bounds, t.rawLayer, t.onScreen, t.minimized,
        t.pendingVanishSinceMs != null, t.tabs,
      ).hashCode()
    }
    return h
  }

  private fun commitIfChanged(): Boolean {
    val s = signature()
    if (s == lastSignature) return false
    lastSignature = s
    return true
  }

  companion object {
    const val VANISH_SETTLE_MS = 1_200L
    const val HINT_TTL_MS = 5_000L
    const val SWAP_WINDOW_MS = 1_500L
    const val STABLE_BOUNDS_MS = 700L
  }
}
