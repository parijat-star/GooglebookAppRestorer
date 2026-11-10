package com.google.android.desktop.apprestorer.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.desktop.apprestorer.inspect.UiNode
import com.google.android.desktop.apprestorer.model.WindowBounds

/** [UiNode] adapter over a live AccessibilityNodeInfo. */
class AccessibilityUiNode(val info: AccessibilityNodeInfo) : UiNode {
  override val viewIdResourceName: String? get() = info.viewIdResourceName
  override val packageName: String? get() = info.packageName?.toString()
  override val className: String? get() = info.className?.toString()
  override val text: String? get() = info.text?.toString()
  override val contentDescription: String? get() = info.contentDescription?.toString()
  override val boundsInScreen: WindowBounds
    get() {
      val r = Rect()
      info.getBoundsInScreen(r)
      return WindowBounds(r.left, r.top, r.right, r.bottom)
    }
  override val isFocused: Boolean get() = info.isFocused
  override val isSelected: Boolean get() = info.isSelected
  override val isVisibleToUser: Boolean get() = info.isVisibleToUser
  override val childCount: Int get() = info.childCount
  override fun child(index: Int): UiNode? = try {
    info.getChild(index)?.let { AccessibilityUiNode(it) }
  } catch (_: RuntimeException) {
    null
  }

  override val customActionLabels: List<String>
    get() = info.actionList.mapNotNull { it.label?.toString() }

  override fun click(): Boolean {
    var node: AccessibilityNodeInfo? = info
    var hops = 0
    while (node != null && hops < 6) {
      if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      node = node.parent
      hops++
    }
    return info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
  }

  override fun showOnScreen(): Boolean =
    info.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)

  override fun performCustomAction(label: String): Boolean {
    val action = info.actionList.firstOrNull { it.label?.toString() == label } ?: return false
    return info.performAction(action.id)
  }

  override fun scrollForward(): Boolean =
    info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
      info.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)

  override fun scrollBackward(): Boolean =
    info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ||
      info.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
}
