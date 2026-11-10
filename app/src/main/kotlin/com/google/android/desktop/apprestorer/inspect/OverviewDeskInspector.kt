package com.google.android.desktop.apprestorer.inspect

import com.google.android.desktop.apprestorer.model.NormalizedBounds

/** One app window thumbnail inside an Overview desk card. */
data class OverviewTask(
  val label: String,
  /** Thumbnail position relative to its desk card == window position relative to the display. */
  val relativeBounds: NormalizedBounds?,
  /** True when rendered via :id/task_thumbnail_live_tile (unminimized window on the active desk). */
  val isLiveTile: Boolean = false,
)

/** One Virtual Desk as shown in Overview (DesktopTaskView). */
data class OverviewDeskCard(
  /** 0-based creation order (RecentsView.addChildrenForAccessibility traversal). */
  val index: Int,
  val tasks: List<OverviewTask>,
  val node: UiNode? = null,
) {
  val isEmpty: Boolean get() = tasks.isEmpty()
}

data class OverviewInspection(
  val isOverviewVisible: Boolean,
  val desks: List<OverviewDeskCard>,
  val addDeskButton: UiNode?,
  val overviewPanel: UiNode? = null,
)

/**
 * Parses Launcher3 Overview on Googlebook: DesktopTaskView (:id/task_view_desktop) cards,
 * TaskContentView (:id/task_content_view) children with Jetpack Compose TaskHeader text or
 * "Close <App>" custom actions (no legacy :id/header_app_title), and :id/add_desktop_button.
 */
object OverviewDeskInspector {

  private val DESK_CARD_IDS = arrayOf("task_view_desktop")
  private val TASK_CONTENT_IDS = arrayOf("task_content_view", "snapshot", "task_thumbnail")
  // Note: Home screen always hosts an idle :id/overview_actions_view; never include it here.
  private val RECENTS_IDS = arrayOf("recents_view", "overview_panel")

  fun isOverview(root: UiNode): Boolean =
    root.findFirst(400) { n ->
      n.hasAnyId(*RECENTS_IDS) || n.hasAnyId(*DESK_CARD_IDS) || n.hasId("add_desktop_button")
    } != null

  fun inspect(roots: List<UiNode>): OverviewInspection {
    val cards = mutableListOf<UiNode>()
    var addButton: UiNode? = null
    var panel: UiNode? = null
    var overview = false
    for (root in roots) {
      root.walk(3000) { n, _ ->
        if (n.hasAnyId(*RECENTS_IDS)) {
          overview = true
          if (panel == null) panel = n
        }
        when {
          n.hasId("add_desktop_button") || n.className?.endsWith("AddDesktopButton") == true -> {
            addButton = n
            false
          }
          isDeskCard(n) -> {
            cards += n
            overview = true
            false // tasks are parsed separately
          }
          else -> true
        }
      }
    }
    val desks = cards.mapIndexed { i, card -> OverviewDeskCard(i, parseTasks(card), card) }
    return OverviewInspection(overview || addButton != null, desks, addButton, panel)
  }

  private fun isDeskCard(n: UiNode): Boolean =
    n.hasAnyId(*DESK_CARD_IDS) || n.className?.endsWith("DesktopTaskView") == true

  internal fun parseTasks(card: UiNode): List<OverviewTask> {
    val cardBounds = card.boundsInScreen
    val containers = card.findAll(600) { it.hasId("task_content_view") }
      .ifEmpty { card.findAll(600) { it.hasAnyId(*TASK_CONTENT_IDS) } }
    return containers.mapNotNull { c ->
      val label = taskLabel(c) ?: return@mapNotNull null
      val b = c.boundsInScreen
      val rel = if (cardBounds.width > 0 && cardBounds.height > 0 && !b.isEmpty) {
        NormalizedBounds(
          ((b.left - cardBounds.left).toFloat() / cardBounds.width).coerceIn(0f, 1f),
          ((b.top - cardBounds.top).toFloat() / cardBounds.height).coerceIn(0f, 1f),
          ((b.right - cardBounds.left).toFloat() / cardBounds.width).coerceIn(0f, 1f),
          ((b.bottom - cardBounds.top).toFloat() / cardBounds.height).coerceIn(0f, 1f),
        )
      } else null
      val liveTile = c.findFirst(40) { it.hasId("task_thumbnail_live_tile") } != null
      OverviewTask(label, rel, isLiveTile = liveTile)
    }
  }

  /** Legacy header title → Compose TaskHeader text → "Close <App>" action label. */
  internal fun taskLabel(container: UiNode): String? {
    container.findFirst(80) { it.hasId("header_app_title") }?.label?.let { return it }
    closeActionApp(container.customActionLabels)?.let { return it }
    var fromChildren: String? = null
    container.walk(120) { n, depth ->
      if (fromChildren != null) return@walk false
      if (depth > 0) {
        closeActionApp(n.customActionLabels)?.let { fromChildren = it; return@walk false }
        val t = n.text.nonNullLabel()
        if (t != null) { fromChildren = t; return@walk false }
      }
      true
    }
    return fromChildren ?: container.contentDescription.nonNullLabel()
  }

  private fun closeActionApp(labels: List<String>): String? =
    labels.firstNotNullOfOrNull { l ->
      l.trim().takeIf { it.startsWith("Close ", ignoreCase = true) }?.substring(6).nonNullLabel()
    }
}
