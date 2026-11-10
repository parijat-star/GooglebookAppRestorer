package com.google.android.desktop.apprestorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot

/** Display + desk selector with a proportional 16:10 layout preview of the chosen desk. */
@Composable
fun WorkspaceDesktopPreview(snapshot: WorkspaceSnapshot, selectedKeys: Set<String>? = null, modifier: Modifier = Modifier) {
  val targets = remember(snapshot) {
    val ids = (snapshot.displays.map { it.displayId } + snapshot.windows.map { it.displayId }).distinct().sorted()
    ids.flatMap { d -> (0 until (snapshot.totalDeskCountByDisplay[d] ?: 1).coerceAtLeast(1)).map { d to it } }
      .ifEmpty { listOf(0 to 0) }
  }
  var selected by remember(snapshot) {
    mutableStateOf(targets.firstOrNull { (d, k) -> d == 0 && k == (snapshot.activeDeskIndexByDisplay[0] ?: 0) } ?: targets.first())
  }
  Column(modifier) {
    if (targets.size > 1) {
      LazyRow {
        items(targets) { t ->
          val count = snapshot.windows.count { it.displayId == t.first && it.deskIndex == t.second }
          val name = if (t.first == 0) "Built-in" else "Display ${t.first}"
          FilterChip(
            selected = t == selected,
            onClick = { selected = t },
            label = { Text("$name · Desk ${t.second + 1} ($count)") },
            modifier = Modifier.padding(end = 6.dp).testTag("desk_chip_${t.first}_${t.second}"),
          )
        }
      }
      Spacer(Modifier.height(6.dp))
    }
    WorkspaceDesktopPreviewCanvas(snapshot, selected.first, selected.second, selectedKeys, Modifier.fillMaxWidth())
  }
}

/**
 * Draws one desk: windows at their exact normalized positions in Z-order, snapped/maximized
 * states labelled, minimized windows as dashed chips along the taskbar.
 */
@Composable
fun WorkspaceDesktopPreviewCanvas(
  snapshot: WorkspaceSnapshot,
  displayId: Int,
  deskIndex: Int,
  selectedKeys: Set<String>?,
  modifier: Modifier = Modifier,
) {
  val windows = snapshot.windows.filter { it.displayId == displayId && it.deskIndex == deskIndex }
  val visible = windows.filter { it.isVisible }.sortedBy { it.zOrderLayer }
  val minimized = windows.filter { !it.isVisible }
  val display = snapshot.display(displayId)
  val aspect = display?.let { it.widthPx.toFloat() / it.heightPx.coerceAtLeast(1) }?.takeIf { it in 1f..3f } ?: 1.6f
  val measurer = rememberTextMeasurer()
  val colors = MaterialTheme.colorScheme
  val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onPrimaryContainer)
  Canvas(modifier.aspectRatio(aspect).testTag("desktop_preview")) {
    val corner = CornerRadius(8.dp.toPx())
    drawRoundRect(colors.surfaceVariant, cornerRadius = corner)
    val taskbarH = size.height * 0.06f
    drawRect(colors.outlineVariant, topLeft = Offset(0f, size.height - taskbarH), size = Size(size.width, taskbarH))
    for (w in visible) {
      val n = w.normalized
      val tl = Offset(n.left * size.width, n.top * size.height)
      val sz = Size(((n.right - n.left) * size.width).coerceAtLeast(6f), ((n.bottom - n.top) * size.height).coerceAtLeast(6f))
      val dimmed = selectedKeys != null && w.windowKey !in selectedKeys
      drawRoundRect(colors.primaryContainer.copy(alpha = if (dimmed) 0.35f else 0.95f), tl, sz, CornerRadius(4.dp.toPx()))
      drawRoundRect(colors.primary, tl, sz, CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
      drawRect(colors.primary.copy(alpha = 0.25f), tl, Size(sz.width, minOf(sz.height, 14.dp.toPx())))
      val text = "${w.label.ifEmpty { w.packageName }}${stateSuffix(w)}"
      val maxW = (sz.width - 6.dp.toPx()).toInt()
      if (maxW > 10) {
        val layout = measurer.measure(text, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW))
        drawText(layout, topLeft = tl + Offset(3.dp.toPx(), 1.dp.toPx()))
      }
    }
    var x = 6.dp.toPx()
    val chipH = taskbarH * 0.7f
    for (w in minimized) {
      val chipW = size.width * 0.12f
      val tl = Offset(x, size.height - taskbarH + (taskbarH - chipH) / 2)
      drawRoundRect(colors.tertiary, tl, Size(chipW, chipH), CornerRadius(3.dp.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
      val layout = measurer.measure(w.label.ifEmpty { w.packageName }, labelStyle.copy(fontSize = 8.sp, color = colors.onSurface),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (chipW - 4).toInt().coerceAtLeast(1)))
      drawText(layout, topLeft = tl + Offset(2f, 0f))
      x += chipW + 4.dp.toPx()
    }
  }
}

private fun stateSuffix(w: CapturedWindow): String = when (w.visualState) {
  WindowVisualState.MAXIMIZED -> " · max"
  WindowVisualState.FULLSCREEN -> " · full"
  WindowVisualState.SNAPPED_LEFT -> " · ◧"
  WindowVisualState.SNAPPED_RIGHT -> " · ◨"
  else -> maxOf(w.tabCount, w.tabUrls.size).let { if (it > 1) " · $it tabs" else "" }
}
