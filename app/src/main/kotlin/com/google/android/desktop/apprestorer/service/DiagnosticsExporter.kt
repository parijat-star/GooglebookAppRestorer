package com.google.android.desktop.apprestorer.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.desktop.apprestorer.WorkspaceRuntime
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.data.WorkspaceSnapshotCodec
import com.google.android.desktop.apprestorer.inspect.OverviewDeskInspector
import com.google.android.desktop.apprestorer.inspect.TwaAndBrowserInspector
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** In-memory ring buffer of capture-engine decisions. */
object DiagnosticsLog {
  private const val MAX = 500
  private val lines = ArrayDeque<String>()
  private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  @Synchronized fun add(line: String) {
    lines.addLast("${fmt.format(Date())} $line")
    while (lines.size > MAX) lines.removeFirst()
  }

  @Synchronized fun snapshot(): List<String> = lines.toList()
}

/**
 * Writes a JSON dump of every window (with pruned accessibility trees, page content excluded),
 * the Overview hierarchy, the live snapshot and the decision log to Documents/AppRestorer/.
 */
object DiagnosticsExporter {

  suspend fun export(service: WorkspaceCaptureAccessibilityService, runtime: WorkspaceRuntime): String {
    val root = JSONObject()
    root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DISPLAY} / SDK ${Build.VERSION.SDK_INT}")
    root.put("displays", JSONArray().apply {
      service.displayProvider.current().forEach { d ->
        put(JSONObject().put("id", d.displayId).put("name", d.name).put("size", "${d.widthPx}x${d.heightPx}")
          .put("workArea", "${d.workArea}").put("dpi", d.densityDpi))
      }
    })
    root.put("windows", dumpAllWindows(service, runtime))
    // Peek at Overview so desk cards / add-desk button structure is captured without corrupting live bounds.
    runtime.automationBusy = true
    try {
      service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
      delay(1_300)
      root.put("overviewWindows", dumpAllWindows(service, runtime, homeOnly = true))
      root.put("overviewParsed", JSONArray().apply {
        OverviewDeskInspector.inspect(service.homeRoots(0)).desks.forEach { d ->
          put(JSONObject().put("desk", d.index + 1).put("tasks", JSONArray(d.tasks.map { it.label })))
        }
      })
      service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
      delay(900)
    } finally {
      runtime.automationBusy = false
    }
    root.put("liveSnapshot", JSONObject(WorkspaceSnapshotCodec.encode(runtime.live.value)))
    root.put("pendingSnapshot", runtime.pending.value?.let { JSONObject(WorkspaceSnapshotCodec.encode(it)) } ?: JSONObject.NULL)
    root.put("log", JSONArray(DiagnosticsLog.snapshot()))
    return write(service, root.toString(1))
  }

  private fun dumpAllWindows(service: WorkspaceCaptureAccessibilityService, runtime: WorkspaceRuntime, homeOnly: Boolean = false): JSONArray {
    val out = JSONArray()
    val all = try { service.windowsOnAllDisplays } catch (_: RuntimeException) { return out }
    for (i in 0 until all.size()) {
      val displayId = all.keyAt(i)
      for (w in all.valueAt(i)) {
        val node = try { w.root } catch (_: RuntimeException) { null }
        val pkg = node?.packageName?.toString()
        val isHome = pkg != null && runtime.catalog.isHomePackage(pkg)
        if (homeOnly && !isHome) continue
        val r = Rect().also { w.getBoundsInScreen(it) }
        val budget = when {
          KnownPackages.isChrome(pkg) -> 2500
          isHome -> 3000
          pkg == KnownPackages.SYSTEM_UI -> 600
          else -> 250
        }
        out.put(JSONObject()
          .put("display", displayId).put("windowId", w.id).put("type", typeName(w.type)).put("layer", w.layer)
          .put("title", w.title?.toString()).put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
          .put("focused", w.isFocused).put("active", w.isActive).put("pkg", pkg)
          .put("class", service.cachedClassName(w.id))
          .put("tree", node?.let { dumpNode(it, intArrayOf(budget)) } ?: JSONObject.NULL))
      }
    }
    return out
  }

  private fun dumpNode(n: AccessibilityNodeInfo, budget: IntArray, depth: Int = 0): JSONObject {
    budget[0]--
    val r = Rect().also { n.getBoundsInScreen(it) }
    val o = JSONObject()
    n.viewIdResourceName?.let { o.put("id", it.substringAfter(":id/")) }
    o.put("cls", n.className?.toString()?.substringAfterLast('.'))
    if (!n.isPassword) {
      n.text?.let { o.put("text", it.toString().take(120)) }
    }
    n.contentDescription?.let { o.put("desc", it.toString().take(120)) }
    o.put("b", "${r.left},${r.top},${r.right},${r.bottom}")
    if (n.isSelected) o.put("sel", true)
    if (n.isFocused) o.put("foc", true)
    if (n.isClickable) o.put("click", true)
    val acts = n.actionList.mapNotNull { it.label?.toString() }
    if (acts.isNotEmpty()) o.put("acts", JSONArray(acts))
    if (depth >= 64 || TwaAndBrowserInspector.isWebContent(AccessibilityUiNode(n))) {
      o.put("webContentSkipped", true)
      return o
    }
    if (n.childCount > 0) {
      val ch = JSONArray()
      for (i in 0 until n.childCount) {
        if (budget[0] <= 0) { ch.put("…truncated"); break }
        val c = try { n.getChild(i) } catch (_: RuntimeException) { null } ?: continue
        ch.put(dumpNode(c, budget, depth + 1))
      }
      o.put("ch", ch)
    }
    return o
  }

  private fun typeName(t: Int) = when (t) {
    AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
    AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVIDER"
    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "MAGNIFICATION"
    else -> "TYPE_$t"
  }

  /** Saves via MediaStore into Documents/AppRestorer/ (no storage permission needed). */
  private fun write(context: Context, json: String): String {
    val name = "apprestorer_diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
      put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/AppRestorer")
    }
    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
      ?: error("MediaStore insert failed")
    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
    return "Documents/AppRestorer/$name"
  }
}
