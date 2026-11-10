package com.google.android.desktop.apprestorer.inspect

import com.google.android.desktop.apprestorer.model.WindowBounds

/**
 * Minimal view of an accessibility node. Production code wraps AccessibilityNodeInfo
 * ([com.google.android.desktop.apprestorer.service.AccessibilityUiNode]); tests use fakes, so all
 * window/Overview parsing logic is JVM-testable.
 */
interface UiNode {
  val viewIdResourceName: String?
  val packageName: String?
  val className: String?
  val text: String?
  val contentDescription: String?
  val boundsInScreen: WindowBounds
  val isFocused: Boolean
  val isSelected: Boolean
  val isVisibleToUser: Boolean
  val childCount: Int
  fun child(index: Int): UiNode?
  /** Labels of custom AccessibilityActions (e.g. Compose TaskHeader "Close Chrome"). */
  val customActionLabels: List<String>
  fun click(): Boolean
  fun showOnScreen(): Boolean
  fun performCustomAction(label: String): Boolean
  fun scrollForward(): Boolean = false
  fun scrollBackward(): Boolean = false
}

fun UiNode.hasId(suffix: String): Boolean = viewIdResourceName?.endsWith(":id/$suffix") == true

fun UiNode.hasAnyId(vararg suffixes: String): Boolean = suffixes.any { hasId(it) }

internal fun String?.nonNullLabel(): String? =
  this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/** Text or content description, whichever is non-blank (ignoring literal "null" placeholders). */
val UiNode.label: String?
  get() = text.nonNullLabel() ?: contentDescription.nonNullLabel()

const val MAX_UI_TREE_DEPTH = 128

/**
 * Depth-first pre-order traversal (== RecentsView accessibility creation order) bounded by
 * [maxNodes] and [MAX_UI_TREE_DEPTH]. [visit] returns false to skip a node's subtree.
 */
fun UiNode.walk(maxNodes: Int = 800, visit: (UiNode, Int) -> Boolean) {
  var budget = maxNodes
  fun rec(node: UiNode, depth: Int) {
    if (budget-- <= 0 || depth > MAX_UI_TREE_DEPTH) return
    if (!visit(node, depth)) return
    for (i in 0 until node.childCount) {
      if (budget <= 0) return
      node.child(i)?.let { rec(it, depth + 1) }
    }
  }
  rec(this, 0)
}

fun UiNode.findFirst(maxNodes: Int = 800, predicate: (UiNode) -> Boolean): UiNode? {
  var found: UiNode? = null
  walk(maxNodes) { n, _ ->
    if (found != null) return@walk false
    if (predicate(n)) {
      found = n
      false
    } else true
  }
  return found
}

fun UiNode.findAll(maxNodes: Int = 800, descendIntoMatches: Boolean = false, predicate: (UiNode) -> Boolean): List<UiNode> {
  val out = mutableListOf<UiNode>()
  walk(maxNodes) { n, _ ->
    if (predicate(n)) {
      out += n
      descendIntoMatches
    } else true
  }
  return out
}
